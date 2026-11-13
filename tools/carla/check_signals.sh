#!/usr/bin/env bash
# Stage-1 check: the app records its signal stream (SignalRecordTest) while the bridge drives CARLA, then
# compare_signals.py checks the app saw what the bridge sent. Docker Desktop and the Pleos emulator must be running.
#   ./check_signals.sh [drive seconds, default 60] [bridge.py options, e.g. --driver]
# Leaves signals/bridge.csv, signals/app.csv, signals/instrument.log, signals/logcat.txt (the device log buffer
# rotates within minutes under the SDK's debug logging). Exit code is compare_signals.py's.
set -euo pipefail
cd "$(dirname "$0")"
DRIVE_S="${1:-60}"
PKG=com.kyusun.driveteacher
app_shell() { adb shell run-as "$PKG" --user 10 "$@"; }

mkdir -p signals
(cd ../.. && ./gradlew -q :app:installDebug :app:installDebugAndroidTest)
app_shell rm -f cache/signals.csv cache/signals.stop
adb logcat -c
adb logcat -v time >signals/logcat.txt 2>&1 &  # $! is adb itself, so kill stops it
logcat=$!
disown "$logcat"  # killed at the end; no 'Terminated' noise
# The bridge runs slower than real time under load (~2x), plus CARLA startup: give the recorder plenty.
adb shell am instrument -w -e class "$PKG.signal.SignalRecordTest" -e maxSeconds $((DRIVE_S * 4 + 600)) \
  "$PKG.test/androidx.test.runner.AndroidJUnitRunner" >signals/instrument.log 2>&1 &
recorder=$!
trap 'app_shell touch cache/signals.stop >/dev/null 2>&1 || true; kill $logcat 2>/dev/null' EXIT  # never leave the recorder running

# Start the bridge only once the app has every signal's first value: under load the SDK setup takes many seconds,
# and the bridge must not race it (compare_signals.py checks the first values came before the bridge's).
deadline=$((SECONDS + 90))
for kind in speed gear brake turn steering belt; do
  until app_shell grep -q ",$kind," cache/signals.csv 2>/dev/null; do
    kill -0 "$recorder" 2>/dev/null || { cat signals/instrument.log; exit 1; }
    ((SECONDS < deadline)) || { echo "FAIL the app recorded no $kind within 90 s of starting"; exit 1; }
    sleep 1
  done
done
./run.sh --seconds "$DRIVE_S" --log signals/bridge.csv "${@:2}"
sleep 2  # let the last updates reach the app
app_shell touch cache/signals.stop
wait "$recorder"
grep -q 'OK (1 test)' signals/instrument.log || { cat signals/instrument.log; exit 1; }
adb exec-out run-as "$PKG" --user 10 cat cache/signals.csv >signals/app.csv
trap - EXIT
kill $logcat 2>/dev/null
app_shell rm -f cache/signals.stop
python3 compare_signals.py signals/bridge.csv signals/app.csv
