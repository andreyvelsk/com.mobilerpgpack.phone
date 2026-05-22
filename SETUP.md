# Running the Project on macOS

## Requirements

| Tool | Version |
|---|---|
| macOS | 12+ (Apple Silicon or Intel) |
| Homebrew | any current |
| JDK | 17 |
| Android SDK Build-Tools | 37.0.0 |
| Android Platform | API 36 |
| Android NDK | 29.0.14206865 |
| CMake | 4.1.2 |
| Git | any current |
| ccache | any current (optional, speeds up rebuilds) |

---

## 1. Install Homebrew

If Homebrew is not installed:

```bash
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
```

---

## 2. Install JDK 17

```bash
brew install --cask temurin@17
```

After installation, add the following to `~/.zshrc` (or `~/.bash_profile`):

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export PATH="$JAVA_HOME/bin:$PATH"
```

Apply:

```bash
source ~/.zshrc
java -version  # should print: openjdk version "17.x.x"
```

---

## 3. Install Android command-line tools

```bash
brew install --cask android-commandlinetools
```

By default the tools are installed to `/opt/homebrew/share/android-commandlinetools`.  
Add the following to `~/.zshrc`:

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
```

Apply:

```bash
source ~/.zshrc
```

> **Note:** Brew installs `cmdline-tools` to `/opt/homebrew/share/android-commandlinetools/`,  
> but `sdkmanager` expects the structure at `$ANDROID_HOME`. After the first run of `sdkmanager`  
> with the `--sdk_root` flag the SDK will be downloaded to the correct location.

---

## 4. Install Android SDK components

Accept licenses and install the required components:

```bash
# Accept licenses
sdkmanager --sdk_root="$ANDROID_HOME" --licenses

# Install components
sdkmanager --sdk_root="$ANDROID_HOME" \
  "platform-tools" \
  "platforms;android-36" \
  "platforms;android-24" \
  "build-tools;37.0.0" \
  "ndk;29.0.14206865" \
  "cmake;4.1.2"
```

> Downloading NDK (~1.5 GB) and CMake will take some time. Make sure you have ~10 GB of free disk space.

---

## 5. Install ccache (optional but recommended)

ccache caches C/C++ compilation and significantly speeds up incremental builds:

```bash
brew install ccache
```

---

## 6. Clone the repository and initialize submodules

```bash
git clone <REPOSITORY_URL>
cd com.mobilerpgpack.phone

# Initialize all submodules (takes time, ~several GB)
git submodule update --init --recursive
```

> **Alternative — required submodules only** (faster):
> ```bash
> git submodule update --init third_party/sdl2-compat
> git submodule update --init third_party/sdl3
> # ... others as needed
> ```

---

## 7. Create `local.properties`

Gradle needs to know the path to the Android SDK. Create the `local.properties` file in the project root:

```bash
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
```

---

## 8. Google flavor configuration (Firebase)

The `google` flavor requires `app/google-services.json` from Firebase.  
If you don't have it — use the `fdroid` flavor, which does not depend on Firebase.

---

## 9. Build

### Debug build (recommended for development)

```bash
# Google flavor
./gradlew assembleGoogleDebug

# F-Droid flavor (no Firebase, does not require google-services.json)
./gradlew assembleFdroidDebug

./gradlew assembleFdroidDebug -Pandroid.injected.build.abi=arm64-v8a
```

To speed things up — build for a single architecture (ARM64 for Apple Silicon):

```bash
./gradlew assembleGoogleDebug -Pandroid.injected.build.abi=arm64-v8a
```

### Install on a device / emulator

```bash
# Make sure ADB sees the device
adb devices

# Build and install in one command
./gradlew installFdroidDebug -Pandroid.injected.build.abi=arm64-v8a
```

#### Manual installation of an already-built APK

The debug APK is located at:
```
app/build/intermediates/apk/fdroid/debug/
```

> **Important:** The debug APK is marked `testOnly`, so a plain `adb install` will return
> `INSTALL_FAILED_TEST_ONLY`. Use the `-t` flag:

```bash
# Find the latest APK
ls -t app/build/intermediates/apk/fdroid/debug/*.apk | head -1

# Install (the -t flag is required for debug APKs)
adb install -t -r -d "app/build/intermediates/apk/fdroid/debug/<name>.apk"

# Or in one line — automatically install the most recent APK
adb install -t -r -d "$(ls -t app/build/intermediates/apk/fdroid/debug/*.apk | head -1)"
```

Flags:
- `-t` — allow installation of a testOnly APK
- `-r` — reinstall, keeping app data
- `-d` — allow version downgrade

---

## 10. Run on an emulator (without a real device)

1. Install Android Studio or create an AVD via command-line:
   ```bash
   sdkmanager --sdk_root="$ANDROID_HOME" "system-images;android-36;google_apis;arm64-v8a"
   avdmanager create avd -n Pixel9 -k "system-images;android-36;google_apis;arm64-v8a"
   ```
2. Start the emulator:
   ```bash
   $ANDROID_HOME/emulator/emulator -avd Pixel9
   ```
3. Install the APK:
   ```bash
   ./gradlew installGoogleDebug -Pandroid.injected.build.abi=arm64-v8a
   ```

---

---

## 11. Collecting crash logs

### Requirements
- USB debugging enabled on the device
- `adb devices` shows the device as `device`

### Quick method — log before and after the crash

```bash
# 1. Clear the log buffer
adb logcat -c

# 2. Launch the app on the device manually and reproduce the crash

# 3. Immediately after the crash — save the log to a file
adb logcat -d > crash.log
```

### Critical lines only (quick review)

```bash
adb logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime|libuzdoom|signal|Abort|backtrace" | head -80
```

### Native crash (tombstone)

If native code (`.so`) crashes, Android saves a tombstone file on the device:

```bash
# List the latest tombstone files
adb shell ls -lt /data/tombstones/ | head -10

# Copy the latest one to Mac
adb pull /data/tombstones/tombstone_00 ./tombstone_00.txt

# View
cat tombstone_00.txt | head -100
```

### Real-time monitoring

```bash
# Follow logs for a specific package in real time
adb logcat --pid=$(adb shell pidof com.mobilerpgpack.phone) 2>/dev/null

# Or filter by UZDoom/zdoom tag
adb logcat -s zdoom:V libuzdoom:V AndroidRuntime:E
```

> **Tip:** After the first symbolicated crash, the native backtrace contains only
> addresses. To decode them into function names — use `ndk-stack` from the Android NDK:
> ```bash
> adb logcat | $ANDROID_HOME/ndk/29.0.14206865/ndk-stack \
>   -sym app/build/intermediates/cxx/Debug/*/obj/arm64-v8a/
> ```

---

## Troubleshooting

| Problem | Solution |
|---|---|
| `SDK location not found` | Create `local.properties` with `sdk.dir=...` |
| `NDK not found` | Make sure NDK `29.0.14206865` is installed via `sdkmanager` |
| `CMake version mismatch` | Install CMake `4.1.2` via `sdkmanager`, not the system brew cmake |
| `google-services.json missing` | Use the `fdroid` flavor or add the file from Firebase |
| `submodule not initialized` | Run `git submodule update --init --recursive` |
| Slow rebuild | Install `ccache` (`brew install ccache`) |
| `INSTALL_FAILED_TEST_ONLY` | Use `adb install -t` for debug APKs |
| APK not found in `outputs/apk` | Debug APK is in `app/build/intermediates/apk/fdroid/debug/` |

---

## Project config versions

```
Gradle:         9.5.0
AGP:            9.2.1
Kotlin:         2.3.21
min SDK:        24
target SDK:     36
NDK:            29.0.14206865
CMake:          4.1.2
Build-Tools:    37.0.0
```

---

## 12. Selecting engines to build

**Single source of truth** — the `ENGINES_TO_BUILD` list in [app/build.gradle](app/build.gradle). It automatically controls:
- which native engines are compiled (passed to CMake as `-DENGINES_TO_BUILD`)
- which engines appear in the UI dropdown (via `BuildConfig.ENGINES_TO_BUILD` → `EngineTypes.ENABLED_ENGINES`)
- which asset directories are included in the APK

### 12.1 Adding or removing an engine

Open [app/build.gradle](app/build.gradle) and find the line:

```groovy
def ENGINES_TO_BUILD = ["UZDoom"]
```

Add the engines you need, for example:

```groovy
def ENGINES_TO_BUILD = ["UZDoom", "PsyDoom"]
```

Available names (`EngineTypes` enum values):

| Name | Game | Asset directory |
|---|---|---|
| `UZDoom` | DOOM (all parts), mods | `uzdoom/` |
| `PsyDoom` | PlayStation DOOM | — |
| `Doom64ExPlus` | DOOM 64 | `doom64ex-plus/` |
| `Doom64ExPlusEnhanced` | DOOM 64 Enhanced | `doom64ex-plus-enhanced/` |
| `DoomRpg` | DOOM RPG | `com.codelobster/`, `opus-ct2-en-ru/` |
| `Doom2Rpg` | DOOM II RPG | `com.codelobster/`, `opus-ct2-en-ru/` |
| `WolfensteinRpg` | Wolfenstein RPG | `com.codelobster/`, `opus-ct2-en-ru/` |
| `ArxLibertatis` | Arx Fatalis | `ArxLibertatis/` |
| `FTEQW` | Quake series | — |
| `Widelands` | Widelands | `widelands/` (~660 MB) |
| `VanillaConquer` | C&C Tiberian Dawn / Red Alert | — |
| `Classic_RBDOOM_3_BFG` | DOOM 3 BFG Edition | `doombfa/` |
| `PerfectDark` | Perfect Dark | — |

> After making changes, rebuild the project: `./gradlew assembleFdroidDebug`

### 12.2 Configuring the WAD file for UZDoom

On first launch the "WAD file path" field is empty. You will see the message "Game resource file not found".

1. Copy a WAD file to the device (e.g. `freedoom2.wad` from [freedoom.github.io](https://freedoom.github.io)).
2. In the app settings, specify the full path to the file.
3. Tap "Launch game".
