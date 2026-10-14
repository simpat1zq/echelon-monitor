#!/usr/bin/env python3
"""Live dashboard for an Echelon Stride / Stride-4s treadmill.

Connects to the treadmill motor base over Bluetooth LE (bypassing the
tablet/app), decodes speed / incline / heart rate, and serves a live web
page you can open from any phone or browser on the same network.

Protocol details are taken from the open source QZ (qdomyos-zwift) project's
echelonstride driver.

Usage:
    python3 stride_monitor.py                 # scan for a treadmill named STRIDE*
    python3 stride_monitor.py --address AA:BB:CC:DD:EE:FF
    python3 stride_monitor.py --demo          # fake data, no treadmill needed
    python3 stride_monitor.py --scan          # just list nearby BLE devices
"""

import argparse
import asyncio
import csv
import json
import logging
import math
import os
import random
import time

from aiohttp import web

log = logging.getLogger("stride")

SERVICE_UUID = "0bf669f1-45f2-11e7-9598-0800200c9a66"
WRITE_UUID = "0bf669f2-45f2-11e7-9598-0800200c9a66"
NOTIFY_UUIDS = (
    "0bf669f3-45f2-11e7-9598-0800200c9a66",
    "0bf669f4-45f2-11e7-9598-0800200c9a66",
)

INIT_GENERIC = [
    "f0a40094",
    "f0a10091", "f0a10091", "f0a10091", "f0a10091",
    "f0a30093",
    "f0a10091",
]
INIT_STRIDE4 = [
    "f0a40094", "f0a40094",
    "f0e0fd3e6548d58d",
    "f0a40094",
    "f0a10091", "f0a10091", "f0a10091", "f0a10091",
    "f0a30093",
]

HERE = os.path.dirname(os.path.abspath(__file__))


class Workout:
    """Current treadmill state plus values integrated over time."""

    def __init__(self, metric):
        self.metric = metric
        self.reset()

    def reset(self):
        self.connected = False
        self.device = None
        self.speed = 0.0          # in the treadmill's native unit (mph or km/h)
        self.incline = 0
        self.heart_rate = 0
        self.distance = 0.0       # same unit as speed (mi or km)
        self.elevation_ft = 0.0
        self.moving_seconds = 0.0
        self.max_speed = 0.0
        self.started_at = None
        self._last_tick = None
        self.raw = ""

    def tick(self):
        now = time.monotonic()
        if self._last_tick is not None:
            dt = now - self._last_tick
            if self.speed > 0:
                if self.started_at is None:
                    self.started_at = time.time()
                step = self.speed * dt / 3600.0
                self.distance += step
                self.moving_seconds += dt
                miles = step if not self.metric else step / 1.60934
                self.elevation_ft += miles * 5280 * self.incline / 100.0
        self._last_tick = now

    def snapshot(self):
        unit = "km/h" if self.metric else "mph"
        pace = None
        if self.speed > 0.3:
            pace = 60.0 / self.speed  # minutes per mile/km
        return {
            "connected": self.connected,
            "device": self.device,
            "speed": round(self.speed, 2),
            "speed_unit": unit,
            "incline": self.incline,
            "heart_rate": self.heart_rate,
            "distance": round(self.distance, 3),
            "distance_unit": "km" if self.metric else "mi",
            "pace_min": pace,
            "pace_unit": "/km" if self.metric else "/mi",
            "elevation_ft": round(self.elevation_ft, 1),
            "moving_seconds": int(self.moving_seconds),
            "max_speed": round(self.max_speed, 2),
            "raw": self.raw,
            "ts": time.time(),
        }


def checksum(data):
    return sum(data) & 0xFF


class StrideBLE:
    def __init__(self, workout, address, name_prefix, debug, protocol="auto"):
        self.workout = workout
        self.protocol = protocol
        self.address = address
        self.name_prefix = name_prefix.upper()
        self.debug = debug
        self.client = None
        self.is_stride4 = False
        self.poll_counter = 1

    async def find_device(self):
        from bleak import BleakScanner

        if self.address:
            log.info("Looking for %s ...", self.address)
            return await BleakScanner.find_device_by_address(self.address, timeout=20)
        log.info("Scanning for a treadmill whose name contains %r ...", self.name_prefix)
        return await BleakScanner.find_device_by_filter(
            lambda d, adv: self.name_prefix in (d.name or adv.local_name or "").upper()
            or SERVICE_UUID in [u.lower() for u in adv.service_uuids],
            timeout=20,
        )

    async def write(self, hexstr_or_bytes):
        data = bytes.fromhex(hexstr_or_bytes) if isinstance(hexstr_or_bytes, str) else bytes(hexstr_or_bytes)
        if self.debug:
            log.debug("TX %s", data.hex(" "))
        await self.client.write_gatt_char(WRITE_UUID, data, response=False)

    def on_notify(self, _char, data: bytearray):
        w = self.workout
        if self.debug:
            log.debug("RX %s", data.hex(" "))
        w.raw = data.hex(" ")
        if len(data) < 2:
            return
        kind = data[1]
        if kind == 0xD3 and len(data) >= 5:
            w.speed = ((data[3] << 8) | data[4]) / 1000.0
        elif kind == 0xD2 and len(data) >= 4:
            w.incline = data[3]
        elif kind == 0xD1 and self.is_stride4 and len(data) >= 9:
            w.speed = ((data[7] << 8) | data[8]) / 100.0
        if kind == 0xD1 and len(data) > 11 and data[11]:
            w.heart_rate = data[11]
        w.max_speed = max(w.max_speed, w.speed)
        w.tick()
        # The motor base expects these packets to be echoed back as an ack.
        if kind in (0xD0, 0xD2, 0xD3):
            asyncio.get_running_loop().create_task(self._safe_write(bytes(data)))

    async def _safe_write(self, data):
        try:
            await self.write(data)
        except Exception as exc:  # noqa: BLE001
            log.debug("echo failed: %s", exc)

    async def run_forever(self):
        from bleak import BleakClient

        while True:
            try:
                device = await self.find_device()
                if device is None:
                    log.warning("Treadmill not found. Is it powered on, and is the "
                                "tablet/app disconnected? Retrying in 5s.")
                    await asyncio.sleep(5)
                    continue
                name = device.name or ""
                if self.protocol == "auto":
                    self.is_stride4 = name.upper().startswith("STRIDE4")
                else:
                    self.is_stride4 = self.protocol == "stride4"
                log.info("Connecting to %s (%s)%s", name, device.address,
                         " [Stride4 mode]" if self.is_stride4 else "")
                disconnected = asyncio.Event()
                async with BleakClient(device, disconnected_callback=lambda _c: disconnected.set()) as client:
                    self.client = client
                    for uuid in NOTIFY_UUIDS:
                        await client.start_notify(uuid, self.on_notify)
                    for cmd in INIT_STRIDE4 if self.is_stride4 else INIT_GENERIC:
                        await self.write(cmd)
                        await asyncio.sleep(0.3)
                    self.workout.connected = True
                    self.workout.device = name or device.address
                    log.info("Connected. Streaming data.")
                    while not disconnected.is_set():
                        await self.send_poll()
                        try:
                            await asyncio.wait_for(disconnected.wait(), timeout=2)
                        except asyncio.TimeoutError:
                            pass
                log.warning("Treadmill disconnected.")
            except FileNotFoundError:
                log.error("Can't reach the Bluetooth service (BlueZ) over D-Bus. On the host, run "
                          "check_host.sh; in Docker, mount /run/dbus/system_bus_socket.")
                await asyncio.sleep(10)
            except Exception as exc:  # noqa: BLE001
                log.warning("BLE error: %s", exc)
            self.workout.connected = False
            self.workout.speed = 0.0
            await asyncio.sleep(3)

    async def send_poll(self):
        pkt = [0xF0, 0xA0, 0x01, self.poll_counter]
        pkt.append(checksum(pkt))
        self.poll_counter = self.poll_counter + 1 if self.poll_counter < 255 else 1
        await self.write(pkt)


async def demo_loop(workout):
    """Fake a workout so the page can be tried without a treadmill."""
    workout.connected = True
    workout.device = "DEMO"
    t0 = time.monotonic()
    while True:
        t = time.monotonic() - t0
        workout.speed = round(3.0 + 1.5 * (1 + math.sin(t / 20)), 1)
        workout.incline = int(3 + 3 * math.sin(t / 45))
        workout.heart_rate = int(110 + workout.speed * 8 + random.randint(-2, 2))
        workout.max_speed = max(workout.max_speed, workout.speed)
        workout.tick()
        await asyncio.sleep(1)


async def csv_logger(workout, path):
    new = not os.path.exists(path)
    with open(path, "a", newline="") as fh:
        writer = csv.writer(fh)
        if new:
            writer.writerow(["timestamp", "speed", "incline", "heart_rate", "distance", "moving_seconds"])
        while True:
            await asyncio.sleep(1)
            if workout.connected and workout.speed > 0:
                s = workout.snapshot()
                writer.writerow([time.strftime("%Y-%m-%dT%H:%M:%S"), s["speed"], s["incline"],
                                 s["heart_rate"], s["distance"], s["moving_seconds"]])
                fh.flush()


def make_app(workout):
    clients = set()

    async def index(_request):
        return web.FileResponse(os.path.join(HERE, "index.html"))

    async def state(_request):
        return web.json_response(workout.snapshot())

    async def reset(_request):
        connected, device = workout.connected, workout.device
        workout.reset()
        workout.connected, workout.device = connected, device
        return web.json_response({"ok": True})

    async def ws_handler(request):
        ws = web.WebSocketResponse(heartbeat=20)
        await ws.prepare(request)
        clients.add(ws)
        try:
            await ws.send_str(json.dumps(workout.snapshot()))
            async for _ in ws:
                pass
        finally:
            clients.discard(ws)
        return ws

    async def broadcaster(_app):
        async def loop():
            while True:
                await asyncio.sleep(1)
                workout.tick()
                msg = json.dumps(workout.snapshot())
                for ws in list(clients):
                    try:
                        await ws.send_str(msg)
                    except Exception:  # noqa: BLE001
                        clients.discard(ws)
        task = asyncio.create_task(loop())
        yield
        task.cancel()

    app = web.Application()
    app.router.add_get("/", index)
    app.router.add_get("/api/state", state)
    app.router.add_post("/api/reset", reset)
    app.router.add_get("/ws", ws_handler)
    app.cleanup_ctx.append(broadcaster)
    return app


async def scan_only():
    from bleak import BleakScanner

    print("Scanning 10s ...")
    found = await BleakScanner.discover(timeout=10, return_adv=True)
    for dev, adv in sorted(found.values(), key=lambda x: -x[1].rssi):
        tag = "  <-- Echelon service" if SERVICE_UUID in [u.lower() for u in adv.service_uuids] else ""
        print(f"{dev.address}  rssi={adv.rssi:4d}  {dev.name or adv.local_name or '?'}{tag}")


async def main_async(args):
    workout = Workout(metric=args.metric)
    runner = web.AppRunner(make_app(workout))
    await runner.setup()
    await web.TCPSite(runner, args.host, args.port).start()
    log.info("Dashboard: http://%s:%d/", "localhost" if args.host in ("0.0.0.0", "") else args.host, args.port)

    tasks = []
    if args.demo:
        tasks.append(asyncio.create_task(demo_loop(workout)))
    else:
        ble = StrideBLE(workout, args.address, args.name, args.debug, args.protocol)
        tasks.append(asyncio.create_task(ble.run_forever()))
    if args.csv:
        tasks.append(asyncio.create_task(csv_logger(workout, args.csv)))
    await asyncio.gather(*tasks)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--address", help="treadmill BLE MAC address (or macOS UUID); default: scan")
    p.add_argument("--name", default="STRIDE", help="text the BLE name must contain (default: STRIDE)")
    p.add_argument("--host", default="0.0.0.0", help="web server bind address")
    p.add_argument("--port", type=int, default=8080, help="web server port (default 8080)")
    p.add_argument("--protocol", choices=["auto", "generic", "stride4"], default="auto",
                   help="init sequence/speed decoding; auto picks stride4 only for names starting STRIDE4")
    p.add_argument("--metric", action="store_true", help="treadmill reports km/h instead of mph")
    p.add_argument("--csv", help="append per-second samples to this CSV file")
    p.add_argument("--demo", action="store_true", help="generate fake data (no treadmill needed)")
    p.add_argument("--scan", action="store_true", help="list nearby BLE devices and exit")
    p.add_argument("--debug", action="store_true", help="log raw BLE packets")
    args = p.parse_args()

    logging.basicConfig(level=logging.DEBUG if args.debug else logging.INFO,
                        format="%(asctime)s %(levelname)s %(message)s", datefmt="%H:%M:%S")
    if not args.debug:
        logging.getLogger("bleak").setLevel(logging.WARNING)
    try:
        asyncio.run(scan_only() if args.scan else main_async(args))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
