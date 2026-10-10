# Polaris Finder

> Android app that finds Polaris for you: photograph the night sky, let astrometry.net plate-solve it, then follow the phone's gyroscope-driven arrow until Polaris is in view.

## How it works

1. **Photograph** the northern sky with the in-app camera. The phone's orientation is recorded at the shutter press.
2. **Solve**: the photo is uploaded to [astrometry.net](https://nova.astrometry.net). The returned FITS file contains the WCS (sky coordinates of every pixel).
3. **Locate**: Python (astropy, running on the phone via Chaquopy) computes the angular offset from the image center to Polaris.
4. **Guide**: the gyroscope tracks how far you rotate the phone. An on-screen arrow and a live "X.X° remaining" counter show where to turn.
5. **Verify**: when you are within 3° and holding steady, the app shoots and solves again. If Polaris is in the frame, it draws a circle around it. If not, it guides you again (up to 3 attempts).

## Project layout

| Path | What it is |
|---|---|
| `app/src/main/python/polaris_solver.py` | astrometry.net upload and FITS download; offset to Polaris from the WCS |
| `app/src/main/java/com/polarisfinder/GuidanceEngine.kt` | Sky offset + phone orientation → remaining angle and arrow direction (unit-tested) |
| `.../OrientationTracker.kt` | Gyro / rotation-vector sensor wrapper |
| `.../AppViewModel.kt` | Photo → solve → guide → verify state machine |
| `.../Screens.kt`, `MainActivity.kt` | Jetpack Compose UI |
| `.../PolarisSolver.kt`, `ImageUtils.kt`, `ApiKeyStore.kt` | Python bridge, upright/downscale of photos, encrypted API-key storage |
| `PROJECT_NOTES.md` | Detailed design notes, math, status and TODO list — read this to continue the project |

## Build and run

Requirements: Android Studio (includes the Android SDK and JDK 17), Python 3.8+ on the build machine, a real Android phone with a gyroscope.

1. Open this folder in Android Studio and let Gradle sync. The first sync downloads Gradle 8.7, the libraries and the numpy/astropy wheels. If Gradle cannot find Python, set `buildPython(...)` in `app/build.gradle.kts`.
2. Connect a phone with USB debugging enabled and press Run. The emulator has no usable gyroscope or sky camera.
3. In the app, open **Settings** and paste your astrometry.net API key (nova.astrometry.net → your profile → API).
4. Point the phone at the northern sky, hold steady, and take a photo.

Run the math tests with `./gradlew test` (or from Android Studio).

## Configuration

Thresholds live in the companion object of `AppViewModel.kt`:

- `VERIFY_WITHIN_DEG` (3.0): how close to Polaris before the verification shot
- `STEADY_DEG_PER_SEC` (5.0) and `STEADY_NANOS` (1 s): how steady the phone must be
- `MAX_ATTEMPTS` (3): verification tries before giving up

## Status

Work in progress. The Python solver and the guidance math are verified on a desktop against a sample plate-solved image. The Android project has been written but **not yet built or tested on a device**. See `PROJECT_NOTES.md` for what is verified and what remains.

## Known limitations

- Ordinary phone photos are short exposures; in a very dark sky they may show too few stars to solve. A long-exposure capture is on the TODO list.
- Guidance assumes you rotate the phone in place, in portrait orientation. Gyro drift grows with time, which is why the app re-photographs near the target.
- Accuracy is about 1–2°: enough to find Polaris in a wide field, not for fine polar alignment.
