# SpeedStream Controller (Android 7.0 – Android 15+)

## How to Get the `.apk` File Without a PC (Using GitHub Actions in Your Browser)
1. Create a free repository on [GitHub](https://github.com/new).
2. Unzip `SpeedStreamController_Project.zip` and upload all files/folders into the repository (preserving the `.github/workflows/build-apk.yml` and `app/` directory structure).
3. Open the **Actions** tab in your GitHub repository — the **Build SpeedStream Android APK** workflow will automatically compile the project using JDK 17 and Gradle 8.9 ([setup-java](https://github.com/actions/setup-java), [setup-gradle](https://github.com/marketplace/actions/build-with-gradle)).
4. Once the workflow finishes (~2 minutes), scroll to the **Artifacts** section at the bottom of the workflow run and download **`SpeedStreamController-APK`** (`app-debug.apk`).

## How to Build Locally in Android Studio
1. Open the unzipped folder in [Android Studio](https://developer.android.com/studio).
2. Select **Build > Build Bundle(s) / APK(s) > Build APK(s)** ([Build your app for release to users](https://developer.android.com/build/build-for-release)).
3. Install `app/build/outputs/apk/debug/app-debug.apk` on your Android device.
