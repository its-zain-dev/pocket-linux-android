#!/usr/bin/env python3
"""PocketLinux Persistent Background Package & Download Manager Daemon (127.0.0.1:18765).

Features:
- Detached background service: survives closing PocketStore or Download Manager windows.
- Shared live state across unlimited Store / Download Manager windows.
- Concurrent multi-app downloads + parallel multi-stream .apk fetching (up to 6 threads)
  with real byte-accurate 0%-100% progress and live KB/s / MB/s speed.
- Serialized atomic local `apk` install phase (prevents exit code 99 DB lock collisions).
- Automatic post-install desktop/menu/Android app sync via pocketlinux-sync-apps.py.
- Direct HTTP/HTTPS URL downloader with parallel range-chunk acceleration into /root/Downloads.
"""

from concurrent.futures import ThreadPoolExecutor, as_completed
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
import re
import shlex
import shutil
import socket
import subprocess
import sys
import tarfile
import threading
import time
import urllib.parse
import urllib.request
import uuid

DAEMON_HOST = "127.0.0.1"
DAEMON_PORT = 18765
PID_FILE = "/tmp/.pocketlinux_pkgd.pid"
STATE_FILE = "/tmp/.pocketlinux_downloads.json"
LOG_DIR = "/tmp/pocketlinux_dl_logs"
CONF_DIR = "/root/.config/pocketlinux"
DL_CONF_FILE = os.path.join(CONF_DIR, "downloads.json")
NET_ENV_FILE = os.path.join(CONF_DIR, "network.env")
DOWNLOADS_DIR = "/root/Downloads"
APK_CACHE_DIR = "/var/cache/apk"
ALPINE_MIRROR = "https://dl-cdn.alpinelinux.org/alpine/v3.20"


def get_arch() -> str:
    try:
        return os.uname().machine
    except Exception:
        return "aarch64"


def load_network_env() -> dict:
    env = os.environ.copy()
    env["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    if os.path.isfile(NET_ENV_FILE):
        try:
            with open(NET_ENV_FILE, "r", encoding="utf-8", errors="replace") as f:
                for raw in f:
                    line = raw.strip()
                    if line.startswith("export ") and "=" in line:
                        k, v = line[7:].split("=", 1)
                        env[k.strip()] = v.strip().strip('"').strip("'")
                    elif line.startswith("unset "):
                        for k in line[6:].split():
                            env.pop(k.strip(), None)
        except OSError:
            pass
    return env


def run_fix_dns(env: dict | None = None) -> None:
    if os.path.isfile("/usr/local/bin/fix-dns.sh"):
        try:
            subprocess.run(
                ["/usr/local/bin/fix-dns.sh"],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                env=env,
            )
        except OSError:
            pass


def build_url_opener(env: dict) -> urllib.request.OpenerDirector:
    proxies = {}
    http_p = env.get("http_proxy") or env.get("HTTP_PROXY")
    https_p = env.get("https_proxy") or env.get("HTTPS_PROXY")
    if http_p:
        proxies["http"] = http_p
    if https_p:
        proxies["https"] = https_p
    handlers = [urllib.request.ProxyHandler(proxies)] if proxies else [urllib.request.ProxyHandler({})]
    opener = urllib.request.build_opener(*handlers)
    opener.addheaders = [("User-Agent", "PocketLinux-DownloadManager/2.1")]
    return opener


def parse_apkindex_map(arch: str) -> dict:
    """Parse cached Alpine APKINDEX.*.tar.gz into { 'pkg-ver': (repo, size_bytes) }."""
    mapping = {}
    if not os.path.isdir(APK_CACHE_DIR):
        return mapping
    repo_urls = []
    try:
        with open("/etc/apk/repositories", "r", encoding="utf-8", errors="replace") as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#"):
                    repo_urls.append(line.rstrip("/"))
    except OSError:
        repo_urls = [f"{ALPINE_MIRROR}/main", f"{ALPINE_MIRROR}/community"]

    for fname in sorted(os.listdir(APK_CACHE_DIR)):
        if not (fname.startswith("APKINDEX.") and fname.endswith(".tar.gz")):
            continue
        path = os.path.join(APK_CACHE_DIR, fname)
        try:
            with tarfile.open(path, "r:gz") as tf:
                member = tf.extractfile("APKINDEX")
                if member is None:
                    continue
                content = member.read().decode("utf-8", errors="replace")
        except Exception:
            continue

        # Infer whether this index is main or community by probing known sentinel packages
        is_community = ("\nP:pcmanfm\n" in content or "\nP:firefox-esr\n" in content or "\nP:jwm\n" in content)
        repo_base = next(
            (r for r in repo_urls if ("/community" in r) == is_community),
            f"{ALPINE_MIRROR}/{'community' if is_community else 'main'}",
        )
        cur_p = ""
        cur_v = ""
        cur_s = 0
        for line in content.splitlines():
            if not line:
                if cur_p and cur_v:
                    mapping[f"{cur_p}-{cur_v}"] = (f"{repo_base}/{arch}/{cur_p}-{cur_v}.apk", cur_s)
                cur_p = ""
                cur_v = ""
                cur_s = 0
            elif line.startswith("P:"):
                cur_p = line[2:]
            elif line.startswith("V:"):
                cur_v = line[2:]
            elif line.startswith("S:"):
                try:
                    cur_s = int(line[2:])
                except ValueError:
                    cur_s = 0
        if cur_p and cur_v:
            mapping[f"{cur_p}-{cur_v}"] = (f"{repo_base}/{arch}/{cur_p}-{cur_v}.apk", cur_s)
    return mapping


class DownloadDaemon:
    def __init__(self) -> None:
        os.makedirs(LOG_DIR, exist_ok=True)
        os.makedirs(CONF_DIR, exist_ok=True)
        os.makedirs(DOWNLOADS_DIR, exist_ok=True)
        os.makedirs(APK_CACHE_DIR, exist_ok=True)

        self.lock = threading.RLock()
        self.install_lock = threading.Lock()
        self.index_lock = threading.Lock()
        self.last_index_update = 0.0
        self.max_streams = self._load_max_streams()
        self.tasks: dict[str, dict] = {}
        self.cancel_events: dict[str, threading.Event] = {}
        self.active_procs: dict[str, subprocess.Popen] = {}
        self.task_executor = ThreadPoolExecutor(max_workers=3, thread_name_prefix="pkgd-task")
        self.global_log_path = os.path.join(LOG_DIR, "daemon.log")
        self._load_persisted_state()
        self._log_global("PocketLinux Package & Download Daemon started on 127.0.0.1:18765")

    def _load_max_streams(self) -> int:
        try:
            with open(DL_CONF_FILE, "r", encoding="utf-8") as f:
                val = int(json.load(f).get("max_streams", 6))
                return max(1, min(8, val))
        except Exception:
            return 6

    def set_max_streams(self, streams: int) -> int:
        val = max(1, min(8, int(streams)))
        self.max_streams = val
        try:
            with open(DL_CONF_FILE, "w", encoding="utf-8") as f:
                json.dump({"max_streams": val}, f)
        except OSError:
            pass
        return val

    def _load_persisted_state(self) -> None:
        if not os.path.isfile(STATE_FILE):
            return
        try:
            with open(STATE_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)
            for t in data.get("tasks", []):
                tid = t.get("id")
                if not tid:
                    continue
                if t.get("status") in ("queued", "resolving", "downloading", "installing"):
                    t["status"] = "interrupted"
                    t["stage"] = "Interrupted before restart — click Retry"
                    t["speed_bps"] = 0
                self.tasks[tid] = t
        except Exception:
            pass

    def _persist_state_locked(self) -> None:
        try:
            payload = {
                "max_streams": self.max_streams,
                "tasks": list(self.tasks.values()),
                "updated_at": time.time(),
            }
            tmp = STATE_FILE + ".tmp"
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(payload, f)
            os.replace(tmp, STATE_FILE)
        except OSError:
            pass

    def _log_global(self, msg: str) -> None:
        ts = time.strftime("%H:%M:%S")
        line = f"[{ts}] {msg}\n"
        try:
            with open(self.global_log_path, "a", encoding="utf-8") as f:
                f.write(line)
        except OSError:
            pass

    def _log_task(self, tid: str, msg: str) -> None:
        ts = time.strftime("%H:%M:%S")
        line = f"[{ts}] {msg.rstrip()}\n"
        log_path = os.path.join(LOG_DIR, f"{tid}.log")
        try:
            with open(log_path, "a", encoding="utf-8") as f:
                f.write(line)
        except OSError:
            pass
        self._log_global(f"[{tid[:6]}] {msg.rstrip()}")

    def read_logs(self, tid: str | None = None) -> str:
        path = os.path.join(LOG_DIR, f"{tid}.log") if tid else self.global_log_path
        if not os.path.isfile(path):
            return "(No log entries yet)"
        try:
            with open(path, "r", encoding="utf-8", errors="replace") as f:
                data = f.read()
            return data[-40000:] if len(data) > 40000 else data
        except OSError:
            return "(Unable to read log file)"

    def get_status(self) -> dict:
        with self.lock:
            tasks_list = sorted(
                self.tasks.values(),
                key=lambda x: x.get("created_at", 0.0),
                reverse=True,
            )
            active = [t for t in tasks_list if t["status"] in ("queued", "resolving", "downloading", "installing")]
            total_speed = sum(t.get("speed_bps", 0) for t in active)
            return {
                "ok": True,
                "max_streams": self.max_streams,
                "active_count": len(active),
                "total_speed_bps": total_speed,
                "tasks": tasks_list,
            }

    def enqueue_install(self, name: str, packages: list[str], launch: str = "") -> dict:
        pkgs = [p.strip() for p in packages if p.strip()]
        with self.lock:
            # Deduplicate active task for same packages
            for existing in self.tasks.values():
                if existing.get("kind") == "install" and existing.get("packages") == pkgs and existing["status"] in (
                    "queued",
                    "resolving",
                    "downloading",
                    "installing",
                ):
                    return existing
            tid = uuid.uuid4().hex[:10]
            task = {
                "id": tid,
                "kind": "install",
                "name": name or " ".join(pkgs),
                "packages": pkgs,
                "launch": launch,
                "url": "",
                "dest": "",
                "status": "queued",
                "stage": "Queued for download...",
                "progress": 0.0,
                "downloaded_bytes": 0,
                "total_bytes": 0,
                "speed_bps": 0,
                "error": "",
                "created_at": time.time(),
                "finished_at": 0.0,
            }
            self.tasks[tid] = task
            self.cancel_events[tid] = threading.Event()
            self._persist_state_locked()
        self._log_task(tid, f"Queued install: {task['name']} ({' '.join(pkgs)})")
        self.task_executor.submit(self._run_install_task, tid)
        return task

    def enqueue_remove(self, name: str, packages: list[str]) -> dict:
        pkgs = [p.strip() for p in packages if p.strip()]
        with self.lock:
            tid = uuid.uuid4().hex[:10]
            task = {
                "id": tid,
                "kind": "remove",
                "name": f"Remove {name}",
                "packages": pkgs,
                "launch": "",
                "url": "",
                "dest": "",
                "status": "queued",
                "stage": "Queued for removal...",
                "progress": 0.0,
                "downloaded_bytes": 0,
                "total_bytes": 0,
                "speed_bps": 0,
                "error": "",
                "created_at": time.time(),
                "finished_at": 0.0,
            }
            self.tasks[tid] = task
            self.cancel_events[tid] = threading.Event()
            self._persist_state_locked()
        self.task_executor.submit(self._run_remove_task, tid)
        return task

    def enqueue_repair(self) -> dict:
        with self.lock:
            tid = uuid.uuid4().hex[:10]
            task = {
                "id": tid,
                "kind": "repair",
                "name": "System Update & Library Repair",
                "packages": [],
                "launch": "",
                "url": "",
                "dest": "",
                "status": "queued",
                "stage": "Queued...",
                "progress": 0.0,
                "downloaded_bytes": 0,
                "total_bytes": 0,
                "speed_bps": 0,
                "error": "",
                "created_at": time.time(),
                "finished_at": 0.0,
            }
            self.tasks[tid] = task
            self.cancel_events[tid] = threading.Event()
            self._persist_state_locked()
        self.task_executor.submit(self._run_repair_task, tid)
        return task

    def enqueue_url_download(self, url: str, filename: str = "") -> dict:
        url = url.strip()
        if not filename:
            parsed = urllib.parse.urlparse(url)
            base = os.path.basename(parsed.path) or "download.bin"
            filename = urllib.parse.unquote(base)
        filename = re.sub(r"[^A-Za-z0-9._-]+", "_", filename).strip("_") or "download.bin"
        dest = os.path.join(DOWNLOADS_DIR, filename)
        with self.lock:
            tid = uuid.uuid4().hex[:10]
            task = {
                "id": tid,
                "kind": "url",
                "name": filename,
                "packages": [],
                "launch": "",
                "url": url,
                "dest": dest,
                "status": "queued",
                "stage": "Queued URL download...",
                "progress": 0.0,
                "downloaded_bytes": 0,
                "total_bytes": 0,
                "speed_bps": 0,
                "error": "",
                "created_at": time.time(),
                "finished_at": 0.0,
            }
            self.tasks[tid] = task
            self.cancel_events[tid] = threading.Event()
            self._persist_state_locked()
        self._log_task(tid, f"Queued URL download: {url} -> {dest}")
        self.task_executor.submit(self._run_url_task, tid)
        return task

    def cancel_task(self, tid: str) -> bool:
        with self.lock:
            task = self.tasks.get(tid)
            if not task:
                return False
            ev = self.cancel_events.get(tid)
            if ev:
                ev.set()
            proc = self.active_procs.get(tid)
            if proc and proc.poll() is None:
                try:
                    proc.terminate()
                except Exception:
                    pass
            if task["status"] in ("queued", "resolving", "downloading", "installing"):
                task["status"] = "cancelled"
                task["stage"] = "Cancelled by user"
                task["speed_bps"] = 0
                task["finished_at"] = time.time()
                self._persist_state_locked()
                self._log_task(tid, "Cancelled by user.")
                return True
            return False

    def retry_task(self, tid: str) -> dict | None:
        with self.lock:
            old = self.tasks.get(tid)
            if not old:
                return None
            kind = old["kind"]
            name = old["name"]
            pkgs = list(old.get("packages", []))
            launch = old.get("launch", "")
            url = old.get("url", "")
            del self.tasks[tid]
            self._persist_state_locked()
        if kind == "install":
            return self.enqueue_install(name, pkgs, launch)
        if kind == "remove":
            return self.enqueue_remove(name.replace("Remove ", "", 1), pkgs)
        if kind == "repair":
            return self.enqueue_repair()
        if kind == "url":
            return self.enqueue_url_download(url, name)
        return None

    def clear_finished(self) -> int:
        with self.lock:
            done_ids = [
                tid
                for tid, t in self.tasks.items()
                if t["status"] in ("completed", "failed", "cancelled", "interrupted")
            ]
            for tid in done_ids:
                self.tasks.pop(tid, None)
            self._persist_state_locked()
            return len(done_ids)

    def _update_task(self, tid: str, **kwargs) -> None:
        with self.lock:
            t = self.tasks.get(tid)
            if not t:
                return
            if t["status"] == "cancelled" and kwargs.get("status") != "cancelled":
                return
            t.update(kwargs)
            self._persist_state_locked()

    def _ensure_apk_index(self, tid: str, env: dict) -> None:
        with self.index_lock:
            has_idx = any(
                f.startswith("APKINDEX.") and f.endswith(".tar.gz")
                for f in os.listdir(APK_CACHE_DIR)
            )
            if has_idx and (time.time() - self.last_index_update) < 1800:
                return
            self._log_task(tid, "Syncing network DNS & Alpine package index...")
            run_fix_dns(env)
            env.update(load_network_env())
            res = subprocess.run(
                ["/usr/local/sbin/apk", "--wait", "15", "--timeout", "25", "update"],
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                env=env,
            )
            for line in res.stdout.splitlines():
                self._log_task(tid, line)
            if res.returncode == 0:
                self.last_index_update = time.time()

    def _run_install_task(self, tid: str) -> None:
        cancel_ev = self.cancel_events[tid]
        with self.lock:
            task = dict(self.tasks[tid])
        pkgs = task["packages"]
        arch = get_arch()

        try:
            self._update_task(tid, status="resolving", stage="Checking network & resolving dependencies...", progress=3.0)
            run_fix_dns()
            env = load_network_env()
            self._ensure_apk_index(tid, env)
            if cancel_ev.is_set():
                return

            self._update_task(tid, progress=8.0, stage="Calculating dependency graph...")
            # Resolve exact packages to install via `apk add --simulate`
            sim = subprocess.run(
                ["/usr/local/sbin/apk", "add", "--simulate", "--upgrade", *pkgs],
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                env=env,
            )
            planned_pkg_vers = []
            for line in sim.stdout.splitlines():
                self._log_task(tid, line)
                # Pattern: "(1/12) Installing foo (1.2.3-r0)" or "Upgrading foo (1.0 -> 1.1)"
                m_inst = re.search(r"Installing\s+([^\s]+)\s+\(([^)\s]+)\)", line)
                m_upg = re.search(r"Upgrading\s+([^\s]+)\s+\([^)]*->\s*([^)\s]+)\)", line)
                if m_inst:
                    planned_pkg_vers.append(f"{m_inst.group(1)}-{m_inst.group(2)}")
                elif m_upg:
                    planned_pkg_vers.append(f"{m_upg.group(1)}-{m_upg.group(2)}")

            if sim.returncode != 0 and not planned_pkg_vers:
                self._log_task(tid, "Simulation returned error; attempting direct apk install...")

            # Map resolved packages to mirror URLs & sizes from APKINDEX
            index_map = parse_apkindex_map(arch)
            to_fetch = []
            total_bytes = 0
            for pv in planned_pkg_vers:
                info = index_map.get(pv)
                if not info:
                    continue
                url, sz = info
                # Check if already cached in /var/cache/apk (Alpine caches as <name>-<ver>.<hash>.apk or <name>-<ver>.apk)
                already_cached = any(
                    f.startswith(pv + ".") and f.endswith(".apk")
                    for f in os.listdir(APK_CACHE_DIR)
                )
                if not already_cached:
                    to_fetch.append((pv, url, max(sz, 4096)))
                    total_bytes += max(sz, 4096)

            # Phase 2: Parallel multi-stream download (8% -> 82%)
            dl_dir = os.path.join("/tmp/pocketlinux_dl_stage", tid)
            os.makedirs(dl_dir, exist_ok=True)
            fetched_files = []

            if to_fetch and not cancel_ev.is_set():
                self._update_task(
                    tid,
                    status="downloading",
                    stage=f"Downloading {len(to_fetch)} package(s) ({self.max_streams} streams)...",
                    total_bytes=total_bytes,
                    downloaded_bytes=0,
                    progress=10.0,
                )
                self._log_task(
                    tid,
                    f"Starting parallel download of {len(to_fetch)} packages (~{total_bytes / (1024*1024):.2f} MiB) with {self.max_streams} streams",
                )

                dl_lock = threading.Lock()
                dl_bytes = [0]
                done_count = [0]
                t_start = time.time()
                last_report = [0.0]
                opener = build_url_opener(env)

                def fetch_one(item: tuple[str, str, int]) -> str | None:
                    pv, url, est_size = item
                    if cancel_ev.is_set():
                        return None
                    out_path = os.path.join(dl_dir, f"{pv}.apk")
                    part_path = out_path + ".part"
                    try:
                        req = urllib.request.Request(url)
                        with opener.open(req, timeout=25) as resp:
                            with open(part_path, "wb") as out_f:
                                while not cancel_ev.is_set():
                                    chunk = resp.read(65536)
                                    if not chunk:
                                        break
                                    out_f.write(chunk)
                                    with dl_lock:
                                        dl_bytes[0] += len(chunk)
                                        now = time.time()
                                        if now - last_report[0] >= 0.2:
                                            last_report[0] = now
                                            elapsed = max(0.1, now - t_start)
                                            spd = int(dl_bytes[0] / elapsed)
                                            ratio = min(1.0, dl_bytes[0] / max(1, total_bytes))
                                            pct = 10.0 + ratio * 72.0
                                            self._update_task(
                                                tid,
                                                downloaded_bytes=dl_bytes[0],
                                                speed_bps=spd,
                                                progress=round(pct, 1),
                                                stage=f"Downloading ({done_count[0]}/{len(to_fetch)} pkgs)...",
                                            )
                        if cancel_ev.is_set():
                            return None
                        os.replace(part_path, out_path)
                        with dl_lock:
                            done_count[0] += 1
                            elapsed = max(0.1, time.time() - t_start)
                            spd = int(dl_bytes[0] / elapsed)
                            ratio = min(1.0, dl_bytes[0] / max(1, total_bytes))
                            self._update_task(
                                tid,
                                downloaded_bytes=dl_bytes[0],
                                speed_bps=spd,
                                progress=round(10.0 + ratio * 72.0, 1),
                                stage=f"Downloading ({done_count[0]}/{len(to_fetch)} pkgs)...",
                            )
                        return out_path
                    except Exception as exc:
                        self._log_task(tid, f"Stream note for {pv}: {exc} (will let apk fetch directly)")
                        return None

                with ThreadPoolExecutor(max_workers=self.max_streams) as pool:
                    futs = [pool.submit(fetch_one, item) for item in to_fetch]
                    for fut in as_completed(futs):
                        res_path = fut.result()
                        if res_path:
                            fetched_files.append(res_path)

            if cancel_ev.is_set():
                shutil.rmtree(dl_dir, ignore_errors=True)
                return

            # Phase 3: Atomic local installation (82% -> 96%)
            self._update_task(tid, status="installing", stage="Waiting for package DB lock...", progress=82.0, speed_bps=0)
            with self.install_lock:
                if cancel_ev.is_set():
                    shutil.rmtree(dl_dir, ignore_errors=True)
                    return
                self._update_task(tid, status="installing", stage="Installing packages into Alpine Linux...", progress=84.0)
                if fetched_files and len(fetched_files) == len(to_fetch):
                    # Install directly from our locally downloaded .apk files + allow apk to pull any extra
                    cmd = ["/usr/local/sbin/apk", "add", "--upgrade", "--allow-untrusted", *fetched_files, *pkgs]
                else:
                    cmd = ["/usr/local/sbin/apk", "add", "--upgrade", *pkgs]
                self._log_task(tid, "$ " + " ".join(shlex.quote(c) for c in cmd[:8]) + (" ..." if len(cmd) > 8 else ""))
                proc = subprocess.Popen(
                    cmd,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,
                    text=True,
                    env=env,
                )
                with self.lock:
                    self.active_procs[tid] = proc
                assert proc.stdout is not None
                step_re = re.compile(r"\((\d+)/(\d+)\)")
                for line in proc.stdout:
                    self._log_task(tid, line)
                    m = step_re.search(line)
                    if m:
                        cur_i, tot_i = int(m.group(1)), max(1, int(m.group(2)))
                        pct = 84.0 + (cur_i / tot_i) * 11.0
                        self._update_task(tid, progress=round(pct, 1), stage=f"Installing ({cur_i}/{tot_i})...")
                code = proc.wait()
                with self.lock:
                    self.active_procs.pop(tid, None)

            shutil.rmtree(dl_dir, ignore_errors=True)

            if cancel_ev.is_set():
                return

            if code != 0:
                self._update_task(
                    tid,
                    status="failed",
                    stage=f"Install failed (exit code {code}) — open Log for details",
                    error=f"apk exited with code {code}",
                    speed_bps=0,
                    finished_at=time.time(),
                )
                return

            # Phase 4: Sync desktop shortcuts, Start Menu, Applications folder & Firefox proxy policy (96% -> 100%)
            self._update_task(tid, progress=96.0, stage="Adding app to Desktop, Menu & Apps...")
            run_fix_dns(env)
            sync_res = subprocess.run(
                ["/usr/local/bin/pocketlinux-sync-apps.py"],
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                env=env,
            )
            if sync_res.stdout.strip():
                self._log_task(tid, sync_res.stdout.strip())

            self._update_task(
                tid,
                status="completed",
                stage="Installed & added to Desktop!",
                progress=100.0,
                speed_bps=0,
                finished_at=time.time(),
            )
            self._log_task(tid, f"SUCCESS: {task['name']} installed and synced to Desktop.")
        except Exception as exc:
            self._log_task(tid, f"ERROR: {exc}")
            self._update_task(
                tid,
                status="failed",
                stage=f"Error: {exc}",
                error=str(exc),
                speed_bps=0,
                finished_at=time.time(),
            )

    def _run_remove_task(self, tid: str) -> None:
        with self.lock:
            task = dict(self.tasks[tid])
        pkgs = task["packages"]
        env = load_network_env()
        try:
            self._update_task(tid, status="installing", stage="Removing packages...", progress=25.0)
            with self.install_lock:
                proc = subprocess.Popen(
                    ["/usr/local/sbin/apk", "del", *pkgs],
                    stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,
                    text=True,
                    env=env,
                )
                assert proc.stdout is not None
                for line in proc.stdout:
                    self._log_task(tid, line)
                code = proc.wait()
            subprocess.run(["/usr/local/bin/pocketlinux-sync-apps.py"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, env=env)
            if code == 0:
                self._update_task(tid, status="completed", stage="Removed cleanly", progress=100.0, finished_at=time.time())
            else:
                self._update_task(tid, status="failed", stage=f"Remove failed ({code})", error=f"exit {code}", finished_at=time.time())
        except Exception as exc:
            self._update_task(tid, status="failed", stage=str(exc), error=str(exc), finished_at=time.time())

    def _run_repair_task(self, tid: str) -> None:
        env = load_network_env()
        try:
            self._update_task(tid, status="installing", stage="Unlocking DB & repairing libraries...", progress=20.0)
            run_fix_dns(env)
            env = load_network_env()
            with self.install_lock:
                try:
                    os.remove("/lib/apk/db/lock")
                except OSError:
                    pass
                proc = subprocess.Popen(
                    ["/bin/sh", "-c", "/usr/local/sbin/apk update && /usr/local/sbin/apk upgrade && /usr/local/sbin/apk fix"],
                    stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,
                    text=True,
                    env=env,
                )
                assert proc.stdout is not None
                for line in proc.stdout:
                    self._log_task(tid, line)
                code = proc.wait()
            subprocess.run(["/usr/local/bin/pocketlinux-sync-apps.py"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, env=env)
            if code == 0:
                self.last_index_update = time.time()
                self._update_task(tid, status="completed", stage="All packages & libraries healthy", progress=100.0, finished_at=time.time())
            else:
                self._update_task(tid, status="failed", stage=f"Repair exited ({code})", error=f"exit {code}", finished_at=time.time())
        except Exception as exc:
            self._update_task(tid, status="failed", stage=str(exc), error=str(exc), finished_at=time.time())

    def _run_url_task(self, tid: str) -> None:
        cancel_ev = self.cancel_events[tid]
        with self.lock:
            task = dict(self.tasks[tid])
        url = task["url"]
        dest = task["dest"]
        part = dest + ".part"
        try:
            self._update_task(tid, status="downloading", stage="Connecting...", progress=1.0)
            run_fix_dns()
            env = load_network_env()
            opener = build_url_opener(env)

            # Probe size and range support
            total_size = 0
            accept_ranges = False
            try:
                head_req = urllib.request.Request(url, method="HEAD")
                with opener.open(head_req, timeout=15) as hr:
                    cl = hr.headers.get("Content-Length")
                    if cl and cl.isdigit():
                        total_size = int(cl)
                    accept_ranges = "bytes" in (hr.headers.get("Accept-Ranges") or "").lower()
            except Exception:
                pass

            t_start = time.time()
            # Use multi-part parallel range download for files >= 2 MiB supporting byte ranges
            if accept_ranges and total_size >= 2 * 1024 * 1024 and self.max_streams >= 2:
                parts = min(self.max_streams, 6)
                chunk_sz = (total_size + parts - 1) // parts
                self._log_task(tid, f"Accelerated {parts}-stream download ({total_size / (1024*1024):.2f} MiB)")
                self._update_task(tid, total_bytes=total_size, stage=f"Downloading ({parts} parallel streams)...")
                with open(part, "wb") as f:
                    f.truncate(total_size)

                dl_lock = threading.Lock()
                dl_bytes = [0]
                last_rep = [0.0]

                def dl_range(idx: int) -> None:
                    start = idx * chunk_sz
                    end = min(total_size - 1, start + chunk_sz - 1)
                    if start > end:
                        return
                    req = urllib.request.Request(url, headers={"Range": f"bytes={start}-{end}"})
                    with opener.open(req, timeout=30) as resp:
                        with open(part, "r+b") as wf:
                            wf.seek(start)
                            while not cancel_ev.is_set():
                                data = resp.read(65536)
                                if not data:
                                    break
                                wf.write(data)
                                with dl_lock:
                                    dl_bytes[0] += len(data)
                                    now = time.time()
                                    if now - last_rep[0] >= 0.2:
                                        last_rep[0] = now
                                        spd = int(dl_bytes[0] / max(0.1, now - t_start))
                                        pct = min(99.5, (dl_bytes[0] * 100.0) / max(1, total_size))
                                        self._update_task(
                                            tid,
                                            downloaded_bytes=dl_bytes[0],
                                            speed_bps=spd,
                                            progress=round(pct, 1),
                                        )

                with ThreadPoolExecutor(max_workers=parts) as pool:
                    futs = [pool.submit(dl_range, i) for i in range(parts)]
                    for fut in as_completed(futs):
                        fut.result()
            else:
                req = urllib.request.Request(url)
                with opener.open(req, timeout=30) as resp:
                    cl = resp.headers.get("Content-Length")
                    if cl and cl.isdigit():
                        total_size = int(cl)
                    self._update_task(tid, total_bytes=total_size, stage="Downloading...")
                    downloaded = 0
                    last_rep = 0.0
                    with open(part, "wb") as wf:
                        while not cancel_ev.is_set():
                            data = resp.read(65536)
                            if not data:
                                break
                            wf.write(data)
                            downloaded += len(data)
                            now = time.time()
                            if now - last_rep >= 0.2:
                                last_rep = now
                                spd = int(downloaded / max(0.1, now - t_start))
                                pct = min(99.5, (downloaded * 100.0) / total_size) if total_size > 0 else 50.0
                                self._update_task(
                                    tid,
                                    downloaded_bytes=downloaded,
                                    speed_bps=spd,
                                    progress=round(pct, 1),
                                )

            if cancel_ev.is_set():
                try:
                    os.remove(part)
                except OSError:
                    pass
                return

            os.replace(part, dest)
            self._log_task(tid, f"Saved to {dest}")
            self._update_task(
                tid,
                status="completed",
                stage=f"Saved to {dest}",
                progress=100.0,
                speed_bps=0,
                finished_at=time.time(),
            )
        except Exception as exc:
            self._log_task(tid, f"Download error: {exc}")
            try:
                os.remove(part)
            except OSError:
                pass
            self._update_task(
                tid,
                status="failed",
                stage=f"Failed: {exc}",
                error=str(exc),
                speed_bps=0,
                finished_at=time.time(),
            )


DAEMON: DownloadDaemon | None = None


class ApiHandler(BaseHTTPRequestHandler):
    def log_message(self, _fmt: str, *_args) -> None:
        return

    def _send_json(self, code: int, obj: dict) -> None:
        raw = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def _read_json(self) -> dict:
        length = int(self.headers.get("Content-Length", "0") or "0")
        if length <= 0:
            return {}
        raw = self.rfile.read(min(length, 1024 * 1024))
        try:
            return json.loads(raw.decode("utf-8"))
        except Exception:
            return {}

    def do_GET(self) -> None:
        assert DAEMON is not None
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path == "/api/ping":
            self._send_json(200, {"ok": True, "pid": os.getpid()})
        elif parsed.path == "/api/status":
            self._send_json(200, DAEMON.get_status())
        elif parsed.path == "/api/logs":
            qs = urllib.parse.parse_qs(parsed.query)
            tid = (qs.get("id") or [None])[0]
            self._send_json(200, {"ok": True, "log": DAEMON.read_logs(tid)})
        else:
            self._send_json(404, {"ok": False, "error": "Not found"})

    def do_POST(self) -> None:
        assert DAEMON is not None
        parsed = urllib.parse.urlparse(self.path)
        body = self._read_json()
        if parsed.path == "/api/install":
            task = DAEMON.enqueue_install(
                body.get("name", ""),
                body.get("packages", []),
                body.get("launch", ""),
            )
            self._send_json(200, {"ok": True, "task": task})
        elif parsed.path == "/api/remove":
            task = DAEMON.enqueue_remove(body.get("name", ""), body.get("packages", []))
            self._send_json(200, {"ok": True, "task": task})
        elif parsed.path == "/api/repair":
            task = DAEMON.enqueue_repair()
            self._send_json(200, {"ok": True, "task": task})
        elif parsed.path == "/api/download-url":
            url = str(body.get("url", "")).strip()
            if not url.startswith(("http://", "https://")):
                self._send_json(400, {"ok": False, "error": "URL must start with http:// or https://"})
                return
            task = DAEMON.enqueue_url_download(url, str(body.get("filename", "")))
            self._send_json(200, {"ok": True, "task": task})
        elif parsed.path == "/api/cancel":
            ok = DAEMON.cancel_task(str(body.get("id", "")))
            self._send_json(200, {"ok": ok})
        elif parsed.path == "/api/retry":
            task = DAEMON.retry_task(str(body.get("id", "")))
            self._send_json(200, {"ok": task is not None, "task": task})
        elif parsed.path == "/api/clear-done":
            n = DAEMON.clear_finished()
            self._send_json(200, {"ok": True, "cleared": n})
        elif parsed.path == "/api/config":
            streams = DAEMON.set_max_streams(int(body.get("max_streams", 6)))
            self._send_json(200, {"ok": True, "max_streams": streams})
        else:
            self._send_json(404, {"ok": False, "error": "Not found"})


def is_daemon_alive() -> bool:
    try:
        with socket.create_connection((DAEMON_HOST, DAEMON_PORT), timeout=0.5):
            return True
    except OSError:
        return False


def main() -> None:
    global DAEMON
    if is_daemon_alive():
        sys.exit(0)

    if "--foreground" not in sys.argv:
        try:
            os.setsid()
        except OSError:
            pass

    with open(PID_FILE, "w", encoding="utf-8") as f:
        f.write(str(os.getpid()) + "\n")

    DAEMON = DownloadDaemon()
    ThreadingHTTPServer.allow_reuse_address = True
    server = ThreadingHTTPServer((DAEMON_HOST, DAEMON_PORT), ApiHandler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
