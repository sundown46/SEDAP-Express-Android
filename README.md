# SEDAP-Express-Android

Android build of the Java reference for [SEDAP-Express](https://github.com/UNIITY-Team/SEDAP-Express), for apps and plugins that use SEDAP-Express messages.

**Status: early, not ready for use.** This is an unofficial version. So far it contains the message classes on `minSdk 21`, the Kotlin layer with transports is still to come.

## Layout

| Folder | Content |
|---|---|
| `upstream/` | Unmodified, filtered upstream code, see [PATCHES.md](PATCHES.md) |
| `sedap-wire/` | Builds the upstream message classes for Android: parsing, serializing, MAC, compression |
| `vectors/` | Expected wire results as JSON, independent of any implementation |
| `wire-probe/` | Small test app that runs all checks on a device. Not part of the library |

## Using it

Apps that use `sedap-wire` with `minSdk` below 33 need [core library desugaring](https://developer.android.com/studio/write/java8-support#library-desugaring). Without it the message classes do not load on API 21. `coreLibraryDesugaring` is pinned in `gradle/libs.versions.toml`.

## Building and testing

Needs JDK 17 and an Android SDK with platform 36.

```
./gradlew :sedap-wire:testDebugUnitTest         # JVM tests, reports in sedap-wire/build/reports/l0
./gradlew :sedap-wire:lintDebug                 # includes the check for APIs missing on API 21
./gradlew :sedap-wire:testDebugUnitTest :sedap-express:testDebugUnitTest -Psedap.testLocale=de-DE  # again under another default locale
./gradlew :wire-probe:connectedDebugAndroidTest # all checks on a device, logcat tag SEDAPProbe
python3 vectors/verify_vectors.py               # recalculates the vectors independently
```

To run the checks in a minified app, build with `:wire-probe:assembleRelease -Pprobe.selfCheck=true`, install it and start it.

## License

Own code: BSD 2-Clause, see [LICENSE](LICENSE). Upstream code in `upstream/`: BSD 2-Clause with its own copyright, see [upstream/LICENSE.txt](upstream/LICENSE.txt).
