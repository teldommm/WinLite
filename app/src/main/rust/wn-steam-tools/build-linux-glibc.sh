#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/../../../../.."

if [ -f "$HOME/.cargo/env" ]; then
    # shellcheck disable=SC1091
    . "$HOME/.cargo/env"
fi

target="aarch64-unknown-linux-gnu"
asset_dir="app/src/main/assets/xvfb-arm64"
manifest="app/src/main/rust/wn-steam-tools/Cargo.toml"
target_dir="app/src/main/rust/wn-steam-tools/target"

if ! command -v cargo >/dev/null 2>&1; then
    echo "cargo not found. Install Rust with: curl https://sh.rustup.rs -sSf | sh" >&2
    exit 1
fi
if ! rustup target list --installed | grep -qx "$target"; then
    rustup target add "$target"
fi
if ! command -v aarch64-linux-gnu-gcc >/dev/null 2>&1; then
    echo "aarch64-linux-gnu-gcc not found. On Debian/Ubuntu install: sudo apt install gcc-aarch64-linux-gnu" >&2
    exit 1
fi

export CARGO_TARGET_AARCH64_UNKNOWN_LINUX_GNU_LINKER="${CARGO_TARGET_AARCH64_UNKNOWN_LINUX_GNU_LINKER:-aarch64-linux-gnu-gcc}"

RUSTFLAGS="-C target-feature=+crt-static" cargo build --release --target "$target" --manifest-path "$manifest" \
    --bin winlite_steamwebhelper_wrapper \
    --bin winlite_driverquery_noop
cargo build --release --target "$target" --manifest-path "$manifest" --lib

mkdir -p "$asset_dir"
cp -f "$target_dir/$target/release/winlite_steamwebhelper_wrapper" \
    "$asset_dir/winlite-steamwebhelper-wrapper"
cp -f "$target_dir/$target/release/winlite_driverquery_noop" \
    "$asset_dir/winlite-driverquery-noop"
cp -f "$target_dir/$target/release/libwinlite_setxid_noop.so" \
    "$asset_dir/libwinlite-setxid-noop.so"

if command -v aarch64-linux-gnu-strip >/dev/null 2>&1; then
    aarch64-linux-gnu-strip \
        "$asset_dir/winlite-steamwebhelper-wrapper" \
        "$asset_dir/winlite-driverquery-noop" \
        "$asset_dir/libwinlite-setxid-noop.so"
fi

sha256sum \
    "$asset_dir/winlite-steamwebhelper-wrapper" \
    "$asset_dir/winlite-driverquery-noop" \
    "$asset_dir/libwinlite-setxid-noop.so"
