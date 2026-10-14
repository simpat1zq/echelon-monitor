# Echelon Monitor

Live web dashboard for an **Echelon Stride / Stride-4s** treadmill that skips the
tablet and the Echelon app. It talks to the treadmill's motor base over
Bluetooth LE, decodes speed, incline and heart rate, and serves a page you can
open from any phone, laptop or TV on your Wi-Fi.

The protocol comes from the open source
[QZ / qdomyos-zwift](https://github.com/cagnulein/qdomyos-zwift) project's
`echelonstride` driver.

## What you need

- A computer with Bluetooth within about 10 m of the treadmill: a Raspberry Pi
  3/4/5 or Zero 2 W, an old laptop, or a Mac.
- Python 3.9 or newer.
- **The tablet and the Echelon app must not be connected to the treadmill.** The
  motor base accepts only one Bluetooth connection at a time. Turn the tablet's
  Bluetooth off, or unplug the tablet.

## Run it

```bash
pip install -r requirements.txt

# Optional: check that the treadmill shows up
python3 stride_monitor.py --scan

# Start the dashboard. It scans for a device whose name contains "STRIDE"
python3 stride_monitor.py
```

Then open `http://<computer-ip>:8080/` on your phone.

Useful options:

| Flag | What it does |
| --- | --- |
| `--address AA:BB:..` | Connect to this device instead of scanning (on macOS, use the UUID that `--scan` prints) |
| `--name TEXT` | Scan for a device whose name contains TEXT, if yours doesn't contain `STRIDE` |
| `--protocol generic\|stride4` | Force a startup sequence. The default picks `stride4` only when the name starts with `STRIDE4`. Try the other one if the speed never updates |
| `--metric` | Use this if the console is set to km/h |
| `--csv workout.csv` | Write one sample per second to a CSV file |
| `--demo` | Show fake data so you can try the page without the treadmill |
| `--debug` | Log every raw BLE packet (useful if a value looks wrong) |

On Linux you may need to run it with `sudo` the first time, or add your user to
the `bluetooth` group.

## Run it in Docker (Linux)

Bluetooth stays on the host. The container talks to the host's BlueZ service
through the system D-Bus socket, so the container doesn't need its own
Bluetooth stack, host networking or `--privileged`.

1. **Check the host first** (not in Docker). Turn the treadmill on and the
   tablet's Bluetooth off, then run:

   ```bash
   bash check_host.sh
   ```

   It checks the Bluetooth adapter, the BlueZ service and the D-Bus socket. Then it
   scans for 20 seconds and prints the treadmill's address. Put that address in
   `compose.yaml` (`--address=...`).

2. **Deploy.** `compose.yaml` is set up for the home server: it joins the external
   `arrstack` network that Caddy is on and stores data in
   `/mnt/wd1/echelon/data`. In Komodo, create a Stack that points at this repo,
   branch `main`, with the default run directory and compose file. Click
   **Deploy** after each push to pull the new image. GitHub Actions builds
   `ghcr.io/simpat1zq/echelon-monitor:latest` on every push to `main`, so wait
   for that run to finish first (about 1–2 minutes). Without Komodo:

   ```bash
   docker compose up -d
   docker logs -f echelon        # wait for "Connected. Streaming data."
   ```

3. **Caddy:**

   ```
   echelon.sphome.top {
   	tls internal
   	reverse_proxy echelon:8080
   }
   ```

   Caddy proxies the live WebSocket with no extra settings.

To test the page without the treadmill:
`docker compose run --rm echelon --demo`.

**Range:** Bluetooth LE normally reaches about 30 ft (10 m), but walls and the
treadmill's metal frame cut that down. If the logs show repeated disconnects,
use a USB Bluetooth 5.0 dongle on a USB extension cable placed closer to the
treadmill.

## What's measured

| Field | Source |
| --- | --- |
| Speed | Read from the treadmill (packets `0xD3` and `0xD1`) |
| Incline | Read from the treadmill (packet `0xD2`) |
| Heart rate | Read from the treadmill when it reports one (packet `0xD1`, byte 11, from the grip sensors or a paired strap) |
| Distance, time, pace, climb | Calculated from speed and incline over time |

The motor base doesn't report its own distance or time counters, so these values
are calculated. They can drift slightly from what the console shows.

## Troubleshooting

- **"Can't reach the Bluetooth service" (in Docker)**: the host's
  `bluetooth` service isn't running, or the D-Bus socket isn't mounted. Run
  `check_host.sh` on the host.
- **"Treadmill not found"**: make sure the belt is powered on and the tablet isn't
  connected. Run `--scan` and look for a device marked `<-- Echelon service`, then
  pass it with `--address`.
- **It connects but the speed stays at 0**: run with `--debug` and look at the
  `RX` lines. If you see packets arriving that this script doesn't decode, the
  hex dump shows what your model sends.
- **The numbers are about 1.6× too high or too low**: toggle `--metric`.
