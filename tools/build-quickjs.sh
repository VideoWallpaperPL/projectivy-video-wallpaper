#!/usr/bin/env bash
set -euo pipefail

archive="$1"
ndk="$2"
work="$3"
output="$4"
shift 4

case "$(uname -s)" in
    Darwin) host=darwin-x86_64 ;;
    Linux) host=linux-x86_64 ;;
    *) echo "Build QuickJS on macOS or Linux." >&2; exit 1 ;;
esac

source_dir="$(mktemp -d "$work/quickjs.XXXXXX")"
trap 'rm -rf "$source_dir"' EXIT
tar -xf "$archive" -C "$source_dir" --strip-components=1
cd "$source_dir"
make repl.c
version="$(cat VERSION)"
sources=(qjs.c repl.c quickjs.c quickjs-libc.c dtoa.c libregexp.c libunicode.c cutils.c)
clang="$ndk/toolchains/llvm/prebuilt/$host/bin/clang"

for abi in "$@"; do
    case "$abi" in
        armeabi-v7a) target=armv7a-linux-androideabi24 ;;
        arm64-v8a) target=aarch64-linux-android24 ;;
        x86) target=i686-linux-android24 ;;
        x86_64) target=x86_64-linux-android24 ;;
        *) echo "Unsupported Android ABI: $abi" >&2; exit 1 ;;
    esac
    mkdir -p "$output/$abi"
    "$clang" "--target=$target" -O2 -fPIE -pie -fwrapv -D_GNU_SOURCE "-DCONFIG_VERSION=\"$version\"" \
        -Wl,-z,max-page-size=16384 -Wl,--gc-sections -Wl,-s \
        "${sources[@]}" -lm -ldl -o "$output/$abi/libprojectyt-qjs.so"
done
