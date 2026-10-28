SDRNova T25 
========

Trunk-tracking SDR scanner for Android

SDRNova T25 decodes and trunk-tracks analog and digital radio systems from a USB SDR attached to an Android device — control-channel following, multi-channel audio, call logging, and recording, all on a phone or tablet. The feature target is sdrtrunk-class monitoring, built on an independent codebase rather than a port.

> **Status: concept build.** T25 is the current design target, not a stable release. Interfaces, the playlist format, and the module layout will change between builds, and several decoders below are scaffolded rather than finished. See [Roadmap](#roadmap) for what is actually wired up.

- The latest builds are available on the [SDRNova Releases](https://github.com/foulfoxhacks/SDRNova-T25/releases) page as signed APKs.
- Project site: [sdrnova.akasammythepuppy.me](https://sdrnova.akasammythepuppy.me)
- Documentation and user manual: [sdrnova.akasammythepuppy.me/docs/](https://sdrnova.akasammythepuppy.me/docs/)
- Build from a checkout with `./gradlew assembleDebug`. Full toolchain notes are in [docs/BUILD.md](docs/BUILD.md).

Feature target:
--------
Trunking and channel following:
  - APCO-25 Phase 1 (C4FM and simulcast/LSM) — control-channel decode with automatic trunked channel following
  - APCO-25 Phase 2 — Phase 1 control channel with automatic Phase 2 TDMA voice following
  - DMR — Tier III, Capacity Plus, Connect Plus, Capacity Max
  - MPT-1327, LTR-Standard, LTR-Net, Passport
  - NXDN — stretch target, behind P25 and DMR

Conventional and analog:
  - NBFM with adaptive squelch, AM, DCS and CTCSS aux decoding
  - MDC-1200, Fleetsync II, Tait 1200 ANI and GPS bursts

Monitoring features:
  - Polyphase channelizer — multiple simultaneous channels from a single tuner, within tuner bandwidth
  - Playlist system: systems, sites, channel configs, and frequency maps, importable and exportable
  - Talkgroup and radio ID aliasing, with per-alias priority, muting, and color coding
  - Call event log with timestamps, talkgroup, radio ID, and duration
  - Call recording to device storage, per-alias or per-system
  - GPS plotting for protocols that carry location (DMR LRRP, P25 unit GPS)
  - Streaming out to Icecast and Broadcastify Calls
  - Background monitoring via foreground service, with audio focus and wake-lock handling

Explicitly out of scope:
  - **No decryption.** Encrypted traffic is detected and logged as encrypted, and nothing more. T25 does not implement, bundle, or accept keys for ADP, DES, or AES protected voice.
  - No transmit. Receive only.

Utilizes:
--------
  - Kotlin + Jetpack Compose, Material 3 (https://developer.android.com/jetpack/compose)
  - liquid-dsp, for demodulation and filtering primitives (http://liquidsdr.org/ -- https://github.com/jgaeddert/liquid-dsp)
  - libusb + Android USB host API, for direct OTG tuner access (https://libusb.info/)
  - Oboe, low-latency audio output (https://github.com/google/oboe)
  - Room, for playlist, alias, and call-event storage (https://developer.android.com/training/data-storage/room)
  - OpenGL ES 3.0, for spectrum and waterfall rendering (https://www.khronos.org/opengles/)
  - Android NDK + CMake (https://developer.android.com/ndk)

Optional Libs:
--------
  - FFTW3 (can be compiled into liquid-dsp if desired) (http://www.fftw.org/ -- https://github.com/FFTW/fftw3)
  - KISS FFT, as a lighter alternative on low-end devices (https://github.com/mborgerding/kissfft)

Vocoder note:
--------
P25 and DMR voice use IMBE and AMBE+2. These are patent-encumbered and no decoder is bundled with this repository. T25 exposes a `VocoderProvider` interface; builds without a provider decode control traffic, metadata, and call events but produce no voice audio. Supplying a provider is the user's responsibility and their jurisdiction's problem, not the project's.

Hardware support:
--------
  - RTL-2832U (R820T, R820T2, R828D / RTL-SDR Blog V4, E4000, FC0013) over USB OTG
  - Airspy Mini, R2, and HF+ Discovery
  - HackRF One
  - Network sources: `rtl_tcp` and SoapyRemote, for running the tuner off-device
  - Baseband IQ file playback, for development and testing without hardware
  - SDRplay RSP — not supported. The vendor API has no Android distribution.

Recommended minimum requirements:
--------------------
  - 64-bit ARM device (arm64-v8a). Trunk tracking with more than two simultaneous channels is not realistic on 32-bit hardware.
  - Android 10 (API 29) or newer. The project compiles against a minimum of API 26.
  - 6GB RAM or better. The channelizer plus two decoded voice channels is the practical floor for 4GB devices.
  - GPU with OpenGL ES 3.0 support.
  - USB OTG host mode, and **a powered OTG hub**. Phones generally cannot supply enough current for an Airspy or HackRF on their own, and the failure mode is dropped samples and decode errors rather than a clean "no power" message.
  - Expect meaningful battery drain and heat. Sustained decoding is a thermal-throttling problem on most phones; a tablet or an actively cooled device is better for long sessions.

Building:
--------
  - Check out the project and open it in Android Studio (Ladybug or newer), or build headless with `./gradlew assembleDebug`.
  - Native code builds through CMake via the NDK. `local.properties` needs an `ndk.dir`, or an `ndkVersion` matching the one pinned in `app/build.gradle.kts`.
  - `./gradlew assembleDebug -Ptuners=rtlsdr,airspy` builds a subset of the tuner backends; the default builds all of them.
  - `./gradlew assembleRelease` requires signing config in `keystore.properties`, which is not committed.
  - Full instructions, options, and troubleshooting: [docs/BUILD.md](docs/BUILD.md).

Roadmap:
--------
  - [ ] Tuner layer: RTL-2832U, Airspy, HackRF over libusb
  - [ ] Polyphase channelizer and multi-channel dispatch
  - [ ] P25 Phase 1 control-channel decode and channel following
  - [ ] P25 Phase 2 TDMA voice following
  - [ ] DMR Tier III and Capacity Plus
  - [ ] Playlist editor and alias database
  - [ ] Call recording and event log
  - [ ] Streaming output
  - [ ] LTR / Passport / MPT-1327
  - [ ] NXDN

Legal:
--------
Radio monitoring law varies by country, and in the US by state. Receiving, recording, and rebroadcasting are three separate questions with three separate answers. Know your local rules before using this software. Protocol names are used nominatively for identification; registered trademarks belong to their respective owners.

Credits:
-------
Built by Sammy The Femboy Puppy ([@akasammythepuppy](https://akasammythepuppy.me)), published under the developer handle **foulfoxhacks**.

License:
-------
  - GPL-3.0
