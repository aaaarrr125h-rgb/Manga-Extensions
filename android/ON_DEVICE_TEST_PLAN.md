# Shura on-device test plan

This is the checklist for validating a Shura APK on a real Android device. Everything that can be
checked without a device already runs in CI (assemble + dex identity check) and in the host unit
tests (150 tests, `:shura-source-host:test`). This document covers only what those cannot: the
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

Open the app. The launcher is **Shura** Home, with a bottom bar (Home / Library / Sources / History /
Settings). The build identity is on **Settings → Build info** (`<version> (<git sha>)`) and, in more
detail, on **Settings → Diagnostics**; confirm the sha matches the commit the APK was built from
before doing anything else, because a stale install otherwise looks identical.

The screens below are named by their path in the new navigation. Installing extensions, managing the
repository, downloads and diagnostics all live under **Settings**.

### 1. Self test (no network)

**Settings → Advanced → Self Test**, then tap the report to re-run it.

- [ ] The report starts with `BUILD: <sha>`, `VERSION: 0.2.1 (3)`, `APK BUILD TIME: ...`.
- [ ] The ABI report ends with `N/N passed`.
- [ ] The extension report ends with `N/N passed`.
- [ ] No `FAIL` line, and no stack trace.

A `FAIL` here is a packaging problem, not a network one. Record the failing label and the first
lines of its detail.

### 2. Repository (network)

**Settings → Sources → Repositories** → **Refresh**.

- [ ] Status shows `OK: <n> extension(s), <m> unusable`.
- [ ] `n` is non-zero (the published index has hundreds of entries).
- [ ] An `Error:` status instead means the device cannot reach
      `https://raw.githubusercontent.com/aaaarrr125h-rgb/Manga-Extensions/main/repo/index.json`.
      Tap the status to retry; a captive-portal or DNS block is the usual cause.

### 3. Install an extension

**Settings → Sources → Extensions**.

- [ ] The list fills with extension names (this fetches the index again).
- [ ] Tap a row's **Install**; the status line ends as `<name> · installed` (or `· updated`), and the
      row now shows `Installed <version>` with **Reinstall** (or **Update** when the index is newer).
- [ ] If the download lands but cannot start, the status shows
      `Downloaded, but could not start. Tap to retry.` and the row shows **Needs repair**; tapping the
      status retries.
- [ ] A failure shows `Error: ...` on the status line; tap **Install** again to retry.

This is the signed-install path: the APK's certificate is checked against the repository
`signingKey` before it is moved into the store. A `refused:` label means that check stopped the
install and is the expected result for anything tampered with.

After a successful install the extension is loaded through a `DexClassLoader`, not the host's
`URLClassLoader`, because a downloaded APK's code is `classes.dex`. If **Sources** stays empty after
a row reads `Installed`, check **Settings → Diagnostics**: an entry ending in `NEEDS REPAIR` means
the registered file did not load, and the `installed extensions` / `loadable sources` counts are the
two halves to compare.

### 4. Sources

Bottom bar → **Sources**.

- [ ] Each installed extension that exposes a source appears, grouped by language.
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
- [ ] The chapter renders as one vertical strip; scrolling does not need a page-turn gesture.
- [ ] Tapping the strip hides/shows the top bar and the Prev/Next bar.
- [ ] The bottom counter tracks the visible page as `page/total`.
- [ ] **Prev**/**Next Chapter** move between chapters (disabled at the ends).
- [ ] A page that fails to decode shows `Page N failed to load. Tap to retry.` and retries on tap.

### 7. Library and reading position

On the details screen tap **Add to Library** (it becomes **Remove from Library**). Read a few pages.

- [ ] Bottom bar → **Library**: the manga is listed (grid by default; the header action toggles list).
- [ ] The list view shows the last-read chapter under the title; the **History** tab shows that
      chapter and `Page N` plus a relative time.
- [ ] Tapping a cover opens the details screen.
- [ ] On the details screen, **Remove from Library** takes it out of the list.

### 8. Offline download

Open a chapter and tap the download icon in the reader's top bar.

- [ ] Status advances `Downloading i/n...` and ends `Downloaded`.
- [ ] The download icon's tint turns green (success) once complete.
- [ ] **Settings → Downloads**: the chapter is listed as `Complete` with its page count.
- [ ] Tap **Open** on that row: the pages render without a network call.
- [ ] Enable airplane mode, force-stop the app, reopen the chapter from **Downloads**: it still
      renders from disk (the reader shows `Offline`).
- [ ] **Delete** removes it from the list.

### 9. Robustness

- [ ] Turn on airplane mode and open **Settings → Sources → Repositories**: the screen shows an error
      with retry rather than closing.
- [ ] Repeat in **Extensions**, **Sources**, **Browse**, and the reader. None of them should crash
      the app.
- [ ] Turn networking back on, tap the error status, and confirm recovery without reinstalling.

### 10. Diagnostics

**Settings → Advanced → Diagnostics** → **Run checks**.

- [ ] `build`, `version`, and `repository` are the expected values.
- [ ] `repository reachable: yes, ...`.
- [ ] `trusted key` equals `9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2`.
- [ ] Installed extensions and loadable sources are listed.
- [ ] `downloads` and `library` counts match what screens 7 and 8 showed.
- [ ] `last error` names the most recent failure from step 9, if any.

## Reporting a failure

Capture `adb logcat -s ShuraAbiSelfTest` around the failing step, the commit from
**Settings → Build info**, and which numbered check failed. The diagnostics screen's `last error` and recent-errors
list usually name the cause without a log.
