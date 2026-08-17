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
keytool -genkeypair -v -keystore release.jks -alias tripoleflux -keyalg RSA -keysize 4096 -validity 10000
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
| `KEY_ALIAS` | `tripoleflux` |
| `KEY_PASSWORD` | the key password (same as the keystore password if you pressed Enter) |

Then delete `release.jks.base64` — it is a plaintext copy of your private key.
Both it and `release.jks` are gitignored, so neither can be committed by
accident.

### 4. Tag a release

See "Cutting a release" below.

## Locking the app down

There are three levels, and only the last actually prevents removal.

| | Stops a casual tap | Stops Android's VPN Disconnect | Stops uninstall |
| --- | --- | --- | --- |
| PIN | yes | no | no |
| Device admin | yes | no | until deactivated in Settings |
| Device owner | yes | **yes** | **yes** |

The PIN and device admin need no setup beyond the buttons in the app. Neither
can close the two holes that matter: Android always offers Disconnect in its own
VPN dialog, and a device admin can be deactivated in Settings, after which
uninstall works normally. That is deliberate on Android's part — an app is not
permitted to trap the user.

### Device owner provisioning

Device owner removes both holes, but can only be set on a phone with **no
accounts configured**. In practice that means a factory reset, and the
provisioning has to happen before signing into anything.

You need `adb` (`winget install Google.PlatformTools`) and a signed release APK.

1. **Factory reset** the phone.
2. On the setup wizard, **skip every account** — no Google account, no Samsung
   or Oppo account, nothing. Adding one before step 5 makes provisioning fail.
3. Enable **Developer options** (tap Build number seven times) and turn on
   **USB debugging**.
4. Install the app:

```bash
adb install tripoleflux-1.0.4.apk
```

5. Make it the device owner:

```bash
adb shell dpm set-device-owner dev.franklin.adblocker/.AdminReceiver
```

6. Now sign into accounts as normal, open TripoleFlux, and press
   **Lock down this device**.

### What lock-down applies

- `setUninstallBlocked` — the app cannot be removed
- `DISALLOW_CONFIG_VPN` — the user cannot reach VPN settings, which is what
  removes Disconnect from the system dialog
- `setAlwaysOnVpnPackage` with lockdown — the tunnel restarts automatically, and
  traffic is blocked entirely whenever it is down

Each is applied independently and the app reports which succeeded, because
manufacturers honour these inconsistently.

### Getting back out

Press **Remove lock-down** in the app and enter the PIN. If the app is ever lost
or the PIN forgotten, the only remaining route is another factory reset —
device owner cannot be cleared from Settings. Worth being sure before starting.

## Changing the app icon

Start from a **square** image, ideally 1024px or larger. It can have a
transparent background or be drawn on a flat white one — the script handles
both.

```bash
pip install pillow
```

```bash
python tools/make_icons.py path/to/logo.png
```

That rewrites all ten files under `app/src/main/res/mipmap-*/` — five legacy
sizes and five adaptive-icon foregrounds — and prints a background colour to
paste into `app/src/main/res/values/colors.xml`.

Then commit and push, and the next build carries the new icon.

### What each file is for

| Path | Used by |
| --- | --- |
| `mipmap-*/ic_launcher.png` | Android 7-8 launchers, and anything asking for a plain icon |
| `mipmap-*/ic_launcher_foreground.png` | The moving layer of the adaptive icon on Android 8+ |
| `mipmap-anydpi-v26/ic_launcher.xml` | Ties the foreground, background colour and monochrome layers together |
| `values/colors.xml` → `launcher_background` | The adaptive icon's backdrop |
| `drawable/ic_launcher_monochrome.xml` | Themed icons on Android 13+, tinted flat by the system |
| `drawable/ic_shield.xml` | The status-bar notification icon |

The last two are vectors and are not touched by the script. The monochrome and
notification layers are rendered as flat silhouettes by Android, so a colour
logo cannot be used for them — it would come out as a solid blob. Edit those by
hand, or leave the shield.

### Things that catch people out

- **Detail near the edges gets clipped.** Launchers mask the adaptive icon to
  roughly a circle. The script insets the artwork to about 59% of the canvas to
  protect against this; lower `ART_RATIO` in the script if your logo still loses
  its edges, raise it if it looks too small.
- **Never put a file in `mipmap-anydpi/`.** That directory outranks every
  density bucket, so anything there silently hides all the PNGs on Android 7-8.
- **The launcher caches icons.** If the old one lingers after installing,
  restart the phone or clear the launcher's cache — the build is fine.

## Cutting a release

Once the four secrets exist, shipping a version is four commands and one click.

### 1. Get your changes onto `main`

```bash
git add -A && git commit -m "Describe the change" && git push
```

Tags point at a commit. Tagging before pushing produces a release built from
code nobody else can see, so push first and let the `Build APK` workflow go
green before tagging.

### 2. Pick the version number

Versions are `MAJOR.MINOR.PATCH`:

- **PATCH** (`1.0.0` → `1.0.1`) — fixes only, nothing new
- **MINOR** (`1.0.1` → `1.1.0`) — new features, nothing broken
- **MAJOR** (`1.1.0` → `2.0.0`) — changes that break existing behaviour

Only `versionName` comes from the tag. `versionCode`, the integer Android uses
to decide what counts as an upgrade, is the workflow's run number, so it always
increases on its own — never edit it by hand.

### 3. Tag and push it

```bash
git tag -a v1.0.1 -m "TripoleFlux 1.0.1" && git push origin v1.0.1
```

The leading `v` matters: the workflow triggers on `v*` and strips it, so the tag
`v1.0.1` becomes version `1.0.1`. `-a` makes an annotated tag, which records who
made it and when; a bare `git tag v1.0.1` works too but keeps no such record.

### 4. Watch the run

<https://github.com/syskraken/ad-blocker/actions> — it restores the keystore,
runs the tests, builds, verifies the result with `apksigner`, then opens a draft
release and wipes the keystore off the runner. Around three minutes.

The `apksigner verify` step is the one that matters: it fails on a wrong
password or a bad alias instead of publishing an APK with an unusable signature.

### 5. Publish the draft

<https://github.com/syskraken/ad-blocker/releases> — open the draft, add notes
if you want, click **Publish release**.

Nothing is downloadable by anyone until you do, and the app's own update check
ignores drafts, so this click is what actually ships the version.

### Fixing a mistake

A tag that was pushed too early can be removed and re-made:

```bash
git push origin :refs/tags/v1.0.1 && git tag -d v1.0.1
```

Delete the draft release it produced as well, or the next run will make a second
one. Only ever do this to a draft — re-pointing a tag that people have already
downloaded leaves them holding a build that no longer matches the source.

### A signed APK without a release

The Actions tab has **Run workflow** on `Release APK`, which takes a version
string and produces a signed APK as a build artifact with no tag and no release.
Useful for testing the signed build before committing to a version number.

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
