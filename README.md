# TripoleFlux

A device-wide ad and tracker blocker for Android. No root, no accounts, nothing
leaves your phone.

## How it works

The app runs a local `VpnService`, but it is not a VPN in the usual sense — no
traffic is proxied anywhere. The tunnel advertises itself as the system DNS
server and routes **only that single address** (`10.111.222.1`) into itself.
Every other packet on the device takes its normal path, untouched.

That means the only thing the app ever sees is DNS queries. Each one is checked
against the blocklists:

- **On the list** → answered locally with `0.0.0.0` (or `::` for IPv6), so the
  connection to the ad server fails instantly.
- **Not on the list** → forwarded to your real resolver over a `protect()`ed
  socket and passed straight back.

Because there is no proxying, the battery and latency cost is close to nothing,
and no request payload is ever readable by the app.

## What it blocks, and what it can't

It blocks ads served from **separate ad and tracker domains** — AdMob,
DoubleClick, AppLovin, Unity Ads, Facebook Audience Network, AppsFlyer, and so
on. In practice that is the large majority of ads in the large majority of apps,
plus ads on web pages in every browser.

It **cannot** remove ads that an app serves from its own domain in the same
response as the real content. YouTube pre-rolls, Instagram sponsored posts, and
TikTok in-feed ads all arrive over the same connection as the video or feed you
asked for, so there is no DNS name to refuse. Nothing that runs at the DNS layer
can strip those — that would require patching each individual app.

Note also that DNS blocking is bypassed by apps that use DNS-over-HTTPS
internally, and by "Private DNS" if you have it set to a specific provider in
Android settings. Turn Private DNS off (Settings → Network → Private DNS →
"Automatic" or "Off") for full coverage.

## Getting the APK

The project builds in CI — you do not need Android Studio or an SDK locally.

1. Create a new GitHub repository and push this directory to it:

```bash
git remote add origin https://github.com/syskraken/ad-blocker.git && git push -u origin main
```

2. Open the repo's **Actions** tab. The `Build APK` workflow starts on push.
3. When it finishes (~3 minutes), open the run and download the
   **tripoleflux-apk** artifact from the Artifacts section.
4. Unzip it and transfer the `.apk` to your phone.

The workflow runs the unit tests before building, so a green run means the
packet and DNS handling verified correctly.

### Installing

The APK is signed with the standard Android debug key, which is fine for
personal use but means Android will warn you about an unknown source. On the
phone: open the file, allow your browser or file manager to install unknown
apps when prompted, then install.

For a build you can share, see below.

## Signing your own release

The `Release APK` workflow produces a minified, properly signed APK. Your
keystore and its passwords live only in GitHub Secrets — never in this repo,
and never on the build machine after the run finishes.

### 1. Create a keystore

`keytool` ships with any JDK. If you do not have one, install Temurin 17
(`winget install EclipseAdoptium.Temurin.17.JDK`) or use the one bundled with
Android Studio.

```bash
keytool -genkeypair -v -keystore release.jks -alias adblocker -keyalg RSA -keysize 4096 -validity 10000
```

It will ask for a keystore password, then some identifying details (a name is
enough; the rest can be left blank), then a key password — pressing Enter reuses
the keystore password, which is fine.

> **Back this file up somewhere safe.** Android identifies an app by its signing
> key. If you lose `release.jks`, you can never ship an update to anyone who
> installed this build — they would have to uninstall and lose their settings
> first. There is no recovery path.

### 2. Turn it into a secret

The keystore is binary, so it has to be base64-encoded to live in a secret.
In PowerShell:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks")) | Set-Content -NoNewline release.jks.base64
```

### 3. Add four repository secrets

Repo → **Settings** → **Secrets and variables** → **Actions** → **New repository secret**:

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | the entire contents of `release.jks.base64` |
| `KEYSTORE_PASSWORD` | the keystore password you chose |
| `KEY_ALIAS` | `adblocker` |
| `KEY_PASSWORD` | the key password (same as the keystore password if you pressed Enter) |

Then delete `release.jks.base64` — it is a plaintext copy of your private key.
Both it and `release.jks` are gitignored, so neither can be committed by
accident.

### 4. Tag a release

```bash
git tag v1.0.0 && git push origin v1.0.0
```

The workflow runs the tests, builds, verifies the signature with `apksigner`,
and creates a **draft** GitHub release with the APK and its SHA-256 attached.
Nothing is publicly downloadable until you open the release and click Publish.

You can also run it from the Actions tab via **Run workflow** to get a signed
APK as a build artifact without creating a release at all.

### Building a signed release locally

Create `keystore.properties` next to `settings.gradle.kts` (it is gitignored):

```properties
storeFile=/absolute/path/to/release.jks
storePassword=...
keyAlias=adblocker
keyPassword=...
```

Then `./gradlew assembleRelease`. Without this file the release build still
compiles, it just comes out unsigned.

## Using it

1. Open the app and tap **Turn on**. Android shows its own VPN consent dialog;
   this is the system asking whether to let the app create the tunnel.
2. Tap **Update blocklists** to replace the small bundled starter list with the
   full ones (StevenBlack unified + AdAway, around 150,000 domains).
3. A persistent notification shows while filtering is active, with a Stop
   action. The key icon in the status bar is Android's standard VPN indicator.

**If an app breaks**, look at the "Recently blocked" list for the domain it
needs, add it to the allowlist, and save. Common culprits are attribution
services like `branch.io` and `appsflyer.com`, which some apps route real
functionality through.

Only one VPN can be active at a time on Android, so this cannot run alongside
another VPN app.

## Building locally instead

If you would rather build on your own machine, you need JDK 17 and the Android
SDK (Android Studio bundles both). Open this directory in Android Studio and run
**Build → Build APK**, or from a terminal:

```bash
gradle wrapper --gradle-version 8.11.1 && ./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`.

## Layout

| Path | What it does |
| --- | --- |
| `app/src/main/java/.../AdVpnService.kt` | The tunnel: sets up routing, reads packets, answers or forwards |
| `app/src/main/java/.../Ip.kt` | IPv4/IPv6 + UDP parsing and reply construction, including checksums |
| `app/src/main/java/.../Dns.kt` | DNS question parsing and forged "no such address" answers |
| `app/src/main/java/.../BlockList.kt` | List loading, downloading, and suffix matching |
| `app/src/main/java/.../MainActivity.kt` | The single screen: toggle, stats, allowlist |
| `app/src/main/assets/default_blocklist.txt` | Bundled starter list, so it works before the first download |
| `app/src/test/java/.../PacketTest.kt` | Unit tests for the packet and DNS code |
