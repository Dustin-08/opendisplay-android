# Architecture

Two independent apps talk over one TCP connection on the local network.
Neither side needs the other's source code — only the shared wire protocol
(see [PROTOCOL_NOTES.md](PROTOCOL_NOTES.md)).

```
┌─────────────────────────────┐         ┌─────────────────────────────┐
│  Mac (sender)                │         │  Android tablet (receiver)   │
│  — not part of this repo —   │         │  — this repo —               │
│                               │         │                               │
│  1. Create a virtual display │  video  │  1. Decode H.264 (MediaCodec)│
│     (CGVirtualDisplay)        │ ──────► │  2. Render to SurfaceView     │
│  2. Capture it                │         │  3. Capture touch events      │
│     (ScreenCaptureKit)        │  touch  │  4. Send touch coords back    │
│  3. Encode (VideoToolbox      │ ◄────── │                               │
│     H.264) and stream over TCP│         │                               │
└─────────────────────────────┘         └─────────────────────────────┘
```

- **Mac side**: this repo does not include it. Build the Mac app from
  [peetzweg/opendisplay](https://github.com/peetzweg/opendisplay) as-is —
  it needs no changes, because this Android app advertises itself over
  Bonjour exactly like an iPhone/iPad would.
- **Android side (this repo)**: a single `MainActivity` for now. As it
  grows, the natural split (not yet done — see [ROADMAP.md](ROADMAP.md)) is:
  - `net/` — socket lifecycle, framing, demux
  - `protocol/` — message parsing/building (`hello`, `touch`, `kf`, ...)
  - `video/` — MediaCodec setup and NAL handling
  - `ui/` — the SurfaceView + touch capture

## Why one TCP connection instead of WebRTC

Early planning considered WebRTC for adaptive bitrate and built-in NAT
traversal. For a personal, same-LAN-only tool, that's unneeded complexity —
OpenDisplay's plain TCP + `TCP_NODELAY` approach already hits low latency on
a local network, and reusing its protocol means the Mac side needs zero
custom code. WebRTC remains an option later if this ever needs to work
across networks (it won't, for this project's purposes).

## Three-stage roadmap (see ROADMAP.md for details)

1. **Mirror only** — see the Mac's real screen on the tablet, no input.
2. **Remote control** — touch input flows back, Mac injects it.
3. **True extended display** — the Mac side creates an actual virtual
   monitor (via `CGVirtualDisplay`), so the tablet is a genuine second
   screen rather than a mirror of an existing one. This is entirely a
   Mac-side concern; the Android receiver code doesn't change for it.
