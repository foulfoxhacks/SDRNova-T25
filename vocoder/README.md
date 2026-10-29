# vocoder

IMBE (P25 Phase 1) and AMBE+2 (P25 Phase 2, DMR) voice decoding from [JMBE](https://github.com/DSheirer/jmbe) by
Dennis Sheirer, licensed GPL-3.0 (see `LICENSE-JMBE`). The `api` and `codec` sources are copied from upstream.

Changes made for Android:

- `jmbe.codec.RealFFT` (new) replaces JTransforms' `FloatFFT_1D` in `MBESynthesizer` and `MBESynthesizerLegacy`
- `AGC` uses `Math` instead of commons-math3 `FastMath`
- The desktop playback `main()` demos in `IMBESynthesizer`, `IMBESynthesizerLegacy` and `AMBESynthesizer` and their
  `javax.sound` imports are removed, along with `jmbe.audio.JMBEAudioFormat` (unused, and `javax.sound` based)

IMBE and AMBE+2 are covered by patents in some jurisdictions. Check that you're allowed to use and distribute them
where you are.
