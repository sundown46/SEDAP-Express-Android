# SEDAP-Express-Android

Android build of the Java reference for [SEDAP-Express](https://github.com/UNIITY-Team/SEDAP-Express), for apps and plugins that use SEDAP-Express messages.

**Status: early, not used in a product yet.** This is an unofficial version. It contains the upstream message classes on `minSdk 21` and a Kotlin layer with TCP and UDP transports, MAC, decompression, time sync and a reconnecting connection. Tested on the JVM, against the unmodified Java reference and on an API 21 emulator, not yet on real devices or against real servers.

## Layout

| Folder | Content |
|---|---|
| `upstream/` | Unmodified, filtered upstream code, see [PATCHES.md](PATCHES.md) |
| `sedap-wire/` | Builds the upstream message classes for Android: parsing, serializing, MAC, compression |
| `sedap-express/` | Kotlin layer: parser gate, send and receive pipeline, MAC over the received line, TCP and UDP, `SedapConnection` |
| `vectors/` | Expected wire results as JSON, independent of any implementation |
| `wire-probe/` | Small test app that runs all checks on a device. Not part of the library |
| `interop-jvm/` | Test module only: the unmodified Java reference for the JVM, which the tests of `sedap-express` talk to over real sockets. It is the only place that uses BouncyCastle, the library itself does not |

## Using it

Apps that use `sedap-wire` with `minSdk` below 33 need [core library desugaring](https://developer.android.com/studio/write/java8-support#library-desugaring). Without it the message classes do not load on API 21. `coreLibraryDesugaring` is pinned in `gradle/libs.versions.toml`.

`sedap-express` adds Kotlin 2.1.10 and kotlinx-coroutines 1.10.2 (both part of its API). It needs no extra R8 rules. It imports nothing from `android.*`: platform parts such as keepalive timings, the multicast lock and network changes are plugged in through `SocketConfigurator`, `MulticastLockHook`, `networkLost()` and `networkAvailable()`.

## Building and testing

Needs JDK 17 and an Android SDK with platform 36.

```
./gradlew :sedap-wire:testDebugUnitTest :sedap-express:testDebugUnitTest  # JVM tests, including the reference in its own process
./gradlew :sedap-wire:lintDebug :sedap-express:lintDebug  # includes the check for APIs missing on API 21
./gradlew :sedap-wire:testDebugUnitTest :sedap-express:testDebugUnitTest -Psedap.testLocale=de-DE  # again under another default locale
./gradlew :wire-probe:connectedDebugAndroidTest # all checks on a device, logcat tag SEDAPProbe; set ANDROID_SERIAL if several are attached
python3 vectors/verify_vectors.py               # recalculates the vectors independently
```

To run the checks in a minified app, build with `:wire-probe:assembleRelease -Pprobe.selfCheck=true`, install it and start it.

## License

Own code: BSD 2-Clause, see [LICENSE](LICENSE). Upstream code in `upstream/`: BSD 2-Clause with its own copyright, see [upstream/LICENSE.txt](upstream/LICENSE.txt).
