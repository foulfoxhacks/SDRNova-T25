# Building SDRNova T25

The current code base is pure Kotlin: no NDK, CMake or native libraries are needed yet.

## Requirements

- JDK 17 or newer (21 is what the project is built with)
- Android SDK with platform 35 and build-tools 35.0.0
- `local.properties` with `sdk.dir=...`, or `ANDROID_HOME` set

Gradle 8.11.1, Android Gradle Plugin 8.7.3 and Kotlin 2.1.0 come in through the wrapper.

## Commands

```bash
./gradlew :core:test           # DSP and P25 decoder tests, runs on the desktop JVM
./gradlew :app:assembleRelease # app/build/outputs/apk/release/app-release.apk
```

The release build is signed with the local debug key so it installs directly with `adb install`. Use it rather
than the debug build: the DSP loop runs much faster when the app isn't debuggable.

### Windows path length

aapt2 fails on paths longer than 260 characters. If the checkout lives deep inside a user profile, move the build
output somewhere short:

```bash
./gradlew :app:assembleRelease -Pt25.buildRoot=C:/t25build
```

## Layout

| Module | What it holds |
| --- | --- |
| `core` | Plain Kotlin/JVM: filters, mixer, FFT, C4FM demodulator, P25 Phase 1 framing and FEC, TSBK parsing, trunking tracker, NBFM. Unit-tested on the desktop. |
| `app` | Android: RTL2832U + R820T/R828D USB driver, `rtl_tcp` client, radio engine threads, foreground service, Compose UI. |

## What works in this build

- RTL-SDR over USB OTG (R820T, R820T2, R828D including RTL-SDR Blog V4), or any `rtl_tcp` server
- P25 Phase 1 control channel: sync, NID, TSBK decode with CRC, band plan, site identity, voice grants and grant updates resolved to frequencies, active and recent calls, talkgroup aliases
- Analog NBFM with noise squelch and audio
- Spectrum view of the 960 kHz around the tuner

## Not yet

- Voice audio for P25: needs a `VocoderProvider` (IMBE), which this repository does not bundle
- Following voice grants onto traffic channels (one tuner, one channel for now)
- LDU/HDU link control, PDU/AMBTC, Phase 2, DMR and the rest of the README feature target
- E4000, FC0012/13 and FC2580 tuners; Airspy and HackRF

## Credits

The receive chain is modeled on [sdrtrunk](https://github.com/DSheirer/sdrtrunk) (GPL-3.0) by Dennis Sheirer: the
RTL2832/R82xx register sequences, the trellis transition table, the TSBK field layouts and the C4FM
symbol-timing approach follow its implementation.
