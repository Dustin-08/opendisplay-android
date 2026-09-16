# Roadmap

## Stage 0 — Mac-side sanity check (no Android involved)
- [ ] Build [DeskPad](https://github.com/Stengo/DeskPad) and confirm a
      virtual display creates correctly on the M4 MacBook Pro
- [ ] Build the [OpenDisplay](https://github.com/peetzweg/opendisplay) Mac
      app and confirm it runs standalone

## Stage 1 — Mirror only
- [x] Android: TCP listener + Bonjour advertisement
- [x] Android: `hello` / `ping` control messages
- [x] Android: H.264 parsing + MediaCodec decode + render to `SurfaceView`
- [ ] **Test on real hardware**: Tab S8 shows up in the Mac app's device list
- [ ] **Test on real hardware**: video actually decodes and displays
- [ ] Fix whatever the above two turn up (see `docs/PROTOCOL_NOTES.md`
      "known simplifications" — SPS-driven sizing is the most likely culprit)

## Stage 2 — Remote control
- [x] Android: `touch` messages (began/moved/ended/cancelled)
- [ ] **Test on real hardware**: touching the tablet actually moves the
      cursor / clicks on the Mac
- [ ] Add `scroll` (two-finger gesture → `dx`/`dy` in video pixels)
- [ ] Persist `deviceId` in `SharedPreferences` instead of regenerating it
      every launch

## Stage 3 — True extended display
- [ ] Confirm the Mac app actually creates a `CGVirtualDisplay` (this is
      the whole point of using OpenDisplay instead of building screen
      capture from scratch — it already does this)
- [ ] Set the virtual display resolution to the Tab S8's native resolution
      (from `hello.pixelsWide/High`) rather than a default
- [ ] Confirm windows can be dragged onto the "tablet" display like any
      other monitor in macOS display arrangement settings

## Later / nice-to-have (not blocking anything above)
- [ ] Clock sync (`pong` handling) for latency stats
- [ ] UDP cursor side channel for smoother cursor motion
- [ ] `pencil` support if an S Pen–capable device is ever used
- [ ] Reconnect/error-handling polish
- [ ] App icon, basic settings screen (e.g. manual IP entry as a Bonjour
      fallback)
