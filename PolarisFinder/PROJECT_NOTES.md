# Polaris Finder — Project Notes (handoff for future sessions)

Last updated: 2026-10-10. Read this first, then `README.md` (build steps).

## 1. Goal
Android app that helps a user point their phone at Polaris:
1. User photographs the northern sky (in-app camera).
2. Image goes to astrometry.net; the returned solved FITS gives the WCS.
3. From the WCS we compute where Polaris is relative to the image center.
4. The phone gyroscope tracks how the phone rotates; an arrow + "X.X° remaining" guides the user.
5. When close and steady, the app auto-takes a new photo, solves it again; if Polaris is inside the frame it draws a small circle around it. If not, it re-guides (max 3 tries).

## 2. Decisions made with the user (don't re-litigate)
- Python (astropy/WCS) runs **on the phone via Chaquopy** (reuses the user's existing code). Alternatives rejected: own server, Kotlin rewrite.
- Guidance UI: **arrow + remaining degrees** (not AR overlay, not text-only).
- Final verification step (user's addition): near the target, shoot again → solve → circle Polaris if `inside`.
- In-app capture only (no gallery import): the phone orientation at shutter time (R0) is needed.
- User builds/runs the app themselves in Android Studio (this dev machine has no Android SDK/Gradle/adb). The user asked for the code to be written, they do the build and device testing.

## 3. Origin material (in `D:\Star calibration project\`)
- `draw_polaris_line.py` — the user's original desktop script (reads solved FITS, computes sep/PA to Polaris, draws a line with matplotlib). Still the reference for expected results.
- `new-image.fits` — solved sample (1200x1600, 3 planes RGB, FITS row 0 = bottom). Center RA/Dec 20h14m, +32°; Polaris is OUTSIDE (pixel ≈ -1175, 271). Expected: sep 57.857°, PA 0.87°, theta ≈ 251.2°.
- `stars4.jpeg`, `new-image*.png/jpeg/jpg` — sample/annotated images.

## 4. What was done

### Project layout (`PolarisFinder/`)
| File | Purpose |
|---|---|
| `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/wrapper/gradle-wrapper.properties` | Gradle project (AGP 8.5.2, Kotlin 1.9.24, Chaquopy 15.0.1, Gradle 8.7). Versions chosen from memory, NOT verified by a build. |
| `app/build.gradle.kts` | Android config, Compose, CameraX 1.3.4, security-crypto, exifinterface; Chaquopy Python 3.11 with pip `numpy`, `astropy`, `requests`; ABIs arm64-v8a + x86_64. |
| `app/src/main/AndroidManifest.xml` | CAMERA + INTERNET, portrait-locked activity. |
| `app/src/main/python/polaris_solver.py` | `solve()` (astrometry.net login/upload/poll/download FITS, https) and `polaris_offset()` / `polaris_offset_json()`. |
| `.../GuidanceEngine.kt` | Pure math: sky offset + R0 + live R(t) → remaining angle + arrow angle. |
| `.../OrientationTracker.kt` | `TYPE_GAME_ROTATION_VECTOR` (fallback: integrated raw gyro). Publishes `Sample(rotation, rateDegPerSec, tNanos)` as a StateFlow. |
| `.../PolarisSolver.kt` | Kotlin ↔ Python bridge (Chaquopy); `PolarisOffset` data class. |
| `.../ImageUtils.kt` | `makeUpright()`: bake EXIF rotation into pixels, downscale to max 2048 px, write JPEG. |
| `.../ApiKeyStore.kt` | astrometry.net API key in EncryptedSharedPreferences. |
| `.../AppViewModel.kt` | State machine (`UiState`: Capture/Busy/Guiding/Result/Error/Settings), pipeline, steady detection, verify loop. |
| `.../Screens.kt` | Compose UI: permission, preview + overlays, guide arrow, result with circle, settings. |
| `.../MainActivity.kt` | Hosts `MainScreen`. |
| `app/src/test/.../GuidanceEngineTest.kt` | 3 JVM unit tests for the math (NOT yet run). |
| `README.md` | Build steps and tunables. |

### Python module details (`polaris_solver.py`)
- Derived from `draw_polaris_line.py` minus matplotlib. Polaris J2000 = 02h31m49.09s +89°15′50.8″.
- Sets `XDG_CACHE_HOME`/`XDG_CONFIG_HOME` to the temp dir *before* importing astropy (astropy writes config at import; Android dirs are read-only otherwise).
- `solve(image_path, api_key, out_fits_path, timeout_s=600, progress=None)`: `progress` may be a Python callable or a Java `Consumer<String>` (called via `.accept`).
- `polaris_offset(fits_path)` returns: `ra_deg, dec_deg, sep_deg, pa_deg, theta_deg, dra_deg, ddec_deg, pixel_x, pixel_y, width, height, inside, circle_radius_px (=2% of width), polaris_pole_offset_deg`.
- **`theta_deg`** = direction to Polaris measured **clockwise from image-up**, in the image frame (x right, y up, FITS). Computed by stepping 0.05° along the great circle toward Polaris and reading the pixel delta from the WCS → rotation and mirroring are handled implicitly. (This replaced the earlier plan idea `pa − image_up_pa`.)
- Handles 2-D and 3-plane FITS data.

### Core math (GuidanceEngine)
- Rotation matrices: row-major `FloatArray(9)`, `v_world = R · v_device` (Android `getRotationMatrixFromVector`). Device frame: x right, y toward top of screen, z out of screen. Rear-camera boresight = −z.
- Captured image must be **upright**: image right = +x, image up = +y (phone portrait). `ImageUtils.makeUpright` guarantees this.
- Target direction in device frame at capture: `d0 = (sinσ·sinθ, sinσ·cosθ, −cosσ)`, σ = `sep_deg`, θ = `theta_deg`.
- World target (fixed): `t = R0 · d0`. Live: `d = R(t)ᵀ · t`. Remaining = `acos(−d_z)`; arrow = `atan2(d_x, d_y)` (degrees clockwise from screen-up).
- FOV is not needed (astrometry already gives angles).
- Verified numerically in numpy: no motion → remaining = sep; boresight aligned (even with roll) → 0°; 10° yaw changes it as expected. The Kotlin port is the same formulas but has never been compiled.

### App flow (AppViewModel)
`onShutter()` → `runPipeline(attempt=0)`: checks sensors/API key, snapshots R0 → takes photo (CameraX `LifecycleCameraController.takePicture`) → `makeUpright` → Python `solve` + `polaris_offset` on IO dispatcher → if `inside` → `Result` (circle) else `startGuiding`. While guiding, each sensor sample updates `guidance`; if remaining < `VERIFY_WITHIN_DEG` (3°) and rate < `STEADY_DEG_PER_SEC` (5°/s) for `STEADY_NANOS` (1 s) → `runPipeline(attempt+1)` (new shot = new baseline R0, which also resets gyro drift). After `MAX_ATTEMPTS` (3) → error "start over".
Result screen: bitmap drawn Fit; circle center = `(pixel_x+0.5)/W` horizontally and `(H−1−pixel_y+0.5)/H` vertically (FITS y is flipped to image rows), radius = `circle_radius_px/W · drawnWidth`.

## 5. What has been verified vs. not
Verified (on the dev PC, Python 3.13, astropy 7.1.1, numpy 2.3.5):
- `polaris_offset('new-image.fits')` matches `draw_polaris_line.py`: sep 57.857°, PA 0.87°, inside False, pixel (−1175.4, 270.8); theta 251.2° (consistent with pixel direction).
- Guidance math in numpy (see above).

NOT verified (no Android SDK here):
- Nothing in the Android project has been compiled, synced, or run. Expect small compile errors / version mismatches on first sync (Compose, CameraX, security-crypto alpha, Chaquopy/AGP compatibility).
- Chaquopy availability of astropy wheels for Python 3.11 / these ABIs, APK size (expect large, ~50–100 MB+), astropy import on-device.
- `LocalLifecycleOwner` import (`androidx.compose.ui.platform`) is the pre-1.7 location; may need `androidx.lifecycle.compose.LocalLifecycleOwner` if dependencies are upgraded.
- Python→Java callback: Kotlin `Consumer<String>` passed to Python and called via `.accept`.
- Unit tests in `GuidanceEngineTest.kt` (expected values reasoned by hand: sep 30°, θ 90° → arrow 90°; yaw ±10° → 20°/40°).
- All real-world behavior (sign conventions, gyro accuracy, solve success on phone photos).

## 6. TODO / what still needs to be done (rough priority)
1. **First build** in Android Studio: fix compile/version issues; run `GuidanceEngineTest`; run on a real phone.
2. **Confirm astropy runs under Chaquopy** (launch app, take a photo of any star field, e.g. an image like `stars4.jpeg` held to the camera, or temporarily call `polaris_offset` on a bundled FITS).
3. **Axis/sign check in the field** — the most likely bug. Test: solve a photo, then rotate the phone toward the target; remaining must go down and the arrow must point the way the phone should turn (it points to where the target appears in the *screen* frame: arrow up = tilt the top of the phone/camera up toward it). If mirrored, check the sign of the x component in `GuidanceEngine` and `theta_deg` clockwise convention; also confirm the saved JPEG is really upright (EXIF handling) on that phone.
4. **Exposure problem (high risk):** normal CameraX stills are short; in a dark sky they may show no stars → astrometry.net fails. Options: Camera2 long exposure (manual `SENSOR_EXPOSURE_TIME` ~4–10 s, high ISO) via `Camera2Interop`, or a CameraX Night extension. The phone must be braced/steady during the exposure (R0 is recorded at shutter press — for long exposures consider averaging orientation over the exposure or recording at the midpoint).
5. **Solve-time UX:** astrometry.net can take 30 s – several minutes. The Busy screen shows status text only; consider a cancel button, timeout messaging, and passing scale hints (`scale_units`/`scale_est`/`scale_err`, e.g. from camera focal length) in the upload args to speed up/improve solves.
6. **Robustness:** handle no network / bad API key messages nicely; handle `theta_deg` when `sep≈0`; reject weak solves; clean old `photo_*.jpg` in cacheDir; lifecycle (stop sensors in background, keep screen on during guiding via `FLAG_KEEP_SCREEN_ON`); a pending job survives rotation/process death? (currently no).
7. **Accuracy:** game-rotation-vector gives ~1–2° accuracy and drifts; the verify-shot loop re-baselines. Possible improvement: shorter gap between shot and guidance start, or use the rotation-vector + magnetometer for yaw sanity.
8. **Polish:** landscape/other orientations (currently portrait-locked and assumed), app icon, string resources/localisation (Hebrew?), dark/light theme, show Polaris-to-pole offset (0.736°) note for polar alignment use, optional AR overlay.
9. **Optional:** unit tests for `ImageUtils` rotation and the Result circle mapping (compare with `new-image_polaris_line.jpeg`); instrumented test for tracker; CI.
10. **Housekeeping:** the folder is not a git repo; `PolarisFinder/` has no `gradlew` wrapper scripts (Android Studio generates/uses its own Gradle; run `gradle wrapper` once if CLI builds are wanted); no `.gitignore` yet.

## 7. Gotchas to remember
- FITS from astrometry.net: row 0 = bottom; WCS pixel coords are y-up, 0-based. The uploaded JPEG is top-down. Always convert when drawing.
- astrometry.net ignores EXIF orientation → bake rotation into pixels before upload (done), and downscaling changes pixel sizes (fine; WCS comes from the uploaded image, and `width/height` come from the FITS).
- Passing the API key: stored encrypted; never log it.
- Keep Windows/desktop experiments separate: use `python -I` and put test scripts outside any untrusted download dirs.
- The user's API usage: astrometry.net public service is rate-limited/shared; solves aren't instant.
- User preference: they asked for explanations of terms (Kotlin = app language, Gradle = build tool, Chaquopy = embedded Python) — keep explanations beginner-friendly.
