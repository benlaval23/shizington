# Shizington

IPTV player for Fire TV / Android TV. Built with Kotlin, Compose for TV, Media3 ExoPlayer, Retrofit/OkHttp, Coil and Room. Supports Android 5.1+ (minSdk 22).

- Signs in with an Xtream Codes account (`player_api.php`). Credentials are entered on the login screen and stored encrypted on the device, never in code.
- Backup servers: if the main server is unreachable, the app moves to the next one and stays on whichever server answers.
- Home, Live TV (now/next EPG), Movies, Series, Search, Favourites and Settings tabs, plus detail pages and Continue Watching.
- The player handles MPEG-TS and HLS, resumes where you left off, and has audio/subtitle pickers. Live TV supports channel up/down.

## Local setup (macOS)

1. Install [Android Studio](https://developer.android.com/studio) (it bundles a JDK and the Android SDK).
2. Clone the repo and open the folder in Android Studio. Let Gradle sync finish.
3. Put your details in `local.properties` in the project root. This file is gitignored, so it is never committed:

   ```properties
   sdk.dir=/Users/<you>/Library/Android/sdk
   xtream.server=http://t1.example.com:8080
   xtream.backups=http://t2.example.com:8080,http://t3.example.com:8080
   xtream.user=...
   xtream.pass=...
   ```

   Debug builds prefill **only** the server fields from this file. You still type the username and password on the login screen. `xtream.user` and `xtream.pass` are read only by the probe script below.

### Check the real API responses

```bash
./tools/xtream-probe.sh
```

This saves real JSON responses to `api-samples/` (gitignored) and hides credentials in the output. Use the samples to check the response shapes, for example numbers sent as strings, `[]` where an object is expected, or missing fields.

### Run in an emulator

1. In Android Studio, open **Device Manager**, then **Create device**, choose **TV**, then **1080p**, and pick a recent system image.
2. Press **Run**. Use the arrow keys, Enter for select, and Esc for back. Those keys stand in for the D-pad.

Or from the terminal:

```bash
./gradlew installDebug
```

### Run on a Fire TV stick

1. On the Fire TV, open **Settings**, then **My Fire TV**, then **Developer options**, and turn on **ADB debugging**. If Developer options is hidden, open **About** and click the device name 7 times.
2. Find the stick's IP address under **Settings**, **My Fire TV**, **About**, **Network**.
3. Connect and install:

   ```bash
   adb connect <fire-tv-ip>:5555
   ./gradlew installDebug
   ```

## Remote controls

| Where | Key | Action |
|---|---|---|
| Anywhere | Hold OK on a live channel | Add or remove favourite |
| Movie/episode player | ◀ ▶ | Seek 10 s |
| Movie/episode player | OK | Show controls (play/pause, audio, subtitles, next episode) |
| Live player | ▲ ▼ / CH+ CH− | Change channel |
| Live player | ◀ | Channel list |
| Live player | OK | Channel info with now/next |
| Player | Menu | Audio tracks |

## Release builds (GitHub Actions)

`.github/workflows/release.yml` builds a signed release APK on every push, uploads it as a workflow artifact, and attaches it to a GitHub release when you push a `v*` tag.

Add these repository secrets so every build uses the same signing key (updates only install over a build signed with the same key):

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 -i release.jks \| pbcopy` |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key password (defaults to the keystore password) |

To create a keystore:

```bash
keytool -genkeypair -v -keystore release.jks -alias shizington -keyalg RSA -keysize 2048 -validity 10000
```

If the secrets are missing, CI signs with a temporary key and shows a warning.
