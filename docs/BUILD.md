# Building YTCache

## Prerequisites

| Tool | Version | Why |
|---|---|---|
| JDK | 17 | Required by AGP 8.5 |
| Android SDK | platform `android-34`, build-tools `34.0.0` | `compileSdk`/`targetSdk` 34 |
| Gradle | 8.7 (wrapper included) | Kotlin 1.9.24, AGP 8.5.2 |
| Node.js | 18+ (optional) | JS test harnesses in `tools/` |

You do **not** need Android Studio. A headless CLI build is fully supported.

## One-command setup

```bash
bash scripts/setup-env.sh
```

The script is idempotent and:

1. finds or downloads a JDK 17 (Temurin) into `$HOME/tools/jdk17`;
2. installs `cmdline-tools`, accepts licences and installs `platform-tools`,
   `platforms;android-34`, `build-tools;34.0.0` into `$HOME/android-sdk`;
3. downloads Gradle 8.7 into `$HOME/tools/gradle-8.7`;
4. writes `local.properties` with `sdk.dir`;
5. generates a development release keystore at `keystore/ytcache.jks`
   (`storepass`/`keypass`/alias all `ytcache`) **if one does not exist yet**.

`keystore/` is git-ignored on purpose. If you publish releases, keep your keystore and its password
in a safe place: Android treats a different key as a different app, so an update signed with a new
key cannot install over the old one.

## Build

```bash
export JAVA_HOME="$HOME/tools/jdk17"
export ANDROID_SDK_ROOT="$HOME/android-sdk"

./gradlew test              # 57 JVM unit tests
./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk (signed if keystore exists)
```

If `keystore/ytcache.jks` is missing, the release variant falls back to the debug signing config so
the build still succeeds — fine for testing, not for publishing.

Clean builds:

```bash
./gradlew clean test assembleDebug assembleRelease
```

Gradle daemon issues on small machines:

```bash
./gradlew --no-daemon -Dorg.gradle.jvmargs=-Xmx2g assembleDebug
```

## JavaScript tests

The capture hook and the shared player helpers run in Node with mocks, so the risky code is covered
without a device.

```bash
node tools/test-core.js    # 19 checks: sniffing, mime candidates, stream ranking, formatting
node tools/test-hook.js    # 19 checks: v1.js capture exactness, transports, idempotence
```

Run both after touching anything in `app/src/main/assets/`.

## Verify the APK

```bash
export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/build-tools/34.0.0:$PATH"

apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
aapt dump badging app/build/outputs/apk/release/app-release.apk | head -5
sha256sum app/build/outputs/apk/release/app-release.apk
```

Expected: `Verifies` with signer `CN=YTCache`, package `com.ytcache.app`, `versionCode 4`,
`versionName 1.2.0`, `sdkVersion: 26`, `targetSdkVersion: 34`.

## Install

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

If installation fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, a build signed with a different key
is already installed — uninstall it first (`adb uninstall com.ytcache.app`), which deletes its
captures.

## Release process used for this repository

1. Bump `versionCode` / `versionName` in `app/build.gradle.kts`.
2. Update `docs/CHANGELOG.md` and the release notes.
3. `./gradlew clean test assembleDebug assembleRelease`.
4. Verify signature, badging and checksum as above.
5. Copy the APKs to `artifacts/` and attach them to a GitHub release tagged `v<versionName>`.
   APKs are **not** committed to git.
6. Include SHA-256 values in the release notes.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `SDK location not found` | Write `local.properties` with `sdk.dir=/path/to/android-sdk` |
| `Unsupported class file major version` | Java is not 17; export `JAVA_HOME` |
| `Failed to install ... build-tools` | Re-run `sdkmanager --install "build-tools;34.0.0"` |
| Gradle out of memory | `-Dorg.gradle.jvmargs=-Xmx2g`, or stop daemons with `./gradlew --stop` |
| Release APK unsigned | Create `keystore/ytcache.jks` (see above) and rebuild |
| Build works, app shows a blank WebView | Check the device's Android System WebView is updated; YouTube needs a modern Chrome engine |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Different signing key installed — uninstall first |

## Project configuration notes

- `buildFeatures { viewBinding = true; buildConfig = true }` — screens use generated binding
  classes, and `BuildConfig.DEBUG` gates console logging.
- `packaging.resources.excludes` drops `META-INF/*.version`, `META-INF/LICENSE*` and `kotlin/**` to
  keep the APK small.
- Minification is intentionally off (`isMinifyEnabled = false`): the app is small, and the JS bridge
  and reflection-free WebView paths are easier to debug.
- No external media dependencies — playback is MSE in the WebView, so Media3/ExoPlayer is not
  needed.
