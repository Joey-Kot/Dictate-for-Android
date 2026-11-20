#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ndk="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
: "${ndk:?Set ANDROID_NDK_HOME to the Android NDK path}"
target=aarch64-linux-android
toolchain="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$toolchain/${target}26-clang"
export CARGO_TARGET_DIR="$root/app/build/rust-vad"
# Cargo selects a single rustflags source. Put Android's required linker option last in that source.
alignment=-Clink-arg=-Wl,-z,max-page-size=16384,-z,common-page-size=16384
if [[ -v CARGO_ENCODED_RUSTFLAGS ]]; then
    separator=$'\x1f'
    export CARGO_ENCODED_RUSTFLAGS="${CARGO_ENCODED_RUSTFLAGS:+$CARGO_ENCODED_RUSTFLAGS$separator}$alignment"
elif [[ -v RUSTFLAGS ]]; then
    export RUSTFLAGS="${RUSTFLAGS:+$RUSTFLAGS }$alignment"
else
    export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="${CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS:+$CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS }$alignment"
fi
cargo build --locked --release --lib --target "$target" --manifest-path "$root/native/vad/Cargo.toml"
library="$CARGO_TARGET_DIR/$target/release/libdictate_vad.so"
"$toolchain/llvm-readelf" -lW "$library" | awk '
    $1 == "LOAD" { count++; if ($NF != "0x4000") bad = 1 }
    END {
        if (!count || bad) { print "VAD ELF LOAD segments must be 16 KiB aligned" > "/dev/stderr"; exit 1 }
    }
'
install -Dm755 "$library" \
    "$root/app/src/main/jniLibs/arm64-v8a/libdictate_vad.so"
