# Releasing a signed build

`android/app/build.gradle.kts` has the scaffolding for a signed release build, but
**no keystore ships in this repo, and none ever should.** This doc is how to create
your own and wire it in, both locally and in CI.

## Generate the keystore (once, keep it forever)

```
keytool -genkeypair -v -keystore ping-release.keystore -alias ping -keyalg RSA -keysize 2048 -validity 10000
```

`keytool` will ask for a store password, a key password, and identity fields (name,
org, etc. — these end up in the certificate, not anything security-sensitive). Store
the resulting `.keystore` file somewhere **outside this repo** — a password manager's
file storage, an encrypted drive, wherever you keep secrets you can't afford to lose.

**There is no recovery if you lose this file or its passwords.** Every future update
to a published app must be signed with the same key, or the Play Store (and users'
existing installs) will refuse it as a different app. Treat it like the identity
private key in `docs/SECURITY.md` — same stakes, same "no recovery by design."

## Local builds

Create `android/keystore.properties` (already gitignored — never commit this):

```properties
storeFile=/absolute/path/to/ping-release.keystore
storePassword=...
keyAlias=ping
keyPassword=...
```

Then `gradlew.bat :android:app:assembleRelease` produces a signed APK. If this file
is absent, the release build type simply comes out unsigned — exactly today's
behavior — so nothing breaks for anyone who hasn't set this up.

## CI (GitHub Actions)

The same four values, as repository secrets instead of a file — **Settings → Secrets
and variables → Actions**:

| Secret | Value |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | `base64 -w0 ping-release.keystore` (the whole file, base64-encoded) |
| `RELEASE_STORE_PASSWORD` | the store password |
| `RELEASE_KEY_ALIAS` | `ping` (or whatever alias you chose) |
| `RELEASE_KEY_PASSWORD` | the key password |

`.github/workflows/ci.yml`'s release job decodes `RELEASE_KEYSTORE_BASE64` back into
a file at build time and points `RELEASE_STORE_FILE` at it — the keystore itself
never touches git history, only a CI runner's ephemeral filesystem for the duration
of one job.

One more step: also set a repository **variable** (not secret — Settings → Secrets
and variables → Actions → **Variables** tab) named `RELEASE_SIGNING_CONFIGURED` to
`true`. The release job gates on this rather than checking the secrets directly,
since secret values aren't reliably readable in a job's `if:` condition — the
variable is just an explicit "yes, I've set the secrets up" flag. Until it's set to
`true`, the release job is skipped entirely and CI behaves exactly as it does today.

## What this doesn't cover

Signing is one piece of an actual release — see `docs/SECURITY.md` and the open-source
readiness discussion in this project's history for the rest: no professional security
audit has happened yet, and Play Store submission needs your own Play Console account
and store listing. This doc is scoped to "how does a signed APK get built," nothing more.
