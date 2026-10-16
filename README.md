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
| `--db runs.db` | Save runs to a SQLite file and turn on the Runs page (`/runs`) |
| `--idle-timeout 180` | End a run after this many seconds without belt movement |
| `--csv workout.csv` | Also write one sample per second to a CSV file |
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

## Menu, users and the Android overlay

The ☰ menu on every page links to Live, Runs, **Manage users** and the
**Android overlay app**, and lists your users so you can pick who's running.
The light in the header is green while the treadmill is connected.

- **Users** store weight, height, age and sex. Calories are estimated for the
  selected runner with the ACSM treadmill equations (weight, speed, incline), or
  with the Keytel heart-rate formula when the treadmill reports a heart rate. With
  nobody selected, calories come from the console's own count. Runs are saved
  under the runner, and the Runs page can filter by person.
- **Android overlay app** (`android/`): a small app that shows speed, time and
  distance in a floating box over other apps, such as Jellyfin. GitHub Actions
  builds it and publishes `echelon-overlay.apk` to the `android` release. The
  Docker image includes the latest APK, so the menu can offer it at
  `/echelon-overlay.apk`. The app opens itself when a run starts, and the
  overlay hides while the app is on screen. The screen stays on while the belt
  moves and for 5 minutes after (menu: "Screen on after runs"); then the
  tablet's own timeout applies. Wake on motion only uses the camera while the
  screen is off. In the overlay, calories take turns with time every 10 seconds,
  and a changed incline shows for 15 seconds. It also restarts the tablet's Wi-Fi
  after 5 minutes without a connection, at most once every 30 minutes (Android 9
  and older; newer Android doesn't let apps switch Wi-Fi).

## Face recognition

On the **Users** page, tap **Face** next to a user and take a few photos while
that person looks at the tablet from where they stand on the treadmill. When a
run starts, the Android app takes a few photos within the first seconds; if the
face belongs to someone other than the selected runner, it asks whether to switch.

- The server turns each photo into a face signature (128 numbers) with OpenCV's
  YuNet and SFace models and keeps only that, in the run-history database. Photos
  aren't stored or sent anywhere else.
- It needs run history (`--db`) and the two models in `models/` (the Docker image
  downloads them). Without them, face recognition is simply off.
- The camera is only used in the app, and only while the app is on screen.
- **Clear face** on the same screen deletes a user's signatures.

## Runs

A run starts automatically when the belt starts moving. It ends when the belt has
been stopped for 3 minutes (`--idle-timeout`), or when you tap **End run**. The
idle stretch at the end isn't counted. Runs shorter than 30 seconds are
discarded. Saved runs are listed at `/runs`, where you can tap a run to see its
speed chart or delete it. Heart rate only appears once the treadmill reports
one.

## What's measured

| Field | Source |
| --- | --- |
| Speed | Read from the treadmill (packets `0xD3` and `0xD1`) |
| Incline | Read from the treadmill (packet `0xD2`) |
| Calories | Read from the console's running total (Stride-4s, packet `0xD1`) |
| Heart rate | Read from the treadmill when it reports one (packet `0xD1`, byte 11, from the grip sensors or a paired strap) |
| Distance, time, pace, climb | Calculated from speed and incline over time |

The motor base doesn't report its own distance or time counters, so these values
are calculated. They can drift slightly from what the console shows.

## Troubleshooting

- **"Can't reach the Bluetooth service" (in Docker)**: the host's
  `bluetooth` service isn't running, or the D-Bus socket isn't mounted. Run
  `check_host.sh` on the host.
- **It only reconnects after power-cycling the treadmill**: once a client
  disconnects, the Stride-4s stops advertising. So on shutdown the app leaves the
  Bluetooth link open in BlueZ (on the host), and the next start picks it back up.
  Use `--disconnect-on-exit` to turn this off.
- **"Treadmill not found"**: make sure the belt is powered on and the tablet isn't
  connected. Run `--scan` and look for a device marked `<-- Echelon service`, then
  pass it with `--address`.
- **It connects but the speed stays at 0**: run with `--debug` and look at the
  `RX` lines. If you see packets arriving that this script doesn't decode, the
  hex dump shows what your model sends.
- **The numbers are about 1.6× too high or too low**: toggle `--metric`.
