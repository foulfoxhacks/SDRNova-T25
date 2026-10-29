# Voice audio (IMBE and AMBE+2)

P25 Phase 1 voice is IMBE-coded and Phase 2 voice is AMBE+2 (half rate). Both are patent-encumbered, so SDRNova T25
ships without a decoder. Without one
the app still follows calls onto their voice channels and logs the talkgroup, the radio ID and whether the call is
encrypted, but it plays no audio.

Supplying a decoder is your responsibility, and so is checking that you're allowed to use one where you live. This
mirrors how sdrtrunk handles [JMBE](https://github.com/DSheirer/jmbe).

The app looks for JMBE's `jmbe.JMBEAudioLibrary` class and drives it through JMBE's `IAudioCodecLibrary` API, asking
for its `IMBE` and `AMBE` codecs. Frames are handed over raw (144-bit IMBE, 72-bit AMBE+2, still FEC coded), exactly
as sdrtrunk passes them, and JMBE does the rest.

> This path hasn't been tested against a real JMBE build yet. Please report what happens.

## Option 1: compile JMBE into your own build

Build JMBE from its repository, then point the app build at the resulting jar or jars (comma-separated if the API
and codec are separate):

```bash
./gradlew :app:assembleRelease -Pjmbe.jar=/path/to/jmbe-codec.jar,/path/to/jmbe-api.jar
```

JTransforms, JLargeArrays and slf4j-api come from Maven Central automatically. Don't publish an APK built this way.

## Option 2: import a provider jar into an installed app

Android can't load plain Java bytecode, so convert JMBE and its dependencies to dex with `d8` from the Android SDK
build-tools:

```bash
d8 --min-api 26 --output jmbe-provider.jar jmbe-codec.jar jmbe-api.jar JTransforms-3.1.jar JLargeArrays-1.6.jar commons-math3-3.6.1.jar slf4j-api-2.0.17.jar
```

Copy `jmbe-provider.jar` to the phone, then go to **Setup → Voice codec → Import provider jar**. The app copies the
jar into its private storage, marks it read-only (Android 14 requires that before loading code), and loads it.

## How audio is chosen

Each followed voice channel gets its own decoder. One call plays at a time: the first one to deliver voice keeps the
speaker until it ends. Encrypted calls are never decoded. They're logged as encrypted, and that's all.
