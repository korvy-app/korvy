#!/usr/bin/env bash
# Builds the Play upload bundle (AAB), signed with the upload key from local.properties (RELEASE_* entries).
set -euo pipefail
cd "$(dirname "$0")/.."
grep -q '^RELEASE_STORE_FILE=' local.properties || { echo "No RELEASE_* entries in local.properties"; exit 1; }
export JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 20)}
./gradlew -q -Pupload clean testPlayDebugUnitTest bundlePlayRelease
AAB=app/build/outputs/bundle/playRelease/app-play-release.aab
ls -la "$AAB"
