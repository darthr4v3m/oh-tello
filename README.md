# Oh-Tello

Minimal Android controller for the DJI/Ryze Tello drone, talking directly to the SDK 2.0 UDP
protocol — no bloat, no packer, no crashes on modern 64-bit devices.

A single-module Kotlin/Compose app that opens two UDP sockets and sends the drone plain ASCII
commands. No third-party SDK, no native code, no dependency on Ryze's app pipeline.

---

## Why this exists

The official Ryze Tello app (`com.ryzerobotics.tello`, sideloaded APK, tested at v1.6.7.0 and
v1.6.8.0) crashes on launch on a Pixel 7 running Android 17. Confirmed from logcat and APK
inspection:

- The APK is wrapped in a third-party app-hardening "shell" packer — the manifest declares
  `application android:name="s.h.e.l.l.S"` and `appComponentFactory="s.h.e.l.l.A"`, so the real
  app code is unpacked by that shell at runtime rather than loaded directly by the OS.
- `lib/armeabi-v7a/` in the APK contains `libpatchtool.so`, the packer's native unpack/decrypt
  helper. `lib/arm64-v8a/` does not contain it at all. That is a packaging bug in Ryze's build:
  the packer's native library was only bundled for the 32-bit target.
- On a 64-bit-only device (Pixel 7 onward), Android loads the arm64-v8a lib set, the shell cannot
  unpack its payload without the missing patch tool, and ART's dex verifier rejects the resulting
  malformed dex with `Invalid debug_info_off` — `Unable to instantiate application ...
  ClassNotFoundException: s.h.e.l.l.S`. This happens before `Application.onCreate()`, so no app
  code of theirs ever runs.
- There is no user-side workaround. Clearing cache and storage, granting every permission, and
  trying both app versions all still crash.

Reported to Ryze/DJI support with those findings. If they ship a fixed arm64 build, the official
app should work again — this project is not waiting on that.

**Oh-Tello does not touch, patch, or repackage Ryze's APK in any way.** It is a clean-room
implementation against DJI's published Tello SDK 2.0 UDP protocol.

## Status

v1 scope is implemented: handshake, takeoff/land, discrete movement, telemetry, and a command
console.

| Milestone | State |
| --- | --- |
| 1. `command` handshake with the raw response shown | done |
| 2. Takeoff / land | done |
| 3. State-port listener, battery + staleness flag | done |
| 4. Discrete movement buttons, clamped to 20–500 cm | done |
| 5. Continuous `rc` joystick control (stretch) | not started — the protocol layer supports `rc`, there is no stick UI |
| 6. Video stream via port 11111 (stretch) | not started |

Also in, beyond the v1 list: a two-tap `emergency` motor cutoff, an idle keepalive so the drone
does not auto-land after 15 seconds of silence, and Wi-Fi socket binding (see
[Talking to a network with no internet](#talking-to-a-network-with-no-internet)).

**Not yet flown.** The protocol and controller layers have unit tests that pass (see
[Testing](#testing)), but nothing here has been run against a real drone yet, and the Android
build itself has not been compiled — it was written in an environment with no access to Google's
Maven repo or the Android SDK. First run should be a bench test with the props off.

## Licence

[PolyForm Noncommercial 1.0.0](LICENSE). In plain language: free for personal, hobby,
educational, research, and nonprofit use; commercial use and resale are not permitted. It is
deliberately not an OSI-approved licence. The copyright holder line at the top of `LICENSE` says
`darthr4v3m` — change it if you want your legal name there instead.

## Building

Requires Android Studio (or a local Android SDK) and JDK 17+.

```bash
./gradlew assembleDebug          # APK at app/build/outputs/apk/debug/
./gradlew installDebug           # to a connected device
./gradlew test                   # protocol + controller unit tests, no device needed
```

Dependency versions are pinned in `gradle/libs.versions.toml` and are deliberately conservative
(AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.12.01). Bump them once the project builds cleanly for
you.

`minSdk` is 24, `targetSdk` 35.

## Flying it

1. Power on the drone and wait for the front light to blink amber.
2. In Android's Wi-Fi settings, join `TELLO-XXXXXX`. Android will warn that the network has no
   internet — stay on it anyway, and decline any offer to switch back to mobile data.
3. Open Oh-Tello and press **Connect**. The console should show `→ command` then `← ok`, and the
   battery readout should start updating.
4. **TAKE OFF** / **LAND**, then the D-pad for movement. The step-size chips (20/30/50/100 cm) and
   yaw chips (30/45/90/180°) set how far each press moves the drone.
5. **Emergency stop** needs two taps within three seconds. It cuts the motors instantly — the
   drone drops. It is for when something has already gone wrong.

Movement buttons grey out while a command is in flight, because the drone answers one command at a
time and queued-up presses would execute long after you meant them. **LAND** deliberately stays
live: if the queue is busy, land skips it rather than waiting out a 20-second timeout.

Keep the prop guards on for every development test flight.

### Safety notes

- The drone auto-lands if it hears nothing for 15 seconds. The app sends an idle `command`
  keepalive every 5 seconds to prevent that, which also means **the drone will not land itself if
  you walk away** — land it deliberately.
- A command timeout does not stop the drone. It means the app stopped waiting for the reply, not
  that the drone stopped moving.
- Battery below 20% shows a warning. The drone gets unhappy well before 0%.

## How it works

```
app/src/main/java/io/github/darthr4v3m/ohtello/
├── MainActivity.kt              Compose host, keeps the screen awake while flying
├── tello/
│   ├── protocol/                pure Kotlin, no Android imports — all of this is unit tested
│   │   ├── TelloCommands.kt     command strings + range clamping
│   │   ├── TelloResponse.kt     ok / value / error / timeout parsing
│   │   └── TelloState.kt        telemetry packet parsing
│   ├── TelloController.kt       the sockets, the serial command queue, the StateFlows
│   ├── SocketBinder.kt          interface for pinning a socket to a network
│   └── WifiSocketBinder.kt      the Android implementation of that
└── ui/                          Compose screen + ViewModel
```

### The protocol

Tello SDK 2.0, over UDP, with the phone joined to the drone's own access point:

| Channel | Port | Direction |
| --- | --- | --- |
| Commands | local 8889 → `192.168.10.1:8889` | request/response, ASCII, no newline |
| State | local 8890 | drone pushes ~10x/sec, unprompted |
| Video | local 11111 | raw H.264 after `streamon` (not implemented) |

`command` must be sent first to enter SDK mode; the drone replies `ok`. Full spec: *Tello SDK
Documentation EN_1.3*, linked from ryzerobotics.com/tello/downloads.

### The two rules that matter

The drone does not queue commands. It answers exactly one at a time, and anything sent before the
previous reply arrives is dropped silently — which in the air looks like the drone ignoring you.
So every send takes a mutex, writes, and waits for its reply or a timeout before the next may
start.

The other half of that: after a timeout, the reply may still turn up. If it were left in the
queue, it would be read as the answer to the *following* command, putting every later reply one
command out of step — which is much worse than the original timeout, because nothing looks wrong.
Each send therefore drains anything left over before writing. Both behaviours have tests.

### Talking to a network with no internet

The Tello's access point has no internet, so Android keeps mobile data as the process-wide default
network and an unbound UDP socket sends the drone's commands out over cellular, where they vanish.
Both sockets are therefore pinned to the Wi-Fi network with `Network.bindSocket()` before use. That
needs `ACCESS_NETWORK_STATE`, and it is the difference between the app working and the app timing
out on a phone with a SIM in it.

Permissions used, in full: `INTERNET` (required for any socket, even a purely local one) and
`ACCESS_NETWORK_STATE`. No location permission — the app never scans for networks, it only looks at
the one you already joined.

## Testing

`./gradlew test` runs 32 tests, none of which need a device:

- `TelloCommandsTest`, `TelloResponseTest`, `TelloStateTest` — command building and clamping,
  response parsing (`ok`, `OK`, values, `error Not joystick`, `out of range`, CRLF and NUL
  padding), and telemetry parsing against a real captured packet, including truncated and
  unfamiliar-key packets.
- `TelloControllerTest` — drives the real controller against `FakeDrone`, a loopback UDP stand-in,
  over real sockets and real time: handshake, a silent drone failing instead of hanging, strict
  serialisation of overlapping commands, the late-reply drain, telemetry freshness going stale
  after 2s of silence, `rc` being sent without waiting for a reply that never comes, and
  disconnect/reconnect.

What that does **not** cover: the Compose UI, the Wi-Fi binder, and anything about how a real
drone behaves. First real-drone smoke test, props off:

1. Connect — expect `← ok` in the console.
2. Press `battery?` — expect a number. That validates the whole socket/response pipeline without
   touching the motors.
3. Watch the telemetry row update, then walk out of range and confirm the status chip flips to
   "no telemetry".

Emulators cannot test any of this; it needs real Wi-Fi and a real drone.

## On KTello

Two existing Kotlin wrappers were reviewed before writing this — `victor-vct/KTello` (MIT, Android,
~135 lines) and `ivanocj/ktello` (GPL-2.0, JVM, ~210 lines). Neither is used, for concrete reasons:

- Neither implements the two rules above. Both send and then block on a single `receive()` with no
  queue discipline, so a late reply desynchronises everything after it.
- Neither reads the state port, so there is no telemetry at all.
- `victor-vct/KTello` logs responses to logcat and returns `Unit`, so a caller cannot tell success
  from `error`, and it dispatches through `GlobalScope`.
- `ivanocj/ktello` compares replies against `"OK"` while the drone answers lowercase `ok`, so every
  `isOK()` returns false; it decodes the whole 1024-byte receive buffer instead of the packet's
  actual length, leaving NUL padding on every reply; and it sets no socket timeout, so a lost
  datagram blocks forever. It is also GPL-2.0, which would not fit this project's licence.

The protocol is roughly 200 lines of Kotlin. Hand-rolling it costs less than patching an
unmaintained dependency. For cross-checking edge cases, `djitellopy` (Python, MIT) is the mature
reference implementation — its 7-second response timeout and 20-second takeoff timeout are the
values used here.

## Roadmap

- Continuous `rc a b c d` stick control on a ~50 ms tick, zeroed on release, with a watchdog that
  sends `rc 0 0 0 0` when the app loses foreground focus.
- Video via `streamon` and `MediaCodec` H.264 decode on port 11111 — only once core control has
  proven solid over a few real flights.
- Flips, mission pads, and the EDU-only commands. Deliberately out of v1.
