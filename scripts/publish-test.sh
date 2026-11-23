#!/usr/bin/env bash
# Builds the optimised (release, debug-signed) APK and copies it to a file share on your own network, so it
# can be installed on a phone with an SMB-capable file manager.
#
# Where it goes is set in local.properties (git-ignored, never committed):
#   TEST_SHARE_SSH=user@host           # SSH login of the machine that serves the share
#   TEST_SHARE_DIR=/path/on/that/host  # folder inside the share
#   TEST_SHARE_SMB=\\host\share\folder # how the phone sees it (only printed)
#
#   ./scripts/publish-test.sh            # play flavour
#   ./scripts/publish-test.sh foss       # F-Droid flavour (separate app ID, can be installed side by side)
set -euo pipefail
cd "$(dirname "$0")/.."
prop() { grep -E "^$1=" local.properties 2>/dev/null | head -1 | cut -d= -f2-; }
SSH_TARGET=$(prop TEST_SHARE_SSH); DEST=$(prop TEST_SHARE_DIR); SMB=$(prop TEST_SHARE_SMB)
[ -n "$SSH_TARGET" ] && [ -n "$DEST" ] || { echo "Set TEST_SHARE_SSH and TEST_SHARE_DIR in local.properties"; exit 1; }
FLAVOR=${1:-play}
CAP="$(tr '[:lower:]' '[:upper:]' <<< "${FLAVOR:0:1}")${FLAVOR:1}"
export JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 20)}
./gradlew -q "assemble${CAP}Release"
APK="app/build/outputs/apk/$FLAVOR/release/app-$FLAVOR-release.apk"
VERSION=$(grep -E 'versionName = ' app/build.gradle.kts | head -1 | sed -E 's/.*"(.*)".*/\1/')
NAME="launcher-$FLAVOR-$VERSION-$(date +%Y%m%d-%H%M).apk"
ssh "$SSH_TARGET" "mkdir -p '$DEST'"
# Upload under a hidden temporary name, check it arrived whole, then rename into place. Renaming is atomic, so a
# phone never sees a half-copied APK, and "latest" is a fresh file rather than one overwritten in place (an SMB
# client that cached the old file's size could otherwise read a mix of both: "package appears to be invalid").
SUM=$(shasum -a 256 "$APK" | cut -d' ' -f1)
scp -q "$APK" "$SSH_TARGET:$DEST/.$NAME.part"
ssh "$SSH_TARGET" "set -e; cd '$DEST'
  [ \"\$(sha256sum '.$NAME.part' | cut -d' ' -f1)\" = '$SUM' ] || { echo 'Upload corrupted, try again'; rm -f '.$NAME.part'; exit 1; }
  cp '.$NAME.part' '.latest.part' && mv -f '.$NAME.part' '$NAME' && mv -f '.latest.part' 'launcher-$FLAVOR-latest.apk'
  ls -t | grep -v '^\.' | head -8 | sed 's/^/  /'"
echo "Copied $NAME (and launcher-$FLAVOR-latest.apk) to ${SMB:-$DEST}"
