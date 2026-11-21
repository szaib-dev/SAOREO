#!/usr/bin/env bash
# Build the conversion-only executable. Existing downloader dependencies stay separate.
set -euo pipefail
export LC_ALL=C TZ=UTC SOURCE_DATE_EPOCH=1790009530
ndk="$(cd "${1:?Usage: build-offline-encoder.sh ANDROID_NDK_HOME [arm64-v8a|x86_64|host]}"; pwd)"
target="${2:-all}"
case "$target" in all|arm64-v8a|x86_64|host) ;; *) exit 2 ;; esac
root="$(cd "$(dirname "$0")/.." && pwd)"
build="${SAOREO_ENCODER_WORK:-$root/app/build/offline-encoder}"
src="${SAOREO_ENCODER_SOURCES:-$build/sources}"
output="${SAOREO_ENCODER_OUTPUT:-$root/app/src/main}"
mkdir -p "$src" "$build"
download() {
    local url="$1" archive="$2" checksum="$3"
    if [[ ! -f "$src/$archive" ]]; then
        curl --fail --location --retry 3 "$url" -o "$src/$archive.partial"
        mv "$src/$archive.partial" "$src/$archive"
    fi
    (cd "$src"; sha256sum -c <<<"$checksum  $archive")
}
download https://ffmpeg.org/releases/ffmpeg-8.1.3.tar.xz ffmpeg-8.1.3.tar.xz 7138d28c96d9d3e3af4ee3d8cad72741f8ffb40da90c1112235dea3ecd3178a3
download https://downloads.sourceforge.net/project/lame/lame/3.100/lame-3.100.tar.gz lame-3.100.tar.gz ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e

build_encoder() (
    local abi="$1" triple="$2" arch="$3"
    local dir="$build/$abi"
    local bin="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
    local -a cross_lame=() cross_ff=()
    local cc=gcc ar=ar ranlib=ranlib nm=nm strip=strip
    local flags="-O2 -ffile-prefix-map=$dir=."
    local ldflags=""
    if [[ "$abi" != "host" ]]; then
        grep -qx 'Pkg.Revision = 28.2.13676358' "$ndk/source.properties"
        cross_lame=(--host="$triple")
        cross_ff=(--enable-cross-compile --target-os=android --arch="$arch" --strip="$bin/llvm-strip")
        cc="$bin/${triple}33-clang"
        ar="$bin/llvm-ar"
        ranlib="$bin/llvm-ranlib"
        nm="$bin/llvm-nm"
        strip="$bin/llvm-strip"
        flags="$flags -fPIE"
        ldflags="-pie -Wl,-z,max-page-size=16384,-z,relro,-z,now"
    fi
    rm -rf "$dir"
    mkdir -p "$dir"
    tar -xf "$src/lame-3.100.tar.gz" -C "$dir"
    tar -xf "$src/ffmpeg-8.1.3.tar.xz" -C "$dir"
    cd "$dir/lame-3.100"
    CC="$cc" AR="$ar" RANLIB="$ranlib" NM="$nm" CFLAGS="$flags -Wno-error=implicit-function-declaration" \
        ./configure "${cross_lame[@]}" --prefix="$dir/lame" --disable-shared --enable-static \
        --with-pic --disable-frontend --disable-decoder
    make -j"${SAOREO_ENCODER_JOBS:-4}"
    make install

    cd "$dir/ffmpeg-8.1.3"
    if [[ "$abi" != "host" ]]; then
        # Terminate the local encoder if Android kills the parent app process.
        sed -i '1i\#include "saoreo_parent_guard.h"' fftools/ffmpeg.c
        cat > fftools/saoreo_parent_guard.h <<'GUARD'
#include <signal.h>
#include <sys/prctl.h>
#include <unistd.h>

__attribute__((constructor)) static void saoreo_stop_on_parent_exit(void)
{
    const pid_t parent = getppid();
    if (parent == 1 || prctl(PR_SET_PDEATHSIG, SIGKILL, 0L, 0L, 0L) < 0 || getppid() != parent)
        _exit(125);
}
GUARD
    fi
    ./configure "${cross_ff[@]}" --cc="$cc" --ar="$ar" --ranlib="$ranlib" --nm="$nm" \
        --extra-cflags="$flags -I$dir/lame/include" --extra-ldflags="$ldflags -L$dir/lame/lib" \
        --disable-autodetect --disable-network --disable-everything --disable-asm \
        --disable-doc --disable-ffplay --disable-ffprobe --disable-avdevice --disable-swscale \
        --enable-small --enable-libmp3lame --enable-encoder=libmp3lame \
        --enable-demuxer=mov,matroska,wav,mp3,ogg,flac,aac --enable-parser=aac,aac_latm,mpegaudio,ac3,opus,vorbis,flac \
        --enable-decoder=aac,aac_latm,mp3,mp3float,mp2,alac,ac3,eac3,opus,vorbis,flac,pcm_s16le,pcm_s16be,pcm_s24le,pcm_s24be,pcm_s32le,pcm_s32be,pcm_f32le,pcm_f32be \
        --enable-muxer=mp3,wav,segment --enable-encoder=pcm_s16le --enable-protocol=file,pipe \
        --enable-filter=aresample,aformat,anull,atrim
    # Only the embedded display string is changed. Actual build paths remain intact.
    sed -i "/^#define FFMPEG_CONFIGURATION / {s|$ndk|ANDROID_NDK|g;s|$dir|BUILD_DIR|g;}" config.h
    make -j"${SAOREO_ENCODER_JOBS:-4}" ffmpeg
    "$strip" --strip-unneeded ffmpeg
    if [[ "$abi" != "host" ]]; then
        install -Dm755 ffmpeg "$output/jniLibs/$abi/libsaoreomp3.so"
        local notices="$output/assets/offline-encoder"
        install -Dm644 COPYING.LGPLv2.1 "$notices/FFmpeg-LGPL-2.1.txt"
        install -Dm644 "$dir/lame-3.100/COPYING" "$notices/LAME-LGPL.txt"
        install -Dm644 LICENSE.md "$notices/FFmpeg-LICENSE.md"
    fi
)
case "$target" in
    all) build_encoder arm64-v8a aarch64-linux-android aarch64
         build_encoder x86_64 x86_64-linux-android x86_64 ;;
    arm64-v8a) build_encoder arm64-v8a aarch64-linux-android aarch64 ;;
    x86_64) build_encoder x86_64 x86_64-linux-android x86_64 ;;
    host) build_encoder host '' '' ;;
esac
