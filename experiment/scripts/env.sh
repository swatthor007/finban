# Source me. Uses the SDK's adb (not /usr/bin/adb, which may be an older version).
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
[ -d "$ANDROID_HOME" ] || ANDROID_HOME="$HOME/Library/Android/sdk"   # macOS default
export ANDROID_HOME ANDROID_SDK_ROOT="$ANDROID_HOME"
ADB="$ANDROID_HOME/platform-tools/adb"
PKG=com.yourteam.guard.harness
SVC="$PKG/$PKG.a11y.GuardAccessibilityService"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [ -z "${JAVA_HOME:-}" ]; then
  for j in /usr/lib/jvm/java-21-openjdk-amd64 /usr/lib/jvm/java-17-openjdk-amd64 "$(/usr/libexec/java_home -v 17+ 2>/dev/null)"; do
    [ -d "$j" ] && export JAVA_HOME="$j" && break
  done
fi
