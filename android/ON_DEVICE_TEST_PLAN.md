# Shura on-device test plan

This is the checklist for validating a Shura APK on a real Android device. Everything that can be
checked without a device already runs in CI (assemble + dex identity check) and in the host unit
tests (146 tests, `:shura-source-host:test`). This document covers only what those cannot: the
Android runtime, the device's network, and real extensions.

An emulator works for most of it. Use a real device for the storage and update cases, because the
emulator's account and storage behaviour differ.

## What you need

- A device on **API 26+** (`minSdk = 26`). The app is built for `targetSdk 34`.
- The APK from the CI run (`shura-debug-<sha>` artifact) or a release build.
- Optional: `adb` for logs. `adb logcat -s ShuraAbiSelfTest`.

`targetSdk 34` blocks cleartext HTTP for page images. Almost every extension uses HTTPS, so this is
not normally visible; an extension that serves plain HTTP images will fail in the reader with a
`Failed to load page` status.

## Install

Installing a new build over an old one works because CI signs every build with the same cached
key. If an install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, the previously installed APK
used a different key: uninstall it first, then install.

```sh
adb install -r shura-debug-<sha>.apk
```

## Checks

Open the app. The launcher is **Shura**, showing `Build: <git sha>`. Confirm that value matches the
commit the APK was built from before doing anything else; a stale install otherwise looks identical.

### 1. Self test (no network)

Tap **Self Test**, then tap the report to re-run it.

- [ ] The report starts with `BUILD: <sha>`, `VERSION: 0.1.0 (1)`, `APK BUILD TIME: ...`.
- [ ] The ABI report ends with `N/N passed`.
- [ ] The extension report ends with `N/N passed`.
- [ ] No `FAIL` line, and no stack trace.

A `FAIL` here is a packaging problem, not a network one. Record the failing label and the first
lines of its detail.

### 2. Repository (network)

Tap **Repositories** → **Refresh Default Repository**.

- [ ] Status shows `OK: <n> extension(s), <m> unusable`.
- [ ] `n` is non-zero (the published index has hundreds of entries).
- [ ] An `Error:` status instead means the device cannot reach
      `https://raw.githubusercontent.com/aaaarrr125h-rgb/Manga-Extensions/main/repo/index.json`.
      Tap the status to retry; a captive-portal or DNS block is the usual cause.

### 3. Install an extension

Tap **Extensions**.

- [ ] The list fills with extension names (this fetches the index again).
- [ ] Tap one; its button ends as `<name> • installed` (or `• updated`).
- [ ] A failure leaves `<name> • failed, tap to retry`; tap it to retry.

This is the signed-install path: the APK's certificate is checked against the repository
`signingKey` before it is moved into the store. A `refused:` label means that check stopped the
install and is the expected result for anything tampered with.

### 4. Sources

Tap **Sources**.

- [ ] Each installed extension that exposes a source appears as `<name> (<language>)`.
- [ ] If the list is empty, the installed extension exposes no sources this host supports.

### 5. Browse and search

Tap a source.

- [ ] **Popular** loads a list of manga titles.
- [ ] Type a query and tap **Search**; results replace the popular list.
- [ ] Pull a source that is down: the status shows `Error: ...` with `Tap to retry`, and the screen
      stays usable.

### 6. Read

Tap a manga, then tap a chapter.

- [ ] Details show, and chapters list.
- [ ] The first page renders; **Prev**/**Next** move through it.
- [ ] The counter tracks `page/total`.
- [ ] A page that fails to decode shows `Failed to load page N` with tap-to-retry.

### 7. Library and reading position

On the details screen tap **Add to Library** (it becomes **Remove from Library**). Read a few pages.

- [ ] Press Back to the launcher, reopen **Library**: the manga is listed.
- [ ] The listing shows `resume: <chapter> p<page>` for where you stopped.
- [ ] Tapping the entry returns to the details screen.
- [ ] **Remove** takes it out of the list.

### 8. Offline download

Open a chapter and tap **Download**.

- [ ] Status advances `Downloading i/n...` and ends `Downloaded n page(s), <bytes> bytes`.
- [ ] The button becomes **Downloaded**.
- [ ] Go to **Downloads**: the chapter is listed as complete with its page count.
- [ ] Open it from **Downloads**: pages render.
- [ ] Enable airplane mode, force-stop the app, reopen the chapter from **Downloads**: it still
      renders from disk.
- [ ] **Delete** removes it from the list.

### 9. Robustness

- [ ] Turn on airplane mode and open **Repositories**: the screen shows an error with retry rather
      than closing.
- [ ] Repeat in **Extensions**, **Sources**, **Browse**, and the reader. None of them should crash
      the app.
- [ ] Turn networking back on, tap the error status, and confirm recovery without reinstalling.

### 10. Diagnostics

Tap **Diagnostics** → **Run checks**.

- [ ] `build`, `version`, and `repository` are the expected values.
- [ ] `repository reachable: yes, ...`.
- [ ] `trusted key` equals `9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2`.
- [ ] Installed extensions and loadable sources are listed.
- [ ] `downloads` and `library` counts match what screens 7 and 8 showed.
- [ ] `last error` names the most recent failure from step 9, if any.

## Reporting a failure

Capture `adb logcat -s ShuraAbiSelfTest` around the failing step, the commit from the launcher
header, and which numbered check failed. The diagnostics screen's `last error` and recent-errors
list usually name the cause without a log.
