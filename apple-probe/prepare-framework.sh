#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
case "${SDK_NAME:-iphonesimulator}" in
  iphonesimulator*) target=IosSimulatorArm64 ;;
  iphoneos*) target=IosArm64 ;;
  appletvsimulator*) target=TvosSimulatorArm64 ;;
  appletvos*) target=TvosArm64 ;;
  *) echo "Unsupported Apple SDK" >&2; exit 1 ;;
esac
./gradlew ":shared:linkDebugFramework${target}"
