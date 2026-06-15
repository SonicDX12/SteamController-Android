# Releasing

This document describes how to produce signed release APKs and publish them on GitHub Releases.

## One-time setup

### 1. Generate your release keystore

**Windows (PowerShell):**

```powershell
.\scripts\generate-keystore.ps1
```

**Linux/macOS:**

```bash
chmod +x scripts/generate-keystore.sh
./scripts/generate-keystore.sh
```

This creates `keystore/release.jks`. You'll be prompted for two passwords (use the same for both) and identity info (your name is enough for a personal project).

**⚠️ Save the passwords in a password manager.** Losing them means losing the ability to publish updates that existing users can install over their copy.

### 2. Create your local `keystore.properties`

Copy the template:

```bash
cp keystore.properties.template keystore.properties
```

Edit `keystore.properties` and replace `CHANGE_ME` with the password you just chose.

Both `keystore.properties` and `keystore/` are gitignored — they stay on your machine only.

### 3. Build a signed APK locally

```bash
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`

Install it on a device:

```bash
adb install app/build/outputs/apk/release/app-release.apk
```

---

## Publishing via GitHub Releases (automated)

The `.github/workflows/release.yml` workflow builds a signed APK and publishes it to GitHub Releases automatically whenever you push a tag matching `v*` (e.g. `v1.0.0`).

### One-time GitHub setup

You need to give GitHub Actions the same signing secrets you use locally.

1. **Encode your keystore in base64.**

   **Windows (PowerShell):**

   ```powershell
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("keystore/release.jks")) | Set-Clipboard
   ```

   **Linux/macOS:**

   ```bash
   base64 -i keystore/release.jks | pbcopy   # macOS
   base64 -w 0 keystore/release.jks | xclip  # Linux
   ```

2. **Add repository secrets** at `https://github.com/<you>/<repo>/settings/secrets/actions`:

   | Secret name | Value |
   |---|---|
   | `SIGNING_KEYSTORE_BASE64` | The base64 blob from step 1 |
   | `SIGNING_STORE_PASSWORD` | Your keystore password |
   | `SIGNING_KEY_ALIAS` | `steamcontroller` (or whatever you used) |
   | `SIGNING_KEY_PASSWORD` | Your key password (same as store password if you followed the script) |

### Cutting a release

```bash
# Bump versionCode and versionName in app/build.gradle.kts first
git add app/build.gradle.kts
git commit -m "Bump to 1.1.0"
git tag v1.1.0
git push origin main --tags
```

GitHub Actions picks up the tag, builds, signs, and publishes a release at `https://github.com/<you>/<repo>/releases/tag/v1.1.0` with the APK attached.

---

## About Play Protect warnings

Sideloaded apps not on the Play Store may trigger a Play Protect warning at install time ("App not verified" or similar). This is unavoidable for any APK outside the Play Store, regardless of how it's signed.

The user can proceed by tapping **Install anyway** in the warning dialog. After a few users install successfully, Play Protect typically stops flagging the app.

A consistent signing key across all releases is essential — without it, updates fail with "signatures don't match" and users have to uninstall first.
