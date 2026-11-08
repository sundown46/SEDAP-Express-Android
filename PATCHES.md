# Changes to upstream

`upstream/` holds an unmodified, filtered copy of [UNIITY-Team/SEDAP-Express](https://github.com/UNIITY-Team/SEDAP-Express). The exact state is in `upstream/UPSTREAM_COMMIT`.

**Patched files in `upstream/`: none.** Android differences are applied to a copy during the build, in `sedap-wire/build.gradle.kts`. A rule that no longer matches upstream fails the build, so an upstream change cannot silently disable it.

## Left out

| Files | Why |
|---|---|
| `module-info.java`, `ReleaseInfo.java` | Not needed on Android |
| `controls/` | JavaFX |
| `json/`, `protobuf/` | Extra dependencies, not used by the wire layer |
| `network/`, `processing/` | The Kotlin layer brings its own transports. They also need `java.net.http` and `com.sun.net.httpserver` |
| `utils/DHUtils`, `ECDHUtils`, `EncryptionUtils`, `FrodoUtils`, `KyberUtils` | Need BouncyCastle, encryption is not part of this library yet |
| `utils/MACUtils` | Replaced, see below |

## Replaced

`de.bundeswehr.uniity.sedapexpress.utils.MACUtils` in `sedap-wire/src/main/java` has the same API, but needs no BouncyCastle and registers no global security provider. Tests compare HMAC, CMAC and GMAC with BouncyCastle, and CMAC with the sample in the ICD.

Deliberate differences:

- `isMessageAuthentic` compares against the received MAC. In upstream the calc methods set the MAC field to `0000` first, so the comparison reads the overwritten value.
- `isMessageAuthentic` does not throw for keys that are not valid AES keys, it just skips CMAC.
- The comparison ignores the case of the received MAC and takes constant time.

## Swapped during the build

| Upstream | Replacement | Why |
|---|---|---|
| `java.util.HexFormat` | `compat.HexFormat` | API 34 |
| BouncyCastle `Base64`, `DecoderException` | `compat.Base64`, `compat.DecoderException` | Keeps BouncyCastle out of the library |
| `Class.getRecordComponents()`, `RecordComponent` | `compat.RecordComponent` with a table generated from the sources | API 34. Dex files lose the declaration order of record fields |
| `new BCDHPublicKey(...)` in `KEYEXCHANGE` | `compat.DhPublicKeys` | Uses the platform provider |
| `String.chars()` | `compat.Strings.chars()` | API 24, not covered by desugaring |
| `StringBuilder.isEmpty()` | `length() != 0` | API 35 |
| `InputStream.readAllBytes()` in `CompressUtils` | `compat.Streams.readAll()` | API 33. Also stops at 1 MiB, against compression bombs |
| `catch (IOException)` in `CompressUtils` | Also catches `IllegalStateException` | Invalid Base64 must not escape from `decompressMessage` |

## Test module `interop-jvm`

Compiles `upstream/` unchanged for the JVM, with BouncyCastle, so `ReferenceInteropProcessTest` in `sedap-express` can run the reference as a separate process. No swaps. Left out are only the parts that need more than BouncyCastle: `module-info.java`, `controls/`, `json/`, `protobuf/`, and in `network/` the MQTT and REST classes and `SSLUtils`. The list is in `interop-jvm/build.gradle.kts`.

## Updating upstream

```
tools/sync_upstream.sh <upstream-commit>
git merge vendor/upstream
./gradlew :sedap-wire:testDebugUnitTest :sedap-express:testDebugUnitTest
```

The tests also pin every known difference between upstream and `vectors/golden-vectors.json`, so a change in wire behaviour fails the build.
