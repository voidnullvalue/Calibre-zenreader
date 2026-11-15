# Target-server verification (2026-11-14)

Target: `http://192.168.88.89:8090/app/`
Device: Kindle Fire `KFTRWI`, Android 9 / API 28, wlan0 `192.168.88.156/24`
App: `io.voidlab.calibreader.debug` (debug APK)

## Host-side reachability

```sh
$ curl --max-time 8 -sS -D - http://192.168.88.89:8090/app/ -o /tmp/target_app.html
HTTP/1.1 200 OK
Content-Type: text/html; charset=UTF-8
Server: calibre 9.18.0
```

## Device reachability

The tablet has no curl/wget; `ping` to 192.168.88.89 succeeds. HTTP was verified
on-device by the app probe (see diagnostics below) and by a CDP `HEAD /app/`.

## Build and install

```sh
$ ./gradlew assembleDebug --console=plain
BUILD SUCCESSFUL in 5s

$ adb uninstall io.voidlab.calibreader.debug
Success
$ adb install app/build/outputs/apk/debug/app-debug.apk
Success

$ adb shell am start -W -n io.voidlab.calibreader.debug/io.voidlab.calibreader.MainActivity
Status: ok
```

The fresh install had no saved server, so `BuildConfig.BUILT_IN_SERVER`
(`http://192.168.88.89:8090/app/`) was normalised, saved and loaded.

logcat:

```
I/CalibReader: using built-in default server: http://192.168.88.89:8090/app/
I/CalibReader: loading http://192.168.88.89:8090/app/
```

## Final acceptance run

```sh
$ ./gradlew assembleDebug --console=plain
BUILD SUCCESSFUL in 3s

$ adb install -r app/build/outputs/apk/debug/app-debug.apk
Success

$ adb shell am force-stop io.voidlab.calibreader.debug
$ adb shell am start -W -n io.voidlab.calibreader.debug/io.voidlab.calibreader.MainActivity \
    -e server_url http://192.168.88.89:8090/app/
Status: ok

I/CalibReader: loading http://192.168.88.89:8090/app/
```

Final screenshot: `artifacts/screenshots/29-final-exact-url.png` (live library,
1,298 books). Final CDP check: `artifacts/logs/30-final-cdp.json` reports
`http://192.168.88.89:8090/app/`, `library · Calibre Zen`, `ready: complete`.
The force-stop was only needed because the previous run had been left in the
background reader/recents state; a launch extra always opens the exact base
(instead of the remembered last page).

## Origin check from the running WebView

CDP (`tools/webview_capture.py`, port 9222 forwarded):

```json
"url": "http://192.168.88.89:8090/app/",
"title": "library · Calibre Zen",
"nav": ["http://192.168.88.89:8090/app/"]
```

Device-side synchronous `HEAD /app/` inside the page:

```json
{"status":200,"server":"calibre 9.18.0","contentType":"text/html; charset=UTF-8",
 "responseURL":"http://192.168.88.89:8090/app/","origin":"http://192.168.88.89:8090"}
```

This is the same origin as the requested target, not another host or port.

## On-device diagnostics (menu → Server diagnostics)

```
Server: http://192.168.88.89:8090/app
Scheme: http (cleartext on the local network)

OK    200  http://192.168.88.89:8090/app/
      26 ms  ·  server: calibre 9.18.0  ·  text/html; charset=UTF-8

OK    200  http://192.168.88.89:8090/opds
      98 ms  ·  server: calibre 9.18.0  ·  application/atom+xml; charset=UTF-8

OK    200  http://192.168.88.89:8090/manifest.json
      10 ms  ·  server: calibre 9.18.0  ·  application/manifest+json; charset=UTF-8

OK    200  http://192.168.88.89:8090/
      120 ms  ·  server: calibre 9.18.0  ·  text/html; charset=UTF-8
```

## Functional observations

* Fresh launch: live Calibre Zen library, "1,298 books", covers rendered.
* Book detail opened at `/app/?book=1298` ("Blood Test"); "Read" opened
  calibre's reader route `/#book_id=1298&...&mode=read_book`; the reader body
  was blank apart from controls (not root-caused; see README Known limits). The
  later long first-layout measured for "A Scanner Darkly" is evidence for that
  book only and does not by itself explain this blank.
* Back navigation returned to the library; "Continue reading" shelf appeared.
* No FATAL/AndroidRuntime crash and no out-of-scope request blocks in logcat.

## Evidence files

* `artifacts/screenshots/20-target-app-firstrun.png` — fresh install, live /app/
* `artifacts/screenshots/21-target-webview-cdp.png` — CDP WebView capture
* `artifacts/screenshots/23-target-diagnostics.png` — on-device diagnostics
* `artifacts/screenshots/25-target-book-open.png` — book detail
* `artifacts/screenshots/26-target-reader.png`,
  `27-target-reader-wait.png` — reader blank-body observation
* `artifacts/screenshots/28-target-back-to-library.png` — back to library
* `artifacts/screenshots/29-final-exact-url.png` — final exact-URL launch
* `artifacts/screenshots/30-final-cdp.png` — final CDP WebView capture
* `artifacts/screenshots/31-final-library.png` — library left on screen
* `artifacts/logs/20-target-launch.log` — fresh launch logcat
* `artifacts/logs/29-final-launch.log` — final acceptance launch logcat
* `artifacts/logs/30-final-cdp.json` — final CDP URL/title/DOM
* `artifacts/logs/21-target-webview-cdp.json` — CDP URL/title/DOM
* `artifacts/logs/22-target-origin-verify.json` — device HEAD origin check
* `artifacts/logs/24-target-relaunch-extra.log` — exact-URL launch extra
* `artifacts/logs/25-target-book-open.json`,
  `26-target-reader.json` — book/reader CDP state
* `artifacts/logs/27-target-reader.log` — reader logcat
