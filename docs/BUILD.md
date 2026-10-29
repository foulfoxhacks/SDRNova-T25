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
| `vocoder` | JMBE (GPL-3.0) IMBE and AMBE+2 voice codecs, Java, adapted for Android. Unit-tested on the desktop. |
| `app` | Android: RTL-SDR, HackRF and Airspy USB drivers, `rtl_tcp` client, radio engine threads, foreground service, Compose UI. |

## What works in this build

- RTL-SDR over USB OTG (R820T, R820T2, R828D including RTL-SDR Blog V4, and FC0013), or any `rtl_tcp` server, at 0.96, 1.92 or 2.4 MS/s
- HackRF One over USB OTG at 2.4, 4.8 or 8 MS/s (signed 8-bit samples; LNA/VGA gain, antenna power)
- Airspy R2 and Mini over USB OTG at the lowest firmware rate that fits (2.5 or 3 MS/s), real-to-I/Q conversion ported from sdrtrunk; LNA/mixer AGC or manual gain, bias tee. A powered OTG hub is strongly recommended.
- AMBTC group voice channel grants (multi-block PDUs with explicit channels)
- P25 Phase 1 control channel: sync, NID, TSBK decode with CRC, band plan, site identity, voice grants and grant updates resolved to frequencies, active and recent calls, talkgroup aliases
- P25 simulcast (LSM/CQPSK): a differential QPSK demodulator at 4800 baud feeds the same framer as C4FM, with AFC steered only by syncs that decode or agree, so it acquires up to about ±2 kHz off frequency. Set per channel by sdrtrunk's `modulation="LSM"`, or with the Simulcast switch in Setup
- Conventional P25: voice on the monitored frequency itself is played, with talkgroup and radio ID from link control
- Traffic channel following: up to four voice channels decoded alongside the control channel, as long as they sit inside the tuner's bandwidth (like sdrtrunk with a single tuner)
- LDU1/LDU2 decoding: IMBE voice frames, link control (talkgroup and radio ID, Hamming(10,6) + Reed-Solomon(24,12,13)), encryption sync (algorithm and key ID, Reed-Solomon(24,16,9)); TDU/TDULC end calls
- P25 Phase 2 TDMA voice channels: grants on 2-slot TDMA bands are followed per timeslot. H-DQPSK demodulation, superframe fragments, ISCH, the WACN/system/NAC scrambling sequence, FACCH/SACCH (RS(63,35) + CRC-12) MAC PDUs (PTT, END PTT, voice channel user) and AMBE+2 voice frames; both timeslots of a carrier share one demodulator
- DMR on a single frequency (repeater or direct mode): BS/MS/direct syncs, CACH timeslots, slot type (Golay 20,8), BPTC(196,96), voice LC headers and terminators (RS(12,9) checked) giving talkgroup and radio ID per timeslot, CSBKs (CRC checked; Tier III grants logged with their LCN), idle bursts, and AMBE+2 voice frames from all six bursts of each superframe
- Voice audio from the built-in JMBE vocoder (IMBE for Phase 1, AMBE+2 for Phase 2 and DMR); see [VOCODER.md](VOCODER.md)
- Analog NBFM with noise squelch and audio
- Spectrum and waterfall of the tuner bandwidth; tap to tune. RF gain and volume (dB) apply live from the main screen
- sdrtrunk playlist import and export (Channels tab): channels with their system, site, frequencies, decoder and alias list, and aliases with talkgroups, talkgroup ranges, radio IDs, priority, record and "do not monitor". Tap a channel to listen; the dongle retunes when it's outside the band in view
- Scan: cycles the ticked channels (any mix of P25, DMR and NBFM), stops on a call or open squelch, waits a hang time for the reply, with hold and skip. Dwell and hang are in Setup
- Alias priority and lockout: locked-out talkgroups aren't followed or played; when every voice channel is busy, a higher-priority grant takes over the lowest-priority call, and it takes the speaker too
- Call recording to WAV in Music/SDRNova T25: off, talkgroups marked record (sdrtrunk's alias flag), or every call (NBFM included, one file per squelch opening)
- Heard-calls history across channels, a shareable event log, and a one-tap ppm correction from the AFC once the control channel is locked

## Not yet

- Rotating through a P25 control channel's alternate frequencies (they're imported and shown, but only the first is used)
- AM, conventional (single-frequency) Phase 2, and the other sdrtrunk decoders: their channels import but show as not decoded
- Streaming (Broadcastify, Icecast) and sdrtrunk's per-channel event log and recording settings

- HDU decoding and TDULC link control (both are only used as call boundaries for now)
- AMBTC opcodes other than the group voice grant are logged by name only; packet data PDUs are skipped
- Phase 2 on 4-slot (25 kHz) TDMA bands, Phase 2 LCCH control channels, and Phase 2 encryption sync (ESS) details
- DMR trunking (Tier III, Capacity Plus, Connect Plus): grants are decoded and logged, but following them needs a logical-channel-to-frequency map, which isn't configurable yet. Embedded link control (late entry), data packets and talker alias aren't decoded
- E4000 over USB (sdrtrunk's driver is ~1,300 lines of gain and filter tables; still to port). FC0012 and FC2580 have no sdrtrunk implementation to follow. All three work through `rtl_tcp`, which drives them with librtlsdr on the server side.

## Credits

The receive chain is modeled on [sdrtrunk](https://github.com/DSheirer/sdrtrunk) (GPL-3.0) by Dennis Sheirer: the
RTL2832/R82xx register sequences, the trellis transition table, the TSBK field layouts and the C4FM
symbol-timing approach follow its implementation.
