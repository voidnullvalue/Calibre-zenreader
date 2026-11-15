# Reader verification — "A Scanner Darkly" (2026-11-14)

Target: `http://192.168.88.89:8090/app/` (`Server: calibre 9.18.0`, calibre-zen 0.5.0)
Device: Kindle Fire `KFTRWI`, Android 9 / API 28, serial `G001KT0511960U8G`,
wlan0 `192.168.88.156/24`, WebView 120.0.6099.266 (Amazon WebView)
App: `io.voidlab.calibreader.debug` 0.1.0-debug, APK
`app/build/outputs/apk/debug/app-debug.apk` (md5 `461c766e0bf00aaba5f884d5d22786d3`)

> Follow-up: this run was done behind the tablet's lock screen. The unlocked,
> touch-driven continuation (readable text, touch page turns, back, lifecycle)
> is in `verification-physical-touch.md`.

## Question

The previous run reported that opening a book reached calibre's reader route but
rendered "blank apart from the reader controls". Is the wrapper blocking the
reader, or is the reader simply not done rendering?

## Answer

The wrapper blocked nothing that was observed, and the reader does render real
book text. Two effects matter, but their scope is limited:

1. **First-layout stall (~60-70 s) for "A Scanner Darkly".** calibre-zen hands
   the whole book section to the page as a single `srcdoc` iframe. For that book
   it is ~3.9 MB of HTML (`document.documentElement.outerHTML` of the reader
   page is ~4.5 MB). Measured after opening it: the WebView renderer accepted no
   CDP command for ~60 s (`Runtime.enable` timed out twice at 25 s) and the page
   showed only the reader controls, then became responsive with
   `readyState: complete` and 480,953 characters of text in the content iframe.
   This is direct evidence for "A Scanner Darkly"; it is not proof that the
   earlier blank "Blood Test" (book 1298) reader had the same cause, which was
   not root-caused.
2. **A hidden WebView has a 0x0 viewport.** While the tablet is on its lock
   screen the activity is `mResumed=false mStopped=true`, the WebView reports
   `Page.getLayoutMetrics` `clientWidth/clientHeight = 0`, nothing is laid out or
   painted, and `Page.captureScreenshot` never returns. Everything below was
   therefore driven and observed through the live WebView over CDP with
   `Emulation.setDeviceMetricsOverride` at the tablet's real size (1200x1758).

No `out-of-scope request` and no `blocked navigation with scheme` line was
logged by the app at any point, and all 79 URLs observed on the wire (library
assets, fonts, `interface-data/*`, `book-file/*`, `book-manifest/*`, `get/cover`,
`get/thumb`, the reader route itself) pass `ServerConfig.isInScope` against the
configured base. Because no blocked same-origin request was observed in this
run (or the earlier one), the parser change described below is preventive
hardening, not a proven fix for the earlier blank reader.

## What was captured

`artifacts/logs/73-reader-pages.json` (page by page, written by the same script
that drove the app's WebView):

| page | scrollX (of 65,960 px column layout) | first visible text |
| ---- | --- | --- |
| 0 | 4800 | "Gentlemen of the Anaheim Lions Club," the man at the microphone said … |
| 1 | 5400 | "I don't look like you," he said. "I can't afford to. My life depends on it." … |
| 2 | 6000 | "You blew it," his superior the prompter said. "See me in your office …" |
| 3 | 6600 | What am I actually? he asked himself. He wished, momentarily, for his scramble suit … |
| 4 | 7200 | He had, naturally, several other leads at present besides Donna … |
| 5 | 7800 | "Can I bring the wolf book and show you? It's by Konrad Lorenz …" |

Reader state: URL
`http://192.168.88.89:8090/#book_id=1&fmt=MOBI&library_id=library&mode=read_book`,
title `A scanner darkly`, content iframe `textLen = 480,953`, `scrollWidth =
65,960`, `clientWidth = 1,160`, percentage readout `5%`. Each `ArrowRight` in the
reader moved the column layout forward by one page and changed the visible text.

`artifacts/logs/71-reader-verify.json` records the same run in one shot: library
loaded (`1,298 books`, `Continue reading` shelf), reader route opened,
480,953 characters in the content iframe, one `about:srcdoc` content frame.

## Lifecycle

`artifacts/logs/74-lifecycle.txt`:

* Before `KEYCODE_HOME`: `ResumedActivity: MainActivity`.
* After `KEYCODE_HOME`: `mResumed=false mStopped=true`.
* Screen off (`KEYCODE_POWER`) and on again: the app is not resumed because the
  device is on a secure keyguard (`isStatusBarKeyguard=true`).
* After that pause/stop cycle the WebView still holds the reader: same URL,
  `title "A scanner darkly"`, `5%`, 480,953 characters, `scrollX 7800` — the
  page that had been turned to. Nothing was reloaded or lost.

`artifacts/logs/75-app-logcat.txt` (full buffer for the run): one
`I/CalibReader: loading http://192.168.88.89:8090/app/`, no `FATAL`, no
`AndroidRuntime`, no `out-of-scope` and no `HTTP 403` lines.

## Blocker: the tablet is PIN-locked

`artifacts/screenshots/74-device-keyguard.png` is the device screen during this
run. The tablet is on a secure lock screen (Fire OS PIN keypad,
`dumpsys window policy` → `secure=true`, `wm dismiss-keyguard` refused). No PIN
is stored in this repository and I did not guess one, so:

* `adb shell screencap` can only show the key guard, never the app;
* `adb shell input tap/swipe` goes to the key guard, so the UI could not be
  driven by touch;
* `Page.captureScreenshot` over CDP never returns while the WebView is not
  visible, so no WebView screenshot of the book could be produced;
* the activity cannot be resumed over the key guard (`am start` leaves
  `mResumed=false mStopped=true`), so the resume half of the lifecycle check is
  documented but not visually verified.

With the screen unlocked all four items become available; the CDP commands used
here (already in `/tmp/reader_pages.py` during the run) work unchanged on an
unlocked device and would then also produce PNG captures.

## Commands

```sh
./gradlew testDebugUnitTest assembleDebug --console=plain     # BUILD SUCCESSFUL
adb -s G001KT0511960U8G install -r app/build/outputs/apk/debug/app-debug.apk
adb -s G001KT0511960U8G shell am force-stop io.voidlab.calibreader.debug
adb -s G001KT0511960U8G shell am start -n io.voidlab.calibreader.debug/io.voidlab.calibreader.MainActivity \
    -e server_url http://192.168.88.89:8090/app/
adb -s G001KT0511960U8G forward tcp:9222 localabstract:webview_devtools_remote_$(adb -s G001KT0511960U8G shell pidof io.voidlab.calibreader.debug | tr -d '\r')
python3 /tmp/reader_pages.py artifacts/logs/73-reader-pages.json
```

## Code change made

`ServerConfig.isInScope` used to parse URLs with `java.net.URI`. That parser
rejects characters a browser sends happily (a raw space, `|`, `^`, `{`, `}` in a
query or path), so such a same-origin URL was treated as foreign: subresources
were answered with `HTTP 403` by `shouldInterceptRequest` and navigations were
pushed to the system browser instead of the WebView — another way for the reader
to look blank. The scope check now parses scheme + authority itself and requires
an exact scheme + host + port match (`\` ends the authority like Chromium does,
userinfo is dropped), so an `http` URL is no longer treated as the same origin as
an `https` base on the same explicit port.
`app/src/test/java/io/voidlab/calibreader/ServerConfigTest.java` pins the
behaviour with the real calibre URLs above plus the negative cases
(`http://host:port@evil.example/`, `http://evil.example\@host:port/`, other
ports, other schemes, `javascript:`, `data:`, `blob:`, `about:`, and
`http`/`https` on the same explicit port in both directions).

## Final acceptance run (13:12-13:16)

```sh
$ ./gradlew testDebugUnitTest assembleDebug --console=plain
BUILD SUCCESSFUL in 4s
# Test-io.voidlab.calibreader.ServerConfigTest.xml: tests=14 failures=0 errors=0

$ adb -s G001KT0511960U8G install -r app/build/outputs/apk/debug/app-debug.apk
Success

$ adb -s G001KT0511960U8G shell am force-stop io.voidlab.calibreader.debug
$ adb -s G001KT0511960U8G shell am start \
    -n io.voidlab.calibreader.debug/io.voidlab.calibreader.MainActivity \
    -e server_url http://192.168.88.89:8090/app/
Starting: Intent { cmp=io.voidlab.calibreader.debug/io.voidlab.calibreader.MainActivity (has extras) }

$ adb -s G001KT0511960U8G forward tcp:9222 localabstract:webview_devtools_remote_<pid>
$ curl -s http://127.0.0.1:9222/json
http://192.168.88.89:8090/app/ | library · Calibre Zen
```

Then the reader route was opened in the app's WebView and paged
(`artifacts/logs/76-final-reader.json`):

| page | reader chrome | scrollX | first visible text |
| ---- | ------------- | ------- | ------------------ |
| 0 | `Loading next section from A scanner darkly, please wait…` | 0 (layout pending) | front matter (column layout not built yet) |
| 1 | `5%` | 3000 | "At the corner people had halted for the sign to say WALK …" |
| 2 | `5%` | 3600 | "Once a guy stood all day shaking bugs from his hair …" |
| 3 | `5%` | 4200 | "I knew a guy who hot-wired one of those new Porsches …" |
| 4 | `5%` | 4800 | "Gentlemen of the Anaheim Lions Club …" |
| 5 | `5%` | 5400 | "'I don't look like you,' he said …" |

Page 0 is the state the earlier run called "blank": the reader chrome says
`Loading next section from A scanner darkly, please wait…` and the content column
layout is still `scrollWidth = 1160` (one screen wide) because the section has not
been laid out yet. One page later the layout is `65,960 px` wide and the novel
text is on screen.

`artifacts/logs/76-final-app-logcat.txt`: `FATAL=0`, `AndroidRuntime=0`,
`out-of-scope/blocked navigation=0`, single `I/CalibReader: loading
http://192.168.88.89:8090/app/`.
