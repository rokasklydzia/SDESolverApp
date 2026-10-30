#!/usr/bin/env bash
# Runs the screenshot tour on a booted emulator and pulls the raw files into build/screenshots/raw.
set -euo pipefail

package=com.sciencio.sdesolver
raw=build/screenshots/raw

adb install -r androidApp/build/outputs/apk/debug/*.apk
adb install -r androidApp/build/outputs/apk/androidTest/debug/*.apk

# Dark theme, and a clean status bar (fixed clock, full battery, no notifications).
adb shell cmd uimode night yes
sleep 5
adb shell settings put global sysui_demo_allowed 1
adb shell settings put global sysui_tuner_demo_on 1
demo() { adb shell am broadcast -a com.android.systemui.demo -e command "$@" > /dev/null; }
demo enter
demo clock -e hhmm 1200
demo battery -e level 100 -e plugged false
demo network -e wifi show -e level 4 -e mobile show -e datatype none -e level 4
demo notifications -e visible false

# Under load the emulator's launcher can stop responding, and its "isn't responding" dialog
# would cover the app. Don't show such dialogs.
adb shell settings put global hide_error_dialogs 1

# The emulator's speed varies between runners, so give the tour up to three attempts.
for attempt in 1 2 3; do
  adb shell am force-stop "$package"
  result=$(adb shell am instrument -w -e class app.sde.ScreenshotTour "$package.test/androidx.test.runner.AndroidJUnitRunner")
  echo "Attempt $attempt:"
  echo "$result"
  if grep -q "^OK (1 test)" <<< "$result"; then break; fi
  # What UI Automator saw when it gave up, if the tour saved it.
  adb shell cat "/sdcard/Android/data/$package/files/screenshots/hierarchy.xml" 2> /dev/null | tr '>' '\n' | grep -E 'scrollable="true"|text="[^"]+"' || true
  if [ "$attempt" = 3 ]; then exit 1; fi
done

mkdir -p "$raw"
adb pull "/sdcard/Android/data/$package/files/screenshots/." "$raw"
adb pull /data/local/tmp/simulate.mp4 "$raw/simulate.mp4"
ls -l "$raw"
