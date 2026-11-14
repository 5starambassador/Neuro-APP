# FocusQuest — EEG neurofeedback focus training (Android)

An original Android app for attention training with a consumer EEG headband.
The player wears a NeuroSky-chip headset (BrainLink Lite, MindWave Mobile, …),
and mini-games respond in real time to their attention level: stay focused and
the game moves forward; lose focus and it slows down.

> FocusQuest is an attention-training game, **not a medical device**. It does not
> diagnose or treat ADHD or any other condition.

## Features

- **Bluetooth headset link:** connects to a paired headset over Bluetooth SPP and
  parses the NeuroSky ThinkGear protocol (attention, meditation, signal quality,
  8 EEG bands, blinks). A watchdog pauses play if data stops.
- **Simulator:** "Automatic" (realistic wandering attention) or "Hold to focus"
  (press the screen), so everything works without hardware.
- **Eight games** in three training styles, all drawn in Compose Canvas:

  | Style | Game | Driven by | What it trains |
  |---|---|---|---|
  | Sustained focus | Sky Rocket, Night Drive | Attention | Holding attention steadily |
  | Sustained focus | Picture Reveal | Attention | Every focused moment uncovers a hidden picture; never loses progress (good for younger kids) |
  | Goal-based | Bloom Garden, Bridge Builder | Attention | Building focus to complete a goal (a flower, a plank), then going again |
  | Focus bursts | Archer's Focus | Attention | Focus draws the bow; steadier focus while drawing lands closer to the bullseye |
  | Relaxation | Calm Balloon, Lantern Lake | Calm (meditation) | Settling down: slow breathing, relaxed body |
- **Session engine:** 3-2-1 countdown, focus hysteresis, streaks with haptic
  milestones, points, **adaptive difficulty** (goal moves ±5 every 30 s),
  auto-pause on poor signal.
- **Results & progress:** focus %, best streak, attention timeline, 7-day
  minutes chart, focus trend, full history, day streak.
- **Multiple players**, each with their own history. All data stays on the device.
- **Classroom mode (local Wi‑Fi, no server):**
  - *Trainer mode* on a tablet hosts a class with a 4-digit join code and advertises it with
    mDNS/NSD; students can also join by IP address and port.
  - Live dashboard of every student, updated each second: attention, calm, signal, current
    game, time left, time on goal, streak, a 90-second trend line, and alerts (disconnected,
    no headset, poor signal for 10+ s, low focus after 60 s).
  - Group sessions: start the same game on every connected phone, end it for everyone, send
    encouragement to one student or the whole class.
  - Results are resent until the trainer acknowledges them, and students reconnect automatically.
- **Reports** (shared via Android's share sheet):
  - Per-student PDF: stats, coaching suggestions, trend charts, per-game breakdown, all sessions.
  - Class summary PDF: every student side by side, flags (needs support / declining / inactive).
  - Live session report (PDF + in-app): every student's result and timeline from a group session.
  - CSV export of all sessions for Excel or Google Sheets.

## Project layout

```
app/src/main/java/com/focusquest/app/
  eeg/        ThinkGearParser, EegHub (Bluetooth + simulator), models
  session/    SessionEngine (focus/calm logic, scoring, adaptive threshold)
  games/      FocusGame interface + 8 games
  classroom/  Protocol (JSON lines over TCP), TrainerServer, StudentLink (NSD discovery)
  report/     Reports (pure calculations), PdfBuilder, ReportWriter (PDF/CSV + sharing)
  data/       Models, Json, Store (JSON file persistence)
  ui/         Compose screens and components
app/src/test/ Unit tests for the parser, session engine and report calculations
```

## Build

Requires the Android SDK (platform 36) and a JDK 17+ (Android Studio's bundled JBR works).

```bash
./gradlew testDebugUnitTest assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Or open the
folder in Android Studio and press Run.

## Using a headset

1. Switch the headband on and pair it in Android Bluetooth settings (PIN usually `0000` or `1234`).
2. In FocusQuest open **Headset**, grant Bluetooth permission, and tap the device.
3. Check that signal quality is high, then pick a game on **Train**.

## Running a class

1. On the trainer tablet: **Settings → Trainer mode**, enter a class name, **Start class**.
2. On each student phone (same Wi‑Fi): **Settings → Join a class**, pick the class and
   enter the code shown on the tablet (or type the tablet's IP, port and code).
3. Watch everyone live; use **Group session** to start a game for all, then open the
   live session report when results are in. **Reports** (top-right icon) has class and
   student PDFs and the CSV export.

### Web dashboard (laptop or projector)

While a class is running, the tablet also serves the dashboard as a web page. The Trainer
screen shows the address (for example `http://192.168.1.20:8080`) and a 6-digit PIN
(tap to reveal). Open the address in any browser on the same Wi‑Fi and enter the PIN.

- Live student tiles, class stats, alerts, search and sort, updated every second.
- Start and end group sessions, send cheers, and download every report (student, class,
  live session PDFs and CSV).
- **Projector mode** for a shared screen: large names and a star for whoever is on goal
  right now. Individual scores and alerts are hidden so no child is singled out.
- Light and dark themes. No external fonts or scripts, so it works without internet.
- Security: PIN sign-in with lockout after 5 wrong tries, an HttpOnly SameSite cookie,
  a custom-header check on every command (blocks cross-site requests), and strict
  security headers. A new class gets a new PIN and signs out old browsers.

Classroom traffic stays on the local network and isn't encrypted. Use it on a trusted
school Wi‑Fi. School networks with "client isolation" enabled block device-to-device
traffic; ask IT to allow it for the training room.

## Release

`assembleRelease` produces an unsigned, minified APK/AAB. Add your own signing
config before publishing to Google Play.
