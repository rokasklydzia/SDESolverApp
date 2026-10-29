#!/usr/bin/env bash
# Runs the screenshot tour on a booted emulator and pulls the raw files into build/screenshots/raw.
set -euo pipefail

package=com.sciencio.sdesolver
raw=build/screenshots/raw

# Dark theme, and a clean status bar (fixed clock, full battery, no notifications).
adb shell cmd uimode night yes
adb shell settings put global sysui_demo_allowed 1
demo() { adb shell am broadcast -a com.android.systemui.demo -e command "$@" > /dev/null; }
demo enter
demo clock -e hhmm 1200
demo battery -e level 100 -e plugged false
demo network -e wifi show -e level 4 -e mobile show -e datatype none -e level 4
demo notifications -e visible false

adb install -r androidApp/build/outputs/apk/debug/*.apk
adb install -r androidApp/build/outputs/apk/androidTest/debug/*.apk

result=$(adb shell am instrument -w -e class app.sde.ScreenshotTour "$package.test/androidx.test.runner.AndroidJUnitRunner")
echo "$result"
grep -q "^OK (1 test)" <<< "$result"

mkdir -p "$raw"
adb pull "/sdcard/Android/data/$package/files/screenshots/." "$raw"
adb pull /data/local/tmp/simulate.mp4 "$raw/simulate.mp4"
ls -l "$raw"
