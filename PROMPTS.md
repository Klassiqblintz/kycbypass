# KYC Bypass App — Original Prompts

## Prompt 1 — Project Setup & Architecture

Build a minimal, standalone Android app that bypasses KYC (Know Your Customer) face verification by acting as a virtual camera. It uses the same architecture as the xCam Xposed module but as a self-contained app with a floating control bubble.

### Architecture (xCam-inspired)

1. **Camera1 (Legacy) path:**
   - Hook: `Camera.setPreviewTexture()` → replace app's `SurfaceTexture` with a dummy one
   - Feed: `MediaPlayer` loops a video onto the original `SurfaceTexture`
   - Capture: On `Camera.takePicture()`, extract a frame from the video and feed it as JPEG

2. **Camera2 path:**
   - Hook: `CameraDeviceImpl.createCaptureSession()` → intercept Surface list
   - For each preview Surface, replace it with a dummy `SurfaceTexture` surface
   - Feed: `ExoPlayer` renders video onto each intercepted preview Surface
   - Capture: On `ImageReader.acquireLatestImage()` with JPEG format, replace buffer contents

3. **Capture/Photo hook:**
   - Hook: `ContentResolver.openFileDescriptor()` → intercept write path for captured images
   - Hook: `Bitmap.compress()` → intercept JPEG output
   - Hook: `BitmapFactory.decodeByteArray()` / `decodeStream()` → intercept decode path

4. **Video recording hook:**
   - Hook: `MediaRecorder.setOutputFile()` / `setNextOutputFile()`
   - On `MediaRecorder.stop()`, replace the recorded file with a trimmed segment of the virtual video

5. **Audio injection:**
   - Hook: `AudioRecord.read()` → inject audio from the virtual video's audio track

### Key Flows
- User picks a video or image (from gallery or camera) → stored locally
- User opens a KYC app → taps floating bubble to enable "Virtual Camera"
- The app hooks the KYC app's camera calls → replaces live feed with the chosen media
- When KYC asks for a photo, the app returns a frame from the video
- When KYC records video, the app replaces the output file

### Requirements
- Android 9+ (API 28+)
- ROOT not required (works via Surface injection + MediaPlayer/ExoPlayer)
- CAMERA permission (to access camera state)
- STORAGE permission (for media files)
- OVERLAY permission (for floating bubble)

---

## Prompt 2 — Standalone (Non-Root) Mode

The app must be a **standalone Android app**, NOT an Xposed module. However, it should be **compatible** with Xposed/LSposed for deeper hooks when those are installed. The architecture should:

1. Detect whether XposedBridge is present at runtime (via reflection on `de.robv.android.xposed.XposedBridge`)
2. If Xposed is found, delegate hooking to XposedBridge
3. If Xposed is NOT found, fall back to a built-in HookRegistry that uses proxy-based hooking (no root needed)
4. The app should work completely standalone on Android 9+ without root — the xCam hooks should also work via the built-in hooking mechanism

---

## Prompt 3 — Features

### Features:
- **Floating bubble overlay** for enable/disable toggle — uses `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY`
- **Per-app scoping** — user selects which installed apps get virtual camera injection (list of target app package names)
- **Media picker** — user can pick a video or image from gallery or camera
- **Video/image as virtual camera feed** — the chosen media is played as a live camera feed
- **Rotation control** — user can rotate the virtual camera feed by 0/90/180/270 degrees
- **Mirror control** — user can mirror the feed horizontally
- **Video loop control** — user can enable/disable video looping
- **Audio injection** — inject audio from the virtual video into the target app's `AudioRecord`

---

## Prompt 4 — Compose-free Layout

Replace the Jetpack Compose UI with a plain Activity using XML layout for compatibility with older Kotlin compilers (CodeAssist environment). The layout should include:

- Status text view showing current state (active/inactive, media path, target app status)
- Buttons: Start Camera, Stop Camera, Start Bubble, Stop Bubble, Grant Permissions, Revoke Permissions, Pick Media
- Spinner for rotation angle (0, 90, 180, 270)
- Checkboxes for mirror and video loop
- Text view showing current media path

All activities should use `AppCompatActivity` with try-catch wrapping in `onCreate()` for crash protection.

---

## Prompt 5 — CodeAssist Compatibility Fixes

The app targets the **CodeAssist environment** which has:
- Old Kotlin compiler
- Old Android API (android.jar at `/root/downloads/android.jar`)
- `SettingsManager` uses explicit getter/setter methods (not direct property access)
- `XC_MethodHook.MethodHookParam` uses `args` (field) and `result` (field), NOT `getArgs()`/`getResult()`
- `MediaExtractor` does NOT have `getFormat()` — use `getTrackFormat(0)` instead
- `FLAG_IMMUTABLE` only available on API 31+ — use reflection on older APIs
- `grantRuntimePermission`/`revokeRuntimePermission` NOT in android.jar's `PackageManager` — access via reflection
- `android.util.PixelFormat` not available — use `android.graphics.PixelFormat`
- `ActivityManager.getRunningAppProcesses()` returns `List`, not array
- `PackageScanner` class does not exist — use PackageManager for app scanning
- `String[]` for target apps — use indexed loop instead of `indexOf`
- `ActivityInfo.loadLabel()` instead of `ActivityInfo.label` for app labels
- `@JvmField` annotation on `instance` and `settingsManager` in `KycBypassApp` for Java interop
- `ActivityManager.getRunningAppProcesses()` returns `List` not array
- `MediaExtractor.getTrackFormat(0).getString(MediaFormat.KEY_DURATION)` instead of `getFormat()`
- `AudioRecord.getChannelCount()` instead of `getChannelMask()`

---

## Prompt 6 — Crash Fixes & Manifest Cleanup

Fix these issues for non-root Android 9+ compatibility:

1. **`foregroundServiceType="camera"`** and **`FOREGROUND_SERVICE_CAMERA`** permission are API 29+ only — remove from manifest, wrap `startForeground` in try-catch
2. **`canDrawOverlays` intent** was launching a system Activity mid-onCreate — removed, handled separately
3. **`ExoPlayer.Builder(ctx, factory)`** → use simple `ExoPlayer.Builder(ctx)` instead
4. **`ContextCompat.getColor()`** → use single-param form
5. **`ActivityResultContracts.GetContent().launch("video/*")`** — fix the launcher pattern
6. **Overlay permission intent** has `FLAG_ACTIVITY_NEW_TASK`
7. **`POST_NOTIFICATIONS`** permission added to manifest
8. **`colorPrimaryDark`** added to theme
9. **`mipmap-anydpi-v26`** adaptive icon resources added
10. **`androidx.activity:activity-ktx`** dependency for `ActivityResultContracts`
11. **Crash try-catch** wrapping on `KycBypassApp`, `MainActivity`, `CameraService`, `FloatingBubbleService`
12. **`ScaleAndRotateTransformation`** replaced with manual Matrix-based rotation/mirror approach
13. **`GET_TASKS`** permission added to manifest (for detecting running target apps)

---

## Prompt 7 — Gradle Build Fix

Fix Gradle build issues:
- Move `libs.versions.toml` from `gradle/libs/versions.toml` to `gradle/libs.versions.toml`
- Use parentheses in Kotlin DSL (e.g., `id("com.android.application")` instead of `id "com.android.application"`)
- Use `=` for property assignment in `android {}` and `defaultConfig {}` blocks
- Add `gradle.properties` with `android.useAndroidX=true`
- Add `local.properties` with `sdk.dir` pointing to Android SDK
- Add Gradle wrapper (`gradlew`, `gradle-wrapper.jar`, `gradle-wrapper.properties`) for Gradle 8.2
- Top-level `build.gradle.kts` with explicit plugin versions
- `compileSdk = 34`, `minSdk = 28`, `targetSdk = 34`
- Dependencies: appcompat 1.6.1, material 1.11.0, constraintlayout 2.1.4, activity-ktx 1.8.2, media3-exoplayer 1.3.1, media3-common 1.3.1

---

## Prompt 8 — Push to GitHub

Push the project to the private GitHub repository:
- Repository: `https://github.com/Klassiqblintz/kycbypass`
- Branch: `main`
- Include all source files, resources, build files, and the DESIGN.md spec document
