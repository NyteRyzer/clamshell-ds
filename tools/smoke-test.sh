#!/usr/bin/env bash
# Installs the APK on a running Android emulator and checks that the menu and a game
# start without crashing, with a simulated second screen like the RG DS / AYN Thor.
APK="$1"
PKG=com.clamshellds
ROM=/sdcard/ClamshellDS/roms/test.nds
fail=0

alive() { adb shell pidof $PKG >/dev/null 2>&1; }
crashes() { adb logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime|F DEBUG|F libc|Fatal signal|ClamshellDS" | tail -80; }

adb install -r -g "$APK" || exit 1
adb shell appops set $PKG MANAGE_EXTERNAL_STORAGE allow
adb shell settings put global overlay_display_devices 640x480/160
sleep 5
echo "== Displays"; adb shell dumpsys display | grep -E "^  Display [0-9]|mDisplayId=|DisplayDeviceInfo\{" | head -10

python3 tools/make_test_rom.py /tmp/test.nds
adb shell mkdir -p /sdcard/ClamshellDS/roms
adb push /tmp/test.nds $ROM
# Games scattered in other folders, like a real SD card: all of them must be found.
adb shell mkdir -p "/sdcard/My Games/DS" /sdcard/Roms/NDS/Favorites
adb push /tmp/test.nds "/sdcard/My Games/DS/second.nds"
adb push /tmp/test.nds /sdcard/Roms/NDS/Favorites/third.NDS

step() {  # name, seconds, am-args...
  local name="$1" secs="$2"; shift 2
  adb logcat -c
  echo "== $name"
  adb shell am start -W "$@"
  sleep "$secs"
  if alive; then echo "RESULT $name: OK (still running)"
  else echo "RESULT $name: CRASHED"; fail=1; fi
  adb shell dumpsys activity activities | grep -E "topResumedActivity|mResumedActivity" | head -2
  crashes
}

step "launcher" 10 -n $PKG/.LauncherActivity
scan=$(adb logcat -d -s ClamshellDS:I | grep "Library scan" | tail -1)
echo "$scan"
if echo "$scan" | grep -q "Library scan: 3 games"; then echo "RESULT library: OK (all 3 games found)"
else echo "RESULT library: FAILED (expected 3 games)"; fail=1; fi
step "game (JIT off)" 15 -n $PKG/.EmulatorActivity --es rom $ROM --ez jit false
adb shell am force-stop $PKG
step "game (JIT on)" 15 -n $PKG/.EmulatorActivity --es rom $ROM --ez jit true
adb shell am force-stop $PKG
step "launcher after game" 8 -n $PKG/.LauncherActivity

exit $fail
