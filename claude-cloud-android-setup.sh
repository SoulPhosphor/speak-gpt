#!/usr/bin/env bash
# Sets up local Android builds in a Claude Code cloud session (see CLAUDE.md
# section 16). Each cloud session starts on a fresh machine, so run this once
# per session before building. Takes a few minutes and about 4 GB.
#
# Afterwards, from the repository root:
#   export ANDROID_HOME=/opt/android-sdk ANDROID_NDK_HOME=/opt/android-sdk/ndk/26.3.11579264
#   ./gradlew --no-daemon --max-workers=2 :app:testDebugUnitTest
set -euo pipefail

SDK=/opt/android-sdk
NDK_VERSION=26.3.11579264
cd "$(dirname "$0")"

if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  tmp="$(mktemp -d)"
  curl -sS -o "$tmp/clt.zip" https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  mkdir -p "$SDK/cmdline-tools"
  unzip -q -o "$tmp/clt.zip" -d "$tmp"
  mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$tmp"
fi

yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null 2>&1 || true
# The same packages the Android Checks workflow uses.
"$SDK/cmdline-tools/latest/bin/sdkmanager" \
  "platforms;android-36" "build-tools;36.0.0" "platform-tools" \
  "ndk;$NDK_VERSION" "cmake;3.22.1" > /dev/null

echo "sdk.dir=$SDK" > local.properties

# Maven Central rate-limits shared cloud addresses ("429 Too Many Requests"),
# so this machine uses Google's copy of Maven Central instead. This file lives
# only on the cloud machine; nothing in the repository's build changes.
mkdir -p ~/.gradle/init.d
cat > ~/.gradle/init.d/central-mirror.gradle <<'EOF'
def mirror = 'https://maven-central.storage-download.googleapis.com/maven2/'
def useMirror = { repos ->
    repos.withType(MavenArtifactRepository).configureEach { repo ->
        if (repo.url.toString().startsWith('https://repo.maven.apache.org')) repo.url = mirror
    }
}
settingsEvaluated { settings ->
    useMirror(settings.pluginManagement.repositories)
    useMirror(settings.dependencyResolutionManagement.repositories)
}
allprojects {
    useMirror(buildscript.repositories)
    // Robolectric downloads its Android jars itself, outside Gradle.
    tasks.withType(Test).configureEach { systemProperty 'robolectric.dependency.repo.url', mirror }
}
EOF

echo "Android build setup complete."
