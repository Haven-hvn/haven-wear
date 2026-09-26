#!/usr/bin/env bash
# Builds libhaven_vetkeys.so for Android ABIs into app/src/main/jniLibs (haven-wear).
#
# Needs: cargo with the Android targets, and an NDK (ANDROID_NDK_HOME, or any
# version under $ANDROID_SDK_ROOT/ndk). Skips gracefully when the toolchain is
# absent (local builds without the NDK): the app still compiles and runs, but
# sealed unlock reports the missing native library instead of working.
#
# CI always has the toolchain, so a CI build always ships the library.
set -euo pipefail

MODULE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../app" && pwd)"
CRATE_DIR="$MODULE_DIR/src/main/rust/haven_vetkeys"
OUT_DIR="$MODULE_DIR/src/main/jniLibs"
API=30
ABIS=(arm64-v8a armeabi-v7a x86_64)
TARGETS=(aarch64-linux-android armv7-linux-androideabi x86_64-linux-android)

command -v cargo >/dev/null 2>&1 || { echo "build-vetkeys: cargo not found, skipping native build"; exit 0; }

NDK="${ANDROID_NDK_HOME:-}"
if [ -z "$NDK" ]; then
    SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
    if [ -n "$SDK" ]; then
        NDK="$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -n 1 || true)"
    fi
fi
if [ -z "${NDK:-}" ] || [ ! -d "$NDK" ]; then
    echo "build-vetkeys: NDK not found, skipping native build"
    exit 0
fi
# NDK ships one prebuilt host toolchain; macOS NDKs use darwin-x86_64 (universal binaries).
TOOLCHAIN="$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/ 2>/dev/null | head -n 1)"
TOOLCHAIN="${TOOLCHAIN%/}"
if [ -z "$TOOLCHAIN" ] || [ ! -d "$TOOLCHAIN" ]; then
    echo "build-vetkeys: NDK host toolchain missing, skipping native build"
    exit 0
fi

echo "build-vetkeys: using NDK $NDK"
# cc-rs needs the NDK toolchain bin dir (ar/ranlib wrappers) on PATH.
export PATH="$TOOLCHAIN/bin:$PATH"
# NDK r23+ ships only llvm-ar; provide the prefixed names cc-rs probes for.
for t in "${TARGETS[@]}"; do
    if [ ! -x "$TOOLCHAIN/bin/$t-ar" ]; then
        ln -sf "llvm-ar" "$TOOLCHAIN/bin/$t-ar" 2>/dev/null || true
    fi
done
rustup target add "${TARGETS[@]}"

for i in "${!ABIS[@]}"; do
    abi="${ABIS[$i]}"
    target="${TARGETS[$i]}"
    case "$target" in
        aarch64-linux-android) clang="$TOOLCHAIN/bin/aarch64-linux-android$API-clang" ;;
        armv7-linux-androideabi) clang="$TOOLCHAIN/bin/armv7a-linux-androideabi$API-clang" ;;
        x86_64-linux-android) clang="$TOOLCHAIN/bin/x86_64-linux-android$API-clang" ;;
    esac
    flat="$(echo "$target" | tr '-' '_')"
    upper="$(echo "$flat" | tr '[:lower:]' '[:upper:]')"
    echo "build-vetkeys: $target ($abi)"
    env "CC_$flat=$clang" \
        "CXX_$flat=${clang/clang/clang++}" \
        "AR_$flat=$TOOLCHAIN/bin/llvm-ar" \
        "CARGO_TARGET_${upper}_LINKER=$clang" \
        cargo build --manifest-path "$CRATE_DIR/Cargo.toml" --release --target "$target"
    mkdir -p "$OUT_DIR/$abi"
    cp "$CRATE_DIR/target/$target/release/libhaven_vetkeys.so" "$OUT_DIR/$abi/"
    "$TOOLCHAIN/bin/llvm-strip" "$OUT_DIR/$abi/libhaven_vetkeys.so" 2>/dev/null || true
done
echo "build-vetkeys: installed ${#ABIS[@]} ABIs into $OUT_DIR"
