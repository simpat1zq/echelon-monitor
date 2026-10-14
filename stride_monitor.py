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
import signal
import time

from aiohttp import web

log = logging.getLogger("stride")

SERVICE_UUID = "0bf669f1-45f2-11e7-9598-0800200c9a66"
WRITE_UUID = "0bf669f2-45f2-11e7-9598-0800200c9a66"
NOTIFY_UUIDS = (
    "0bf669f3-45f2-11e7-9598-0800200c9a66",
    "0bf669f4-45f2-11e7-9598-0800200c9a66",
)

FTMS_SERVICE = "00001826-0000-1000-8000-00805f9b34fb"
FTMS_TREADMILL_DATA = "00002acd-0000-1000-8000-00805f9b34fb"

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
# Set by the Docker build (GitHub Actions passes the commit SHA and build time).
APP_VERSION = os.environ.get("APP_VERSION", "dev")
APP_BUILD_DATE = os.environ.get("APP_BUILD_DATE", "")


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
            "version": APP_VERSION,
            "built": APP_BUILD_DATE,
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
        self.write_response = True
        self.has_echelon = True
        self.last_echelon_rx = 0.0
        self.last_ftms_rx = 0.0

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
        await self.client.write_gatt_char(WRITE_UUID, data, response=self.write_response)

    def log_gatt(self, client):
        log.info("GATT services offered by the treadmill:")
        for service in client.services:
            log.info("  service %s  %s", service.uuid, service.description)
            for char in service.characteristics:
                log.info("    char %s  [%s]  %s", char.uuid, ",".join(char.properties), char.description)

    def on_other_notify(self, char, data: bytearray):
        log.info("RX(other %s) %s", char.uuid[4:8], data.hex(" "))

    def on_ftms(self, _char, data: bytearray):
        """Standard Bluetooth FTMS Treadmill Data (0x2ACD)."""
        w = self.workout
        if self.debug:
            log.debug("RX(ftms) %s", data.hex(" "))
        w.raw = "ftms " + data.hex(" ")
        self.last_ftms_rx = time.monotonic()
        try:
            parsed = parse_ftms_treadmill(bytes(data))
        except (IndexError, ValueError) as exc:
            log.debug("FTMS parse error: %s", exc)
            return
        if "speed_kmh" in parsed:
            kmh = parsed["speed_kmh"]
            w.speed = kmh if w.metric else kmh / 1.60934
        if "incline" in parsed:
            w.incline = round(parsed["incline"], 1)
        if parsed.get("heart_rate"):
            w.heart_rate = parsed["heart_rate"]
        w.max_speed = max(w.max_speed, w.speed)
        w.tick()

    def on_notify(self, _char, data: bytearray):
        w = self.workout
        if self.debug:
            log.debug("RX %s", data.hex(" "))
        self.last_echelon_rx = time.monotonic()
        if self.last_ftms_rx and time.monotonic() - self.last_ftms_rx < 5:
            return  # FTMS data is flowing and is the better source
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

        # A previous run that died without disconnecting leaves BlueZ holding the
        # link, and the treadmill stops advertising until it's power-cycled.
        await self.release_stale_connection()
        while True:
            try:
                device = await self.find_device()
                if device is None:
                    await self.release_stale_connection()
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
                    await self.setup_notifications(client)
                    await self.send_init()
                    self.workout.connected = True
                    self.workout.device = name or device.address
                    log.info("Connected. Streaming data.")
                    connected_at = time.monotonic()
                    switched = False
                    while not disconnected.is_set():
                        if (not switched and self.protocol == "auto" and not self.last_echelon_rx
                                and not self.last_ftms_rx and time.monotonic() - connected_at > 10):
                            switched = True
                            self.is_stride4 = not self.is_stride4
                            log.warning("No data after 10s; retrying with the %s init sequence.",
                                        "stride4" if self.is_stride4 else "generic")
                            await self.send_init()
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

    def _matches(self, address, name):
        if self.address:
            return address.upper() == self.address.upper()
        return self.name_prefix in (name or "").upper()

    async def release_stale_connection(self):
        """Disconnect the treadmill if BlueZ still holds a connection to it."""
        try:
            from dbus_fast import BusType, Message, MessageType
            from dbus_fast.aio import MessageBus
        except ImportError:
            return
        bus = None
        try:
            bus = await MessageBus(bus_type=BusType.SYSTEM).connect()
            reply = await bus.call(Message(destination="org.bluez", path="/",
                                           interface="org.freedesktop.DBus.ObjectManager",
                                           member="GetManagedObjects"))
            if reply.message_type == MessageType.ERROR:
                log.debug("GetManagedObjects failed: %s", reply.body)
                return
            for path, ifaces in reply.body[0].items():
                dev = ifaces.get("org.bluez.Device1")
                if not dev or not dev.get("Connected") or not dev["Connected"].value:
                    continue
                address = dev["Address"].value
                name = dev["Name"].value if "Name" in dev else ""
                if not self._matches(address, name):
                    continue
                log.warning("BlueZ still has a connection to %s (%s) from an earlier run; "
                            "disconnecting it so the treadmill advertises again.", name, address)
                res = await bus.call(Message(destination="org.bluez", path=path,
                                             interface="org.bluez.Device1", member="Disconnect"))
                if res.message_type == MessageType.ERROR:
                    log.warning("Disconnect failed: %s", res.body)
                else:
                    await asyncio.sleep(2)  # give the treadmill a moment to start advertising
        except Exception as exc:  # noqa: BLE001
            log.debug("Stale-connection check skipped: %s", exc)
        finally:
            if bus is not None:
                bus.disconnect()

    async def setup_notifications(self, client):
        self.log_gatt(client)
        self.last_echelon_rx = self.last_ftms_rx = 0.0
        write_char = client.services.get_characteristic(WRITE_UUID)
        self.has_echelon = write_char is not None
        if self.has_echelon:
            # Qt (and so QZ) writes with response by default; only skip the
            # response if the characteristic can't do it.
            self.write_response = "write" in write_char.properties
            log.info("Writing commands %s response.", "with" if self.write_response else "without")
        else:
            log.warning("No Echelon command characteristic; relying on other services only.")
        for char in [c for svc in client.services for c in svc.characteristics]:
            if not {"notify", "indicate"} & set(char.properties):
                continue
            uuid = char.uuid.lower()
            if uuid in NOTIFY_UUIDS:
                handler = self.on_notify
            elif uuid == FTMS_TREADMILL_DATA:
                handler = self.on_ftms
            else:
                handler = self.on_other_notify
            try:
                await client.start_notify(char, handler)
                log.info("Subscribed to %s", uuid)
            except Exception as exc:  # noqa: BLE001
                log.warning("Couldn't subscribe to %s: %s", uuid, exc)

    async def send_init(self):
        if not self.has_echelon:
            return
        for cmd in INIT_STRIDE4 if self.is_stride4 else INIT_GENERIC:
            try:
                await self.write(cmd)
            except Exception as exc:  # noqa: BLE001
                log.warning("init write %s failed: %s", cmd, exc)
            await asyncio.sleep(0.3)

    async def send_poll(self):
        if not self.has_echelon:
            return
        pkt = [0xF0, 0xA0, 0x01, self.poll_counter]
        pkt.append(checksum(pkt))
        self.poll_counter = self.poll_counter + 1 if self.poll_counter < 255 else 1
        await self.write(pkt)


def parse_ftms_treadmill(data):
    """Decode a Bluetooth FTMS Treadmill Data characteristic value."""
    flags = int.from_bytes(data[0:2], "little")
    i = 2
    out = {}

    def take(n, signed=False):
        nonlocal i
        if i + n > len(data):
            raise IndexError("short FTMS packet")
        v = int.from_bytes(data[i:i + n], "little", signed=signed)
        i += n
        return v

    if not flags & 0x0001:            # bit 0 clear = instantaneous speed present
        out["speed_kmh"] = take(2) / 100.0
    if flags & 0x0002:                # average speed
        take(2)
    if flags & 0x0004:                # total distance (m)
        out["distance_m"] = take(3)
    if flags & 0x0008:                # inclination + ramp angle
        out["incline"] = take(2, signed=True) / 10.0
        take(2, signed=True)
    if flags & 0x0010:                # elevation gain +/-
        take(2)
        take(2)
    if flags & 0x0020:                # instantaneous pace
        take(1)
    if flags & 0x0040:                # average pace
        take(1)
    if flags & 0x0080:                # energy: total, per hour, per minute
        take(2)
        take(2)
        take(1)
    if flags & 0x0100:                # heart rate
        out["heart_rate"] = take(1)
    if flags & 0x0200:                # metabolic equivalent
        take(1)
    if flags & 0x0400:                # elapsed time (s)
        out["elapsed_s"] = take(2)
    return out


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
    log.info("Echelon Monitor version %s%s", APP_VERSION[:7],
             f" (built {APP_BUILD_DATE})" if APP_BUILD_DATE else "")
    log.info("Dashboard: http://%s:%d/", "localhost" if args.host in ("0.0.0.0", "") else args.host, args.port)

    tasks = []
    if args.demo:
        tasks.append(asyncio.create_task(demo_loop(workout)))
    else:
        ble = StrideBLE(workout, args.address, args.name, args.debug, args.protocol)
        tasks.append(asyncio.create_task(ble.run_forever()))
    if args.csv:
        tasks.append(asyncio.create_task(csv_logger(workout, args.csv)))

    # docker stop sends SIGTERM, which a Python PID 1 otherwise ignores until
    # it's SIGKILLed. Cancel the tasks instead so BleakClient disconnects
    # cleanly and the treadmill is free for the next run.
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGTERM, signal.SIGINT):
        try:
            loop.add_signal_handler(sig, lambda s=sig: (log.info("Got %s, disconnecting...", s.name),
                                                        [t.cancel() for t in tasks]))
        except (NotImplementedError, RuntimeError):
            pass
    try:
        await asyncio.gather(*tasks)
    except asyncio.CancelledError:
        pass
    finally:
        await runner.cleanup()
        log.info("Stopped.")


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
    # bleak's own debug output repeats every write; keep it quiet either way.
    logging.getLogger("bleak").setLevel(logging.WARNING)
    try:
        asyncio.run(scan_only() if args.scan else main_async(args))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
