# Changelog

## [0.1.0] — initial scaffold
- First-draft `MainActivity` implementing the OpenDisplay receiver role:
  TCP listener, Bonjour/NSD advertisement, `hello`/`ping`/`touch`/`kf`
  control messages, H.264 Annex B parsing, MediaCodec decode to
  `SurfaceView`.
- Not yet tested on real hardware — see `docs/ROADMAP.md` Stage 1 for the
  next concrete steps.
