# Device Manager

Futuristic HUD device manager for Android. Package: `com.device.mux.manager`

## Build
Push to GitHub -> Actions -> "Build APK" -> download artifact `DeviceManager-debug-apk`.

## Releasing an update
1. Change `appVersionName` in `app/build.gradle.kts` (and `version` in `metadata.json`).
2. Push, wait for the build, download the APK.
3. GitHub -> Releases -> new release, tag `v1.0.1` (same number), attach the APK, publish.

The app checks `releases/latest` of `mahfuz0712/DeviceManager` on launch and from SYS -> CHECK FOR UPDATES.
`app/debug.keystore` keeps the signature identical on every build, so new APKs install over old ones.

Author: Mohammad Mahfuz Rahman, mahfuzrahman0712@gmail.com, https://github.com/mahfuz0712
