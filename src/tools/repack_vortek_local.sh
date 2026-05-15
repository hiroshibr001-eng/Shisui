#!/usr/bin/env bash
set -euo pipefail

# Usage:
#   ./tools/repack_vortek_local.sh /path/to/vortek-2.1.tzst /path/to/libvulkan_vortek_mali_wrapper.so
# Output:
#   ./vortek-2.1-mali-wrapper.tzst

ORIG="${1:-vortek-2.1.tzst}"
WRAP="${2:-libvulkan_vortek_mali_wrapper.so}"
OUT="vortek-2.1-mali-wrapper.tzst"
TMP="work_vortek_repack"

rm -rf "$TMP"
mkdir -p "$TMP"
tar --use-compress-program=unzstd -xf "$ORIG" -C "$TMP"
test -f "$TMP/usr/lib/libvulkan_vortek.so"
mv "$TMP/usr/lib/libvulkan_vortek.so" "$TMP/usr/lib/libvulkan_vortek_real.so"
cp "$WRAP" "$TMP/usr/lib/libvulkan_vortek.so"
tar -C "$TMP" --use-compress-program="zstd -19" -cf "$OUT" .
echo "Created: $OUT"
