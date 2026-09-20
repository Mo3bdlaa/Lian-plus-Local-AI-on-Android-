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
| glslc | any recent version |

Install the SDK pieces with:

```bash
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0" \
           "ndk;27.2.12479018" "cmake;3.22.1"
```

`glslc` compiles the Vulkan compute shaders on the build host:

```bash
apt install glslc          # Debian/Ubuntu
brew install glslang       # macOS
```

It is the only host dependency outside the SDK. Without it the build still
succeeds — CMake prints a warning and produces a CPU-only APK, about half the
size. The Vulkan headers themselves are vendored as submodules
(`native/Vulkan-Headers`, `native/SPIRV-Headers`) pinned to 1.3.275, the version
the NDK's loader implements; a mismatch there compiles cleanly and then
misbehaves on device, which is not a failure mode worth leaving open.

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
| `lian.vulkan` | `true` | `false` skips the Vulkan backend in both engines. Halves the APK and cuts about fifteen minutes off a clean build; the GPU option then reports itself unavailable with that reason. |

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

Roughly 96 MB, almost all of it the two native libraries: about 28 MB for the
text engine and 62 MB for the image engine once stripped and compressed. The
compiled Vulkan shaders are the bulk of that — 1238 SPIR-V variants in the
text engine and 1611 in the image engine — `-Plian.vulkan=false` brings
the APK back to about 44 MB and gives up the GPU.

To drop the image engine entirely, set `LIAN_BUILD_IMAGE=OFF` in
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

**Out of disk during the native build** — a full two-engine build with Vulkan
needs about 12 GB of scratch space under `app/.cxx`; the shader objects alone are
several gigabytes before archiving. `./gradlew clean` reclaims it, and
`-Plian.vulkan=false` roughly halves the requirement.

**The APK came out at 44 MB instead of 96 MB** — Vulkan was skipped. The build
log says why, as a CMake warning: `glslc` missing, or a submodule not
initialised. `LIAN_VULKAN_READY` gates the backend on both, so a missing piece
degrades to CPU-only rather than failing the build.

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

## Versioning

`versionName` comes from the tag and `versionCode` is derived from it:
`0.2.3` becomes `20003`. Android refuses to install a package whose
`versionCode` is not greater than the installed one, so this is what makes an
update install over the previous build instead of being rejected.

Local builds default to `0.1.0`; override with
`-Plian.versionName=1.2.3` when you need to check a specific version.

## Signing

The APK's signing key is the app's permanent identity. Every update has to be
signed with the same key or Android treats it as a different app and refuses to
install over the existing one — and there is no recovery from losing it beyond
asking people to uninstall, which wipes their chats and downloaded models.

Create one once:

```bash
keytool -genkeypair -v -keystore lian-release.jks -storetype PKCS12 \
  -alias lian-release -keyalg RSA -keysize 4096 -validity 10950 \
  -dname 'CN=Your Name, O=Lian\+, C=EG'
```

Note the escaped `+`: X.500 treats it as a separator between name components,
so an unescaped one fails with "empty AVA in RDN".

For local release builds, put a `keystore.properties` in the project root (it is
gitignored). For CI, set the four repository secrets listed below.

## Publishing a release

Tag and push; CI does the rest:

```bash
git tag -a v0.2.0 -m "Lian+ v0.2.0"
git push origin v0.2.0
```

`.github/workflows/release.yml` checks out the tag with submodules, installs the
NDK, runs the unit tests, builds the release APK, and creates a GitHub release
with the APK attached and its SHA-256 in the notes. Put hand-written notes in
`.github/release-notes/<tag>.md` and they are used verbatim.

Without signing secrets the workflow generates a throwaway debug key so the APK
is at least installable, and says so in the release notes. To sign properly, set
four repository secrets:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `KEYSTORE_PASSWORD` | the store password |
| `KEY_ALIAS` | the key alias |
| `KEY_PASSWORD` | the key password |

The same workflow can be started by hand from the Actions tab, which is the way
to re-run a publish without moving the tag.
