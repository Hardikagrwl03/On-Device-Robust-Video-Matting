---
name: rvm-app-release
description: Build, sign and publish a release APK of the RVM app to a GitHub release -- signing config, ABI packaging, artifact naming and checksums. Use when cutting a version, when assembleRelease produces an unsigned APK, or before uploading anything users will install.
---

# RVM app: building and publishing a release

Distribution is a **single APK attached to a GitHub release** (not Play), so
one universal APK must install on every supported device -- see "ABIs" below.

## Signing

Signing material is deliberately outside version control (`.gitignore` excludes
`*.keystore`, `*.jks`, `keystore.properties`, `dist/`).
`app/build.gradle.kts` reads `keystore.properties` from the project root:

```properties
storeFile=rvm-release.keystore
storePassword=...
keyAlias=rvm-release
keyPassword=...
```

If that file is **absent the build still succeeds**, just unsigned -- a fresh
clone is never blocked by a missing secret. If `assembleRelease` gives you an
unsigned APK, that file is missing or unreadable.

The key is 4096-bit RSA, valid to 2054. Only **v3** signing is enabled: v1/v2
are redundant at `minSdk 35`, and v3 is what permits key rotation later.

> **The keystore is irreplaceable.** Android installs an update over an existing
> install only if it is signed with the same key. Lose it and every user must
> uninstall before they can update. Back up `rvm-release.keystore` **and**
> `keystore.properties` outside the repo -- a gitignored file is still destroyed
> by `git clean -xdf`.

## ABIs

`ndk { abiFilters += listOf("arm64-v8a", "x86_64") }`. TFLite native libs are
~73 MB per ABI; `armeabi-v7a`/`x86` are unreachable at `minSdk 35` and were
dropped (597 MB -> 227 MB). Keep **both** remaining ABIs in the published APK --
`arm64-v8a` is every real phone, `x86_64` covers emulators and Chromebooks.

Do **not** narrow `abiFilters` further for a public release: it hard-excludes
devices. If per-device size ever matters more, use an AAB (Play) or ABI splits
(direct APK) -- both keep full coverage.

## Cutting a version

1. Bump `versionCode` (must increase every release) and `versionName` in
   `app/build.gradle.kts`.
2. Build both variants:

```bash
./gradlew assembleDebug assembleRelease
```

3. Verify the signature:

```bash
$ANDROID_HOME/build-tools/<ver>/apksigner verify -v \
  app/build/outputs/apk/release/app-release.apk        # expect: Verifies, v3 true
```

4. Stage with **release-artifact naming** -- the release APK carries **no
   `-release` suffix**; only non-default variants are qualified:

```bash
mkdir -p dist
cp app/build/outputs/apk/release/app-release.apk dist/rvm-<version>.apk
cp app/build/outputs/apk/debug/app-debug.apk     dist/rvm-<version>-debug.apk
sha256sum dist/*.apk
```

5. Install the *release* APK on a real device and run a full matte before
   publishing (see `rvm-app-verify`) -- uninstall first, since a debug-signed
   build is already there.

6. Publish. **Tags are component-prefixed** — this repo also publishes the model weights from the
   same release list (`models-v1`), so a bare `v1.0` would be ambiguous. App releases are
   `app-v<version>`; `v1.0` shipped as **`app-v1.0`**.

```bash
gh release create app-v<version> \
  --target application \
  --title "RVM Android App v<version>" \
  --notes-file <notes>.md \
  --draft \
  dist/rvm-<version>.apk

gh release download app-v<version> -p 'rvm-<version>.apk' -D /tmp/verify
sha256sum /tmp/verify/rvm-<version>.apk dist/rvm-<version>.apk   # must match

gh release edit app-v<version> --draft=false
```

Draft first, always: the tag is not created until a draft is published, so a bad upload can be
discarded without leaving a tag behind, and the round-trip checksum catches a truncated 200 MB
transfer before users hit it. Publish the SHA-256 in the release notes.

## Two things to get right

- **Do not publish the debug APK to users.** It is `debuggable`, signed with the
  shared Android debug key, and allows a debugger to attach and app data to be
  pulled. Build it for local testing; attach only the release APK.
- **R8 is off** (`optimization { enable = false }`). Enabling it would shrink the
  APK further but risks breaking TFLite's reflection-based paths, so it needs a
  real matting run to validate -- treat it as its own change, never as part of
  cutting a release.
