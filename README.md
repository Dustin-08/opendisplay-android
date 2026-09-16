# OpenDisplay Android Receiver

A personal, free, wireless alternative to Sidecar — turns an Android tablet
into an extra Mac display (mirror → touch control → true extended display),
using the [OpenDisplay](https://github.com/peetzweg/opendisplay) wire
protocol so the Mac-side app needs **zero modification**.

Status: **early scaffold, untested on real hardware.** See
[`docs/ROADMAP.md`](docs/ROADMAP.md) for exactly where things stand.

Personal project, not distributed — built for one MacBook Pro (M4) and one
Galaxy Tab S8 on the same Wi-Fi network. No App Store, no signing, no
external users to support.

## Why this exists

Sidecar does exactly this, but only for iPads. This is the same idea for an
Android tablet: it reuses the Mac-side app from
[peetzweg/opendisplay](https://github.com/peetzweg/opendisplay) unchanged
(virtual display creation, screen capture, H.264 encoding, TCP streaming —
all already solved there) and adds a new client that speaks the same wire
protocol from the Android side.

## Repo layout

```
app/                     Android app module (single activity for now)
docs/
  ARCHITECTURE.md        System diagram, why plain TCP over WebRTC
  PROTOCOL_NOTES.md       What's implemented vs. stubbed, implementation log
  ROADMAP.md             Staged checklist: mirror → touch → extended display
CHANGELOG.md
```

## Requirements

- Android Studio (recent stable)
- A Mac running the OpenDisplay Mac app (build separately from
  [peetzweg/opendisplay](https://github.com/peetzweg/opendisplay))
- Both devices on the same Wi-Fi network

## Building

1. Open this folder in Android Studio.
2. Let it sync Gradle (generates the wrapper if missing).
3. Run on a physical device — `SurfaceView` + `MediaCodec` behave
   inconsistently on emulators, so test on the real tablet.
4. Watch `adb logcat` filtered to tag `OpenDisplayReceiver` for connection
   and decoder status.

## Where to start reading the code

Everything lives in `MainActivity.kt` for now (deliberately — see
`docs/ARCHITECTURE.md` for the planned package split once it grows).
Roughly top-to-bottom: NSD advertisement → TCP accept loop → framing →
control-message handling → video decode → touch capture.

## License

MIT (see `LICENSE`) for the code in this repo. This is an independent
implementation of a published wire protocol, not a fork of the (GPL-3.0)
Mac/iOS OpenDisplay apps.
