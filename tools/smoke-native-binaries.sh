#!/usr/bin/env bash
# =============================================================================
# smoke-native-binaries.sh — contract/smoke test for the vendored native binaries
# (proot, aria2c, tar, gzip, xz, rsync, nano, less) shipped in the APK jniLibs.
#
# Why: the binaries are rebuilt weekly upstream. This gives each one a known
# input -> known-good output ("answer sheet") so a recompiled bundle that no
# longer behaves as expected is caught BEFORE it is published / adopted.
# Standard + inventory: see Claude-Env docs; ticket ADFA-4465.
#
# Execution model (mirrors tools/build-iiab-rootfs.sh):
#   - Run the REAL lib*.so from jniLibs/<arch>.
#   - Prefer NATIVE execution (run the smoke job on a matching-arch runner);
#     fall back to QEMU user-static for a foreign arch.
#   - proot is the only test that needs a guest rootfs; it runs the real launch
#     when native, and is reduced to a version check (logged) under emulation,
#     because proot under qemu-user is unreliable (same caveat as the rootfs tool).
#
# Usage:
#   smoke-native-binaries.sh --jnilibs <dir> [--arch arm64-v8a|armeabi-v7a]
#   <dir> is the folder that CONTAINS jniLibs/, or the jniLibs/<arch> folder itself.
#
# Exit 0 = all contract rows passed; non-zero = at least one failed.
# Copyright (c) 2026 AppDevForAll.
# =============================================================================
set -u

RED="\033[31m"; GRN="\033[32m"; YEL="\033[33m"; BLU="\033[34m"; RST="\033[0m"
ok()   { printf "${GRN}[smoke] PASS${RST} %s\n" "$*"; }
bad()  { printf "${RED}[smoke] FAIL${RST} %s\n" "$*"; }
skip() { printf "${YEL}[smoke] SKIP${RST} %s\n" "$*"; }
log()  { printf "${BLU}[smoke]${RST} %s\n" "$*"; }

ARCH="arm64-v8a"
JNILIBS=""
while [ $# -gt 0 ]; do
  case "$1" in
    --jnilibs) JNILIBS="$2"; shift 2 ;;
    --arch)    ARCH="$2"; shift 2 ;;
    -h|--help) grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown arg: $1" >&2; exit 2 ;;
  esac
done
[ -n "$JNILIBS" ] || { echo "ERROR: --jnilibs <dir> is required" >&2; exit 2; }

# Resolve the directory that holds the lib*.so for this arch.
if [ -d "$JNILIBS/jniLibs/$ARCH" ]; then BIN="$JNILIBS/jniLibs/$ARCH"
elif [ -d "$JNILIBS/$ARCH" ];        then BIN="$JNILIBS/$ARCH"
elif [ -f "$JNILIBS/libtar.so" ];    then BIN="$JNILIBS"
else echo "ERROR: cannot find jniLibs for $ARCH under $JNILIBS" >&2; exit 2; fi
BIN="$(cd "$BIN" && pwd)"
log "Binaries dir: $BIN"
log "Target arch : $ARCH"

# --- Native vs QEMU detection -------------------------------------------------
# Map android arch -> the qemu-user binary we would need for emulation.
case "$ARCH" in
  arm64-v8a)   QEMU_WANT="qemu-aarch64-static" ;;
  armeabi-v7a) QEMU_WANT="qemu-arm-static" ;;
  x86_64)      QEMU_WANT="" ;;
  *)           QEMU_WANT="qemu-${ARCH}-static" ;;
esac
QEMU=""
chmod +x "$BIN"/*.so 2>/dev/null || true
# Probe: can this host execute the target tar directly? (126 = exec format error)
if [ -f "$BIN/libtar.so" ] && "$BIN/libtar.so" --version >/dev/null 2>&1; then
  NATIVE=1; log "Native execution confirmed (host runs $ARCH directly)."
else
  NATIVE=0
  if [ -n "$QEMU_WANT" ] && command -v "$QEMU_WANT" >/dev/null 2>&1; then
    QEMU="$(command -v "$QEMU_WANT")"; log "Using QEMU emulation: $QEMU"
  else
    echo "ERROR: $ARCH is foreign to this host and $QEMU_WANT is not installed." >&2
    echo "       Install qemu-user-static or run on a matching-arch runner." >&2
    exit 2
  fi
fi

# run_bin <lib.so> [args...] — exec a bundle binary natively or via QEMU.
run_bin() {
  local b="$1"; shift
  if [ "$NATIVE" -eq 1 ]; then "$b" "$@"; else "$QEMU" "$b" "$@"; fi
}

PASS=0; FAIL=0
# chk <description> <command...>  — the "answer sheet" engine.
chk() { local d="$1"; shift; if "$@" >/dev/null 2>&1; then ok "$d"; PASS=$((PASS+1)); else bad "$d"; FAIL=$((FAIL+1)); fi; }

WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
cd "$WORK"

# --- Fixtures (the known inputs) ---------------------------------------------
mkdir -p src/sub
printf 'hello knowledge to go\n' > src/a.txt
head -c 4096 /dev/zero | tr '\0' 'Z' > src/sub/b.bin   # deterministic 4 KiB blob

# ============================== CONTRACT ROWS ================================

# 1) Integrity: every expected binary is present (+ sha256 vs ninja_manifest.json)
present_all() {
  local f; for f in libproot.so libaria2c.so libtar.so libgzip.so libxz.so librsync.so libnano.so libless.so; do
    [ -f "$BIN/$f" ] || { echo "missing $f"; return 1; }
  done
}
chk "all 8 binaries present in jniLibs/$ARCH" present_all

manifest_match() {
  local mf="$BIN/../ninja_manifest.json"; [ -f "$mf" ] || mf="$JNILIBS/ninja_manifest.json"
  [ -f "$mf" ] || { echo "no manifest"; return 0; }   # absence is not a failure here
  local f got exp
  for f in libproot.so libtar.so libaria2c.so; do
    exp="$(grep -oE "\"$f\"[^}]*\"sha256\"[[:space:]]*:[[:space:]]*\"[0-9a-f]+\"" "$mf" 2>/dev/null | grep -oE '[0-9a-f]{64}' | head -1)"
    [ -n "$exp" ] || continue
    got="$(sha256sum "$BIN/$f" | awk '{print $1}')"
    [ "$exp" = "$got" ] || { echo "$f sha mismatch"; return 1; }
  done
}
chk "manifest sha256 matches (when present)" manifest_match

# 2) tar — round-trip create + extract (byte-identical) and stdin gzip path
tar_roundtrip() {
  run_bin "$BIN/libtar.so" -cf arc.tar -C src . || return 1
  rm -rf out && mkdir out
  run_bin "$BIN/libtar.so" -xf arc.tar -C out || return 1
  diff -r src out
}
chk "tar create+extract round-trip is byte-identical" tar_roundtrip

tar_stdin_gzip() {   # mirrors TarExtractor: gzip-decompress | tar -xvf -
  run_bin "$BIN/libtar.so" -cf arc2.tar -C src . || return 1
  run_bin "$BIN/libgzip.so" -c arc2.tar > arc2.tar.gz || return 1
  rm -rf out2 && mkdir out2
  run_bin "$BIN/libgzip.so" -dc arc2.tar.gz | run_bin "$BIN/libtar.so" -xvf - -C out2 || return 1
  diff -r src out2
}
chk "gzip -dc | tar -xvf - (app extraction path)" tar_stdin_gzip

# 3) gzip — round-trip
gzip_roundtrip() {
  run_bin "$BIN/libgzip.so" -c src/sub/b.bin > b.gz || return 1
  run_bin "$BIN/libgzip.so" -dc b.gz > b.out || return 1
  cmp -s src/sub/b.bin b.out
}
chk "gzip compress/decompress round-trip" gzip_roundtrip

# 4) xz — round-trip
xz_roundtrip() {
  run_bin "$BIN/libxz.so" -z -c src/sub/b.bin > b.xz || return 1
  run_bin "$BIN/libxz.so" -dc b.xz > b.xzout || return 1
  cmp -s src/sub/b.bin b.xzout
}
chk "xz compress/decompress round-trip" xz_roundtrip

# 5) rsync — local mirror round-trip (daemon mode is exercised on-device)
rsync_roundtrip() {
  rm -rf dest && mkdir dest
  run_bin "$BIN/librsync.so" -a --delete src/ dest/ || return 1
  diff -r src dest
}
chk "rsync -a mirror reproduces the tree" rsync_roundtrip

# 6) aria2c — executes and still has the features the app depends on.
#    (Version strings drift each rebuild; feature TOKENS are stable.)
aria2_features() {
  local out; out="$(run_bin "$BIN/libaria2c.so" --version 2>&1)" || return 1
  echo "$out" | grep -qi 'Metalink' || { echo "no Metalink"; return 1; }
  echo "$out" | grep -qiE 'https|TLS|SSL' || { echo "no TLS/https"; return 1; }
  echo "$out" | grep -qi 'BitTorrent' || { echo "no BitTorrent"; return 1; }
}
chk "aria2c runs and has Metalink/https/BitTorrent" aria2_features

# 7) nano / less — execute (interactive on-device; here just confirm they run)
chk "nano --version runs" run_bin "$BIN/libnano.so" --version
chk "less --version runs" run_bin "$BIN/libless.so" --version

# 8) proot — real launch contract when native; version-only under emulation.
proot_launch() {
  command -v busybox >/dev/null 2>&1 || { echo "busybox not installed on runner"; return 1; }
  rm -rf rootfs && mkdir -p rootfs/bin
  cp "$(command -v busybox)" rootfs/bin/busybox || return 1
  local out
  out="$(run_bin "$BIN/libproot.so" -r "$PWD/rootfs" -w / /bin/busybox echo hello 2>/dev/null)" || return 1
  [ "$out" = "hello" ]
}
if [ "$NATIVE" -eq 1 ]; then
  chk "proot launches a command in a minimal rootfs (stdout=hello)" proot_launch
else
  if run_bin "$BIN/libproot.so" --version >/dev/null 2>&1; then
    skip "proot launch (emulated $ARCH); version OK — full launch verified on native arch / device"
  else
    bad "proot --version failed under emulation"; FAIL=$((FAIL+1))
  fi
fi

# ============================== VERDICT ======================================
echo
log "Result: ${PASS} passed, ${FAIL} failed (arch=$ARCH, mode=$([ "$NATIVE" -eq 1 ] && echo native || echo qemu))."
[ "$FAIL" -eq 0 ] || { bad "Contract NOT satisfied — do not publish/adopt this build."; exit 1; }
ok "All native-binary contracts satisfied."
