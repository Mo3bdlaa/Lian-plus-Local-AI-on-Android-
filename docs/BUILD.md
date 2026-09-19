# Building

## Toolchain

| | Version |
|---|---|
| JDK | 17 or newer |
| Android Gradle Plugin | 8.7.3 |
| Gradle | 8.11.1 (via the wrapper) |
| compileSdk / targetSdk | 35 |
| minSdk | 29 (Android 10) |
| NDK | 27.2.12479018 |
| CMake | 3.22.1 |

Install the SDK pieces with:

```bash
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0" \
           "ndk;27.2.12479018" "cmake;3.22.1"
```

## Getting the sources

The two engines are git submodules:

```bash
git clone --recurse-submodules <repo-url>
# or, if you already cloned:
git submodule update --init --recursive
```

`native/stable-diffusion.cpp` has submodules of its own, which is why
`--recursive` matters.

## Building

```bash
./gradlew assembleDebug      # debug APK
./gradlew assembleRelease    # minified, shrunk release APK
./gradlew test               # unit tests (no device needed)
```

The first native build compiles llama.cpp and stable-diffusion.cpp from source
and takes a while. Later builds are incremental.

## Build flags

Set in `gradle.properties` or passed with `-P`:

| Flag | Default | Effect |
|---|---|---|
| `lian.buildNative` | `true` | `false` builds stub engines — the APK installs and the UI works, but the engines report themselves unavailable. Cuts a full build to about a minute; use it for UI work. |
| `lian.abiFilters` | `arm64-v8a` | ABIs to build. `arm64-v8a` covers every modern phone. Adding `armeabi-v7a` roughly doubles build time and APK size for devices that have too little memory to be useful anyway. |

```bash
./gradlew assembleDebug -Plian.buildNative=false
```

## Signing a release

Create `keystore.properties` in the project root (it is gitignored):

```properties
storeFile=/absolute/path/to/release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

Without it, `assembleRelease` produces an unsigned APK.

## APK size

Roughly 45 MB, dominated by the two native libraries (about 4 MB for the text
engine, about 35 MB for the image engine once stripped). To drop the image
engine entirely, set `LIAN_BUILD_IMAGE=OFF` in
`app/src/main/cpp/CMakeLists.txt`; the Images screen then reports the engine as
unavailable and everything else works.

## Installing

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

## Troubleshooting

**`native/llama.cpp is empty`** — the submodules were not initialised. Run
`git submodule update --init --recursive`.

**`No resources.properties file found`** — `app/src/main/res/resources.properties`
is missing. It declares the locale of the unqualified `res/values` folder, which
`generateLocaleConfig` requires.

**Out of disk during the native build** — a full two-engine build needs about
6 GB of scratch space under `app/.cxx`. `./gradlew clean` reclaims it.

**`UnsatisfiedLinkError` at runtime** — the APK was built with
`lian.buildNative=false`, or for the wrong ABI. Check with:

```bash
unzip -l app-release.apk | grep '\.so$'
```

**Model loads, then the app is killed** — the file is too large for the device.
The Device screen's "largest model" figure is the number to stay under; the
Models screen marks files that exceed it.

## Testing on a device

Unit tests (`./gradlew test`) cover the tool-call parser, the calculator's
expression grammar, chunking, vector search, quantisation detection and the
chat templates — all the pure logic, no device required.

The engines themselves need real hardware. A useful smoke test:

```bash
adb shell am start -n com.lian.plus/.MainActivity
adb logcat -s LianNative LianEngine LianRuntime
```

Then, with the API server running:

```bash
adb forward tcp:8080 tcp:8080
curl localhost:8080/health
curl localhost:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"messages":[{"role":"user","content":"Say hello"}]}'
```
