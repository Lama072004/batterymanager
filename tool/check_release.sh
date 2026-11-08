#!/usr/bin/env bash
# Prüft den Release-Build gegen den Akzeptanzvertrag (AC-001, AC-002, AC-003, AC-075).
# Voraussetzung: flutter build apk --release --split-per-abi
set -u
cd "$(dirname "$0")/.."
fail=0
ok()  { echo "  OK   $*"; }
bad() { echo "  FAIL $*"; fail=1; }

MANIFEST=$(ls build/app/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml \
               build/app/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml 2>/dev/null | head -1)
APK=$(ls build/app/outputs/flutter-apk/app-arm64-v8a-release.apk build/app/outputs/flutter-apk/app-release.apk 2>/dev/null | head -1)

echo "Merged Manifest: ${MANIFEST:-nicht gefunden}"
if [ -n "$MANIFEST" ]; then
  grep -q 'package="de.mlampert.batterymanager"' "$MANIFEST" && ok "applicationId de.mlampert.batterymanager" || bad "applicationId"
  for p in INTERNET WAKE_LOCK SCHEDULE_EXACT_ALARM USE_EXACT_ALARM; do
    grep -q "android.permission.$p\"" "$MANIFEST" && bad "Berechtigung $p im Release" || ok "keine $p"
  done
  grep -q 'android:allowBackup="false"' "$MANIFEST" && ok "allowBackup=false" || bad "allowBackup"
  grep -q 'android:debuggable="true"' "$MANIFEST" && bad "debuggable" || ok "nicht debuggable"
  grep -q 'android:minSdkVersion="24"' "$MANIFEST" && ok "minSdk 24" || bad "minSdk"
  grep -q 'android:targetSdkVersion="36"' "$MANIFEST" && ok "targetSdk 36" || bad "targetSdk"
else
  bad "Merged Manifest fehlt – zuerst Release bauen"
fi

grep -q 'cupertino_icons' pubspec.yaml && bad "cupertino_icons in pubspec" || ok "kein cupertino_icons"

echo "APK: ${APK:-nicht gefunden}"
if [ -n "$APK" ]; then
  SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-D:/Android/Sdk}}"
  APKSIGNER=$(ls "$SDK"/build-tools/*/apksigner* 2>/dev/null | sort | tail -1)
  if [ -n "$APKSIGNER" ]; then
    CERT=$("$APKSIGNER" verify --print-certs "$APK" 2>/dev/null | grep -m1 'Signer #1 certificate DN')
    echo "  Zertifikat: $CERT"
    echo "$CERT" | grep -q 'CN=Android Debug' && bad "mit DEBUG-Schlüssel signiert (android/key.properties anlegen)" || ok "eigener Release-Schlüssel"
  else
    bad "apksigner nicht gefunden"
  fi
fi

exit $fail
