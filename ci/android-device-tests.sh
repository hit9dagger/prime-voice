#!/usr/bin/env bash
set -euo pipefail
adb install -r jirka-test.apk
adb shell settings put secure tts_default_synth com.k2fsa.sherpa.onnx.tts.engine
adb install -r apks/debug/app-x86_64-debug.apk
adb install -r apks/androidTest/debug/app-debug-androidTest.apk
adb logcat -c
trap 'adb logcat -d -v threadtime > emulator-logcat.txt' EXIT
# Download translation data once. OCR and the external TTS APK already bundle their models.
adb shell am instrument -w -r -e class cz.primevoice.ModelPreparationTest cz.primevoice.test/androidx.test.runner.AndroidJUnitRunner > model-preparation.txt
cat model-preparation.txt
grep -q 'OK (1 test)' model-preparation.txt
adb shell svc wifi disable
adb shell svc data disable
adb shell am instrument -w -r -e notClass cz.primevoice.ModelPreparationTest cz.primevoice.test/androidx.test.runner.AndroidJUnitRunner > emulator-results.txt
cat emulator-results.txt
grep -q 'OK (8 tests)' emulator-results.txt
