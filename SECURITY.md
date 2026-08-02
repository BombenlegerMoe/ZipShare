# Security

ZipShare holds an API token that grants full access to a Zipline account, so the threat model
is taken seriously. This document states what is actually implemented and, just as importantly,
what is not.

## Reporting a vulnerability

Please open a private security advisory on GitHub rather than a public issue. If you have no
GitHub account, open an issue asking for a contact address without including details.

## What is implemented

**Token at rest.** Tokens live only in `EncryptedSharedPreferences` — AES256-SIV for keys,
AES256-GCM for values, under a Keystore master key, requesting StrongBox where the device
provides it. The token is never written to a log, never placed in a URL or query string, and
never included in a crash trace.

**Transport.** HTTPS is required. `http://` is refused unless a profile explicitly opts in *and*
its host is listed in `network_security_config.xml` — there is no global cleartext switch. The
OkHttp client is restricted to `RESTRICTED_TLS` and `MODERN_TLS` (TLS 1.2/1.3 with modern cipher
suites); `COMPATIBLE_TLS` is deliberately excluded so a downgrade cannot reach weaker ciphers.

There is no custom `TrustManager`, no `HostnameVerifier` override and no trust-all path anywhere
in the codebase. Certificate validation is the platform's.

**Certificate pinning (optional).** Per profile, an SPKI SHA-256 pin can be set. "Fetch current
pin" performs a TOFU lookup showing the live chain labelled Leaf / Intermediate / Root, and
recommends the **intermediate** — pinning a leaf breaks the app at every certificate renewal.
Several pins may be given (comma separated) so a backup pin prevents lockout. Pinning is additive:
it runs after normal chain validation, never instead of it.

**Secrets in transit between components.** WorkManager persists a worker's input `Data` as a
plaintext blob in `androidx.work.workdb`. An upload password is therefore never placed in it —
only an opaque id is, with the value held in the encrypted store and removed once the work
reaches a terminal state.

**Clipboard.** Copied links are always flagged `EXTRA_IS_SENSITIVE`. On a private instance the
URL is the secret.

**Screen capture.** `FLAG_SECURE` is applied to the screens that display a token, a pin or a TOTP
secret (server editor, app lock, two-factor enrollment), so they are excluded from screenshots and
the recents thumbnail.

**Permissions.** `INTERNET`, `POST_NOTIFICATIONS`, and `FOREGROUND_SERVICE_DATA_SYNC` - that is
the whole list. There is no `CAMERA` (nothing in the app uses one), and no `READ_MEDIA_*` or
`READ_EXTERNAL_STORAGE`: files are reached exclusively through the Storage Access Framework and
the Photo Picker. Additional permissions visible in the merged manifest are contributed by
AndroidX libraries (WorkManager, biometric).

**Sign-in.** A profile can be created from an API token, from a username and password, or by
registering with an invite link. The password flow posts to `/api/auth/login`, which opens a
*session* rather than
returning a token, then immediately spends that session cookie on `/api/user/token` and keeps only
the resulting token. The password is never written to disk and is cleared from memory state as
soon as the token arrives; the session cookie lives in a per-attempt in-memory jar that is
discarded with the client. Two-factor accounts are supported: the server's `{"totp": true}`
response prompts for the six-digit code. Certificate pinning applies to the login exchange too.

**Media playback.** Video and audio are streamed through the profile's own OkHttp client, so the
`authorization` header, the TLS policy and any certificate pin apply to media exactly as they do
to the API. The token is never appended to a media URL.

**Backup.** `allowBackup="false"`, with `dataExtractionRules` additionally excluding the encrypted
preferences and the Room database from cloud backup and device transfer.

**Network scope.** The app talks to the configured server and nothing else. There is no
analytics, no crash reporting and no telemetry of any kind.

**Release builds** are minified with R8 full mode; the debug-only HTTP logger redacts
`authorization`, `cookie` and `x-zipline-password` and is compiled out of release entirely.

**Diagnostic log.** The app keeps an activity log (app start, API failures as method + path +
error code, upload lifecycle, profile changes) in app-private storage. Every line is encrypted
individually with AES-256-GCM under a non-exportable Android Keystore key, so the file is
ciphertext to `adb`, to root and to a physical extraction. It is readable only by exporting it
from Settings, which decrypts it to a plain-text file handed to the share sheet. Tokens,
passwords and share URLs are never passed to it — on a private instance the URL is the secret, so
success lines record the file id instead. A separate `auth.log` channel records lock/unlock
events only.

## What is *not* implemented

Being explicit about the gaps matters more than the list above.

- **No certificate transparency verification.** Pinning is the available mitigation.
- **No root, emulator or tamper detection**, and no Play Integrity attestation.
- **The token is extractable by anyone with physical access to an unlocked, developer-enabled
  device**, or on a rooted device. Keystore protects it at rest, not against a live debugger.
  Use a token you can revoke.
- **`FLAG_SECURE` is not applied to content screens** — the dashboard, file grid and image viewer
  can be screenshotted and appear in the recents thumbnail.
- **The app lock is only as strong as the device credential.** With no PIN, pattern or biometric
  enrolled it cannot engage; the settings screen says so rather than implying protection.
- **The share target accepts any URI any app sends it.** That is inherent to being a share target,
  but there is no size or type guard beyond the server's own limits.
- **A TOTP enrollment secret is a credential in visual form.** While two-factor setup is on
  screen, the secret and the server's QR are both visible. That screen sets `FLAG_SECURE` and the
  image is never written to storage - but anyone who photographs it can generate your codes
  indefinitely. Treat it like a displayed password.
- **`otpauth://` hand-off goes to whatever app claims the scheme.** "Add to authenticator app"
  fires an implicit intent carrying the secret, so it reaches whichever installed app registered
  for `otpauth` - the same trust model as scanning the QR with that app. On a device with a
  malicious app claiming the scheme, use the copy-secret fallback instead.
- **The login log is exportable from the lock screen, before authenticating.** This is deliberate,
  so an owner locked out of the app can still retrieve the record of access attempts, and that
  channel is restricted to lock/unlock events for exactly this reason. The consequence is that
  anyone holding the locked device can read the unlock-attempt history (timestamps and outcomes).
  It contains no server, file or token data. The main diagnostic log stays behind the lock.
- **Wrong device-PIN attempts are not recorded.** Android's credential prompt handles those
  internally and reports only cancellation to the app, so the login log sees rejected biometrics,
  errors and successes — not each wrong PIN.
- **Tested on Android 8.0 (API 26), 11 (API 30) and 15 (API 35).** The full flow — install,
  profile in hardware-backed encrypted storage, dashboard sync, upload, notification — was
  verified on those three; the API levels in between are untested, and Keystore, StrongBox and
  foreground-service behaviour vary across them.

## Verification

The security properties above were checked against a live Zipline instance, not only reasoned
about: the token was searched for across all app-private files in ASCII, base64, hex and UTF-16
(absent — ciphertext only); the app's open sockets were enumerated to confirm the server was the
sole endpoint; and pinning was exercised with both a correct and a deliberately wrong pin.
