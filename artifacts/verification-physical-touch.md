# Physical verification — unlocked, touch-driven (2026-11-14 13:51–14:08)

Target: `http://192.168.88.89:8090/app/` (`Server: calibre 9.18.0`, calibre-zen
0.5.0), single server address, unchanged for this run.
Device: Kindle Fire `KFTRWI`, Android 9 / API 28, serial `G001KT0511960U8G`,
wlan0 `192.168.88.156/24`, 1200x1920 at 240 dpi with a 180 dpi override,
WebView 120.0.6099.266 (Amazon WebView).
App: `io.voidlab.calibreader.debug` 0.1.0-debug, APK
`app/build/outputs/apk/debug/app-debug.apk` (md5 `5afc33c0bf23a1b6c339bb9385233107`).

This run finishes the physical check that the earlier run had to do over CDP
because the tablet was PIN-locked (`verification-reader.md`). For most of this
run the tablet was unlocked: the app was driven with `adb shell input tap` /
`input keyevent` and every step was captured with `adb shell screencap` (device
framebuffer, not CDP).

Scope for this run, as stated by the project owner: verify the known-good book
on the physical tablet — readable text, touch page turns, back and lifecycle,
with screenshots and logcat as evidence. Explicitly **excluded**: e-ink
optimisations, multiple server profiles, TTS, biometric lock, and any
investigation/optimisation of the server-side loading delay. No server, library
or device setting was changed.

## 1. Session resumed on a live reader (13:51)

The app had been left on the reader. After `am force-stop` + `am start` with no
launch extra it restored the last in-scope page, i.e. the reader route
`/#book_id=1&bookpos=epubcfi(/4/2/4/6[filepos2459]/94/1:212)&fmt=MOBI&library_id=library&mode=read_book`,
title `A scanner darkly`, and showed novel text within 12 s
(`110-current-state.png`, `120-reader-state-restored.json`: 4%, content iframe
480,945 characters, `scrollX 3204` of a 92,876 px column layout; the same
restored reader is in `111-fresh-launch-library.png`).

Touch, right-hand tap zone at `(1080, 900)`:

| step | evidence | reader state |
| ---- | -------- | ------------ |
| tap forward | `112-touch-page-forward.png`, `121-touch-page-forward.json` | 5%, `scrollX 4272` (+1068 = one column), different text |
| tap back at `(120, 900)` | `113-touch-page-back.png`, `122-touch-page-back.json` | 4%, `scrollX 3204` (same page as before) |
| 3 more forward taps | `114-touch-page-forward-3x.png`, `123-touch-three-more-pages.json` | 7%, `scrollX 6408`, different text |

So a single tap on the tablet's right edge turns one page forward and a tap on
the left edge turns one page back, on the device framebuffer.

## 2. Back from a cold-restored reader leaves the app (13:54)

`KEYCODE_BACK` while the reader was the *only* entry in the WebView history (the
activity had been started directly on the restored reader URL) ran the
`onBackPressed` fallback `super.onBackPressed()`: the activity finished and the
Fire launcher appeared (`115-back-to-library.png`). Nothing was lost - the
reader page stays saved and is restored on the next launch - but there was no
library page to go back to in that session. Back from a reader that was opened
from the library is covered in section 4 below.

## 3. Fresh app data, library and reader reached by touch (13:55–14:03)

`adb shell pm clear io.voidlab.calibreader.debug` (app-private state only; the
baked single-server default is untouched) then `am start` with no extra:

| step | evidence |
| ---- | -------- |
| library from the baked default `http://192.168.88.89:8090/app/`, "1,298 books" | `116-library-firstrun.png` |
| tap search field, `input text scanner` | `117-library-search-scanner.png` |
| 1 of 1,298 books: "A scanner darkly", Philip K. Dick | `118-library-search-results.png` |
| tap cover -> book detail panel with `Read` and `MOBI 541 KB` | `119-book-detail.png` |
| tap `Read` -> reader route, "Book id #1", page not painted yet at +4 s | `120-reader-opening.png` |
| first painted page, the scanned title-page image, 1% | `121-reader-titlepage-image.png`, `130-reader-frontmatter-state.json` |
| next tap -> front matter "Novels by Philip K. Dick", 1%, column layout 92,876 px | `122-reader-frontmatter-text.png` |
| tap forward -> title page, 2%, `scrollX 1068` | `123-touch-page-forward.png`, `131-touch-turn-forward-1.json` |
| 6 more forward taps -> chapter two text, 9%, `scrollX 7476` | `124-touch-page-forward-7x.png`, `132-touch-turn-forward-7.json` |
| tap back -> previous page, 7%, `scrollX 6408` | `125-touch-page-back.png`, `133-touch-page-back.json` |

The two page-turn screenshots show different, readable novel text at the same
viewport: 9% is the Orange County Lions Club scene ("Gentlemen of the Anaheim
Lions Club" ...), 7% is the Jerry Fabin passage ("I'm tired" ...). The
percentage readout and the column offset move together with each touch.

App-private state after the run (`run-as ... cat shared_prefs/calibreader.xml`)
still holds exactly one server address and the last page - no profile list:

```xml
<string name="server_base_url">http://192.168.88.89:8090/app</string>
<string name="last_page_url">http://192.168.88.89:8090/#book_id=1&amp;bookpos=epubcfi(/4/2/4/6[filepos2459]/320/1:74)&amp;fmt=MOBI&amp;library_id=library&amp;mode=read_book</string>
```

**Timing note (not investigated, per instruction).** After `pm clear` the
reader page stayed unpainted ("Book id #1") at +4 s and the content iframe
reported no text at +180 s; the first painted page appeared about four minutes
after tapping `Read`. Only then did one tap reveal the text section. Wall-clock
timing was recorded but the server/web-app loading delay was deliberately not
analysed. With the page already in the WebView, opening from the book detail
reached 7% / `scrollX 6408` / 480,945 characters within 20 s
(`127-reader-reopened.png`, `134-reader-reopened.json`), although at that
20 s mark the visible frame still showed only the reader's control hints.

## 4. Back returns to the library (14:03)

`KEYCODE_BACK` from the reader opened in section 3 restored the library with the
"A scanner darkly" detail panel still open, and the sidebar had gained a
"Continue reading -> A scanner darkly" entry (`126-android-back.png`). The
activity stayed resumed. This is the normal library -> book -> reader -> back
path and it works by touch/key.

## 5. Lifecycle (14:04–14:06)

| step | evidence | result |
| ---- | -------- | ------ |
| `KEYCODE_HOME` | `128-home.png`, `137-session-logcat.txt` | app `mResumed=false mStopped=true`, Fire launcher resumed |
| `am start` activity again | `129-relaunch-resumed.png`, `135-relaunch-state.json` | "Activity not started, its current task has been brought to the front": same task, reader still at 7%, `scrollX 6408`, 480,945 characters, same `bookpos` CFI |
| `KEYCODE_POWER` (screen off) | `130-screen-off.png` | `mWakefulness=Asleep` |
| `KEYCODE_POWER` (screen on) | `131-screen-on.png` | Fire OS lock screen; `isStatusBarKeyguard=true` |
| swipe up on the lock screen | `132-after-unlock.png` | Fire OS PIN keypad |

The activity was not destroyed by any of this: `dumpsys activity
io.voidlab.calibreader.debug` reports `mResumed=false mStopped=true
mFinished=false`, `mCurState=3`, `mDestroyed=false` (`139-lifecycle-dumpsys.txt`),
and CDP against the still-running WebView (pid 32432) reported the reader route,
title `A scanner darkly`, 7%, `scrollX 6408`, 480,945 characters
(`136-state-under-keyguard.json`) while the lock screen was up. So the reader
page and its position survive background, screen off/on and the lock screen;
only the visual confirmation after unlocking is missing (see blocker).

## 6. Logcat

`137-session-logcat.txt` is the full buffer for 13:56:17–14:08:34 (3,002 lines)
with `138-logcat-summary.txt`: 0 `FATAL EXCEPTION`, 0 `ANR in
io.voidlab.calibreader`, 0 `out-of-scope`, 0 `blocked navigation`, 0 `HTTP 403`
from the app. The only app lines are:

```
I CalibReader: using built-in default server: http://192.168.88.89:8090/app/
I CalibReader: loading http://192.168.88.89:8090/app/
```

No request outside the configured scheme+host+port was attempted or blocked
while browsing the library and reading the book.

## Blocker: the tablet locks with a PIN

Turning the screen off re-locks the tablet behind a Fire OS PIN keypad
(`132-after-unlock.png`); `wm dismiss-keyguard` is refused and no PIN exists in
this repository, so the device cannot be unlocked from automation. Consequences:

* the reader could not be photographed *after* the screen-off/on + keyguard
  cycle; its state is verified over CDP instead (`136-state-under-keyguard.json`);
* the tablet is left on the lock screen - enter the PIN to get back to the
  restored reader at 7%;
* no attempt was made to disable the lock screen or change any device setting.

## Exclusions (per project owner)

Not done, not attempted, and not required for acceptance:

* e-ink / refresh-mode optimisations;
* multiple server profiles - the app keeps the single configured server
  (baked default `http://192.168.88.89:8090/app/`, unchanged);
* TTS / accessibility read-aloud;
* biometric (or any other) app lock;
* investigation or optimisation of the server-side / web-app loading delay. It
  was observed and timed only, as recorded in section 3.

## Acceptance commands

```sh
./gradlew testDebugUnitTest assembleDebug --console=plain    # BUILD SUCCESSFUL, 14/14 tests
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm clear io.voidlab.calibreader.debug
adb shell am start -n io.voidlab.calibreader.debug/io.voidlab.calibreader.MainActivity
adb shell input tap 560 126 && adb shell input text scanner    # library search
adb shell input tap 414 365                                    # book cover
adb shell input tap 778 555                                    # Read
adb shell input tap 1080 1200                                  # page forward
adb shell input tap 120 1200                                   # page back
adb shell input keyevent KEYCODE_BACK                          # back to library
adb shell screencap -p /sdcard/s.png && adb pull /sdcard/s.png artifacts/screenshots/<step>.png
adb logcat -d > artifacts/logs/137-session-logcat.txt
```
