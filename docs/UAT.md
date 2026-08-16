# Oh-Tello — user acceptance test

A run-through for a real phone and a real drone. Roughly 45 minutes including the flying.

Everything up to Part B is bench work with the **propellers off** — do that indoors first, because
half the failures worth finding show up before anything spins.

**Build under test:** `______________________` (the version string under the title on the first
screen, e.g. `0.1.0-pr1-6e93c15`)

**Date / tester:** `______________________`

---

## Result

**Part A 20/20. Part B 15/15. Part C: C2 done, C1 outstanding.**

The only check left is **C1** — the red *"Battery at N% — land it."* line, which needs the drone
below 20% and so has to be caught opportunistically rather than staged.

Part B was run frozen at `229dd1e`, deliberately: every fix during the earlier rounds invalidated
tests that had already passed, so the code was pinned and defects were written down rather than
fixed mid-run. Nothing regressed during the frozen run.

**Four defects were found and fixed before the freeze:**

1. A binary packet on the command port read as the handshake reply, failing the first connect.
2. Link loss going unnoticed when the drone's Wi-Fi vanished — an unsendable command was being
   counted as a healthy reply.
3. A movement the drone silently ignores locking every control for 20 seconds.
4. The airborne gate reading `h`, which reports 0 on a flying drone.

**Two results were measured by the flight recorder rather than judged by eye:** B5's yaw (+89, -89,
+179 degrees against 90, 90, 180 asked) and B13's failsafe timing.

**Findings worth carrying:**

- **The failsafe is 18.5-24.9 s from last command to motors off**, not the documented 15. See B13.
- **`h` reads low by an amount fixed within a flight but different every flight** — 10, 12, 35 and
  58 cm measured across four. There is no height below which it can be trusted. See B14.
- **The drone refuses to descend below about 28 cm and says nothing at all** — it stops, hovers, and
  never answers. Seen three times.
- **Returning to the app during a failsafe landing interrupts it.** The keepalive resumes within half
  a second and tells the drone the pilot is back, leaving it with motors running at ground level.
- **Export pairs the newest console log with the newest recording independently**, so exporting right
  after a fresh launch can pair a new empty log with an older CSV.

The last two are open issues, not fixed here.

**Which build each result came from:** A1-A9, A13-A15 and A17 on `b05f374`; A16 and A18 on
`a9bdfb4`; A10, A19 on `c882619`; A11, A12 and all of Part B on `229dd1e`.

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
- [x] Pass — notes: `installed over 0.1.0-pr1-b05f374, no uninstall needed`

### A2 — Launch and identify
**Do:** open the app.
**Expect:** dark screen; "Oh-Tello" with the version string beneath it; chip on the right reads
**offline**; Take off / Land / Emergency at the **bottom**, not scrolling away; console says
"Nothing sent yet."
- [x] Pass — notes: `version string matches the APK filename`

### A3 — Nothing is armed while offline
**Do:** without connecting, try every control.
**Expect:** only **Connect** responds. Take off, Land, Emergency, the D-pad and `battery?` are all
greyed out. No telemetry warning is shown (there is nothing to be stale yet).
- [x] Pass — notes: `all flight controls greyed out while offline`

### A4 — Rotation is locked
**Do:** turn the phone sideways.
**Expect:** the screen stays portrait.
- [x] Pass — notes: `stays portrait, no rotation offered`

### A5 — Connect fails cleanly when not on the drone's network
**Do:** stay on your normal Wi-Fi (or mobile data only). Press **Connect**.
**Expect:** after about 5 seconds the chip reads **failed** and a red line explains it. The console
shows `no Wi-Fi network found …` or `no reply to `command` within 5s`. The app does not hang or
crash.
- [x] Pass — notes: `failed cleanly with the network hint, no crash`

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
- [x] Pass — notes: `connected; drone's own auto-land took 30-45s, not 15s - see B13`

### A7 — Notification permission is asked at the right moment
**Do:** watch for the permission prompt (first connect only, Android 13+).
**Expect:** Android asks for notification permission **on connecting**, not at launch. Allow it.
- [x] Pass — notes: `notification appeared on backgrounding`

### A8 — Query round trip
**Do:** tap `battery?`.
**Expect:** `→ battery?` then `← 87` (or whatever the level is). The number matches the battery
readout.
- [x] Pass — notes: `battery? returned 72`

### A9 — Telemetry is live
**Do:** watch the telemetry row and the raw line beneath it for 10 seconds.
**Expect:** battery, height (0 cm), tof and flight time populate; the raw `pitch:…;bat:…;` line
updates continuously. No "No state packet" warning.
- [x] Pass — notes: `telemetry and battery? agree`

### A10 — Keepalive is running
**Do:** turn the **keepalives** switch on in the console; wait 15 seconds.
**Expect:** a dimmed `→ command` / `← ok` pair roughly every 5 seconds. Turn the switch back off
and they disappear from the view.
- [x] Pass — notes: `dimmed pairs every ~5s, gone from view when switched off`

### A11 — Idle banner does NOT appear on the ground
**Do:** with the drone connected and sitting on the table, touch nothing for 20 seconds.
**Expect:** **no** banner. A drone on the table is not hovering on borrowed time, and saying so
would teach you to ignore the warning. Same rule as the notification in A12: silent when telemetry
reports height 0.
**The airborne half of this is B8** — that is where the banner should appear, and where tapping a
direction must clear it. The keepalives must never clear it, which is why the idle clock counts
only commands you send.
- [x] Pass — notes: `re-run on 229dd1e after the gate rewrite: still silent on the table`

### A12 — Backgrounding on the ground does NOT nag
**Do:** with the drone connected and sitting on the floor, press Home. Wait 10 seconds.
**Expect:** **no** notification — telemetry says height 0, so there is nothing to warn about.
Reopen the app: the console shows the keepalive stopped and resumed.
- [x] Pass — notes: `re-run on 229dd1e: no notification, keepalive stopped and resumed`

### A13 — Movement is rejected on the ground
**Do:** tap **forward**.
**Expect:** `→ forward 30` and then an error from the drone (`← error Not joystick`, `← error Auto
land` or similar) shown in red. The app stays responsive.
- [x] Pass — notes: `forward 30 rejected with `error Motor stop``

### A14 — Emergency reaches the drone
**Do:** tap **Emergency stop** once, then again within 3 seconds.
**Expect:** first tap changes the label to "TAP AGAIN TO CUT MOTORS"; second tap sends
`→ emergency (jumped the queue, 1/3)`, `2/3`, `3/3`. Three copies is deliberate — one lost packet
must not lose the command.
**Also:** tap it once and wait 4 seconds without a second tap — the label reverts, nothing is sent.
- [x] Pass — notes: `label reverted, three emergency lines sent`

### A15 — Back is guarded
**Do:** with the drone connected, swipe back (or press back).
**Expect:** a dialog, *"The drone may still be flying"*, offering **Land, then quit** and **Quit
anyway**. Dismiss it; the app stays open and connected.
- [x] Pass — notes: `dialog appeared and dismissed`

### A16 — Link loss is noticed
**Do:** with the app connected and the screen in the foreground, **power the drone off**. Watch for
~20 seconds.
**Expect:** the chip goes to "no telemetry" within about 2 seconds, then the connection fails with
`link lost — the drone stopped answering…`. The chip reads failed and **Disconnect** greys out. The
app does not sit there claiming to be connected.
**Note:** powering the drone off takes its Wi-Fi access point with it, so the keepalive may fail with
`could not send` rather than timing out — both count towards the two strikes that end the link.
- [x] Pass — notes: `on a9bdfb4: timeout then ENETUNREACH, link lost after 5.5s (failed on b05f374)`

### A17 — Reconnect after a drop
**Do:** power the drone back on, rejoin its Wi-Fi if needed, press **Connect**.
**Expect:** it connects normally. No app restart needed.
- [x] Pass — notes: `Disconnect then Connect returned to green, buttons updated`

### A18 — Logs survive and can be shared
**Do:** force-stop the app (or swipe it from recents), reopen it, tap **Share** in the console.
**Expect:** a share sheet with the whole log as text. It contains **the previous session too**,
each headed with build/phone/Android. Send it to yourself — that is the artefact to attach to a bug
report.
- [x] Pass — notes: `previous sessions still present, share sheet opened`

### A19 — The first connect of a session works
**Do:** power the drone **fully off and on again**, join its Wi-Fi, and press **Connect** exactly
once. Do not press it twice. Repeat the whole thing three times, from a cold drone each time.
**Expect:** it connects on the first press, all three times.
**Note:** the drone also emits binary packets on the command port, and one arriving in the
handshake window used to be read as the reply and fail the connect. Those are now ignored and
logged as `ignored a non-SDK packet on the command port: cc …`, and the handshake gets one retry.
Seeing that line followed by `handshake did not take, trying once more` and then `SDK mode entered`
is a **pass** — the junk was handled. Seeing the connect fail is not.
- [x] Pass — notes: `three cold cycles, three first-press connects; run 3 filtered a cc packet`

### A20 — Console text can be copied, and Share reads newest first
**Do:** with a few lines in the console, long-press one of them. Then tap **Share**.
**Expect:** long-press starts a text selection with drag handles and a **Copy** action, and what
you copy pastes correctly elsewhere. Selecting across lines scrolled out of view may not work —
that is a Compose limitation of selection inside a lazy list, not a bug; what is on screen is the
case that matters.
**In the shared text:** the **newest** session is at the top, headed `===== session-… =====`, with
older ones below. Lines within each session stay in the order they happened — only the sessions are
reversed. If the text was long enough to be cut, a `…` marks where.
- [x] Pass — notes: `copy works; newest session first in the shared text`

---

## Part B — first flight, prop guards on

Fit the propellers and the guards. Clear space. Drone on a flat surface, front away from you.

Have a thumb near **Land** for all of these.

### B1 — Take off and land
**Do:** connect, press **TAKE OFF**. Let it hover. Press **LAND**.
**Expect:** climbs to roughly 1 m and holds; console `→ takeoff` / `← ok`. Land brings it down
under control; console `→ land (jumped the queue, 1/3)` and the height readout falls to 0.
**Note:** `takeoff` only answers `ok` once the drone is at hover, about 6 seconds. Land's own `ok`
never appears as `← ok` — a priority command does not wait for its reply, so it turns up later
as `discarded late reply: ok` when the next keepalive drains the queue. Correct, if ugly.
- [x] Pass — notes: `off, steady hover, controlled landing; takeoff ok at 5.94s; land 3x at 154ms`

### B2 — Height telemetry is honest
**Do:** take off again. Read **height**, **tof** and your own estimate of the real height at the
same moment. Land, and read height and tof again.
**Expect:** **tof** matches reality within a few cm. **height** does not — it is barometric and
reads low, measured at 40 against a real 80. On the ground height reads 0 and tof reads 10; ten is
the sensor's floor rather than a measurement, so read it as "on a surface".
**A known quirk, not a failure.** What would be a failure: tof disagreeing with reality, or height
failing to fall to 0 on landing. See the README, *Height telemetry*, for why it matters.
- [x] Pass — notes: `h 40 / tof 76 / real ~80 hovering; h 0 / tof 10 landed; baro delta 0.79`

### B3 — Movement, all six directions
**Do:** with 30 cm selected, press each of forward, back, left, right, then up and down.
**Expect:** the drone moves in the direction the label says, roughly 30 cm each time. **Directions
are from the drone's nose, not yours** — if forward comes toward you, the drone is facing you, not
a bug.
- [x] Pass — notes: `fwd/back/left/right by eye; up 100 moved tof 49->148, down 100 148->51,
  down 30 78->52 — direction and distance confirmed from the recorder`

### B4 — Step size
**Do:** select 100 cm, press forward. Then 20 cm, press back twice.
**Expect:** distances scale accordingly; the centre of the D-pad shows the selected step.
- [x] Pass — notes: `100cm ~5x the 20cm hop; D-pad centre tracks the step. 2nd back-20 hard to see, drone acked it`

### B5 — Yaw
**Do:** select 90°, press **cw**, then **ccw**.
**Expect:** the drone rotates in place, about a quarter turn each way. Position holds.
- [x] Pass — notes: `cw 90 -> yaw +89, ccw 90 -> -89, cw 180 -> +179; altitude held within 2cm`

### B6 — Controls lock during a command
**Do:** press forward 100 cm and watch the D-pad while it runs.
**Expect:** the movement buttons grey out until the drone answers, then come back. **Land stays
available the whole time** — check it is not greyed.
- [x] Pass — notes: `pad greyed, LAND stayed live, 'waiting for the drone' shown, over a 3.6s forward 100`

### B7 — Land works while the drone is busy
**Do:** press **forward 100**, and while it is still moving press **LAND**.
**Expect:** it lands promptly — it should not wait for the move to finish. Console shows land
jumping the queue.
- [x] Pass — notes: `land 1/3 sent 1.0s into an unanswered forward 100; drone replied `error Auto land``

### B8 — Idle banner in the air
**Do, part one:** hover at a normal height and touch nothing for ~15 seconds. Then press a
direction.
**Expect:** the banner appears, the drone keeps hovering (the keepalive is holding it up), and the
direction press clears it.
- [x] Pass — notes: `banner appeared, direction press cleared it (16 Aug)`

**Part two — check after the fact, do not try to stage it.** The failure this guards against is a
hover where **height reads 0 while the drone is flying**. Whether that is even reachable depends on
the takeoff datum error, which varies per flight and nobody controls: measured at 35 cm on one
flight and 10 cm on another. At 35 cm a 30 cm hover shows `h` 0; at 10 cm the same hover shows 20.
**So:** after any flight, look in the recorder for rows where `h` is 0 while `time` is still
advancing. If you find some, and you were not touching the controls for 12 seconds during them, the
banner should have been up. Asking a pilot to produce that condition on demand does not work.
- [ ] Checked — notes: `______________________`

### B9 — The one that matters: background while flying
**Do:** hover at about 1 m. Press **Home** (or lock the phone). **Watch the drone, not the phone.**
**Expect:**
- a notification within ~2 seconds: *"The drone is about to land itself"*
- the drone begins descending roughly 10–15 seconds after you left the app
- it lands under control, not a drop
**Then:** tap the notification — the app reopens and the console shows the keepalive resumed.
- [x] Pass — notes: `notification appeared, controlled landing, keepalive resumed at 17:29:50.126`

### B10 — Coming straight back does not land it
**Do:** hover, press Home, and return to the app within ~3 seconds.
**Expect:** the drone keeps hovering. A notification may briefly appear; it is cleared on return.
- [x] Pass — notes: `3s away mid-takeoff; held 70cm throughout, telemetry never stopped`

### B11 — Back gesture lands it
**Do:** hover, swipe back, choose **Land, then quit**.
**Expect:** the drone lands, *then* the app closes. Not the other way round.
- [x] Pass — notes: `land sent 0.7s before the app closed; recorder stops mid-descent`

### B12 — Walking out of range
**Do:** hover, walk away until telemetry goes stale (15–30 m, or put a wall between you).
**Expect:** chip flips to "no telemetry", then the link fails with `link lost…`, and the drone
lands itself on its own failsafe. Walk back, reconnect.
**Caution:** do this over grass, and keep the drone in sight.
- [x] Pass — notes: `fridge test: commands died, telemetry kept flowing, link lost in 10.5s`

### B13 — How long is the drone's failsafe, really?  — MEASURED

Measuring the firmware, not the app. The SDK says 15 seconds after the last command. It is not 15.

**Four runs on 16 August**, all by backgrounding the app and letting the drone put itself down. No
stopwatch: `time` is DJI's motor-on counter, it freezes when the motors cut, and it is cumulative,
so touchdown can be read off the recorder afterwards even though Android freezes the app while it
is in the background.

| run | hover height | last command reached the drone | motors off | elapsed |
| --- | --- | --- | --- | --- |
| B9 | 0.8 m | 17:29:26.1 | 17:29:44.6 | **18.5 s** |
| 1 | 1.8 m | 18:03:16.2 | 18:03:38.8 | **22.6 s** |
| 2 | 1.8 m | 18:03:56.0 | 18:04:19.4 | **23.4 s** |
| 3 | 1.8 m | 18:04:57.8 | 18:05:22.7 | **24.9 s** |

**Silence to motors-off is 18.5-24.9 s**, and it scales with height because the descent is part of
it. So the failsafe itself fires somewhere around 15-19 s and the drone then takes several seconds
to come down.

**Read run 3 first if you only read one.** It is the only run where the motors were still turning
when the app came back, so its touchdown is directly observed rather than inferred from the counter
having already stopped. It is also the longest.

**This retires the 23.2 s figure that started the doubt.** That measured the drone still *answering*
after 23 s of silence, and a landed Tello answers perfectly well. It was never a measure of flight.
The earlier suspicion of a 30 s failsafe was an artefact of watching the wrong thing.

**What it means for the app:** leaving the app with the drone airborne buys **up to about 25
seconds** of unattended hovering, not 15. The idle warning fires at 12 s, which is a thinner margin
than intended.
- [x] Done — notes: `18.5 / 22.6 / 23.4 / 24.9 s across four runs; trigger ~15-19 s plus descent`

### B14 — Is the height error a scale or an offset?

Settles the one open question from B2, in a single flight. Cheap, and it decides how thin the
airborne gate really is.

**Do:** take off and let it settle. Note **height**, **tof** and the real height. Then select
**100 cm**, press **up**, let it settle again, and note the same three.

**Easier than eyeballing it:** the app now records `h`, `tof` and `baro` to a CSV twice a second.
Pull it with `adb exec-out run-as io.github.darthr4v3m.ohtello tar c files/logs > logs.tar` and the
two hovers are just rows — no need to read numbers off a screen while holding a tape measure.

**Then work out which of these holds:**
- `height / tof` stayed near the same ratio at both altitudes → the error is a **scale**.
- `tof - height` stayed near the same number at both altitudes → the error is an **offset**.

**Why it matters:** at a 45 cm hover the scale model puts `height` at 20 and the idle banner still
fires; the offset model puts it at 0 and the warning goes silent on a drone that is airborne. The
offset is the more likely of the two, and it is the dangerous one. Whichever it is, write both sets
of numbers into the notes — they are the evidence, not the conclusion.
**Already answered, on 16 Aug.** A descent captured by the flight recorder gave 108 samples from
0 to 150 cm: `tof - h` held at 35.3 cm (15% spread) while `h / tof` ranged 0.75 down to 0 (92%
spread). It is an **offset** — `h` reads about 35 cm low at every height. Re-run this only if you
want independent corroboration.
- [x] Pass — notes: `offset, ~35cm. tof-h 35.3 +/- 5.3 over 108 samples; h/tof 0.32 +/- 0.29`
### B15 — Emergency cut (optional, do it last)
**Only over grass or a mat, at 30–50 cm, with guards on. The drone will drop.**
**Do:** hover low, tap Emergency twice.
**Expect:** motors cut instantly; the drone falls the short distance.
- [x] Pass — notes: `two taps, 3 copies, motors off ~0.5s later; vgz 29 on the drop`



---

## Part C — after

### C1 — Battery warning
**Do:** at some point when the drone is below 20%, look at the telemetry card.
**Expect:** a red "Battery at N% — land it." line.
- [ ] Pass — notes: `______________________`

### C2 — Collect the evidence
**Do:** tap **Share**, send the log to yourself.
**Expect:** the whole afternoon's sessions, newest last, each headed with the build.
- [x] Pass — notes: `exported after every Part B test; newest session first`

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
