# Protocol notes

This app speaks the **OpenDisplay wire protocol**, defined by the
[peetzweg/opendisplay](https://github.com/peetzweg/opendisplay) project in its
[`PROTOCOL.md`](https://github.com/peetzweg/opendisplay/blob/main/PROTOCOL.md).
That file is the source of truth — this document is just an implementation
log for *this* Android client: what's done, what's stubbed, and where in
`MainActivity.kt` to look.

Read the upstream `PROTOCOL.md` before changing anything network-related.
This file intentionally does not copy its contents.

## Role

This app is a **receiver** (the "extra screen" side). It listens on TCP 9000
and waits for a **sender** (the Mac app) to connect — the roles are fixed,
receivers always listen, so the same code path works over WiFi and, on Apple
platforms, over USB.

## Implemented

| Area | Status | Where |
|---|---|---|
| Length-prefixed framing (4-byte BE length + payload) | ✅ | `readFrame` / `writeFrame` |
| JSON vs. video demux heuristic | ✅ | `handleFrame` |
| Bonjour/NSD advertisement (`_opensidecar._tcp`, `id`/`pv` TXT records) | ✅ | `registerNsd` |
| `hello` (sent first, on every connection) | ✅ | `sendHello` |
| `ping` every 2s | ✅ | `startPing` |
| `touch` (began/moved/ended/cancelled, normalized coords) | ✅ | `handleTouch` |
| `kf` (keyframe request on decode loss) | ✅ | `requestKeyframe` |
| H.264 Annex B parsing, telemetry-prefix stripping, SPS/PPS extraction | ✅ | `handleVideoFrame`, `extractParamSets` |
| MediaCodec decode → Surface | ✅ (needs real-device testing) | `createDecoder`, `feedDecoder` |

## Not implemented yet

- **`scroll`** — two-finger scroll deltas. Same shape as `touch`; add a
  gesture detector and send `{"type":"scroll","dx":...,"dy":...}` in video
  pixels, natural-scrolling sign.
- **`pencil` / `proximity`** — only relevant if a stylus-capable device is
  ever targeted. Skip for the Tab S8 unless using an S Pen.
- **Clock sync (`pong` handling)** — only needed for latency stats, not for
  basic function.
- **UDP cursor side channel** — optional optimization; cursor still works
  fine over TCP without it.
- **`hello.maxEncodeWide/High`** decode-ceiling negotiation — matters once
  testing against very high-resolution Mac displays.
- **Cable/USB transport** — WiFi only for now. USB would need an
  Android-side equivalent binding (e.g. `adb reverse tcp:9000 tcp:9000`),
  which the upstream spec explicitly leaves open for non-Apple receivers.

## Known simplifications to revisit

- `createDecoder()` hardcodes a placeholder 1920x1080 `MediaFormat` size
  instead of parsing the real dimensions out of the SPS. Section 5.2 of the
  spec says receivers MUST take dimensions from the SPS, not from `hello`.
  Works today because `MediaCodec` typically self-corrects via
  `INFO_OUTPUT_FORMAT_CHANGED`, but this should be tightened.
- Device id (`deviceId`) is regenerated every launch instead of persisted in
  `SharedPreferences`. Low priority, but means the Mac app will treat every
  relaunch as a "new" device rather than recognizing a returning one.
