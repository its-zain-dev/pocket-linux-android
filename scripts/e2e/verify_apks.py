#!/usr/bin/env python3
"""Static dual-ABI verifier for PocketLinux Android APKs (Python 3 stdlib only)."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import gzip
import hashlib
import io
import json
import os
from pathlib import PurePosixPath
import re
import shutil
import struct
import subprocess
import sys
import tarfile
import zipfile
import zlib
from typing import Any

ELF_MACHINES = {
    "arm64-v8a": 0x00B7,  # 183 = EM_AARCH64
    "x86_64": 0x003E,     # 62  = EM_X86_64
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
    "etc/alpine-release",
)

REQUIRED_ROOTFS_ANY_OF = (
    ("sbin/apk", "usr/sbin/apk"),
)

REQUIRED_EXECUTABLE_ROOTFS = (
    "usr/local/bin/start-desktop.sh",
    "usr/local/bin/sysinfo.sh",
)

SPECIAL_USE_BIT = 0x40000000


def compute_sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            chunk = f.read(1024 * 1024)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()


def find_sdk_tool(tool_name: str, explicit_path: str | None) -> str | None:
    if explicit_path:
        if os.path.isfile(explicit_path) and os.access(explicit_path, os.X_OK):
            return explicit_path
        return None
    on_path = shutil.which(tool_name)
    if on_path:
        return on_path
    for env_var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        sdk_root = os.environ.get(env_var)
        if not sdk_root:
            continue
        build_tools_dir = os.path.join(sdk_root, "build-tools")
        if os.path.isdir(build_tools_dir):
            def ver_key(v: str) -> tuple[int, ...]:
                return tuple(int(p) if p.isdigit() else 0 for p in v.split("."))

            versions = sorted(os.listdir(build_tools_dir), key=ver_key)
            for ver in reversed(versions):
                candidate = os.path.join(build_tools_dir, ver, tool_name)
                if os.path.isfile(candidate) and os.access(candidate, os.X_OK):
                    return candidate
    return None


def verify_elf64(data: bytes, expected_machine: int, label: str) -> dict[str, Any]:
    info: dict[str, Any] = {
        "valid": False,
        "sizeBytes": len(data),
        "eiClass": None,
        "eiData": None,
        "eType": None,
        "eMachine": None,
        "error": None,
    }
    if len(data) < 20:
        info["error"] = f"{label}: file too small ({len(data)} bytes) for ELF header"
        return info
    if data[:4] != b"\x7fELF":
        info["error"] = f"{label}: invalid ELF magic {data[:4]!r}"
        return info
    ei_class, ei_data = data[4], data[5]
    info["eiClass"] = ei_class
    info["eiData"] = ei_data
    if ei_class != 2 or ei_data != 1:
        info["error"] = (
            f"{label}: expected ELF64 little-endian (class=2, data=1), "
            f"got class={ei_class}, data={ei_data}"
        )
        return info
    e_type, e_machine = struct.unpack("<HH", data[16:20])
    info["eType"] = e_type
    info["eMachine"] = e_machine
    if e_machine != expected_machine:
        info["error"] = (
            f"{label}: expected e_machine=0x{expected_machine:04x} ({expected_machine}), "
            f"got 0x{e_machine:04x} ({e_machine})"
        )
        return info
    if e_type not in (2, 3):  # ET_EXEC or ET_DYN
        info["error"] = f"{label}: unexpected ELF e_type={e_type} (expected ET_EXEC=2 or ET_DYN=3)"
        return info
    info["valid"] = True
    return info


def verify_rootfs_stream(
    fileobj: io.BufferedReader,
    abi: str,
) -> tuple[dict[str, Any], list[str], list[str]]:
    errors: list[str] = []
    warnings: list[str] = []
    seen: set[str] = set()
    entry_count = 0
    symlink_count = 0
    traversal_entries: list[str] = []
    special_files: list[str] = []
    qemu_helpers: list[str] = []
    non_exec: list[str] = []
    resolv_content: str | None = None

    try:
        with gzip.GzipFile(fileobj=fileobj, mode="rb") as gz_stream:
            with tarfile.open(fileobj=gz_stream, mode="r|") as tf:
                for member in tf:
                    raw_name = member.name
                    if (raw_name.startswith("/") and raw_name != "/") or ".." in PurePosixPath(raw_name).parts:
                        traversal_entries.append(raw_name)
                    norm = re.sub(r"^(\./+)+", "", raw_name)
                    if norm in ("", ".", "/"):
                        continue
                    entry_count += 1
                    seen.add(norm)

                    if member.issym():
                        symlink_count += 1
                    if member.ischr() or member.isblk() or member.isfifo():
                        special_files.append(norm)
                    base = os.path.basename(norm)
                    if base.startswith("qemu-") and (
                        base.endswith("-static") or base in ("qemu-aarch64", "qemu-x86_64")
                    ):
                        qemu_helpers.append(norm)
                    if norm in REQUIRED_EXECUTABLE_ROOTFS and (member.mode & 0o111) == 0:
                        non_exec.append(f"{norm} (mode={oct(member.mode)})")
                    if norm == "etc/resolv.conf" and member.isfile():
                        extracted = tf.extractfile(member)
                        if extracted is not None:
                            resolv_content = extracted.read().decode("utf-8", errors="replace")
            # Drain remaining compressed/trailing bytes so truncated gzip trailers fail cleanly.
            while gz_stream.read(65536):
                pass
    except (tarfile.TarError, gzip.BadGzipFile, EOFError, zlib.error, OSError, ValueError) as exc:
        errors.append(f"rootfs-{abi}.tar.gz stream error: {exc}")

    missing = [p for p in REQUIRED_ROOTFS_FILES if p not in seen]
    for group in REQUIRED_ROOTFS_ANY_OF:
        if not any(candidate in seen for candidate in group):
            missing.append(" or ".join(group))

    if traversal_entries:
        errors.append(f"rootfs-{abi}.tar.gz unsafe path traversal entries: {traversal_entries[:10]}")
    if missing:
        errors.append(f"rootfs-{abi}.tar.gz missing required entries: {missing}")
    if symlink_count == 0:
        errors.append(f"rootfs-{abi}.tar.gz contains zero symlinks")
    if special_files:
        errors.append(f"rootfs-{abi}.tar.gz contains device/FIFO special files: {special_files[:10]}")
    if qemu_helpers:
        errors.append(f"rootfs-{abi}.tar.gz contains leftover QEMU binaries: {qemu_helpers[:10]}")
    if non_exec:
        errors.append(f"rootfs-{abi}.tar.gz scripts missing executable bit: {non_exec}")
    if resolv_content is None or "127.0.0.53" in resolv_content or "nameserver" not in resolv_content:
        errors.append(f"rootfs-{abi}.tar.gz has invalid etc/resolv.conf: {resolv_content!r}")

    summary = {
        "entries": entry_count,
        "uniquePaths": len(seen),
        "symlinks": symlink_count,
        "missing": missing,
        "specialFiles": special_files[:10],
        "qemuHelpers": qemu_helpers[:10],
        "nonExecutableScripts": non_exec,
        "traversalEntries": traversal_entries[:10],
        "resolvConfValid": (
            resolv_content is not None
            and "nameserver" in resolv_content
            and "127.0.0.53" not in resolv_content
        ),
    }
    return summary, errors, warnings


def _parse_bool_attr(raw_val: str) -> bool | None:
    v = raw_val.strip().strip('"').lower()
    if v in ("true", "-1", "0xffffffff", "0x1", "1"):
        return True
    if v in ("false", "0", "0x0", "0x00000000"):
        return False
    m = re.search(r"(-?\d+|0x[0-9a-f]+)\s*$", v)
    if m:
        num = int(m.group(1), 0)
        return num != 0
    return None


def _parse_special_use_attr(raw_val: str) -> bool:
    low = raw_val.lower()
    if "specialuse" in low:
        return True
    m = re.search(r"(0x[0-9a-f]+|\d+)\s*$", low)
    if m:
        try:
            num = int(m.group(1), 0)
            return (num & SPECIAL_USE_BIT) != 0
        except ValueError:
            return False
    return False


def parse_xmltree_manifest(xmltree_output: str) -> dict[str, Any]:
    """Parse `aapt2 dump xmltree --file AndroidManifest.xml` output."""
    elements: list[tuple[int, str, dict[str, str]]] = []
    stack: list[tuple[int, str, dict[str, str]]] = []

    elem_re = re.compile(r"^(\s*)E:\s+(\S+)")
    attr_re = re.compile(r'^(\s*)A:\s+(?:https?://[^:]+:|android:)?([A-Za-z0-9_]+)(?:\([^)]*\))?=(.+)$')

    for line in xmltree_output.splitlines():
        em = elem_re.match(line)
        if em:
            indent = len(em.group(1))
            tag = em.group(2)
            while stack and stack[-1][0] >= indent:
                stack.pop()
            node = (indent, tag, {})
            stack.append(node)
            elements.append(node)
            continue

        am = attr_re.match(line)
        if am and stack:
            attr_name = am.group(2)
            raw_val = am.group(3).strip()
            # Strip trailing '(Raw: "...")' if present while preserving string value
            raw_clean = re.sub(r'\s*\(Raw:\s*"[^"]*"\)\s*$', "", raw_val).strip()
            if raw_clean.startswith('"') and raw_clean.endswith('"'):
                raw_clean = raw_clean[1:-1]
            stack[-1][2][attr_name] = raw_clean

    extract_native_libs: bool | None = None
    special_use_service = False
    linux_service_found = False
    automation_receiver_found = False
    automation_receiver_exported: bool | None = None

    for _, tag, attrs in elements:
        if tag == "application" and "extractNativeLibs" in attrs:
            extract_native_libs = _parse_bool_attr(attrs["extractNativeLibs"])
        elif tag == "service":
            name = attrs.get("name", "")
            if name.endswith("LinuxRuntimeService"):
                linux_service_found = True
                fg_type = attrs.get("foregroundServiceType", "")
                if fg_type and _parse_special_use_attr(fg_type):
                    special_use_service = True
        elif tag == "receiver":
            name = attrs.get("name", "")
            if name.endswith("AutomationReceiver"):
                automation_receiver_found = True
                if "exported" in attrs:
                    automation_receiver_exported = _parse_bool_attr(attrs["exported"])

    return {
        "extractNativeLibs": extract_native_libs,
        "linuxServiceFound": linux_service_found,
        "specialUseService": special_use_service,
        "automationReceiverFound": automation_receiver_found,
        "automationReceiverExported": bool(
            automation_receiver_found and automation_receiver_exported is True
        ),
    }


def verify_aapt2_manifest(
    apk_path: str,
    expected_abi: str,
    aapt2_path: str,
    require_automation_receiver: bool,
) -> tuple[dict[str, Any], list[str], list[str]]:
    errors: list[str] = []
    warnings: list[str] = []
    manifest_info: dict[str, Any] = {
        "skipped": False,
        "aapt2Path": aapt2_path,
        "package": None,
        "permissions": [],
        "nativeCode": [],
        "extractNativeLibs": None,
        "specialUseService": False,
        "automationReceiverFound": False,
        "automationReceiverExported": False,
    }

    badging_res = subprocess.run(
        [aapt2_path, "dump", "badging", apk_path],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    if badging_res.returncode != 0:
        errors.append(f"aapt2 dump badging failed: {badging_res.stderr.strip()}")
    else:
        badging_out = badging_res.stdout
        pkg_m = re.search(r"package:\s+name='([^']+)'", badging_out)
        if pkg_m:
            manifest_info["package"] = pkg_m.group(1)
        if manifest_info["package"] != "com.pocketlinux":
            errors.append(
                f"Unexpected applicationId in manifest: {manifest_info['package']!r} (expected 'com.pocketlinux')"
            )

        perms = re.findall(r"uses-permission:\s+name='([^']+)'", badging_out)
        manifest_info["permissions"] = perms
        for req_perm in (
            "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
            "android.permission.POST_NOTIFICATIONS",
        ):
            if req_perm not in perms:
                errors.append(f"Missing required permission in badging: {req_perm}")

        nc_m = re.search(r"native-code:\s+(.+)", badging_out)
        if nc_m:
            abis = re.findall(r"'([^']+)'", nc_m.group(1))
            manifest_info["nativeCode"] = abis
            if abis != [expected_abi]:
                errors.append(
                    f"Manifest native-code mismatch: got {abis}, expected [{expected_abi!r}]"
                )
        else:
            errors.append(
                f"Manifest missing native-code in aapt2 dump badging (expected [{expected_abi!r}])"
            )

    xmltree_res = subprocess.run(
        [aapt2_path, "dump", "xmltree", "--file", "AndroidManifest.xml", apk_path],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    if xmltree_res.returncode != 0:
        errors.append(f"aapt2 dump xmltree failed: {xmltree_res.stderr.strip()}")
    else:
        parsed = parse_xmltree_manifest(xmltree_res.stdout)
        manifest_info.update(parsed)
        if parsed["extractNativeLibs"] is not True:
            errors.append(
                f"Manifest android:extractNativeLibs must be true (got {parsed['extractNativeLibs']!r})"
            )
        if not parsed["linuxServiceFound"]:
            errors.append("Manifest missing LinuxRuntimeService declaration")
        elif not parsed["specialUseService"]:
            errors.append("LinuxRuntimeService missing foregroundServiceType='specialUse' (0x40000000)")

        if not parsed["automationReceiverExported"]:
            msg = "AutomationReceiver missing or android:exported!=true in APK manifest"
            if require_automation_receiver:
                errors.append(msg)
            else:
                warnings.append(msg)

    return manifest_info, errors, warnings


def verify_single_apk(apk_path: str, abi: str, args: argparse.Namespace) -> dict[str, Any]:
    errors: list[str] = []
    warnings: list[str] = []
    result: dict[str, Any] = {
        "path": apk_path,
        "abi": abi,
        "exists": os.path.isfile(apk_path),
        "status": "FAIL",
        "sizeBytes": 0,
        "sizeMiB": 0.0,
        "sha256": None,
        "zipCrcOk": False,
        "coreEntriesOk": False,
        "abiIsolationOk": False,
        "elfChecks": {},
        "rootfs": {},
        "manifest": {"skipped": True},
        "apksigner": {"verified": False, "skipped": True},
        "warnings": warnings,
        "errors": errors,
    }

    if not result["exists"]:
        errors.append(f"APK file not found: {apk_path}")
        return result

    size_bytes = os.path.getsize(apk_path)
    size_mib = size_bytes / (1024.0 * 1024.0)
    result["sizeBytes"] = size_bytes
    result["sizeMiB"] = round(size_mib, 3)
    result["sha256"] = compute_sha256(apk_path)

    if size_mib < args.min_size_mb:
        errors.append(f"APK size {size_mib:.2f} MiB < minimum {args.min_size_mb:.2f} MiB")
    elif size_mib > args.max_size_mb:
        errors.append(f"APK size {size_mib:.2f} MiB > maximum {args.max_size_mb:.2f} MiB")
    elif size_mib > args.warn_size_mb:
        warnings.append(
            f"APK size {size_mib:.2f} MiB exceeds warn threshold {args.warn_size_mb:.2f} MiB "
            f"(ceiling {args.max_size_mb:.2f} MiB)"
        )

    expected_machine = ELF_MACHINES[abi]
    expected_asset = f"assets/rootfs-{abi}.tar.gz"

    try:
        with zipfile.ZipFile(apk_path, "r") as zf:
            bad_entry = zf.testzip()
            if bad_entry is not None:
                errors.append(f"ZIP CRC check failed on entry: {bad_entry}")
            else:
                result["zipCrcOk"] = True

            names = set(zf.namelist())
            missing_core = [c for c in ("AndroidManifest.xml", "classes.dex", "resources.arsc") if c not in names]
            if missing_core:
                errors.append(f"Missing core APK entries: {missing_core}")
            else:
                result["coreEntriesOk"] = True

            # Strict ABI isolation across lib/* and assets/rootfs-*
            unexpected_lib_abis = sorted({
                parts[1]
                for n in names
                if n.startswith("lib/")
                for parts in [n.split("/")]
                if len(parts) >= 3 and parts[1] != abi
            })
            unexpected_rootfs = sorted(
                n for n in names
                if re.match(r"^assets/rootfs-.*\.tar(\.gz)?$", n) and n != expected_asset
            )
            if unexpected_lib_abis:
                errors.append(f"Strict ABI violation: unexpected lib/<abi> dirs {unexpected_lib_abis}")
            if unexpected_rootfs:
                errors.append(f"Strict ABI violation: unexpected rootfs assets {unexpected_rootfs}")
            result["abiIsolationOk"] = not unexpected_lib_abis and not unexpected_rootfs

            for lib_name in REQUIRED_ELF_LIBS:
                lib_path = f"lib/{abi}/{lib_name}"
                if lib_path not in names:
                    err = f"Missing native library: {lib_path}"
                    errors.append(err)
                    result["elfChecks"][lib_name] = {"present": False, "valid": False, "error": err}
                else:
                    elf_info = verify_elf64(zf.read(lib_path), expected_machine, lib_path)
                    elf_info["present"] = True
                    result["elfChecks"][lib_name] = elf_info
                    if not elf_info["valid"] and elf_info["error"]:
                        errors.append(elf_info["error"])

            if expected_asset not in names:
                actual_assets = sorted(n for n in names if n.startswith("assets/"))
                errors.append(f"Missing rootfs asset {expected_asset} (found: {actual_assets})")
            else:
                asset_info = zf.getinfo(expected_asset)
                is_stored = asset_info.compress_type == zipfile.ZIP_STORED
                if not is_stored:
                    errors.append(
                        f"{expected_asset} must be ZIP_STORED (compress_type={asset_info.compress_type}); "
                        "check androidResources.noCompress / merge*Assets"
                    )
                with zf.open(expected_asset, "r") as asset_stream:
                    rootfs_summary, rf_errs, rf_warns = verify_rootfs_stream(asset_stream, abi)
                rootfs_summary["zipStored"] = is_stored
                rootfs_summary["compressedSizeBytes"] = asset_info.compress_size
                rootfs_summary["uncompressedSizeBytes"] = asset_info.file_size
                result["rootfs"] = rootfs_summary
                errors.extend(rf_errs)
                warnings.extend(rf_warns)
    except (zipfile.BadZipFile, OSError) as exc:
        errors.append(f"Failed to open APK as ZIP: {exc}")

    # aapt2 checks
    if not args.skip_aapt2:
        aapt2_bin = find_sdk_tool("aapt2", args.aapt2)
        if not aapt2_bin:
            msg = "aapt2 not found in PATH or ANDROID_HOME/build-tools"
            if args.require_aapt2:
                errors.append(msg)
            else:
                warnings.append(f"{msg}; manifest checks skipped")
        else:
            m_info, m_errs, m_warns = verify_aapt2_manifest(
                apk_path, abi, aapt2_bin, args.require_automation_receiver
            )
            result["manifest"] = m_info
            errors.extend(m_errs)
            warnings.extend(m_warns)

    # apksigner checks
    if not args.skip_apksigner:
        apksigner_bin = find_sdk_tool("apksigner", args.apksigner)
        if not apksigner_bin:
            msg = "apksigner not found in PATH or ANDROID_HOME/build-tools"
            if args.require_apksigner:
                errors.append(msg)
            else:
                warnings.append(f"{msg}; signature check skipped")
        else:
            sig_res = subprocess.run(
                [apksigner_bin, "verify", "--verbose", "--print-certs", apk_path],
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
            )
            cert_lines = [
                ln.strip()
                for ln in sig_res.stdout.splitlines()
                if "Signer #" in ln or "Verified using" in ln
            ]
            result["apksigner"] = {
                "verified": sig_res.returncode == 0,
                "skipped": False,
                "toolPath": apksigner_bin,
                "details": cert_lines[:6],
            }
            if sig_res.returncode != 0:
                errors.append(f"apksigner verify failed:\n{sig_res.stdout.strip()}")

    if errors:
        result["status"] = "FAIL"
    elif warnings:
        result["status"] = "WARN"
    else:
        result["status"] = "PASS"

    return result


def render_markdown(report: dict[str, Any]) -> str:
    lines = [
        "# PocketLinux Static APK Verification Report",
        "",
        f"- **Generated At**: `{report['generatedAt']}`",
        f"- **Overall Status**: **{report['overallStatus']}**",
        (
            f"- **Size Thresholds (MiB)**: min `{report['thresholdsMiB']['min']}`, "
            f"warn `{report['thresholdsMiB']['warn']}`, max `{report['thresholdsMiB']['max']}`"
        ),
        "",
        "| ABI | Status | Size (MiB) | ELF64 | Rootfs Entries (Symlinks) | `aapt2` Manifest | `apksigner` | Warnings / Errors |",
        "|---|---|---:|:---:|:---:|:---:|:---:|---|",
    ]
    for abi, info in report["apks"].items():
        elf_ok = bool(info["elfChecks"]) and all(v.get("valid") for v in info["elfChecks"].values())
        rf = info.get("rootfs") or {}
        rf_str = f"{rf.get('entries', 0)} ({rf.get('symlinks', 0)})" if rf else "missing"
        man = info.get("manifest") or {}
        if man.get("skipped"):
            man_str = "skipped"
        else:
            man_str = (
                f"pkg={man.get('package')}, extract={man.get('extractNativeLibs')}, "
                f"specialUse={man.get('specialUseService')}, receiver={man.get('automationReceiverExported')}"
            )
        sig = info.get("apksigner") or {}
        sig_str = "skipped" if sig.get("skipped") else ("PASS" if sig.get("verified") else "FAIL")
        notes = []
        for e in info.get("errors", []):
            notes.append(f"ERR: {e.splitlines()[0]}")
        for w in info.get("warnings", []):
            notes.append(f"WARN: {w}")
        note_str = "; ".join(notes) if notes else "None"
        lines.append(
            f"| `{abi}` | **{info['status']}** | {info['sizeMiB']:.2f} | "
            f"{'PASS' if elf_ok else 'FAIL'} | {rf_str} | {man_str} | {sig_str} | {note_str} |"
        )
    lines.append("")
    return "\n".join(lines)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Verify PocketLinux arm64 and/or x86_64 APKs (size, ABI, ELF64, rootfs, manifest, signature)."
    )
    parser.add_argument("--arm64-apk", default=None, help="Path to arm64-v8a APK")
    parser.add_argument(
        "--x86-64-apk", "--x86_64-apk",
        dest="x86_64_apk",
        default=None,
        help="Path to x86_64 APK",
    )
    parser.add_argument(
        "--out-json", "--json-out",
        dest="out_json",
        default=None,
        help="Output path for structured JSON report",
    )
    parser.add_argument(
        "--out-md", "--md-out",
        dest="out_md",
        default=None,
        help="Output path for Markdown summary report",
    )
    parser.add_argument("--min-size-mb", type=float, default=20.0, help="Minimum APK size in MiB (default: 20.0)")
    parser.add_argument("--warn-size-mb", type=float, default=75.0, help="Warn APK size threshold in MiB (default: 75.0)")
    parser.add_argument("--max-size-mb", type=float, default=85.0, help="Maximum APK size in MiB (default: 85.0)")
    parser.add_argument("--aapt2", default=None, help="Explicit path to aapt2 binary")
    parser.add_argument("--skip-aapt2", action="store_true", help="Skip aapt2 manifest checks")
    parser.add_argument("--require-aapt2", action="store_true", help="Fail if aapt2 binary is not found")
    parser.add_argument("--apksigner", default=None, help="Explicit path to apksigner binary")
    parser.add_argument("--skip-apksigner", action="store_true", help="Skip apksigner verification")
    parser.add_argument("--require-apksigner", action="store_true", help="Fail if apksigner binary is not found")
    parser.add_argument(
        "--require-automation-receiver",
        action="store_true",
        help="Fail if exported AutomationReceiver is absent from manifest",
    )
    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()

    targets: list[tuple[str, str]] = []
    if args.arm64_apk:
        targets.append(("arm64-v8a", args.arm64_apk))
    if args.x86_64_apk:
        targets.append(("x86_64", args.x86_64_apk))

    if not targets:
        parser.error("At least one of --arm64-apk or --x86-64-apk/--x86_64-apk must be provided.")

    apk_results: dict[str, Any] = {}
    for abi, path in targets:
        res = verify_single_apk(path, abi, args)
        apk_results[abi] = res
        print(
            f"[verify_apks] {abi} ({path}): {res['status']} "
            f"[{res['sizeMiB']:.2f} MiB, errors={len(res['errors'])}, warnings={len(res['warnings'])}]"
        )
        for w in res["warnings"]:
            print(f"[verify_apks]   WARN: {w}", file=sys.stderr)
        for e in res["errors"]:
            print(f"[verify_apks]   FAIL: {e}", file=sys.stderr)

    statuses = [r["status"] for r in apk_results.values()]
    if "FAIL" in statuses:
        overall = "FAIL"
    elif "WARN" in statuses:
        overall = "WARN"
    else:
        overall = "PASS"

    report: dict[str, Any] = {
        "generatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "overallStatus": overall,
        "thresholdsMiB": {
            "min": args.min_size_mb,
            "warn": args.warn_size_mb,
            "max": args.max_size_mb,
        },
        "apks": apk_results,
    }

    if args.out_json:
        parent = os.path.dirname(os.path.abspath(args.out_json))
        if parent:
            os.makedirs(parent, exist_ok=True)
        with open(args.out_json, "w", encoding="utf-8") as f:
            json.dump(report, f, indent=2)
            f.write("\n")

    if args.out_md:
        parent = os.path.dirname(os.path.abspath(args.out_md))
        if parent:
            os.makedirs(parent, exist_ok=True)
        with open(args.out_md, "w", encoding="utf-8") as f:
            f.write(render_markdown(report))

    return 1 if overall == "FAIL" else 0


if __name__ == "__main__":
    sys.exit(main())
