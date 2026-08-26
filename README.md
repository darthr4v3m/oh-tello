<img src="docs/img/oh-tello-icon.svg" alt="" width="96">

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

Also in, beyond the v1 list: a two-tap `emergency` motor cutoff; an idle keepalive so the drone
does not auto-land while you are looking at it, sent only while the app is in the foreground so
that walking away hands control back to the drone's own failsafe; a flight recorder that writes the
telemetry stream to a CSV; a back-gesture guard that offers to land first; and Wi-Fi socket binding
(see [Talking to a network with no internet](#talking-to-a-network-with-no-internet)).

**Acceptance tested against a real drone.** [docs/UAT.md](docs/UAT.md) — Part A 20/20, Part B 15/15,
Part C 2/2, with Part B run against a pinned build so that fixes could not invalidate results
mid-run. Four defects were found and fixed before that run, and nothing regressed during it.

**Flown.** First real flight on 13 August 2026, from a Pixel 7 running Android 17 — the device the
official app cannot launch on. Connect and handshake, takeoff, land, the discrete moves, yaw, and
altitude all worked against the drone. The protocol and controller layers also have unit tests that
pass without a device (see [Testing](#testing)).

## Licence

[PolyForm Noncommercial 1.0.0](LICENSE). In plain language: free for personal, hobby,
educational, research, and nonprofit use; commercial use and resale are not permitted. It is
deliberately not an OSI-approved licence. The copyright holder line at the top of `LICENSE` says
`darthr4v3m` — change it if you want your legal name there instead.

## Building

You do not need a local Android SDK to get a build onto a phone. Every CI run publishes an
installable debug APK to a prerelease:

- **From a pull request** — a rolling `pr-<n>` prerelease. CI posts a comment on the PR with a
  direct link, edited in place as new commits land, so the link at the top of the thread is always
  the current build. The previous APK is deleted on each push, so there is no stale row to install
  by mistake.
- **A release** — push a `v*` tag and that commit is published as a real
  [release](../../releases), which nothing later overwrites.

Pushing to `main` builds and tests but publishes nothing. Cutting a release is a deliberate act:

```bash
git tag v0.1.0 && git push origin v0.1.0
```

Or, with no checkout to hand: **Actions → Release → Run workflow**, on `main`, with the version in
the box. Tick **dry_run** to check and build without creating anything — it answers "is main
releasable?" without spending a version number on finding out, which is the one question worth
asking before a release rather than after.

Either way the build runs first and the tag is created only if it passes, so a release can never
point at a commit that failed to compile. It refuses a tag that already exists, a tag that does not start
with `v`, and any branch other than `main` — none of which a plain `git tag && git push` checks.
Triggering a workflow needs write access on the repository, the same as pushing a tag, so this route
is open to exactly the same people and no one else.

Releases are a separate workflow from CI (`release.yml` against `android.yml`) because the two jobs
have nothing in common but the build: one runs constantly and produces things meant to be replaced,
the other runs rarely and produces something permanent. They share `.github/actions/build-apk`, so a
release is never built differently from the thing CI tested.

Tap the link on the phone and Android offers to install it; no zip to unpack and no sign-in, since
release assets on a public repo are served directly. Android will ask you to allow installs from
your browser the first time. From a desktop, `adb install app-debug.apk` does the same job.

The same APK is also attached to each run as the `oh-tello-debug-apk` artifact, which is a
login-walled zip — the release link is the friendlier route. The **Run workflow** button on the
Android CI workflow builds an APK from any branch on demand.

These are debug builds, signed with a shared debug key so that successive CI builds replace each
other on a device. Without one, every run generates its own key, the signing certificates differ,
and Android refuses to install one build over another.

The key is not in the repository. It lives in the `DEBUG_KEYSTORE_BASE64` repository secret and is
written to `app/debug.keystore` during the build. To set it up, or to rotate it:

```bash
keytool -genkeypair -keystore debug.keystore -storetype PKCS12 \
  -storepass android -keypass android -alias androiddebugkey \
  -dname "CN=Android Debug,O=Android,C=US" \
  -keyalg RSA -keysize 2048 -validity 10950
base64 -w0 debug.keystore     # paste into Settings > Secrets and variables > Actions
```

The alias must be `androiddebugkey` and both passwords `android` — the signing config asks for the
key by that name. Generate the keystore with `keytool`, not with something else: Windows'
`Export-PfxCertificate`, for instance, names the entry itself and ignores `-FriendlyName`, and the
build then fails at `packageDebug` with "No key with alias 'androiddebugkey'".

On Windows, `keytool` comes with a JDK and is usually not on `PATH`. Android Studio bundles one at
`C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe`; without it,
`winget install EclipseAdoptium.Temurin.21.JDK` is enough. In PowerShell, the line continuation is
a backtick rather than a backslash:

```powershell
$keytool = "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe"
& $keytool -genkeypair -keystore debug.keystore -storetype PKCS12 -storepass android -keypass android -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" -keyalg RSA -keysize 2048 -validity 10950
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$PWD\debug.keystore")) | Set-Clipboard
```

The passwords and alias are Android's debug defaults and the build expects them. Rotating the key
changes the signing certificate, so the next build will not install over an older one — uninstall
once after rotating.

Local builds normally use AGP's own per-machine debug key, so a local build and a CI build will not
install over each other. Put a copy of the same keystore at `app/debug.keystore` if you want them
to; it is gitignored.

### Which build am I holding?

APKs are named `oh-tello-<provenance>-<built>-<commit>-debug.apk`, so a Downloads folder with
several of them in it is readable at a glance:

```
oh-tello-pr1-20260813-1352Z-abcdef1-debug.apk     from pull request 1
oh-tello-v0.1.0-20260814-0907Z-1122334-debug.apk  from main, working towards 0.1.0
```

The timestamp is UTC, and the commit is what pins the file to exact source — two builds can share a
minute, but not a commit.

That only helps until you install it. The same `<provenance>-<commit>` string is therefore stamped
into the app's version name, so an installed build can still say where it came from: it appears
under the title on the first screen, and in Settings → Apps → Oh-Tello. Local builds have no such
suffix and read as plain `0.1.0`.

One caveat: every build shares an `applicationId`, so installing one replaces any other. You cannot
keep a PR build and a main build side by side without giving them distinct application IDs.

To build locally instead, you need Android Studio (or a local Android SDK) and JDK 17+.

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

- The drone auto-lands when it hears nothing for a while. The app sends an idle `command`
  keepalive every 5 seconds to prevent that **only while the app is in the foreground**. Lock the
  phone, switch apps, or close it, and the keepalive stops: the drone lands itself shortly after,
  wherever it happens to be. That is deliberate — an unattended drone should come down — but it
  also means glancing at another app mid-flight will land it.
- **Leaving the app while the drone is flying hands it over, and coming back does not take it
  back.** Returning shows a banner and leaves the controls locked; the drone carries on landing
  until you tap **Take back control**. Land and Emergency are never locked. This is not caution
  for its own sake: the drone treats *any* command as "the pilot is back" and abandons its
  landing for it, so returning used to cancel a touchdown already in progress and leave the
  aircraft with its motors running at ground level, exactly where a hand reaches in for it. The
  lock lifts by itself once telemetry says the drone is down. Backgrounding with it parked on
  the floor changes nothing — there is no landing to interrupt.
- **How long is "a while" is not settled.** The SDK documents 15 seconds from the last command. A
  session log from 14 August 2026 shows the drone answering normally after **23.2 seconds** of
  total silence, and the pilot watching it reported a landing somewhere past 30. Note that a
  landed Tello still answers `ok`, so the log bounds the link, not the flight. Test B13 measures
  it properly; until then the app deliberately says "shortly" rather than a number it cannot
  stand behind.
- Two warnings come with that. **Twelve seconds without a command from you**, with the app in
  front of you, raises a banner above the flight controls: nothing is wrong, the drone is hovering,
  but this screen is the only reason it still is. **Backgrounding the app while the drone is
  airborne** posts a notification instead, since by then you are not looking at the screen. The
  banner counts commands *you* send — keepalives deliberately do not reset it, or it could never
  fire.
- The notification needs `POST_NOTIFICATIONS`, asked for the first time you connect rather than at
  launch. Deny it and everything else still works; you lose that one warning. It is skipped when
  telemetry says the drone is on the ground, and cancelled when you come back. Nothing runs in the
  background to produce it — no service, no alarm, no wakelock.
- Pressing back while connected asks first, and offers to land before leaving.
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
│   ├── SessionLogStore.kt       the console of the last 10 app sessions, on disk
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

Permissions used, in full: `INTERNET` (required for any socket, even a purely local one),
`ACCESS_NETWORK_STATE`, and `POST_NOTIFICATIONS` for the one warning described in
[Safety notes](#safety-notes). No location permission — the app never scans for networks, it only looks at
the one you already joined.

**A trap waiting for whoever raises `targetSdk` to 37.** Android 17 puts traffic to local network
addresses behind a new runtime permission, `ACCESS_LOCAL_NETWORK`, covering all such traffic rather
than only discovery. `192.168.10.1` is a local address. Apps targeting API 36 and below are exempt,
which is the only reason this works today at `targetSdk` 35 — but the moment that number changes,
every packet to the drone is blocked, and the symptom is indistinguishable from the socket-binding
failure described above. Declaring the permission is the easy half; it also needs a runtime request
and a sensible refusal path. There is a note in the manifest next to `INTERNET` saying the same
thing, because that is where someone will be looking.

## Testing

`./gradlew test` runs 63 tests, none of which need a device:

- `TelloCommandsTest`, `TelloResponseTest`, `TelloStateTest` — command building and clamping,
  response parsing (`ok`, `OK`, values, `error Not joystick`, `out of range`, CRLF and NUL
  padding), rejecting the drone's binary packets as replies, and telemetry parsing against a real
  captured packet, including truncated and unfamiliar-key packets.
- `TelloControllerTest` — drives the real controller against `FakeDrone`, a loopback UDP stand-in,
  over real sockets and real time: handshake, a silent drone failing instead of hanging, strict
  serialisation of overlapping commands, the late-reply drain, telemetry freshness going stale
  after 2s of silence, `rc` being sent without waiting for a reply that never comes, a link that
  goes quiet tearing itself down, the idle warning staying silent for a grounded drone, and
  disconnect/reconnect.
- `SessionLogStoreTest`, `TelemetryLogStoreTest` — rotation, the size cap, decimation, concurrent
  writes, sharing newest-session-first, and the two stores sharing a directory without pruning
  each other's files.

What that does **not** cover: the Compose UI, the Wi-Fi binder, and anything about how a real
drone behaves. First real-drone smoke test, props off:

1. Connect — expect `← ok` in the console.
2. Press `battery?` — expect a number. That validates the whole socket/response pipeline without
   touching the motors.
3. Watch the telemetry row update, then walk out of range and confirm the status chip flips to
   "no telemetry".

Emulators cannot test any of this; it needs real Wi-Fi and a real drone. [docs/UAT.md](docs/UAT.md)
is a full run-through for a phone and a drone — bench tests with the propellers off first, then the
flying ones.

### When something goes wrong in the air

The console keeps the last 10 app sessions on disk, written as they happen rather than saved on
exit, so a log survives the app being killed mid-flight. **Share** in the console sends all of them
as text — each is headed with the build, the phone and the Android version. That is the thing worth
attaching to a bug report; the on-screen console is gone the moment the app restarts.

### The flight recorder

Alongside the console log, the app records the drone's state stream to a CSV in the same directory.
Two logs rather than one on purpose: the console is prose for a human reading a failure, this is a
table for measuring a flight, and the two want opposite formats and rates.

```
# Oh-Tello 0.1.0-pr1-abc1234 — Google Pixel 7, Android 17 (API 37)
# started 14:31:51.199
# t_ms: milliseconds since the first packet. clock: matches the console log.
# h,tof: cm. baro: metres. yaw,pitch,roll: degrees. vgz: cm/s. agz: 0.001g. time: motor-on seconds.
t_ms,clock,bat,h,tof,baro,yaw,pitch,roll,vgz,agz,time
0,14:31:54.780,45,0,10,114.23,5,0,0,0,-1002.00,28
500,14:31:55.281,45,10,31,114.44,5,-1,2,18,-1013.00,28
```

It exists because the questions this project still has open are arithmetic on those columns, and the
console cannot answer them. When the drone's failsafe actually lands it, for instance, is invisible
in the console — a landed Tello still answers `ok` — but obvious here, because `time` stops
advancing when the motors cut, `h` falls to 0 and `tof` drops to its floor. Likewise the two rules
that decide whether the height error is a scale or an offset use only `h` and `tof`.

Size is bounded and deliberately modest. The drone pushes ~10 packets a second; samples are
decimated to 2 Hz, which keeps every manoeuvre visible for about 7 KB a minute. Each file stops at
1 MB — roughly two and a half hours — with a `# stopped at …` line marking the cut, and only the
last 5 are kept, so the worst case on disk is about 5 MB. A file is created on the first packet
rather than at launch, so app runs that never connect leave nothing behind and cannot evict the
recording of a real flight.

**Getting it off the phone** currently needs a cable — **Share** sends the console log only, since a
CSV would blow the size an intent can carry:

```bash
PKG=io.github.darthr4v3m.ohtello
adb shell run-as $PKG ls files/logs
adb exec-out run-as $PKG tar c files/logs > logs.tar
```

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

### Why the first connect used to fail — solved

Connect would sometimes fail on the first press and work on the second. The suspects were the
Wi-Fi lookup racing Android's association, or the drone dropping the first `command`. It was
neither.

Port 8889 carries more than SDK text. The Tello also speaks the binary protocol its official app
uses, and pushes those packets at the phone unprompted. One landing inside the handshake window was
read as the reply to `command`, decoded to mojibake, and failed the connect outright. Caught in the
act during UAT:

```
13:24:41.104  → command
13:24:41.137  ignored a non-SDK packet on the command port: cc 18 01 b9 88 56 00 e1 ... (35 bytes)
13:24:41.140  ← ok
```

`cc` magic, a length in bits (`0x0118` = 280, so 35 bytes, matching), a header CRC, a packet type,
and message id `0x0056` — the flight-data message — at sequence 225. It arrived 33 ms after the
command and three milliseconds ahead of the real `ok`.

Fixed by filtering the reply queue to datagrams that are plausibly SDK replies, and giving the
handshake a single retry as a belt-and-braces measure. The full backoff-and-rebind strategy
described here previously was never needed.

### Height telemetry: `h` is not the number to trust

Measured on a real flight at roughly 80 cm:

| field | reading | what it is |
| --- | --- | --- |
| `h` | 40 | height above the takeoff point, quantised to 10 cm |
| `tof` | 76 | distance to the ground below; valid 30-1000, floors under 30 |
| `baro` | 115.02 hovering, 114.23 landed | absolute pressure altitude, **in metres** |

`tof` matches reality. So does the `baro` delta of 0.79 m. `h` under-reads by about 38 cm.

**`baro` is in metres, not centimetres.** DJI's own 1.3 document contradicts itself — its
read-command table says `(m)`, its state-packet section says `cm`, and SDK 2.0 carried the wrong one
forward. Metres is what survives contact with reality: `djitellopy` multiplies the field by 100 to
get centimetres, the value sits around 115 at an ordinary ground elevation, and it goes negative on
a high-pressure day. The accessor here was called `barometerCm` and was wrong by 100x; it is now
`barometerMetres`.

**`h` is a decimetre value.** The drone's internal height is an integer number of decimetres, which
is why every reading is a multiple of 10 and why `height?` answers `4dm` rather than a number of
centimetres. It cannot be more precise than 10 cm, it is relative to a pressure datum captured at
takeoff, it drifts over a flight, and it can legitimately go negative.

**It is an offset, not a scale — settled.** One airborne sample could not tell `h = true / 2` from
`h = true - 38 cm`, since both predict 40 at a true 80. A flight recording answered it with 108
samples across the whole altitude range:

| | mean | spread |
| --- | --- | --- |
| `tof - h` | 35.3 cm | **15%** |
| `h / tof` | 0.32 | 92% |

The difference holds steady from 10 cm to 150 cm while the ratio collapses from 0.75 to 0.

**But the size of the offset is not a constant — it is set fresh at every takeoff.** Three flights on
16 August measured 35.3, 12.2 and about 10 cm, each tight within itself and wildly different from the
others. That is what a corrupted takeoff datum predicts: latched once while the props spin up, held
for the rest of the flight, different every time.

Which makes `h` worse than a merely inaccurate reading. There is **no height below which it can be
trusted and no way to know in advance when it will read 0 on a flying drone** — on the 35 cm flight a
30 cm hover showed `h` 0; on the 12 cm flight the same hover showed 20. One recording has the drone
holding 30 cm with the motors running for sixteen seconds while `h` read 0 throughout.

### The airborne gate

Two things need to know whether the drone is flying: the idle banner, and the notification posted
when the app is backgrounded. Both used to ask `h > 0`, each with its own copy of the test, and both
were wrong for the same reason — `h` reads about 35 cm low, so it reports 0 for any hover below that.
A real flight held 30 cm with the motors running for sixteen seconds while both warnings stayed
silent.

The judgement now lives in one place, `TelloController.judgeAirborne`, and reads:

```
airborne  =  motors turning  OR  h > 0  OR  nothing known
```

- **Motors turning** — the `time` field advanced within the last three seconds. DJI documents it as
  "the amount of time the motor has been used", so a counter that is moving means props that are
  spinning. It is the only signal that does not pass through an altitude estimate, and it is the one
  that catches the low hover. The first reading of a session sets a baseline and nothing more: the
  counter never resets, so connecting to a drone that flew earlier finds a large number sitting
  still, and still means still.
- **`h > 0`** — a second, independent opinion for anything the barometer can see, kept as a backstop
  because only one flight has been examined in this detail.
- **Nothing known** — no telemetry yet, or a packet without `h`. Warn.

The asymmetry is the whole design: a false "airborne" costs a warning nobody needed, a false "on the
ground" costs silence about an aircraft that is about to put itself down. Every term errs the
harmless way, including the three seconds of "airborne" that linger after touchdown.

**`tof` is deliberately not used**, though an earlier plan here said it would be. The data killed it:
this airframe reads 10 sitting on the floor and will not hover below about 27, so a threshold has to
be placed in a 17 cm gap — and the 10 is not a documented constant, while the SDK says readings under
30 are not valid distances at all. That is a number picked between two numbers worth no confidence.
Motor state answers the same question without one.

### SDK version: this targets 1.3, not 2.0

Every command the app sends — `command`, `takeoff`, `land`, `emergency`, the six movements,
`cw`/`ccw`, `rc`, `battery?` — is present in SDK **1.3**, with identical or wider ranges. That is a
strength rather than a compromise: it is why this works on a standard Ryze Tello, which nominally
predates 2.0. The 2.0-only additions are mission pads, station mode, `stop`, `sdk?` and `sn?`, and
none of them are used here.

`sdk?` and `sn?` do not exist before 2.0, so a standard Tello answering `unknown command` to them is
the expected reply and a diagnostic in its own right rather than a fault — see issue #3.
