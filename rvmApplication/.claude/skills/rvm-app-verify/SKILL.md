---
name: rvm-app-verify
description: Verify RVM app changes on a real device with adb -- driving the UI from the shell, reading per-stage logs, and confirming downloads and matting runs actually worked. Use when testing a change end-to-end, reproducing a crash, or measuring per-frame timing rather than assuming a build success means it works.
---

# RVM app: on-device verification

This app's important behaviour (GPU delegate, ~100 MB downloads, multi-minute
matting runs) cannot be checked by a green build. Everything below is
`adb`-driven so it can run without touching the phone.

## Logging tags

```bash
adb logcat -c                                  # clear first
adb logcat -d | grep -E "MatteModule|TFLiteModelRunner|ModelRepository"
```

| Tag | Covers |
|---|---|
| `Controller` | per-frame timing of the matte/foreground/composite pass |
| `MatteModule` | matting config, RVM inference timing, hidden-state reset |
| `TFLiteModelRunner` | delegate setup, **delegate-rejection CPU fallback**, inference timing |
| `ModelRepository` | bootstrap enqueueing, install/failure, full failure stack traces |
| `ModelDownloader` | download start (URL, expected bytes), completion (elapsed, MB/s) |
| `VideoFrameDecoder` / `VideoFrameEncoder` | decode/encode per frame |

> `adb logcat -s TAG:D` has been observed returning nothing on this setup even
> when the lines exist. `adb logcat -d | grep` is reliable -- prefer it.

## Driving the UI from the shell

Never guess tap coordinates. Dump the tree and compute the centre of the node:

```bash
adb shell uiautomator dump >/dev/null 2>&1
adb shell cat /sdcard/window_dump.xml | grep -oE 'text="[^"]*"' | grep -v '=""'
```

```bash
B=$(adb shell cat /sdcard/window_dump.xml | python3 -c "
import sys,re;x=sys.stdin.read()
m=re.search(r'text=\"Import\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"',x)
l,t,r,b=map(int,m.groups());print((l+r)//2,(t+b)//2)")
adb shell input tap $B
```

**Re-dump after every interaction.** Acting on a stale dump silently taps the
wrong place and looks like the feature is broken -- this has wasted real time.

Model cards expose only `content-desc` (they use `clearAndSetSemantics` to read
as one unit), e.g. `"MobileNetV3, 720 × 1280 · downsample Auto · gpu source, Downloaded"`
-- match on that, not on `text=`.

Screenshots for anything visual (colour, wrapping, contrast):

```bash
adb shell screencap -p /sdcard/s.png && adb pull /sdcard/s.png ./s.png
```

Two dialogs commonly block a fresh install: the lock screen (`input keyevent
KEYCODE_WAKEUP` then swipe up) and a "This app isn't 16 KB-compatible" platform
warning about TFLite's `.so` alignment -- pre-existing and unrelated; dismiss it.

## Waiting for long operations

Poll a condition; do not chain sleeps.

```bash
until adb shell run-as dev.hamster.rvm ls files/models 2>/dev/null | grep -q "ds_auto.tflite$"; do sleep 4; done
until adb shell "uiautomator dump /sdcard/w.xml >/dev/null 2>&1; cat /sdcard/w.xml" | grep -q "Done in"; do sleep 5; done
```

## What to actually assert

- **Downloads**: file present at its **exact manifest byte size**, and **no
  `.part` left behind** (`adb shell run-as dev.hamster.rvm ls -la files/models`).
- **Model load**: `MatteModule: Model:` names the file you expected, and
  `MatteModule: Device:` the device you expected -- device can be coerced to CPU
  (see `rvm-app-models`).
- **Matting run**: the completion strip reads `Done in Xs · Y ms/frame avg`.
  Healthy is roughly **320 ms/frame** (mobilenetv3) to **430 ms/frame**
  (resnet50) at 720x1280 `ds auto`. A large jump means a per-frame regression --
  compare the per-stage `Controller`/`VideoFrameEncoder` timings to find the gap.
- **Crashes**: `adb logcat -d | grep -c "FATAL EXCEPTION"` should be `0`.

## Testing the offline path

**Airplane mode is not enough** -- Samsung keeps Wi-Fi enabled across it, and a
"offline" test will happily download. Disable the radios explicitly:

```bash
adb shell svc wifi disable && adb shell svc data disable
adb shell dumpsys connectivity | grep -m1 "Active default network"   # expect: none
```

Re-enable with `svc wifi enable` / `svc data enable`.

## Fresh-install testing

`adb uninstall dev.hamster.rvm` wipes `filesDir`, so models re-download -- the
only way to genuinely exercise bootstrap. Note a release-signed APK cannot be
installed over a debug one; uninstall between them.
