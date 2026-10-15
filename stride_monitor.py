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
import sqlite3
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
# Model code reported in the 0xA1 reply; the Stride-4s reports 0x20.
MODEL_STRIDE_4S = 0x20
# Read-only queries the Echelon app sends after the basic init on this model.
INIT_STRIDE_4S_EXTRA = ["f0a20092", "f0a50095", "f0a60096", "f0a70097", "f0a80098"]

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


class RunStore:
    """Saves runs and their per-second samples to SQLite."""

    MIN_RUN_SECONDS = 30  # shorter runs are discarded as noise

    def __init__(self, path):
        self.db = sqlite3.connect(path)
        self.db.row_factory = sqlite3.Row
        self.db.executescript("""
            CREATE TABLE IF NOT EXISTS runs (
                id INTEGER PRIMARY KEY,
                started REAL NOT NULL,
                ended REAL,
                moving_s REAL,
                distance REAL,
                dist_unit TEXT,
                avg_speed REAL,
                max_speed REAL,
                elevation_ft REAL,
                avg_hr REAL,
                max_hr INTEGER,
                calories INTEGER
            );
            CREATE TABLE IF NOT EXISTS samples (
                run_id INTEGER NOT NULL,
                t REAL NOT NULL,
                speed REAL,
                incline REAL,
                hr INTEGER,
                distance REAL,
                elevation_ft REAL,
                calories INTEGER
            );
            CREATE INDEX IF NOT EXISTS samples_run ON samples(run_id);
        """)
        # Add columns introduced after the first release.
        for table in ("runs", "samples"):
            cols = {r[1] for r in self.db.execute(f"PRAGMA table_info({table})")}
            if "calories" not in cols:
                self.db.execute(f"ALTER TABLE {table} ADD COLUMN calories INTEGER")
        self.db.commit()
        # Finish runs left open by a crash or restart.
        for row in self.db.execute("SELECT id FROM runs WHERE ended IS NULL").fetchall():
            self.finish(row["id"])

    def start(self, started, dist_unit):
        cur = self.db.execute("INSERT INTO runs (started, dist_unit) VALUES (?, ?)", (started, dist_unit))
        self.db.commit()
        return cur.lastrowid

    def add_sample(self, run_id, t, speed, incline, hr, distance, elevation_ft, calories=None):
        self.db.execute("INSERT INTO samples (run_id, t, speed, incline, hr, distance, elevation_ft, calories)"
                        " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        (run_id, t, speed, incline, hr or None, distance, elevation_ft, calories))
        self.db.commit()

    def finish(self, run_id):
        """Compute the run summary from its samples. Returns False if it was too short to keep."""
        # Drop the idle stretch that ended the run, so it ends at the last movement.
        last = self.db.execute("SELECT MAX(t) FROM samples WHERE run_id = ? AND speed > 0",
                               (run_id,)).fetchone()[0]
        if last is not None:
            self.db.execute("DELETE FROM samples WHERE run_id = ? AND t > ?", (run_id, last + 1))
        r = self.db.execute("""
            SELECT MAX(t) AS ended,
                   SUM(CASE WHEN speed > 0 THEN 1 ELSE 0 END) AS moving_s,
                   MAX(distance) AS distance, MAX(speed) AS max_speed,
                   MAX(elevation_ft) AS elevation_ft,
                   AVG(hr) AS avg_hr, MAX(hr) AS max_hr,
                   MAX(calories) - MIN(calories) AS calories
            FROM samples WHERE run_id = ?""", (run_id,)).fetchone()
        moving = r["moving_s"] or 0
        if moving < self.MIN_RUN_SECONDS:
            self.delete(run_id)
            return False
        distance = r["distance"] or 0.0
        self.db.execute("""
            UPDATE runs SET ended = ?, moving_s = ?, distance = ?, avg_speed = ?, max_speed = ?,
                            elevation_ft = ?, avg_hr = ?, max_hr = ?, calories = ?
            WHERE id = ?""",
                        (r["ended"], moving, distance, distance / (moving / 3600.0), r["max_speed"],
                         r["elevation_ft"], r["avg_hr"], r["max_hr"], r["calories"] or None, run_id))
        self.db.commit()
        return True

    def delete(self, run_id):
        self.db.execute("DELETE FROM samples WHERE run_id = ?", (run_id,))
        self.db.execute("DELETE FROM runs WHERE id = ?", (run_id,))
        self.db.commit()

    def list_runs(self):
        rows = self.db.execute("SELECT * FROM runs WHERE ended IS NOT NULL ORDER BY started DESC")
        return [dict(r) for r in rows]

    def samples(self, run_id):
        rows = self.db.execute("SELECT t, speed, incline, hr FROM samples WHERE run_id = ? ORDER BY t",
                               (run_id,))
        return [dict(r) for r in rows]


class Workout:
    """Current treadmill state, the active run and values integrated over time.

    A run starts when the belt starts moving and ends automatically after
    `idle_timeout` seconds without movement (or when ended from the page).
    """

    def __init__(self, metric, store=None, idle_timeout=180):
        self.metric = metric
        self.store = store
        self.idle_timeout = idle_timeout
        self.connected = False
        self.device = None
        self.speed = 0.0          # in the treadmill's native unit (mph or km/h)
        self.incline = 0
        self.heart_rate = 0
        self.hr_seen = False      # only show heart rate once the treadmill reports one
        self.calories = None      # console's calorie count (Stride-4s), None if not reported
        self.console_seconds = None
        self.console_distance = None
        self._run_cal_start = None
        self.raw = ""
        self.run_id = None
        self.run_started = None
        self.last_saved = None    # summary of the run that just ended
        self._last_tick = None
        self._last_active = None
        self.reset_stats()

    def reset_stats(self):
        self.distance = 0.0       # same unit as speed (mi or km)
        self.elevation_ft = 0.0
        self.moving_seconds = 0.0
        self.max_speed = 0.0

    def set_heart_rate(self, bpm):
        if bpm:
            self.heart_rate = bpm
            self.hr_seen = True

    def tick(self):
        now = time.monotonic()
        if self._last_tick is not None and self.speed > 0:
            if self.run_id is None:
                self.start_run()
            dt = now - self._last_tick
            step = self.speed * dt / 3600.0
            self.distance += step
            self.moving_seconds += dt
            miles = step if not self.metric else step / 1.60934
            self.elevation_ft += miles * 5280 * self.incline / 100.0
            self.max_speed = max(self.max_speed, self.speed)
        if self.speed > 0:
            self._last_active = now
        elif self.run_id is not None and now - self._last_active >= self.idle_timeout:
            log.info("No movement for %ds; ending run.", self.idle_timeout)
            self.end_run()
        self._last_tick = now

    def start_run(self):
        self.reset_stats()
        self.last_saved = None
        self.run_started = time.time()
        self._last_active = time.monotonic()
        self._run_cal_start = self.calories
        if self.store:
            self.run_id = self.store.start(self.run_started, "km" if self.metric else "mi")
        else:
            self.run_id = 0
        log.info("Run started.")

    def end_run(self):
        if self.run_id is None:
            return
        kept = self.store.finish(self.run_id) if self.store else True
        log.info("Run ended: %.2f %s in %ds%s.", self.distance, "km" if self.metric else "mi",
                 self.moving_seconds, "" if kept else " (too short, discarded)")
        self.last_saved = kept
        self.run_id = None

    def record_sample(self):
        if self.run_id and self.store:
            self.store.add_sample(self.run_id, time.time(), round(self.speed, 2), self.incline,
                                  self.heart_rate if self.hr_seen else None,
                                  round(self.distance, 4), round(self.elevation_ft, 1), self.calories)

    def run_calories(self):
        """Calories burned this run, from the console's running total."""
        if self.calories is None:
            return None
        if self._run_cal_start is None or self.calories < self._run_cal_start:
            # First reading of this run, or the console started a new count.
            self._run_cal_start = self.calories if self._run_cal_start is None else 0
        return self.calories - self._run_cal_start

    def snapshot(self):
        unit = "km/h" if self.metric else "mph"
        pace = None
        if self.speed > 0.3:
            pace = 60.0 / self.speed  # minutes per mile/km
        idle_left = None
        if self.run_id is not None and self.speed <= 0 and self._last_active is not None:
            idle_left = max(0, int(self.idle_timeout - (time.monotonic() - self._last_active)))
        return {
            "connected": self.connected,
            "device": self.device,
            "speed": round(self.speed, 2),
            "speed_unit": unit,
            "incline": self.incline,
            "heart_rate": self.heart_rate,
            "hr_seen": self.hr_seen,
            "calories": self.run_calories(),
            "distance": round(self.distance, 3),
            "distance_unit": "km" if self.metric else "mi",
            "pace_min": pace,
            "pace_unit": "/km" if self.metric else "/mi",
            "elevation_ft": round(self.elevation_ft, 1),
            "moving_seconds": int(self.moving_seconds),
            "max_speed": round(self.max_speed, 2),
            "run_active": self.run_id is not None,
            "idle_left": idle_left,
            "last_saved": self.last_saved,
            "history": self.store is not None,
            "raw": self.raw,
            "ts": time.time(),
            "version": APP_VERSION,
            "built": APP_BUILD_DATE,
        }


def checksum(data):
    return sum(data) & 0xFF


class StrideBLE:
    def __init__(self, workout, address, name_prefix, debug, protocol="auto", disconnect_on_exit=False):
        self.workout = workout
        self.disconnect_on_exit = disconnect_on_exit
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
        self.last_d3 = 0.0
        self.last_d1 = b""
        self.model = None

    async def find_device(self):
        from bleak import BleakScanner

        try:
            known = await self.known_treadmills()
        except Exception as exc:  # noqa: BLE001
            log.debug("BlueZ lookup failed: %s", exc)
            known = []
        for path, address, name, connected in known:
            if connected:
                from bleak.backends.device import BLEDevice

                log.info("BlueZ is still connected to %s; picking that connection back up.", address)
                return BLEDevice(address, name, {"path": path, "props": {}})

        if self.address:
            log.info("Scanning for %s ...", self.address)
            device = await BleakScanner.find_device_by_address(self.address, timeout=15)
        else:
            log.info("Scanning for a treadmill whose name contains %r ...", self.name_prefix)
            device = await BleakScanner.find_device_by_filter(
                lambda d, adv: self.name_prefix in (d.name or adv.local_name or "").upper()
                or SERVICE_UUID in [u.lower() for u in adv.service_uuids],
                timeout=15,
            )
        if device is not None:
            return device
        # The scan can miss a treadmill BlueZ already knows. Connecting through
        # its BlueZ device path doesn't need a fresh scan result.
        if known:
            from bleak.backends.device import BLEDevice

            path, address, name, _ = known[0]
            log.info("Not seen in the scan; trying a direct connection to %s.", address)
            return BLEDevice(address, name, {"path": path, "props": {}})
        return None

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
        w.set_heart_rate(parsed.get("heart_rate"))
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
        now = time.monotonic()
        if kind == 0xD3 and len(data) >= 5:
            self.last_d3 = now
            w.speed = ((data[3] << 8) | data[4]) / 1000.0
        elif kind == 0xD2 and len(data) >= 4:
            w.incline = data[3]
        elif kind == 0xA1 and len(data) >= 4:
            if self.model != data[3]:
                self.model = data[3]
                log.info("Treadmill model code 0x%02x", self.model)
        elif kind == 0xD1:
            if bytes(data) != self.last_d1:
                log.debug("status %s", data.hex(" "))
                self.last_d1 = bytes(data)
            if self.model == MODEL_STRIDE_4S and len(data) >= 13:
                # Layout used by the Echelon app for this model: elapsed seconds,
                # distance (thousandths of the console's unit), calories, heart rate.
                w.console_seconds = (data[3] << 8) | data[4]
                w.console_distance = int.from_bytes(data[5:9], "big") / 1000.0
                w.calories = (data[9] << 8) | data[10]
                w.set_heart_rate(data[11])
            else:
                # Stride4-family consoles report speed here (hundredths). Use it
                # unless the treadmill is also sending 0xD3 speed packets.
                if len(data) >= 9 and (self.is_stride4 or now - self.last_d3 > 5):
                    w.speed = ((data[7] << 8) | data[8]) / 100.0
                if len(data) > 11:
                    w.set_heart_rate(data[11])
        w.tick()
        # The motor base expects these packets to be echoed back as an ack
        # (the Echelon app also acks 0xD4 on the Stride-4s).
        if kind in (0xD0, 0xD2, 0xD3) or (kind == 0xD4 and self.model == MODEL_STRIDE_4S):
            asyncio.get_running_loop().create_task(self._safe_write(bytes(data)))

    async def _safe_write(self, data):
        try:
            await self.write(data)
        except Exception as exc:  # noqa: BLE001
            log.debug("echo failed: %s", exc)

    async def run_forever(self):
        from bleak import BleakClient

        while True:
            client = None
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
                client = BleakClient(device, disconnected_callback=lambda _c: disconnected.set())
                await client.connect()
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
            except asyncio.CancelledError:
                # Shutting down. Once disconnected, the Stride-4s stops advertising
                # until it's power-cycled, so by default leave the link to BlueZ
                # (which runs on the host and outlives this process); the next
                # start picks the same connection back up.
                if client is not None and client.is_connected and self.disconnect_on_exit:
                    await client.disconnect()
                    log.info("Disconnected from the treadmill.")
                elif client is not None and client.is_connected:
                    log.info("Leaving the Bluetooth link open for the next start.")
                raise
            except FileNotFoundError:
                log.error("Can't reach the Bluetooth service (BlueZ) over D-Bus. On the host, run "
                          "check_host.sh; in Docker, mount /run/dbus/system_bus_socket.")
                await asyncio.sleep(10)
            except Exception as exc:  # noqa: BLE001
                log.warning("BLE error: %s", exc)
                if client is not None:
                    try:
                        await client.disconnect()
                    except Exception:  # noqa: BLE001
                        pass
            self.workout.connected = False
            self.workout.speed = 0.0
            await asyncio.sleep(3)

    def _matches(self, address, name):
        if self.address:
            return address.upper() == self.address.upper()
        return self.name_prefix in (name or "").upper()

    async def _bluez_call(self, path, interface, member):
        from dbus_fast import BusType, Message, MessageType
        from dbus_fast.aio import MessageBus

        bus = await MessageBus(bus_type=BusType.SYSTEM).connect()
        try:
            reply = await bus.call(Message(destination="org.bluez", path=path,
                                           interface=interface, member=member))
            if reply.message_type == MessageType.ERROR:
                raise RuntimeError(f"{member} failed: {reply.error_name} {reply.body}")
            return reply.body
        finally:
            bus.disconnect()

    async def known_treadmills(self):
        """Treadmills BlueZ already knows about: [(path, address, name, connected)]."""
        body = await self._bluez_call("/", "org.freedesktop.DBus.ObjectManager", "GetManagedObjects")
        found = []
        for path, ifaces in body[0].items():
            dev = ifaces.get("org.bluez.Device1")
            if not dev:
                continue
            address = dev["Address"].value
            name = dev["Name"].value if "Name" in dev else ""
            if self._matches(address, name):
                connected = bool(dev["Connected"].value) if "Connected" in dev else False
                found.append((path, address, name, connected))
        return found

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
        cmds = INIT_STRIDE4 if self.is_stride4 else INIT_GENERIC
        # The 0xA1 reply tells us the model; the extra queries depend on it.
        for cmd in cmds + [None] + INIT_STRIDE_4S_EXTRA:
            if cmd is None:
                if self.model != MODEL_STRIDE_4S:
                    break
                continue
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
        # Walk for 50s, then stand still for 20s, so auto-ending runs can be tried.
        moving = (t % 70) < 50
        workout.speed = round(3.0 + 1.5 * (1 + math.sin(t / 20)), 1) if moving else 0.0
        workout.incline = int(3 + 3 * math.sin(t / 45))
        workout.set_heart_rate(int(110 + workout.speed * 8 + random.randint(-2, 2)))
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

    async def runs_page(_request):
        return web.FileResponse(os.path.join(HERE, "runs.html"))

    async def runs_api(_request):
        if not workout.store:
            return web.json_response({"history": False, "runs": []})
        return web.json_response({"history": True, "runs": workout.store.list_runs()})

    async def run_samples(request):
        if not workout.store:
            raise web.HTTPNotFound()
        return web.json_response(workout.store.samples(int(request.match_info["run_id"])))

    async def run_delete(request):
        if not workout.store:
            raise web.HTTPNotFound()
        run_id = int(request.match_info["run_id"])
        if run_id == workout.run_id:
            return web.json_response({"ok": False, "error": "run in progress"}, status=409)
        workout.store.delete(run_id)
        return web.json_response({"ok": True})

    async def state(_request):
        return web.json_response(workout.snapshot())

    async def end_run(_request):
        workout.end_run()
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
                try:
                    workout.record_sample()
                except sqlite3.Error as exc:
                    log.warning("Couldn't save sample: %s", exc)
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
    app.router.add_post("/api/end", end_run)
    app.router.add_get("/runs", runs_page)
    app.router.add_get("/api/runs", runs_api)
    app.router.add_get("/api/runs/{run_id:\\d+}/samples", run_samples)
    app.router.add_delete("/api/runs/{run_id:\\d+}", run_delete)
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
    store = None
    if args.db:
        store = RunStore(args.db)
        log.info("Saving runs to %s", args.db)
    workout = Workout(metric=args.metric, store=store, idle_timeout=args.idle_timeout)
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
        ble = StrideBLE(workout, args.address, args.name, args.debug, args.protocol,
                        args.disconnect_on_exit)
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
        workout.end_run()
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
    p.add_argument("--db", help="SQLite file to save runs in (enables the runs page)")
    p.add_argument("--idle-timeout", type=int, default=180,
                   help="end a run after this many seconds without movement (default 180)")
    p.add_argument("--csv", help="also append per-second samples to this CSV file")
    p.add_argument("--demo", action="store_true", help="generate fake data (no treadmill needed)")
    p.add_argument("--scan", action="store_true", help="list nearby BLE devices and exit")
    p.add_argument("--disconnect-on-exit", action="store_true",
                   help="drop the Bluetooth link on shutdown (by default it's left to BlueZ so a "
                        "restart can reuse it)")
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
