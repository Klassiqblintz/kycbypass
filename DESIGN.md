Build a minimal, standalone Android app that bypasses KYC (Know Your Customer) face verification
by acting as a virtual camera. It uses the same architecture as the xCam Xposed module but as
a self-contained app with a floating control bubble.

ARCHITECTure (xCam-inspired):
1. Camera1 (Legacy) path:
   - Hook: Camera.setPreviewTexture() → replace app's SurfaceTexture with a dummy one
   - Feed: MediaPlayer loops a video onto the original SurfaceTexture
   - Capture: On Camera.takePicture(), extract a frame from the video and feed it as JPEG

2. Camera2 path:
   - Hook: CameraDeviceImpl.createCaptureSession() → intercept Surface list
   - For each preview Surface, replace it with a dummy SurfaceTexture surface
   - Feed: ExoPlayer renders video onto each intercepted preview Surface
   - Capture: On ImageReader.acquireLatestImage() with JPEG format, replace buffer contents

3. Capture/Photo hook:
   - Hook: ContentResolver.openFileDescriptor() → intercept write path for captured images
   - Hook: Bitmap.compress() → intercept JPEG output
   - Hook: BitmapFactory.decodeByteArray() / decodeStream() → intercept decode path

4. Video recording hook:
   - Hook: MediaRecorder.setOutputFile() / setNextOutputFile()
   - On MediaRecorder.stop(), replace the recorded file with a trimmed segment of the virtual video

5. Audio injection:
   - Hook: AudioRecord.read() → inject audio from the virtual video's audio track

KEY FLOWS:
- User picks a video or image (from gallery or camera) → stored locally
- User opens a KYC app → taps floating bubble to enable "Virtual Camera"
- The app hooks the KYC app's camera calls → replaces live feed with the chosen media
- When KYC asks for a photo, the app returns a frame from the video
- When KYC records video, the app replaces the output file

REQUIREMENTS:
- Android 9+ (API 28+)
- ROOT not required (works via Surface injection + MediaPlayer/ExoPlayer)
- CAMERA permission (to access camera state)
- STORAGE permission (for media files)
- OVERLAY permission (for floating bubble)

DESIGN:
- Single MainActivity with Jetpack Compose UI
- Floating bubble (WindowManager overlay) for enable/disable toggle
- Media picker screen for choosing video/image
- Per-app scoping (list of target apps to enable virtual camera for)
- Settings: rotation (0/90/180/270), mirror, repeat
