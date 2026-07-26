# VoiceZettelAssist

A lightweight, production-ready Android app that lets you capture voice notes
from anywhere on your device with a custom hardware key combination, transcribes
them with **Gemini 1.5 Flash**, and appends the transcript as a timestamped
Markdown entry into your [Zettel Notes](https://github.com/zettelnotes-app)
repository folder.

---

## Features

- **Hardware-key trigger** — double-press Volume Down (or pick another combo) to
  pop up the recording overlay from any app, even the lockscreen.
- **Floating overlay UI** — minimalist pulsing-mic animation with Stop & Cancel
  buttons; auto-stops after 60 s as a safety net.
- **Gemini 1.5 Flash transcription** — AAC audio is Base64-encoded and POSTed
  to the `generateContent` REST endpoint; only the plain transcript text is
  extracted from the response.
- **Zettel Notes vault integration** — picks a vault folder via SAF
  (`ACTION_OPEN_DOCUMENT_TREE`), persists the URI, and appends to a daily note
  named `YYYY-MM-DD.md` in the form:

  ```markdown
  # 2026-07-26

  ## [14:32] Voice Note
  Your transcribed text here.
  ```
- **Secure secret storage** — the Gemini API key is stored in
  `EncryptedSharedPreferences` (AES-256 GCM master key, AES-256 SIV key
  wrapping).

---

## Tech Stack

| Concern               | Choice                                                  |
|-----------------------|---------------------------------------------------------|
| Language              | Kotlin 2.0.0                                            |
| UI                    | Jetpack Compose + Material 3                            |
| Min / Target SDK      | 26 (Android 8.0) / 34 (Android 14)                      |
| Architecture          | MVVM with `AndroidViewModel` + `StateFlow`              |
| Async                 | Coroutines & Flow                                       |
| Network               | OkHttp 4.12                                             |
| Storage abstraction   | `androidx.documentfile` (SAF / DocumentFile)            |
| Secret storage        | `androidx.security:security-crypto`                     |
| Build tooling         | AGP 8.5, Gradle 8.7, version catalog (`libs.versions.toml`) |

---

## Project Layout

```
VoiceZettelAssist/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/gradle-wrapper.properties
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/voicezettel/assist/
│       │   ├── VoiceZettelApp.kt              # Application — registers notification channel
│       │   ├── MainActivity.kt                # Compose settings UI
│       │   ├── SettingsViewModel.kt           # StateFlow-backed settings VM
│       │   ├── SecurePrefs.kt                 # EncryptedSharedPreferences wrapper
│       │   ├── VoiceAccessibilityService.kt   # Hardware key combo detection
│       │   ├── VoiceRecordOverlay.kt          # Floating recording overlay (Compose)
│       │   ├── AudioRecorder.kt               # MediaRecorder → AAC file
│       │   ├── GeminiTranscriber.kt           # OkHttp + Gemini REST
│       │   ├── MarkdownNoteWriter.kt          # SAF DocumentFile appender
│       │   └── ui/theme/                      # Compose Material3 theme
│       └── res/
│           ├── xml/accessibility_service_config.xml
│           ├── drawable/ic_launcher_foreground.xml
│           ├── mipmap-anydpi-v26/ic_launcher.xml
│           ├── mipmap-anydpi-v26/ic_launcher_round.xml
│           └── values/{strings,colors,themes}.xml
```

---

## How to Build

### Prerequisites

- **Android Studio Iguana (2023.2.1) or newer** (or just the Android SDK +
  JDK 17 + Gradle 8.7 if building from CLI).
- JDK 17 installed and on `JAVA_HOME`.
- An Android device or emulator running **API 26 (Android 8.0) or higher**.
  A physical device is strongly recommended — hardware volume-key interception
  and `SYSTEM_ALERT_WINDOW` overlays behave unreliably on emulators.

### Option A — Android Studio (recommended)

1. Launch Android Studio → **File → Open** → select the `VoiceZettelAssist/`
   directory.
2. Wait for Gradle sync to finish (it will download AGP 8.5, Kotlin 2.0.0,
   Compose BOM 2024.06.00, etc.).
3. Connect your Android device with **USB debugging** enabled.
4. Select the `app` run configuration and click **Run** ▶.

### Option B — Command Line

```bash
cd VoiceZettelAssist

# Debug build & install on a connected device.
./gradlew installDebug

# Or assemble a release APK (will be unsigned unless you provide a keystore).
./gradlew assembleRelease
# → app/build/outputs/apk/release/app-release-unsigned.apk
```

The Gradle wrapper files (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`,
`gradle/wrapper/gradle-wrapper.properties`) are already included in the repo, so
you don't need a system-wide Gradle install.

### Option C — GitHub Actions (no local build toolchain needed)

A prebuilt workflow lives at [`.github/workflows/build.yml`](.github/workflows/build.yml).
It's adapted from [BaseMax/AndroidAutoBuildAPK](https://github.com/BaseMax/AndroidAutoBuildAPK)
with the following modernizations:

| Aspect                     | BaseMax original                 | This project                          |
|----------------------------|----------------------------------|---------------------------------------|
| JDK                        | 11                               | **17** (required by AGP 8.5)          |
| `actions/checkout`         | v3 (deprecated)                  | **v4**                                |
| `actions/setup-java`       | v3 (deprecated)                  | **v4**                                |
| Release creation           | `actions/create-release@v1` (deprecated) | **`gh release create`** (GitHub CLI) |
| Release asset upload       | `actions/upload-release-asset@v1` (deprecated) | **`gh release create <tag> <file>`** |
| Auth token                 | Custom PAT stored as `secrets.TOKEN` | **Built-in `secrets.GITHUB_TOKEN`** (auto-provisioned, no PAT needed) |
| Trigger                    | Comment on issue #1              | **Push to `main` + version tags `v*.*.*` + manual `workflow_dispatch`** |

**Setup steps:**

1. **Revoke any leaked tokens.** If you've ever pasted a GitHub PAT into a chat,
   log, or commit, revoke it at https://github.com/settings/tokens — you don't
   need a PAT for this workflow.
2. Create a new GitHub repository (e.g. `yourname/VoiceZettelAssist`).
3. Push the project:
   ```bash
   cd VoiceZettelAssist
   git init
   git add .
   git commit -m "Initial VoiceZettelAssist project + GitHub Actions workflow"
   git branch -M main
   git remote add origin git@github.com:yourname/VoiceZettelAssist.git
   git push -u origin main
   ```
4. Go to **Actions** tab in your GitHub repo. The "Build & Release APK" workflow
   will run automatically on every push.
5. Once it succeeds, download the APK from the run's **Artifacts** section
   (`voicezettel-assist-debug-apk.zip` → unzip → `app-debug.apk`).
6. **(Optional) Publish a Release.** Tag a commit to trigger a Release:
   ```bash
   git tag v1.0.0
   git push origin v1.0.0
   ```
   The workflow will create a GitHub Release named `VoiceZettelAssist v1.0.0`
   with the APK attached, and auto-generate release notes from the commits
   since the previous tag.

No GitHub secrets need to be configured — the built-in `GITHUB_TOKEN` is
automatically provisioned by Actions and granted `contents: write` permission
by the `permissions:` block at the top of the workflow.

---

## Initial Permission Setup

After installing, complete this checklist **once** before the trigger combo
will work:

### 1. Open the app
Launch **VoiceZettelAssist** from your launcher. You'll land on the settings
screen.

### 2. Grant Microphone permission
- Tap the **Microphone (RECORD_AUDIO)** row → **Open**.
- In the system permission sheet, choose **Allow only while using the app**
  (the overlay is launched as an activity, so "while using" is sufficient).

### 3. Grant "Display over other apps" (SYSTEM_ALERT_WINDOW)
- Tap **Display over other apps** → **Open**.
- Toggle **Allow display over other apps** for VoiceZettelAssist.
  This is required so the floating overlay can appear on top of whatever app
  you were in when you triggered the combo.

### 4. Enable the Accessibility Service
- Tap **Accessibility Service** → **Enable**.
- Scroll to **Voice Zettel Assist** and toggle it on.
- Acknowledge the system warning about key-event monitoring. (We only
  intercept volume keys, and only consume them when a configured combo is
  actually triggered — normal volume changes still work.)

The settings screen should now show **Accessibility Service: ACTIVE** with a
green dot. If not, return to the app from the accessibility settings screen to
refresh the status card.

### 5. Configure Gemini API Key
- Obtain a key from
  [Google AI Studio → API keys](https://aistudio.google.com/app/apikey).
- Paste it into the **Gemini API Key** field and tap **Save**. The field is
  masked by default; tap **Show** to verify.

### 6. Pick your Zettel Notes vault folder
- Tap **Pick Zettel Notes Vault Folder**.
- In the system document picker, navigate to the folder where Zettel Notes
  stores your Markdown repository and tap **Use this folder** → **Allow**.
  The app will persist this URI across reboots.

### 7. Pick a trigger combination
- Use the dropdown under **Trigger Key Combination**. The default is
  **Double-Press Volume Down (within 400 ms)**.

You're ready. Lock the screen, navigate to another app, then double-press
Volume Down — the floating overlay should appear.

---

## Daily Note Format

The app creates (or appends to) a file named `YYYY-MM-DD.md` inside the
selected vault folder. Each voice note appends the following block:

```markdown

## [HH:mm] Voice Note
<transcribed text here>

```

If the file doesn't yet exist, a top-level `# YYYY-MM-DD` heading is added
before the first entry so the file isn't bare.

---

## Troubleshooting

| Symptom                                                | Likely cause / fix                                                                 |
|--------------------------------------------------------|------------------------------------------------------------------------------------|
| Combo does nothing                                     | Accessibility service not enabled. Re-check the green status dot in settings.      |
| Overlay appears then immediately disappears            | Recording too short (<256 bytes) — record for at least ~1 second.                  |
| "Could not transcribe audio (HTTP 400)"                | Usually means the API key is invalid or the audio file is corrupt. Re-paste key.   |
| "Failed to save note to vault" toast                   | Vault URI permission was revoked — re-pick the folder in Settings.                 |
| Volume keys no longer change the ringer volume         | This is expected **only** for the keys that form the active combo; others still work. |
| Overlay doesn't appear over the lockscreen             | Make sure **Display over other apps** is granted and the device isn't in battery-saver mode. |

---

## Privacy Notes

- The Gemini API key never leaves the device except as a query parameter on the
  `generativelanguage.googleapis.com` request — it is not logged or otherwise
  transmitted.
- Audio is recorded to the app's private cache directory, uploaded once, and
  deleted immediately after transcription regardless of success/failure.
- No analytics, no telemetry, no background network calls outside of an active
  transcription request.

---

## License

MIT — see source headers. Built as a reference implementation; adapt freely.
