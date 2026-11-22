#!/usr/bin/env python3
"""Verify PocketLinux Android APK structure, native ELF ABIs, Alpine rootfs contents, and signature."""

import argparse
import io
import os
import shutil
import struct
import subprocess
import sys
import tarfile
import zipfile

ELF_MACHINES = {
    "arm64-v8a": 183,  # EM_AARCH64
    "x86_64": 62,      # EM_X86_64
}

OTHER_ABI = {
    "arm64-v8a": "x86_64",
    "x86_64": "arm64-v8a",
}

REQUIRED_ELF_LIBS = (
    "libproot.so",
    "libproot-loader.so",
    "libptybridge.so",
)

REQUIRED_ROOTFS_FILES = (
    "bin/busybox",
    "bin/bash",
    "usr/bin/Xvnc",
    "usr/bin/jwm",
    "usr/bin/python3",
    "usr/bin/xterm",
    "usr/bin/xcalc",
    "usr/bin/xclock",
    "usr/bin/feh",
    "usr/bin/mousepad",
    "usr/bin/galculator",
    "usr/bin/pcmanfm",
    "usr/bin/xwininfo",
    "usr/bin/xdpyinfo",
    "usr/bin/xrandr",
    "usr/bin/xsetroot",
    "root/.jwmrc",
    "usr/local/bin/start-desktop.sh",
    "usr/local/bin/sysinfo.sh",
    "etc/resolv.conf",
)

REQUIRED_EXECUTABLE_ROOTFS = (
    "usr/local/bin/start-desktop.sh",
    "usr/local/bin/sysinfo.sh",
)


def fail(msg: str) -> None:
    print(f"[verify-apk] FAIL: {msg}", file=sys.stderr)
    sys.exit(1)


def verify_elf64(data: bytes, expected_machine: int, label: str) -> None:
    if len(data) < 20:
        fail(f"{label}: file too small ({len(data)} bytes) to be an ELF binary")
    if data[:4] != b"\x7fELF":
        fail(f"{label}: invalid ELF magic {data[:4]!r}")
    ei_class, ei_data = data[4], data[5]
    if ei_class != 2 or ei_data != 1:
        fail(f"{label}: expected ELF64 little-endian (class=2, data=1), got class={ei_class}, data={ei_data}")
    e_type, e_machine = struct.unpack("<HH", data[16:20])
    if e_machine != expected_machine:
        fail(f"{label}: expected e_machine={expected_machine}, got {e_machine}")
    if e_type not in (2, 3):  # ET_EXEC or ET_DYN
        fail(f"{label}: unexpected ELF e_type={e_type}")


def verify_rootfs_tar_gz(fileobj: io.BufferedReader, abi: str) -> None:
    seen = set()
    symlink_count = 0
    special_files = []
    qemu_helpers = []
    non_exec = []
    resolv_content = None

    with tarfile.open(fileobj=fileobj, mode="r|gz") as tf:
        for member in tf:
            norm = member.name.lstrip("./")
            if not norm:
                continue
            seen.add(norm)

            if member.issym():
                symlink_count += 1
            if member.ischr() or member.isblk() or member.isfifo():
                special_files.append(norm)
            base = os.path.basename(norm)
            if base.startswith("qemu-") and (base.endswith("-static") or base in ("qemu-aarch64", "qemu-x86_64")):
                qemu_helpers.append(norm)
            if norm in REQUIRED_EXECUTABLE_ROOTFS and (member.mode & 0o111) == 0:
                non_exec.append(f"{norm} (mode={oct(member.mode)})")
            if norm == "etc/resolv.conf" and member.isfile():
                extracted = tf.extractfile(member)
                if extracted is not None:
                    resolv_content = extracted.read().decode("utf-8", errors="replace")

    missing = [p for p in REQUIRED_ROOTFS_FILES if p not in seen]
    if missing:
        fail(f"rootfs-{abi}.tar.gz missing required entries: {missing}")
    if symlink_count == 0:
        fail(f"rootfs-{abi}.tar.gz contains zero symlinks")
    if special_files:
        fail(f"rootfs-{abi}.tar.gz contains device/FIFO special files: {special_files[:10]}")
    if qemu_helpers:
        fail(f"rootfs-{abi}.tar.gz still contains QEMU static binaries: {qemu_helpers}")
    if non_exec:
        fail(f"rootfs-{abi}.tar.gz overlay scripts missing executable bit: {non_exec}")
    if resolv_content is None or "127.0.0.53" in resolv_content or "nameserver" not in resolv_content:
        fail(f"rootfs-{abi}.tar.gz has invalid /etc/resolv.conf: {resolv_content!r}")

    print(
        f"[verify-apk] OK rootfs-{abi}.tar.gz: {len(seen)} entries, "
        f"{symlink_count} symlinks, all {len(REQUIRED_ROOTFS_FILES)} required files present."
    )


def find_apksigner(explicit_path: str | None) -> str | None:
    if explicit_path:
        return explicit_path
    on_path = shutil.which("apksigner")
    if on_path:
        return on_path
    android_home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if android_home:
        build_tools_dir = os.path.join(android_home, "build-tools")
        if os.path.isdir(build_tools_dir):
            def ver_key(v: str) -> tuple[int, ...]:
                return tuple(int(p) if p.isdigit() else 0 for p in v.split("."))

            versions = sorted(os.listdir(build_tools_dir), key=ver_key)
            for ver in reversed(versions):
                candidate = os.path.join(build_tools_dir, ver, "apksigner")
                if os.path.isfile(candidate) and os.access(candidate, os.X_OK):
                    return candidate
    return None


def main() -> None:
    parser = argparse.ArgumentParser(description="Verify PocketLinux APK artifact.")
    parser.add_argument("--apk", required=True, help="Path to APK file")
    parser.add_argument("--abi", required=True, choices=sorted(ELF_MACHINES.keys()), help="Expected ABI")
    parser.add_argument("--min-size-mb", type=float, default=20.0, help="Minimum APK size in MiB")
    parser.add_argument("--max-size-mb", type=float, default=120.0, help="Maximum APK size in MiB")
    parser.add_argument("--apksigner", default=None, help="Explicit path to apksigner")
    parser.add_argument("--skip-apksigner", action="store_true", help="Skip apksigner verification")
    args = parser.parse_args()

    if not os.path.isfile(args.apk):
        fail(f"APK not found: {args.apk}")

    size_bytes = os.path.getsize(args.apk)
    size_mb = size_bytes / (1024.0 * 1024.0)
    if size_mb < args.min_size_mb or size_mb > args.max_size_mb:
        fail(f"APK size {size_mb:.2f} MiB outside expected bounds [{args.min_size_mb}, {args.max_size_mb}] MiB")
    print(f"[verify-apk] OK APK size: {size_mb:.2f} MiB ({size_bytes} bytes)")

    other = OTHER_ABI[args.abi]
    expected_machine = ELF_MACHINES[args.abi]
    expected_asset = f"assets/rootfs-{args.abi}.tar.gz"
    forbidden_asset = f"assets/rootfs-{other}.tar.gz"

    with zipfile.ZipFile(args.apk, "r") as zf:
        bad_entry = zf.testzip()
        if bad_entry is not None:
            fail(f"ZIP CRC error in entry: {bad_entry}")

        names = set(zf.namelist())
        for core in ("AndroidManifest.xml", "classes.dex", "resources.arsc"):
            if core not in names:
                fail(f"Missing core APK entry: {core}")

        other_libs = [n for n in names if n.startswith(f"lib/{other}/")]
        if other_libs:
            fail(f"Single-ABI violation: found {other} libraries in {args.abi} APK: {other_libs}")
        if forbidden_asset in names:
            fail(f"Single-ABI violation: found {forbidden_asset} in {args.abi} APK")

        for lib_name in REQUIRED_ELF_LIBS:
            lib_path = f"lib/{args.abi}/{lib_name}"
            if lib_path not in names:
                fail(f"Missing native library: {lib_path}")
            verify_elf64(zf.read(lib_path), expected_machine, lib_path)
            print(f"[verify-apk] OK ELF64 ({args.abi}): {lib_path}")

        if expected_asset not in names:
            fail(f"Missing rootfs asset: {expected_asset}")
        with zf.open(expected_asset, "r") as asset_stream:
            verify_rootfs_tar_gz(asset_stream, args.abi)

    if not args.skip_apksigner:
        apksigner = find_apksigner(args.apksigner)
        if not apksigner:
            fail("apksigner not found (pass --apksigner or --skip-apksigner)")
        res = subprocess.run(
            [apksigner, "verify", "--verbose", "--print-certs", args.apk],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
        )
        if res.returncode != 0:
            fail(f"apksigner verify failed:\n{res.stdout}")
        print(f"[verify-apk] OK apksigner verified ({apksigner})")

    print(f"[verify-apk] ALL CHECKS PASSED for {args.apk} ({args.abi})")


if __name__ == "__main__":
    main()
