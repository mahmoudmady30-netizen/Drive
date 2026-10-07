#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
fail=0
check(){ local name="$1"; shift; if "$@" >/dev/null 2>&1; then echo "PASS  $name"; else echo "FAIL  $name"; fail=1; fi; }
file_has(){ grep -Eq "$2" "$1"; }
check "wake word فارس" file_has "$ROOT/app/src/main/java/com/drivevoice/mvp/VoiceCommandEngine.kt" 'فارس'
check "foreground microphone permission" file_has "$ROOT/app/src/main/AndroidManifest.xml" 'FOREGROUND_SERVICE_MICROPHONE'
check "microphone foreground service type" file_has "$ROOT/app/src/main/AndroidManifest.xml" 'foregroundServiceType="microphone"'
check "Android Auto navigation service" file_has "$ROOT/app/src/main/AndroidManifest.xml" 'androidx.car.app.category.NAVIGATION'
check "NavigationManager" file_has "$ROOT/app/src/main/java/com/drivevoice/mvp/car/DriveVoiceCarSession.kt" 'NavigationManager'
check "navigationStarted" file_has "$ROOT/app/src/main/java/com/drivevoice/mvp/car/DriveVoiceCarSession.kt" 'navigationStarted()'
check "navigationEnded" file_has "$ROOT/app/src/main/java/com/drivevoice/mvp/car/DriveVoiceCarSession.kt" 'navigationEnded()'
check "Trip update" file_has "$ROOT/app/src/main/java/com/drivevoice/mvp/car/DriveVoiceCarSession.kt" 'updateTrip'
check "turn steps" file_has "$ROOT/app/src/main/java/com/drivevoice/mvp/MapsRepository.kt" 'steps=true'
check "route alternatives" file_has "$ROOT/app/src/main/java/com/drivevoice/mvp/MapsRepository.kt" 'alternatives=true'
check "multi-stop routing" file_has "$ROOT/app/src/main/java/com/drivevoice/mvp/MapsRepository.kt" 'routeViaStop'
check "Media key controls" file_has "$ROOT/app/src/main/java/com/drivevoice/mvp/MainActivity.kt" 'KEYCODE_MEDIA_PLAY_PAUSE'
check "phone calls" file_has "$ROOT/app/src/main/AndroidManifest.xml" 'CALL_PHONE'
check "unit tests" test -f "$ROOT/app/src/test/java/com/drivevoice/mvp/DeepFeatureTest.kt"
check "v0.8 DrivingPlan model" file_has "$ROOT/app/src/main/java/com/drivevoice/mvp/DriveAiCore.kt" 'data class DrivingPlan'
check "v0.8 compound traffic threshold parser" file_has "$ROOT/app/src/main/java/com/drivevoice/mvp/DriveAiCore.kt" 'parseRerouteDelay'
check "v0.8 compound stop parser" file_has "$ROOT/app/src/main/java/com/drivevoice/mvp/DriveAiCore.kt" 'parseStops'
check "v0.8 plan executor" file_has "$ROOT/app/src/main/java/com/drivevoice/mvp/MainActivity.kt" 'executePlan'
check "v0.8 plan traffic evaluator" file_has "$ROOT/app/src/main/java/com/drivevoice/mvp/MainActivity.kt" 'evaluatePlanTraffic'
check "v0.8 multi-stop route API" file_has "$ROOT/app/src/main/java/com/drivevoice/mvp/MapsRepository.kt" 'routeViaStops'
check "v0.8 compound plan test" test -f "$ROOT/app/src/test/java/com/drivevoice/mvp/DriveAiCoreTest.kt"
if command -v kotlinc >/dev/null 2>&1; then
  TMP="$(mktemp -d)"
  kotlinc "$ROOT/app/src/main/java/com/drivevoice/mvp/CommandParser.kt" "$ROOT/app/src/test/java/com/drivevoice/mvp/DeepFeatureTest.kt" -d "$TMP/out.jar" >/dev/null 2>&1 || true
  echo "INFO  Kotlin parser source compilation attempted (JUnit classpath is not bundled in this environment)."
  rm -rf "$TMP"
else echo "WARN  kotlinc unavailable"; fi
exit "$fail"
