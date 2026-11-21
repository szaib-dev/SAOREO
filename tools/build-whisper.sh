#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
ndk="${1:?Provide Android NDK r28c directory}"
abi="${2:-arm64-v8a}"
case "$abi" in arm64-v8a|x86_64|host) ;; *) exit 2 ;; esac
work="${SAOREO_WHISPER_WORK:-$root/app/build/whisper}"
mkdir -p "$work"
archive="$work/whisper-1.8.3.tar.gz"
if [[ ! -f "$archive" ]]; then
  curl -fL https://codeload.github.com/ggml-org/whisper.cpp/tar.gz/refs/tags/v1.8.3 -o "$archive.partial"
  mv "$archive.partial" "$archive"
fi
sha256sum -c <<<"870ba21409cdf66697dc4db15ebdb13bc67037d76c7cc63756c81471d8f1731a  $archive"
src="$work/src-$abi"
mkdir -p "$src"
tar xf "$archive" --strip-components=1 -C "$src"
cross=()
if [[ "$abi" != host ]]; then
  grep -qx 'Pkg.Revision = 28.2.13676358' "$ndk/source.properties"
  cross=(-DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" -DANDROID_ABI="$abi"
    -DANDROID_PLATFORM=android-33 -DANDROID_STL=c++_static -DCMAKE_EXE_LINKER_FLAGS=-Wl,-z,max-page-size=16384)
  sed -i '1i\#include <signal.h>\n#include <sys/prctl.h>\n#include <unistd.h>\n__attribute__((constructor)) static void stop_on_parent_exit() { const auto parent = getppid(); if (parent == 1 || prctl(PR_SET_PDEATHSIG, SIGKILL, 0L, 0L, 0L) < 0 || getppid() != parent) _exit(125); }' "$src/examples/cli/cli.cpp"
fi
cmake -S "$src" -B "$work/build-$abi" "${cross[@]}" -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=OFF -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DWHISPER_CURL=OFF \
  -DWHISPER_BUILD_TESTS=OFF -DWHISPER_BUILD_SERVER=OFF
cmake --build "$work/build-$abi" --target whisper-cli --parallel "${SAOREO_WHISPER_JOBS:-4}"
if [[ "$abi" != host ]]; then
  install -Dm755 "$work/build-$abi/bin/whisper-cli" "$root/app/src/main/jniLibs/$abi/libsaoreowhisper.so"
  "$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded "$root/app/src/main/jniLibs/$abi/libsaoreowhisper.so"
  install -Dm644 "$src/LICENSE" "$root/app/src/main/assets/offline-encoder/Whisper-MIT.txt"
fi
