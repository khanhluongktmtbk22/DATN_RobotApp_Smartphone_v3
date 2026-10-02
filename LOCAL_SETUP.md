# Local configuration

1. Install Android SDK and let Android Studio create `local.properties`.
2. Download the Firebase Android configuration for package `com.example.datn_v1` and save it as `app/google-services.json`.
3. Set `GROQ_API_KEY` in your user environment before opening Android Studio or running Gradle. For example, in PowerShell: `[Environment]::SetEnvironmentVariable('GROQ_API_KEY', '<your key>', 'User')`. Start a new terminal or restart Android Studio afterward.
4. Build with `.\gradlew.bat assembleDebug`.

`GROQ_API_KEY` is injected into `BuildConfig` at build time. This keeps it out of Git, but the key can still be extracted from a distributed APK. Use a backend proxy if the APK will be shared publicly. `app/google-services.json` and `local.properties` are ignored by Git.