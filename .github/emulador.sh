#!/bin/bash
# Roda dentro do emulador: instala, testa e junta prints, relatório e log.
mkdir -p out
adb shell settings put system screen_off_timeout 600000 || true
adb logcat -c || true
gradle -p android connectedDebugAndroidTest --no-daemon --stacktrace > out/gradle-testes.txt 2>&1
STATUS=$?
adb pull /sdcard/Download/ne out/prints || true
adb logcat -d -v time > out/logcat.txt || true
grep -E "NaoEsquece|AndroidRuntime|FATAL" out/logcat.txt > out/logcat-app.txt || true
cp -r android/app/build/reports/androidTests out/relatorio 2>/dev/null || true
cp -r android/app/build/outputs/androidTest-results out/resultados 2>/dev/null || true
tail -40 out/gradle-testes.txt
exit $STATUS
