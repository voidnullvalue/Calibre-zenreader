# CalibReader

A deliberately thin Android wrapper around a [calibre-zen](https://github.com/other-void/calibre-zen)
style web reader. There is no reader logic in the app: the library UI is the
server's own web application, and this project only adds a native frame, an
allow-list, downloads, diagnostics and a settings screen.

The app is built entirely on the Android framework. It has no AndroidX and no
third-party runtime dependencies, so the whole surface can be audited in four
Java files.

## Requirements

* A calibre-compatible web server reachable from the tablet (plain HTTP on the
  LAN is the common case).
* JDK 17 or newer (the project is built with Java 17 language level).
* Android SDK with build-tools for compile SDK 35 (`local.properties` points at
  the SDK; replace `sdk.dir` for your machine).

## Build

```sh
./gradlew assembleDebug
```

The debug APK is written to:

```
app/build/outputs/apk/debug/app-debug.apk
```

The debug variant uses the application id `io.voidlab.calibreader.debug` and
enables WebView remote debugging. Release builds use `io.voidlab.calibreader`.

The debug build bakes in a first-run default server, including the path, so the
calibre-zen entry point under `/app/` is loaded rather than the server root:

```
http://192.168.88.89:8090/app/
```

This is the deployment target used for the verification below. It is normalised
and validated exactly like a typed address, and it is only a default: the
settings menu can replace it. Override it at build time with:

```sh
./gradlew assembleDebug -PcalibreaderServer=http://192.168.1.10:8080
```

A path is preserved: `http://host:8090/app/` loads `/app/`, not `/`. The
calibre endpoints `/opds` and `/manifest.json` stay at the origin root for the
diagnostics probe.

## Install and launch

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n io.voidlab.calibreader.debug/io.voidlab.calibreader.MainActivity
```

Automation can point a fresh install at a server without touching the UI:

```sh
adb shell am start -n io.voidlab.calibreader.debug/io.voidlab.calibreader.MainActivity \
  -e server_url http://192.168.1.10:8080
```

On first run, when a build-time default is present, the app saves it and loads
its start page (`base + "/"`, so `/app/` stays `/app/`). With no usable default
it shows the settings dialog. The dialog accepts what a person types
(`192.168.1.5:8080`, `host`, `https://books.example.org/calibre/`) and
normalises it to `scheme://host[:port][/path]`. The `Test connection` button
probes the start page that will actually be loaded, without sending
credentials.

## Security model

The wrapper is not a general-purpose browser. Its guarantees are:

* **One origin.** Top-level navigation is only allowed to the configured
  scheme, host and port. Any other `http`/`https` link is handed to the system
  browser; an `http` URL and an `https` URL on the same explicit port are
  different origins. Scope is decided from scheme, host and port only, without
  `java.net.URI`: a
  URL whose path or query holds characters that class rejects (a raw space,
  `|`, `^`, `{`, `}`) is still recognised as the configured origin instead of
  being refused, and Chromium's treatment of `\` as a path separator is
  matched so `http://evil\@host:port/` cannot pass as the configured host.
* **No off-origin subresources.** `shouldInterceptRequest` cancels every
  network request outside the configured scheme, host and port, including
  fonts, CDNs and analytics. The block is logged and returns HTTP 403.
* **No other schemes.** `file:`, `content:`, `intent:`, `javascript:` and
  unknown schemes are refused. Only `mailto:`, `tel:` and `sms:` are forwarded
  to the system.
* **TLS is never bypassed.** Certificate errors cancel the load and show an
  error panel; there is no "continue anyway" path.
* **No file or content access.** `allowFileAccess`, `allowContentAccess` and
  the universal-access flags are all off, mixed content is never allowed, and
  third-party cookies are rejected.
* **Downloads stay in scope.** `DownloadManager` is only used for URLs on the
  configured host; the WebView cookie is forwarded so authenticated downloads
  work. Roaming downloads are disabled.
* **Small backup surface.** Backups are disabled and only the base URL and the
  last in-scope page are stored in `SharedPreferences`.

Cleartext HTTP is enabled because calibre-server has no built-in TLS and the
host cannot be known at build time. Android's network security config cannot
name an arbitrary user-supplied host, so cleartext is constrained at the
application layer instead (the allow-list above). The settings dialog warns
when the configured address is not HTTPS.

## Known limits

* The app is a Java/Android-framework shell, **not** the Kotlin + Jetpack
  Compose application that was originally requested. It also does not yet
  implement all of the requested reader features (for example in-app library
  management, reading-progress sync, TTS/accessibility controls, offline cache
  and a Compose settings UI). It should be treated as a working wrapper
  baseline, not a completed product. TTS/read-aloud, biometric or other app
  lock, e-ink optimisations and multiple server profiles are explicitly out of
  scope for this project (see Verification), so these are deliberate gaps
  rather than failed checks.
* On the test tablet the first page of a book can take a while: calibre-zen
  hands the whole section to the page as one ~4 MB `srcdoc` iframe, which keeps
  the WebView renderer busy (measured 60-70 s after opening "A Scanner Darkly"
  with a hidden WebView, and about four minutes on a cold WebView cache after
  `pm clear`). Until the section has been painted the reader shows only its
  controls, which looks like a blank page. That is direct evidence for
  "A Scanner Darkly"; it does not by itself explain the earlier blank reader
  for "Blood Test" (book 1298), which was not root-caused. No out-of-scope
  request was observed while reading either book, so the scheme/authority
  parser change is preventive allow-list hardening, not a proven fix for the
  blank reader. Once the section is laid out, text and touch page turns work
  (see Verification). Investigating or optimising this delay is explicitly out
  of scope.
* The app does not bundle or manage a calibre server; the server must already
  be running and reachable.
* The allow-list is scheme+host+port based. A server that redirects to another
  scheme or host will open that page in the system browser instead of inside
  the reader.
* Only the last in-scope page is remembered; there is no offline cache.
* Downloads are delegated to `DownloadManager`; on Android 13+ the
  notification permission is requested, but the file is downloaded either way.
* Self-signed HTTPS certificates are not supported by design.

## Diagnostics

The overflow menu offers *Invert colors (white on black)*, *Reload*, *Server
settings*, *Server diagnostics*, *Open in browser*, *Copy address* and *Clear
site data*. The colour mode is remembered across launches and is off by
default, so the normal appearance remains available. It works by injecting a
stylesheet into the reader page: the page is inverted and its hue rotated back
(its background is set to white so the filter renders it black), while images
and video are inverted a second time so they keep their real colours. Media is
compensated only once, so a `picture`/`img` pair and nested svg are not
double-inverted. Diagnostics performs
plain `GET` requests against the configured start page (e.g. `/app/`) and
against `/opds`, `/manifest.json` and `/` at the origin root, then shows status
code, latency, `Server` and `Content-Type` headers.

## Capturing the live WebView

Debug builds expose the WebView over Chrome DevTools Protocol, which works even
when only the WebView is interesting:

```sh
adb forward tcp:9222 localabstract:webview_devtools_remote_$(adb shell pidof io.voidlab.calibreader.debug | tr -d '\r')
python3 tools/webview_capture.py --port 9222 --out artifacts/screenshots/webview.png
```

The script prints the page URL, title, a small DOM summary and writes a PNG
screenshot. It needs the `websockets` Python package.

## Verification

Tested on a Kindle Fire tablet (`KFTRWI`, Android 9 / API 28, 1200x1920 at
240 dpi with a 180 dpi override, wlan0 192.168.88.156/24) against the target
server at `http://192.168.88.89:8090/app/` (same LAN, `Server: calibre 9.18.0`):

* `curl --max-time 8 -D - http://192.168.88.89:8090/app/` — HTTP 200,
  `Server: calibre 9.18.0`, `Content-Type: text/html; charset=UTF-8`.
* `./gradlew assembleDebug` — BUILD SUCCESSFUL.
* Fresh install (`adb uninstall` + `adb install`) with no launch extra loaded
  `http://192.168.88.89:8090/app/` from the baked default and showed the live
  library ("1,298 books").
* CDP reported page URL `http://192.168.88.89:8090/app/`, title
  `library · Calibre Zen`, and a device-side `HEAD /app/` returned `200` with
  `Server: calibre 9.18.0`.
* On-device diagnostics reported `OK 200` for `/app/`, `/opds`,
  `/manifest.json` and `/`, all with `server: calibre 9.18.0`.
* Book detail, back navigation and the "Continue reading" shelf worked in that
  lock-screen run; the reader view there was blank until its section had been
  laid out (see Known limits).
* No `FATAL`/`AndroidRuntime` crash and no out-of-scope request blocks were
  logged during the run.
* Reader check with the known-good book ("A Scanner Darkly", book 1, MOBI):
  the reader route `/#book_id=1&fmt=MOBI&library_id=library&mode=read_book`
  loaded a content iframe holding 480,953 characters of the novel, and paging
  moved the column layout forward one page per `ArrowRight` (scrollX 4800 →
  5400 → 6000 → 6600 → 7200 → 7800) with different text on every page. The
  percentage readout was 5%. `artifacts/logs/73-reader-pages.json` holds the
  page-by-page text samples. This is evidence that the reader renders that
  book after its first layout; it does not reproduce or explain the earlier
  "Blood Test" blank.
* Unlocked physical run (2026-11-14 13:51-14:08), driven by `adb shell input`
  and captured with `adb shell screencap` rather than CDP:
  * app data cleared (server address untouched), fresh launch loaded the baked
    default `http://192.168.88.89:8090/app/` and the live library
    ("1,298 books", screenshot `116-library-firstrun.png`);
  * the book was found by typing `scanner` into the library search, opened by
    tapping the cover and then `Read` (`117`-`119`);
  * the reader showed readable novel text on the tablet screen: the front
    matter, then chapter two at 9% (`122`-`124`);
  * a tap on the right edge turned one page forward and a tap on the left edge
    one page back - scrollX 7476 → 6408 for one back tap, percentage readout and
    visible text changed with every turn (`123`-`125`);
  * `KEYCODE_BACK` from the reader returned to the library with the book detail
    panel open and a new "Continue reading" entry (`126`); back from a reader
    that was the only WebView history entry (activity started on the restored
    reader URL) finished the activity instead (`115`);
  * `KEYCODE_HOME` stopped the activity, relaunching brought the same task
    forward with the reader still at 7% / scrollX 6408 / 480,945 characters;
    screen off/on re-locked the tablet on its PIN keypad, and while the lock
    screen was up the activity was still `mStopped` (not destroyed) and the
    WebView still held the same reader page (`128`-`132`, `135`, `136`, `139`);
  * no `FATAL EXCEPTION`, no ANR, no out-of-scope request, no blocked
    navigation and no app `403` in the run's logcat (`137`, `138`).

Out of scope for this project (not implemented, not required): TTS /
read-aloud, biometric or other app lock, e-ink / refresh-mode optimisations,
multiple server profiles (the app keeps one configured server address), and
any work on the server or web-app side of the book-loading delay above. No
server, library or device setting was changed during verification.

Evidence: `artifacts/screenshots/20-*` through `31-*`, the logcat/CDP JSON under
`artifacts/logs/`, and the run notes in `artifacts/verification-target.md`,
`artifacts/verification-reader.md` and `artifacts/verification-physical-touch.md`.

## Tests

```sh
./gradlew testDebugUnitTest
```

JUnit 4 tests run on the JVM and cover address normalisation and the navigation
allow-list (`app/src/test/java/io/voidlab/calibreader/ServerConfigTest.java`),
including the reader and book-file URLs calibre actually serves.
