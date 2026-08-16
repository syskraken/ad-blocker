# Ad Blocker

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
git init && git add -A && git commit -m "Initial commit" && git branch -M main && git remote add origin https://github.com/YOUR-USERNAME/ad-blocker.git && git push -u origin main
```

2. Open the repo's **Actions** tab. The `Build APK` workflow starts on push.
3. When it finishes (~3 minutes), open the run and download the
   **ad-blocker-apk** artifact from the Artifacts section.
4. Unzip it and transfer the `.apk` to your phone.

The workflow runs the unit tests before building, so a green run means the
packet and DNS handling verified correctly.

### Installing

The APK is signed with the standard Android debug key, which is fine for
personal use but means Android will warn you about an unknown source. On the
phone: open the file, allow your browser or file manager to install unknown
apps when prompted, then install.

To share it more widely you would want a release build signed with your own
keystore — say the word and I will add that to the workflow.

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
