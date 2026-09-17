#!/usr/bin/env bash
# Installs the R8-shrunk ci release build on a running device/emulator and proves the SDK's
# WorkManager drain actually executes: the ci flavor points at an unresolvable host, so a
# "POST ... failed (network)" line can only come from PushSyncWorker running for real.
#
#   cd sample && ./gradlew :app:assembleCiRelease && ci/release-smoke.sh
set -euo pipefail

ADB="${ADB:-adb}"
PKG=com.example.arselsample.ci
APK="${1:-$(dirname "$0")/../app/build/outputs/apk/ci/release/app-ci-release.apk}"
TIMEOUT="${SMOKE_TIMEOUT:-120}"

"$ADB" uninstall "$PKG" >/dev/null 2>&1 || true
"$ADB" install -r "$APK" >/dev/null
"$ADB" logcat -c
"$ADB" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1

logs() { "$ADB" logcat -d -s Arsel:V WM-WorkerWrapper:V WM-InputMerger:V AndroidRuntime:E; }

for ((i = 0; i < TIMEOUT; i += 3)); do
    out="$(logs)"
    # WorkManager 2.9.x under R8 full mode loses OverwritingInputMerger.<init>, and then no
    # worker in the app ever runs — the SDK itself logs nothing about it.
    if grep -qE "Could not create Input Merger|not started —|initialize failed|FATAL EXCEPTION" <<<"$out"; then
        echo "FAIL: the SDK cannot run in a shrunk release build" >&2
        echo "$out" | tail -40 >&2
        exit 1
    fi
    if grep -qE "POST /\S+ failed \(network\)" <<<"$out"; then
        echo "OK: PushSyncWorker ran in the shrunk release build"
        exit 0
    fi
    sleep 3
done

echo "FAIL: no drain attempt within ${TIMEOUT}s" >&2
logs | tail -40 >&2
exit 1
