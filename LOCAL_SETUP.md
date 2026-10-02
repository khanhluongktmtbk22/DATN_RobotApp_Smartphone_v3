# Local configuration

1. Install Android SDK and let Android Studio create `local.properties`.
2. Download the Firebase Android configuration for package `com.example.datn_v1` and save it as `app/google-services.json`.
3. Add this line to `local.properties` in the project root:

   ```properties
   GROQ_API_KEY=your_groq_key
   ```

4. Build with `.\gradlew.bat assembleDebug`.

`local.properties` and `app/google-services.json` are ignored by Git, so they stay on your machine. If `GROQ_API_KEY` is absent from `local.properties`, Gradle can also read it from the Windows environment. The key is injected into `BuildConfig` at build time and can still be extracted from a distributed APK. Use a backend proxy before distributing the APK publicly.