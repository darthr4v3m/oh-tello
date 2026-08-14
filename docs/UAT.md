# Oh-Tello — user acceptance test

A run-through for a real phone and a real drone. Roughly 45 minutes including the flying.

Everything up to Part B is bench work with the **propellers off** — do that indoors first, because
half the failures worth finding show up before anything spins.

**Build under test:** `______________________` (the version string under the title on the first
screen, e.g. `0.1.0-pr1-6e93c15`)

**Date / tester:** `______________________`

---

## Before you start

- Propellers **off** for Part A. Prop **guards on** for Part B.
- Tello battery above 50%. Phone above 50%.
- Part B needs about 3 × 3 m of clear space and a soft floor or grass. Indoors is fine and
  preferable for a first run — no wind.
- Nobody within 2 m of the drone once the motors are live.
- Phone: mobile data **on**. That is deliberate — the app pins its sockets to Wi-Fi, and leaving
  data on is what proves it.

If a step fails, stop, tap **Share** in the console, and keep the log. Note the test number
against it. The log carries the build, the phone and the Android version in its first line.

---

## Part A — bench, propellers off

### A1 — Install over the previous build
**Do:** install the APK on a phone that already has an older Oh-Tello.
**Expect:** it replaces the old one without asking you to uninstall.
*If Android refuses:* the installed copy predates the shared signing key. Uninstall once; this
should not recur.
- [ ] Pass — notes: `______________________`

### A2 — Launch and identify
**Do:** open the app.
**Expect:** dark screen; "Oh-Tello" with the version string beneath it; chip on the right reads
**offline**; Take off / Land / Emergency at the **bottom**, not scrolling away; console says
"Nothing sent yet."
- [ ] Pass — notes: `______________________`

### A3 — Nothing is armed while offline
**Do:** without connecting, try every control.
**Expect:** only **Connect** responds. Take off, Land, Emergency, the D-pad and `battery?` are all
greyed out. No telemetry warning is shown (there is nothing to be stale yet).
- [ ] Pass — notes: `______________________`

### A4 — Rotation is locked
**Do:** turn the phone sideways.
**Expect:** the screen stays portrait.
- [ ] Pass — notes: `______________________`

### A5 — Connect fails cleanly when not on the drone's network
**Do:** stay on your normal Wi-Fi (or mobile data only). Press **Connect**.
**Expect:** after about 5 seconds the chip reads **failed** and a red line explains it. The console
shows `no Wi-Fi network found …` or `no reply to `command` within 5s`. The app does not hang or
crash.
- [ ] Pass — notes: `______________________`

### A6 — Connect for real
**Do:** power the drone on, wait for the amber blink, join `TELLO-XXXXXX` in Android Wi-Fi
settings, ignore "no internet", return to the app, press **Connect**.
**Expect, in the console, in order:**
```
connecting to 192.168.10.1:8889
udp/8889: socket bound to the Wi-Fi network
udp/8890: socket bound to the Wi-Fi network
→ command
← ok
SDK mode entered
```
Chip turns green, reads **connected**.
**This is the single most important line:** `socket bound to the Wi-Fi network`. If it says
`no Wi-Fi network found` or `socket left on the default network` and the connect still worked, note
it — it means the binding is not doing its job and it will fail on some other phone.
- [ ] Pass — notes: `______________________`

### A7 — Notification permission is asked at the right moment
**Do:** watch for the permission prompt (first connect only, Android 13+).
**Expect:** Android asks for notification permission **on connecting**, not at launch. Allow it.
- [ ] Pass — notes: `______________________`

### A8 — Query round trip
**Do:** tap `battery?`.
**Expect:** `→ battery?` then `← 87` (or whatever the level is). The number matches the battery
readout.
- [ ] Pass — notes: `______________________`

### A9 — Telemetry is live
**Do:** watch the telemetry row and the raw line beneath it for 10 seconds.
**Expect:** battery, height (0 cm), tof and flight time populate; the raw `pitch:…;bat:…;` line
updates continuously. No "No state packet" warning.
- [ ] Pass — notes: `______________________`

### A10 — Keepalive is running
**Do:** turn the **keepalives** switch on in the console; wait 15 seconds.
**Expect:** a dimmed `→ command` / `← ok` pair roughly every 5 seconds. Turn the switch back off
and they disappear from the view.
- [ ] Pass — notes: `______________________`

### A11 — Idle banner
**Do:** touch nothing for about 15 seconds.
**Expect:** a coloured banner appears just above the flight controls: *"Hovering — no command for a
while…"*. Tap any direction button; the banner clears.
**Note:** the keepalives from A10 must **not** clear it — that is the point of the test.
- [ ] Pass — notes: `______________________`

### A12 — Backgrounding on the ground does NOT nag
**Do:** with the drone connected and sitting on the floor, press Home. Wait 10 seconds.
**Expect:** **no** notification — telemetry says height 0, so there is nothing to warn about.
Reopen the app: the console shows the keepalive stopped and resumed.
- [ ] Pass — notes: `______________________`

### A13 — Movement is rejected on the ground
**Do:** tap **forward**.
**Expect:** `→ forward 30` and then an error from the drone (`← error Not joystick`, `← error Auto
land` or similar) shown in red. The app stays responsive.
- [ ] Pass — notes: `______________________`

### A14 — Emergency reaches the drone
**Do:** tap **Emergency stop** once, then again within 3 seconds.
**Expect:** first tap changes the label to "TAP AGAIN TO CUT MOTORS"; second tap sends
`→ emergency (jumped the queue, 1/3)`, `2/3`, `3/3`. Three copies is deliberate — one lost packet
must not lose the command.
**Also:** tap it once and wait 4 seconds without a second tap — the label reverts, nothing is sent.
- [ ] Pass — notes: `______________________`

### A15 — Back is guarded
**Do:** with the drone connected, swipe back (or press back).
**Expect:** a dialog, *"The drone may still be flying"*, offering **Land, then quit** and **Quit
anyway**. Dismiss it; the app stays open and connected.
- [ ] Pass — notes: `______________________`

### A16 — Link loss is noticed
**Do:** with the app connected, **power the drone off**. Watch for ~15 seconds.
**Expect:** the chip goes to "no telemetry" within about 2 seconds, then the connection fails with
`link lost — the drone stopped answering…`. The app does not sit there claiming to be connected.
- [ ] Pass — notes: `______________________`

### A17 — Reconnect after a drop
**Do:** power the drone back on, rejoin its Wi-Fi if needed, press **Connect**.
**Expect:** it connects normally. No app restart needed.
- [ ] Pass — notes: `______________________`

### A18 — Logs survive and can be shared
**Do:** force-stop the app (or swipe it from recents), reopen it, tap **Share** in the console.
**Expect:** a share sheet with the whole log as text. It contains **the previous session too**,
each headed with build/phone/Android. Send it to yourself — that is the artefact to attach to a bug
report.
- [ ] Pass — notes: `______________________`

---

## Part B — first flight, prop guards on

Fit the propellers and the guards. Clear space. Drone on a flat surface, front away from you.

Have a thumb near **Land** for all of these.

### B1 — Take off and land
**Do:** connect, press **TAKE OFF**. Let it hover. Press **LAND**.
**Expect:** climbs to roughly 1 m and holds; console `→ takeoff` / `← ok`. Land brings it down
under control; console `→ land (jumped the queue, 1/3)` and the height readout falls to 0.
- [ ] Pass — notes: `______________________`

### B2 — Height telemetry is honest
**Do:** take off again; compare the height readout to reality.
**Expect:** roughly 80–120 cm, and it tracks up/down commands.
- [ ] Pass — notes: `______________________`

### B3 — Movement, all six directions
**Do:** with 30 cm selected, press each of forward, back, left, right, then up and down.
**Expect:** the drone moves in the direction the label says, roughly 30 cm each time. **Directions
are from the drone's nose, not yours** — if forward comes toward you, the drone is facing you, not
a bug.
- [ ] Pass — notes: `______________________`

### B4 — Step size
**Do:** select 100 cm, press forward. Then 20 cm, press back twice.
**Expect:** distances scale accordingly; the centre of the D-pad shows the selected step.
- [ ] Pass — notes: `______________________`

### B5 — Yaw
**Do:** select 90°, press **cw**, then **ccw**.
**Expect:** the drone rotates in place, about a quarter turn each way. Position holds.
- [ ] Pass — notes: `______________________`

### B6 — Controls lock during a command
**Do:** press forward 100 cm and watch the D-pad while it runs.
**Expect:** the movement buttons grey out until the drone answers, then come back. **Land stays
available the whole time** — check it is not greyed.
- [ ] Pass — notes: `______________________`

### B7 — Land works while the drone is busy
**Do:** press **forward 100**, and while it is still moving press **LAND**.
**Expect:** it lands promptly — it should not wait for the move to finish. Console shows land
jumping the queue.
- [ ] Pass — notes: `______________________`

### B8 — Idle banner in the air
**Do:** hover and touch nothing for ~15 seconds.
**Expect:** the banner appears; the drone keeps hovering (the keepalive is holding it up). Press a
direction; banner clears.
- [ ] Pass — notes: `______________________`

### B9 — The one that matters: background while flying
**Do:** hover at about 1 m. Press **Home** (or lock the phone). **Watch the drone, not the phone.**
**Expect:**
- a notification within ~2 seconds: *"The drone is about to land itself"*
- the drone begins descending roughly 10–15 seconds after you left the app
- it lands under control, not a drop
**Then:** tap the notification — the app reopens and the console shows the keepalive resumed.
- [ ] Pass — notes: `______________________`

### B10 — Coming straight back does not land it
**Do:** hover, press Home, and return to the app within ~3 seconds.
**Expect:** the drone keeps hovering. A notification may briefly appear; it is cleared on return.
- [ ] Pass — notes: `______________________`

### B11 — Back gesture lands it
**Do:** hover, swipe back, choose **Land, then quit**.
**Expect:** the drone lands, *then* the app closes. Not the other way round.
- [ ] Pass — notes: `______________________`

### B12 — Walking out of range
**Do:** hover, walk away until telemetry goes stale (15–30 m, or put a wall between you).
**Expect:** chip flips to "no telemetry", then the link fails with `link lost…`, and the drone
lands itself on its own failsafe. Walk back, reconnect.
**Caution:** do this over grass, and keep the drone in sight.
- [ ] Pass — notes: `______________________`

### B13 — How long is the drone's failsafe, really?

Measuring the firmware, not the app. The SDK says the drone lands 15 seconds after the last
command. On 14 August 2026 a session log showed it answering normally after **23.2 seconds** of
silence, and the pilot watching it saw a landing somewhere past 30 — so the documented figure is
not what this firmware does, and the app currently says "shortly" because it cannot honestly say a
number.

**Do:**
1. Turn **keepalives** on in the console — you need to see the last one's timestamp.
2. Take off, hover at about 1 m, over grass or a mat.
3. Press Home **and start a stopwatch on the same press**.
4. Watch the drone. Stop the watch the moment it **touches down**, not when it starts descending.
5. Reopen the app, read the timestamp of the last `KEEPALIVE → command` before
   `app backgrounded`, and the `app backgrounded` line itself.

Do it twice — Tello firmware is not famously consistent.

| Run | Last keepalive | `app backgrounded` at | Stopwatch to touchdown | Began descending at |
| --- | --- | --- | --- | --- |
| 1 | `________` | `________` | `______ s` | `______ s` |
| 2 | `________` | `________` | `______ s` | `______ s` |

**The number that matters** is touchdown minus the *last keepalive*, not minus the backgrounding —
they can be up to 5 seconds apart, since the keepalive fires on its own cadence.

*Note:* a landed Tello still answers `ok`, so the log alone cannot tell you when it stopped flying.
That is why this test needs eyes and a stopwatch.
- [ ] Done — result fed back into the app's wording

### B14 — Emergency cut (optional, do it last)
**Only over grass or a mat, at 30–50 cm, with guards on. The drone will drop.**
**Do:** hover low, tap Emergency twice.
**Expect:** motors cut instantly; the drone falls the short distance.
- [ ] Pass — notes: `______________________` / [ ] Skipped

---

## Part C — after

### C1 — Battery warning
**Do:** at some point when the drone is below 20%, look at the telemetry card.
**Expect:** a red "Battery at N% — land it." line.
- [ ] Pass — notes: `______________________`

### C2 — Collect the evidence
**Do:** tap **Share**, send the log to yourself.
**Expect:** the whole afternoon's sessions, newest last, each headed with the build.
- [ ] Pass — notes: `______________________`

---

## Verdict

| | |
| --- | --- |
| Tests passed | `____ / 34` |
| Blocking failures | `______________________` |
| Ship it? | Yes / No |

**Anything that felt wrong but passed:** (worth more than the checkboxes)

`____________________________________________`

`____________________________________________`
