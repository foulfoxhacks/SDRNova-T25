# Voice audio (IMBE and AMBE+2)

P25 Phase 1 voice is IMBE; P25 Phase 2 and DMR voice is AMBE+2 (half rate, 3600 x 2450). SDRNova T25 decodes both
with [JMBE](https://github.com/DSheirer/jmbe) by Dennis Sheirer (GPL-3.0), the same vocoder sdrtrunk uses. Its source
is built into the app from the `vocoder` module. [vocoder/README.md](../vocoder/README.md) lists the small changes
made for Android.

Both codecs are covered by patents in some jurisdictions. Check that you're allowed to use, and to distribute, a
decoder where you are.

## How it's used

- Frames are handed to JMBE raw (144-bit IMBE, 72-bit AMBE+2, still FEC coded), most-significant bit first, the way
  JMBE's `BinaryFrame.fromBytes` reads them. JMBE does the error correction and synthesis and returns 8 kHz audio.
  The app upsamples that to 48 kHz.
- Each followed call has its own decoder, because the codecs keep state between frames.
- One call plays at a time: the first one to deliver voice keeps the speaker until it ends.
- Encrypted calls are never decoded. They're logged as encrypted and nothing more.
