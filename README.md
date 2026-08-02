<div align="center">

<img src="docs/logo.svg" width="128" alt="ZipShare logo">

# ZipShare

**A native Android client for your self-hosted [Zipline v4](https://zipline.diced.sh) server.**

Upload from anywhere on your phone, browse and manage your files, and administer the
server — against your own instance, with your token in hardware-backed encrypted storage
and no telemetry of any kind.

[![License: MIT](https://img.shields.io/badge/License-MIT-2ea44f.svg)](LICENSE)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3ddc84.svg?logo=android&logoColor=white)](#requirements)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.x-7f52ff.svg?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285f4.svg)](https://developer.android.com/compose)
[![Build](https://github.com/BombenlegerMoe/ZipShare/actions/workflows/build.yml/badge.svg)](../../actions/workflows/build.yml)

</div>

---

<div align="center">

| Dashboard | Files | Two-factor setup |
|:---:|:---:|:---:|
| <img src="docs/screenshots/03-dashboard.png" width="230" alt="Dashboard"> | <img src="docs/screenshots/09-files-grid.png" width="230" alt="File grid"> | <img src="docs/screenshots/06-totp-setup.png" width="230" alt="TOTP enrollment with QR and authenticator hand-off"> |

<sub><b>30 more screenshots</b> — open a section below.</sub>

<details>
<summary><b>Signing in &amp; your account</b> — sign-in, invite signup, account menu, avatar, sessions, two-factor</summary>

| Sign in | Sign up from an invite | Account menu |
|:---:|:---:|:---:|
| <img src="docs/screenshots/01-sign-in.png" width="230" alt="Sign-in screen"> | <img src="docs/screenshots/02-invite-signup.png" width="230" alt="Registering from an invite link"> | <img src="docs/screenshots/04-account-menu.png" width="230" alt="Account menu"> |

| Account settings | Logged-in devices | Viewing files |
|:---:|:---:|:---:|
| <img src="docs/screenshots/29-account-settings.png" width="230" alt="Account settings: avatar, username, password"> | <img src="docs/screenshots/30-account-sessions.png" width="230" alt="Logged-in devices with per-device sign-out"> | <img src="docs/screenshots/31-viewing-files.png" width="230" alt="Viewing files settings"> |

| Two-factor setup | Two-factor on | App settings |
|:---:|:---:|:---:|
| <img src="docs/screenshots/06-totp-setup.png" width="230" alt="TOTP enrollment"> | <img src="docs/screenshots/07-totp-on.png" width="230" alt="Two-factor enabled"> | <img src="docs/screenshots/08-settings.png" width="230" alt="App settings"> |

| Diagnostic | Server version | Sharing links |
|:---:|:---:|:---:|
| <img src="docs/screenshots/32-diagnostic.png" width="230" alt="Diagnostic page: history, logs, settings backup and import"> | <img src="docs/screenshots/33-server-version.png" width="230" alt="Zipline server version panel"> | <img src="docs/screenshots/34-sharing.png" width="230" alt="Link format: plain, markdown or view page"> |

</details>

<details>
<summary><b>Files</b> — grid and list, sorting, search, details, tags, passwords, bulk actions</summary>

| Navigation | File grid | List view |
|:---:|:---:|:---:|
| <img src="docs/screenshots/05-navigation.png" width="230" alt="Navigation drawer"> | <img src="docs/screenshots/09-files-grid.png" width="230" alt="File grid"> | <img src="docs/screenshots/11-files-list.png" width="230" alt="List view"> |

| Sorting | Search | Bulk actions |
|:---:|:---:|:---:|
| <img src="docs/screenshots/10-sort.png" width="230" alt="Sort menu"> | <img src="docs/screenshots/12-search.png" width="230" alt="Search with field selector"> | <img src="docs/screenshots/16-bulk-select.png" width="230" alt="Bulk selection"> |

| File details | Tag editor | File password |
|:---:|:---:|:---:|
| <img src="docs/screenshots/13-file-detail.png" width="230" alt="File detail sheet"> | <img src="docs/screenshots/14-tags.png" width="230" alt="Tag editor"> | <img src="docs/screenshots/15-password.png" width="230" alt="File password"> |

| Per-format compression | | |
|:---:|:---:|:---:|
| <img src="docs/screenshots/35-auto-compression.png" width="230" alt="Auto compression with separate JPEG and PNG quality"> | | |

</details>

<details>
<summary><b>Viewing &amp; uploading</b> — images, video, text viewer and editor, folders</summary>

| Image viewer | Video player | Text viewer |
|:---:|:---:|:---:|
| <img src="docs/screenshots/26-image-viewer.png" width="230" alt="Image viewer"> | <img src="docs/screenshots/25-video-player.png" width="230" alt="Video player"> | <img src="docs/screenshots/27-text-viewer.png" width="230" alt="Text viewer"> |

| Text editor | Upload text | Folders |
|:---:|:---:|:---:|
| <img src="docs/screenshots/28-text-editor.png" width="230" alt="Text editor"> | <img src="docs/screenshots/24-upload-text.png" width="230" alt="Upload text"> | <img src="docs/screenshots/17-folders.png" width="230" alt="Folders"> |

</details>

<details>
<summary><b>Administration</b> — URLs, users, invites, metrics, server settings and actions</summary>

| Shortened URLs | Users | Invites |
|:---:|:---:|:---:|
| <img src="docs/screenshots/18-urls.png" width="230" alt="Shortened URLs"> | <img src="docs/screenshots/19-users.png" width="230" alt="User management"> | <img src="docs/screenshots/20-invites.png" width="230" alt="Invites"> |

| Metrics | Server settings | Server actions |
|:---:|:---:|:---:|
| <img src="docs/screenshots/21-metrics.png" width="230" alt="Instance metrics"> | <img src="docs/screenshots/22-server-settings.png" width="230" alt="Server settings editor"> | <img src="docs/screenshots/23-server-actions.png" width="230" alt="Server actions"> |

</details>

<sub>Screenshots are taken against the bundled mock server, so no real instance data appears. The
two-factor screens normally set <code>FLAG_SECURE</code> and cannot be captured at all; it was
lifted just long enough to screenshot them, and the secret shown is the mock's fixed test value.</sub>

</div>

---

## Quick start

1. **Install** — grab the APK from [Releases](../../releases), or build it yourself (see
   [Building](#building)).
2. **Sign in** — the app opens straight onto a sign-in screen. Enter your server address, then
   pick whichever suits you:
   - **Username** — your Zipline login and password (two-factor supported); the app fetches the
     API token for you and never stores the password
   - **Token** — paste an API token from your Zipline user settings
   - **Invite** — no account yet? Paste the `https://your-server/invite/…` link someone sent
     you, pick a username and password, and the app creates the account and signs you in. The
     link fills in the server address for you, so this is the only field you need.

   Username and Invite sign you in the moment the server hands over a token — there is no second
   button to press. Only the Token option needs **Connect**, since nothing was fetched. Cleartext
   HTTP, a certificate pin and a custom label live behind *Advanced options*; further servers are
   added later from *Servers*.
3. **Upload** — share a file to ZipShare from any app, use the **Files** / **Media** buttons on
   the dashboard, or long-press the launcher icon for a shortcut. The link lands on your
   clipboard when the upload finishes.

> [!TIP]
> Optional but recommended: in *Servers* → your profile → **Fetch current pin**, pin your
> server's **intermediate** certificate. It survives certificate renewal, unlike pinning the leaf.

## Requirements

| | |
|---|---|
| **Android** | 8.0 (API 26) or newer |
| **Server** | Zipline v4 |
| **Tested on** | Android 8.0 (API 26), 11 (API 30) and 15 (API 35) — full flow: install, profile storage, dashboard sync, upload, notification |

## Features

### Uploads

- Share sheet (`ACTION_SEND` / `ACTION_SEND_MULTIPLE`, any MIME type), photo picker and file picker
- Launcher shortcuts (long-press the icon) and a Quick Settings tile jump straight into an upload
- Large files switch automatically to Zipline's resumable `/api/upload/partial` endpoint above a
  configurable threshold
- Streamed in 8 KiB chunks straight from the content `Uri` — a multi-GiB file costs 8 KiB of heap,
  never a `ByteArray`
- Each file is one `CoroutineWorker` with a foreground progress notification; batches run as a
  chained unique work item, retrying with exponential backoff on transport errors and 5xx only
- Saved upload defaults (expiry, name format, folder, compression, password, max views, extension
  override, return domain), with an optional "upload immediately" mode that skips the options sheet
- **Auto compression** re-encodes each image to the format it already is, so a PNG stays a PNG.
  The quality is set per format, because the number does not mean the same thing in each — for
  JPEG it is a real lossy quality, while PNG is lossless and barely shrinks at the same value.
  Leave one blank to skip that format; anything Zipline cannot re-encode uploads untouched
- Text/snippet upload with language selection

> [!NOTE]
> Android's photo picker hides real file names from apps that hold no media permissions — you get
> numbered stand-ins like `1000062127.png`. ZipShare deliberately holds none, so the upload sheet
> tells you when this happens. Use the **Files** picker or share from your gallery app when the
> original name matters; generated name formats (`random`, `uuid`, `gfycat`…) are unaffected.

### Dashboard and browsing

- Zipline's own dashboard, natively: welcome header, recent uploads with authenticated previews,
  quota lines, the eight stat cards and the file-types table — each section can be switched off
- Paginated file browser in grid or list view, with sorting (date, name, size, type, views, either
  direction), search by name / original name / type / tag, and a favourites filter
- Tap a file for the full detail sheet: preview, timestamps, size, type, view count and limit,
  folder and link, plus favourite, move, rename, view-limit and delete actions
- Tags with colours — create, rename, recolour, delete and assign from the detail sheet
- Per-file password protection, which can be set, changed and removed
- Long-press to select, then favourite, move or delete in bulk
- Full-screen image viewer with pinch-zoom and copy/share
- **Text viewer and editor** for text, code and config uploads — monospaced, non-wrapping so
  indentation survives, with editing that uploads the result as a new file (Zipline has no API to
  replace a file's contents, and the UI says so rather than implying otherwise)
- Account avatar in the header, showing the profile picture from your server, with a menu for
  copying the token, regenerating it and signing out
- The dashboard reloads every time you return to it
- **Video and audio play in the app**, streamed through the same authenticated connection as the
  API — the token stays in the header and never touches a media URL
- **Animated GIF and WebP** play in the viewer, which loads the full file rather than the server's
  static thumbnail
- Create folders (public or private, nested under an existing folder), drill into them, and edit
  them — rename, toggle public, allow uploads from others, or delete while choosing whether the
  files inside are kept or removed
- Create short links, vanity codes included

### Notifications

Three separate channels — progress, completed, failed — each with its own on/off switch in
Settings, so they can also be silenced from Android's own notification screen. Turning progress
detail off keeps the ongoing notification Android requires for foreground work but hides the file
name and percentage. A completed upload carries **Open / Copy / Share** actions, and the link is
copied to the clipboard marked sensitive so it stays out of clipboard previews.

### Administration

Shown only for `ADMIN` / `SUPERADMIN`: instance metrics with charts, a server-settings editor
generated from the live API response, server actions (clear temp, clear zero-byte files, re-query
sizes, generate thumbnails) each behind a confirmation, full user management (create, rename, set
a password, change role and quota, delete with or without their content) and invite management
(create and revoke).

### Multi-server

Several profiles, each with its own base URL, token, cleartext policy and optional certificate
pin, switchable from the top bar.

### Sharing links

**Settings → Sharing** picks what every *Copy link* button writes to the clipboard:

| | Clipboard | In Discord |
|---|---|---|
| **Plain link** | `https://…/u/holiday.png` | the URL, with a preview under it |
| **Markdown** | `[holiday.png](https://…/u/holiday.png)` | just the file name, no preview |
| **View page** | `https://…/view/holiday.png` | a rich embed — title, description, colour, image |

The view page is your server's HTML route rather than the raw file, so chat apps read its
OpenGraph tags and build an embed out of the **Viewing files** settings. Set *Embed title* to
`{file.name}` and you get the file name as the clickable heading with the image below it.

### Account settings

Everything the web dashboard offers for your own account, from the avatar menu in the top right:
upload or remove your avatar, change your username, change your password, and see every device
you are signed in on — with a **Sign out** on each and one button for all the others at once. The
same screen carries Zipline's **Viewing files** settings: view routes, what the view page shows,
and the OpenGraph embed tags other apps read.

### Two-factor authentication

Turn 2FA on or off for your account from Settings. The server renders the enrollment QR, and
**Add to authenticator app** hands the account straight to an installed authenticator over an
`otpauth://` link — useful precisely when the QR is unscannable because it is on the same screen
you would be scanning with. The secret is shown grouped for manual entry as a fallback, and that
screen is capture-blocked while it is visible.

### Diagnostic

Its own page in the drawer, for when something is wrong rather than when you want to change
something. An on-device activity log (uploads, API errors, server switches — never tokens or
passwords), encrypted at rest with a Keystore key and readable only by exporting it. A separate
login log records lock/unlock events and can be exported from the lock screen itself, so being
locked out does not lock you out of the access record. Alongside them: your upload history, a JSON
backup of every app setting that can be exported and imported again — handy when moving to a new
phone — and the Zipline version the server is running, with its commit and whether an update is
available.

## Security

Summarised here, in full in **[SECURITY.md](SECURITY.md)**.

- **Token at rest** — `EncryptedSharedPreferences` (AES256-SIV / AES256-GCM under a Keystore
  master key, StrongBox when available). Never in a log, a URL or a crash trace.
- **Transport** — HTTPS required unless a profile explicitly opts into cleartext *and* the host is
  allow-listed in `network_security_config.xml`. TLS 1.2/1.3 with modern ciphers only, and no
  custom `TrustManager` or hostname-verifier override anywhere.
- **Pinning** — optional per-profile SPKI pinning, with the UI steering you to the intermediate
  certificate and accepting a comma-separated backup pin.
- **Permissions** — `INTERNET`, `POST_NOTIFICATIONS` and `FOREGROUND_SERVICE_DATA_SYNC` only. No
  storage or media permissions: everything goes through SAF and the Photo Picker.
- **No telemetry** — zero analytics, zero crash reporting, no network call to any host but your
  server.

> [!IMPORTANT]
> Please read the **"What is not implemented"** section of [SECURITY.md](SECURITY.md) before
> trusting this with a token you cannot revoke.

## Building

Requires JDK 17+ and the Android SDK (API 35, build-tools 35.0.0).

```bash
./gradlew :app:assembleDebug
```

```bash
./gradlew :app:testDebugUnitTest
```

`assembleRelease` produces an unsigned APK unless you supply signing material. To sign, create a
keystore and a `keystore.properties` at the repository root:

```bash
keytool -genkeypair -v -keystore keystore/release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias zipshare
```

```properties
storeFile=keystore/release.jks
storePassword=...
keyAlias=zipshare
keyPassword=...
```

Both are gitignored. Without them the release build still assembles, just unsigned, so a fresh
clone and CI are never blocked by missing keys.

## Developing without a Zipline instance

`tools/mock-zipline.py` implements the endpoints this client uses — dashboard, stats, files,
folders, URLs, users, invites, server settings and actions, plain and **chunked** uploads — with
fake data and no dependencies beyond the Python standard library.

```bash
python tools/mock-zipline.py
```

It listens on `:8099`. From an emulator the host is reachable at `10.0.2.2`, which is already in
the cleartext allow-list, so add a server with base URL `http://10.0.2.2:8099`, token
`MOCKTOKEN123`, and "Allow cleartext" ticked.

It also serves the sign-in flow — username `zipshare`, password `zipshare`. Set `LOGIN_TOTP=1` to
exercise the two-factor branch (code `123456`). Drop any `.mp4` at `tools/sample.mp4`, or point
`MOCK_VIDEO` at one, and it is served as a playable file complete with HTTP range support so the
in-app player can be tested.

It logs each request with the multipart filename, part content-type and every `x-zipline-*`
header, which makes it straightforward to see exactly what the app put on the wire. Pass `--tls`
to serve HTTPS instead (point `MOCK_TLS_CERT` at a PEM) if you need to exercise the pinning paths.

## API notes

The Zipline v4 contract is pinned from the server source. Two details cost real debugging time and
are worth knowing if you extend this:

- Partial upload uses `content-range: bytes {start}-{end}/{total}` with an **exclusive** end. The
  server issues `partialIdentifier` only on the `start == 0` chunk and requires it on every
  subsequent one, so chunks must be uploaded sequentially.
- Error responses are `{ error, code, statusCode }`. The `error` string is surfaced verbatim in the
  UI; codes drive side effects (E2001 marks the profile unauthenticated, E4001 clears the saved
  default folder).

## Built with

Kotlin · Jetpack Compose (Material 3) · Hilt · Retrofit + OkHttp · kotlinx.serialization ·
WorkManager · Room · DataStore · androidx.security-crypto · androidx.biometric · Coil ·
Media3 ExoPlayer

## Contributing

Issues and pull requests are welcome. Please run `./gradlew testDebugUnitTest` before opening a PR.
Security issues should go through a private advisory — see [SECURITY.md](SECURITY.md).

## Licence

[MIT](LICENSE) © BombenlegerMoe

ZipShare is an independent client and is not affiliated with or endorsed by the Zipline project.
It contains no Zipline code — the API contract was implemented from the public server source and
documentation. Zipline itself is MIT licensed.
