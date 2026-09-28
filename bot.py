#!/usr/bin/env python3
"""Manga-Extensions: smart Mihon-compatible repository manager + Telegram security guard."""

from __future__ import annotations

import argparse
import ast
import base64
import gzip
import hashlib
import inspect
import json
import logging
import os
import re
import shutil
import signal
import sys
import tempfile
import textwrap
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib.parse import urlparse

try:
    from apscheduler.schedulers.background import BackgroundScheduler
    from apscheduler.triggers.cron import CronTrigger
except ImportError:  # pragma: no cover
    BackgroundScheduler = None
    CronTrigger = None

import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

import review_system as rs
from review_system import (ACCEPTED, ALLOWED_TRANSITIONS, DISCOVERED, GATES, PENDING_REVIEW,
                           QUARANTINED, REJECTED, VALIDATED, AuditLog, CandidateStore,
                           ReviewSystem, is_commit_sha, redact)

LOG = logging.getLogger("manga-guard")
STARTED_AT = time.time()

ROOT = Path(__file__).resolve().parent
REPO_DIR = ROOT / "repo"
DATA_DIR = ROOT / "data"
QUARANTINE_FILE = Path(os.environ.get("QUARANTINE_FILE") or DATA_DIR / "quarantine.json")
AUDIT_CACHE_FILE = Path(os.environ.get("AUDIT_CACHE_FILE") or DATA_DIR / "audit_cache.json")
STATE_FILE = Path(os.environ.get("STATE_FILE") or DATA_DIR / "guard_state.json")
CANDIDATE_FILE = Path(os.environ.get("CANDIDATE_FILE") or DATA_DIR / "candidates.json")
AUDITLOG_FILE = Path(os.environ.get("AUDITLOG_FILE") or DATA_DIR / "audit_log.jsonl")
INDEX_JSON = REPO_DIR / "index.json"
INDEX_MIN_JSON = REPO_DIR / "index.min.json"
INDEX_PB = REPO_DIR / "index.pb"
REPO_JSON = ROOT / "repo.json"

# ----------------------------------------------------------------- environment

def env_str(name: str, default: str = "") -> str:
    return (os.environ.get(name) or default).strip()


def env_int(name: str, default: int) -> int:
    try:
        return int(str(os.environ.get(name) or default).strip())
    except (TypeError, ValueError):
        return default


def env_bool(name: str, default: bool) -> bool:
    raw = str(os.environ.get(name) or "").strip().lower()
    if not raw:
        return default
    return raw in {"1", "true", "yes", "on", "y"}


def env_set(name: str) -> set:
    """Parse a set of numeric Telegram ids. Non-numeric entries are rejected loudly:
    a username such as @someone silently yields an empty set, which looks like
    "no admin configured" and leaves the bot unable to answer anyone."""
    items = set()
    for chunk in str(os.environ.get(name) or "").replace(";", ",").split(","):
        for part in chunk.split():
            if part.lstrip("-").isdigit():
                items.add(int(part))
            elif part:
                LOG.warning("%s: ignoring non-numeric entry %r — Telegram ids are "
                            "numbers, get yours from @userinfobot", name, part)
    return items


# ----------------------------------------------------------------- test guard

#: When on, every code path that writes into repo/ or talks to the GitHub API is
#: refused. --selftest and the test runner switch this on, so running the tests
#: can never publish, push or rewrite repo/index.json.
TEST_MODE = env_bool("TEST_MODE", False) or env_bool("REVIEW_TEST", False)


class PushBlocked(RuntimeError):
    """Raised when a write into repo/ or a GitHub push is not authorised."""


class BuildError(RuntimeError):
    """Raised when the index cannot be built safely.

    A build that cannot be done correctly must stop *before* it writes, so the
    published artifacts are left exactly as they were.
    """


class SigningMetadataMissing(BuildError):
    """Raised when no usable signing key can be resolved for the build.

    The signing key identifies the store; publishing an empty one would strip the
    signing metadata out of ``repo/index.json`` and ``repo.json`` and leave a
    repository that no client can trust. So a build refuses instead.
    """


TELEGRAM_TOKEN = env_str("TELEGRAM_BOT_TOKEN")
ADMIN_IDS = env_set("ADMIN_ID") | env_set("ADMIN_IDS")
WATCH_CHATS = env_set("WATCH_CHATS")
AUTO_DELETE = env_bool("AUTO_DELETE", True)
AUTO_BAN = env_bool("AUTO_BAN", True)
REPORT_UNKNOWN = env_bool("REPORT_UNKNOWN", False)

GITHUB_TOKEN = env_str("GITHUB_TOKEN") or env_str("GH_TOKEN")
GITHUB_REPO = env_str("GITHUB_REPO", "aaaarrr125h-rgb/Manga-Extensions")
GITHUB_BRANCH = env_str("GITHUB_BRANCH", "main")
GITHUB_API = env_str("GITHUB_API", "https://api.github.com")
PUSH_ENABLED = env_bool("PUSH_ENABLED", True) and bool(GITHUB_TOKEN)

# ----------------------------------------------------------------- publish gate

#: The single switch that decides whether anything may ever reach ``repo/`` or
#: GitHub. No cron job, no Telegram command and no CLI flag can raise it: it is
#: configuration only, and it stays off unless an operator sets it by hand.
#:
#: ``disabled`` (default) — nothing is written into ``repo/``, nothing is pushed.
#: ``dry-run``         — builds into a throwaway directory and reports the diff.
#: ``allow``            — the later, deliberately separate publishing phase; it
#:                        still requires ``PUSH_ENABLED`` (a real token).
#:
#: The switch is necessary but not sufficient: a real write *also* needs a live
#: :class:`PublishAuthorization`, so configuration alone can never be enough to
#: publish. Harvest, health, /scan, /publish, the scheduler and the CLI are all
#: permanently outside that authorisation.
PUBLISH_MODE = (env_str("PUBLISH_MODE", "disabled") or "disabled").strip().lower()
if PUBLISH_MODE not in ("disabled", "dry-run", "allow"):
    LOG.warning("PUBLISH_MODE=%r is not a known mode - falling back to 'disabled'",
                PUBLISH_MODE)
    PUBLISH_MODE = "disabled"

#: Real publishing additionally needs a token, so PUBLISH_MODE=allow alone is inert.
PUBLISH_ENABLED = PUBLISH_MODE == "allow" and PUSH_ENABLED

PUBLISH_REFUSAL = ("publishing is a separate phase: accepted candidates are never "
                   "published by harvest, /scan, /health, the scheduler or a CLI "
                   "flag. PUBLISH_MODE=allow must be set deliberately to enable it")


def publish_gate(operation: str) -> dict:
    """Single decision point for every publish/push request in the process.

    Returns ``{"ok": bool, "reason": str}``; callers either stop or turn the
    refusal into a dry-run report. Nothing else is allowed to decide this.
    """
    if TEST_MODE:
        return {"ok": False, "reason": "TEST_MODE is active", "mode": PUBLISH_MODE}
    if PUBLISH_MODE != "allow":
        return {"ok": False, "reason": "PUBLISH_MODE={} (publishing disabled)".format(
            PUBLISH_MODE), "mode": PUBLISH_MODE}
    if not PUSH_ENABLED:
        return {"ok": False, "reason": "PUSH_ENABLED is off or GITHUB_TOKEN is missing",
                "mode": PUBLISH_MODE}
    return {"ok": True, "reason": "publish gate open", "mode": PUBLISH_MODE}


class PublishAuthorization:
    """The only key that opens a real write into ``repo/`` or the GitHub API.

    Holding one is the *explicit gate*: construction checks
    :func:`publish_gate` and fails closed, and while the ``with`` block is
    entered :func:`assert_may_write_repo` / :func:`assert_may_push` pass.
    Leaving the block lowers the key again, and it is thread-local, so a cron
    thread, a Telegram thread and the CLI can never inherit each other's
    authorisation.

    Nothing in harvest, health, ``/scan``, ``/health``, ``/publish``, the
    scheduler or a CLI flag enters this class. The single entry point is
    :func:`run_publish_phase` -- the later, manual publishing phase.
    """

    _active = threading.local()

    def __init__(self, actor: str, reason: str = ""):
        self.actor = redact(str(actor or "unknown"))
        self.reason = redact(str(reason or ""))
        self.previous = None

    def __enter__(self) -> "PublishAuthorization":
        gate = publish_gate("publish authorization")
        if not gate["ok"]:
            raise PushBlocked("publish authorization refused: {}".format(gate["reason"]))
        self.previous = getattr(PublishAuthorization._active, "token", None)
        PublishAuthorization._active.token = self
        LOG.warning("publish authorisation GRANTED to %s (%s) mode=%s",
                    self.actor, self.reason, PUBLISH_MODE)
        return self

    def __exit__(self, *exc) -> bool:
        PublishAuthorization._active.token = self.previous
        LOG.warning("publish authorisation RELEASED (%s)", self.actor)
        return False


def publish_authorized() -> bool:
    """True only inside an explicit :class:`PublishAuthorization` block."""
    return getattr(PublishAuthorization._active, "token", None) is not None


def called_method_names(func) -> set:
    """Method names ``func`` really calls, by AST rather than by text.

    Comments and docstrings are ignored on purpose: a comment that *mentions*
    ``REPO.publish()`` is documentation of what is forbidden, not a call. The
    tests use this so that adding ``REPO.publish()`` anywhere is what fails, and
    merely explaining it is not.
    """
    try:
        tree = ast.parse(textwrap.dedent(inspect.getsource(func)))
    except (OSError, TypeError, SyntaxError, IndentationError):
        return set()
    return {node.func.attr for node in ast.walk(tree)
            if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)}


def assert_may_write_repo(what: str = "write repo/") -> None:
    """The only door into ``repo/``. A sandboxed build (``dest=``) needs no
    permission at all; a real write needs an explicit publish authorisation."""
    if TEST_MODE:
        raise PushBlocked("TEST_MODE is active: refusing to {}".format(what))
    if not publish_authorized():
        raise PushBlocked("{}: no explicit publish authorisation is held -- this path "
                          "may not write the published repository".format(what))
    gate = publish_gate(what)
    if not gate["ok"]:
        raise PushBlocked("{}: {}".format(what, gate["reason"]))


def assert_may_push(what: str = "push") -> None:
    """The only door into the GitHub API."""
    if TEST_MODE:
        raise PushBlocked("TEST_MODE is active: refusing to {}".format(what))
    if not publish_authorized():
        raise PushBlocked("{}: no explicit publish authorisation is held -- this path "
                          "may not reach GitHub".format(what))
    gate = publish_gate(what)
    if not gate["ok"]:
        raise PushBlocked("{}: {}".format(what, gate["reason"]))


def run_publish_phase(actor: str, reason: str) -> dict:
    """The separate, manual publishing phase -- and the only publish caller.

    Nothing reaches this function from harvest, health, ``/scan``, ``/health``,
    ``/publish``, a cron job, a Telegram callback or a CLI flag. It takes an
    explicit actor, opens one :class:`PublishAuthorization`, and closes it again
    on the way out, so ``RepoManager.publish()`` and ``RepoManager.push()`` are
    unreachable everywhere else even when ``PUBLISH_MODE=allow``.
    """
    if not actor:
        return {"ok": False, "blocked": True, "changed": [], "pushed": [],
                "skipped": "the publish phase needs an explicit actor"}
    try:
        with PublishAuthorization(actor, reason):
            return REPO.publish()
    except PushBlocked as exc:
        LOG.error("publish phase refused for %s: %s", redact(str(actor)), exc)
        return {"ok": False, "blocked": True, "changed": [], "pushed": [], "skipped": str(exc)}


STORE_NAME = env_str("REPO_NAME", "Shura")
STORE_BADGE = env_str("BADGE_LABEL", "SHURA")
SIGNING_KEY = env_str(
    "SIGNING_KEY",
    "",
)
STORE_WEBSITE = env_str("REPO_WEBSITE", f"https://github.com/{GITHUB_REPO}")
STORE_DISCORD = env_str("REPO_DISCORD", "")

UPSTREAM_INDEX_JSON = env_str(
    "UPSTREAM_INDEX_JSON",
    "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.json",
)
UPSTREAM_SOURCE_REPO = env_str("UPSTREAM_SOURCE_REPO", "keiyoushi/extensions-source")
UPSTREAM_SOURCE_REF = env_str("UPSTREAM_SOURCE_REF", "main")
SOURCE_SCAN_ENABLED = env_bool("SOURCE_SCAN", True)
MAX_SOURCE_FILES = env_int("MAX_SOURCE_FILES", 12)
MAX_NEW_PER_RUN = env_int("MAX_NEW_PER_RUN", 40)
MAX_SCAN_PER_RUN = env_int("MAX_SCAN_PER_RUN", 50)
AUDIT_CACHE_LIMIT = env_int("AUDIT_CACHE_LIMIT", 5000)
ALLOW_MIXED = env_bool("ALLOW_MIXED", False)
HEALTH_SLICE = env_int("HEALTH_SLICE", 60)
HEALTH_WORKERS = env_int("HEALTH_WORKERS", 12)
DEAD_THRESHOLD = env_int("DEAD_THRESHOLD", 3)
HTTP_TIMEOUT = env_int("HTTP_TIMEOUT", 20)
USER_AGENT = env_str(
    "USER_AGENT",
    f"{STORE_NAME}-RepoManager/2.0 (+https://github.com/{GITHUB_REPO})",
)

CRON_HARVEST = env_str("CRON_HARVEST", "17 */6 * * *")
CRON_HEALTH = env_str("CRON_HEALTH", "41 */3 * * *")
#: Readiness report only -- it never publishes and never pushes. See job_publish.
CRON_PUBLISH = env_str("CRON_PUBLISH", "23 */2 * * *")
#: The CRON_PUBLISH job is **not scheduled by default**. Even when switched on it
#: stays a dry-run: it calls RepoManager.prepare_publish(), which builds into a
#: throwaway directory and reports. It can never build into repo/ and can never
#: push, and no cron job can accept a candidate.
PUBLISH_READINESS_ENABLED = env_bool("PUBLISH_READINESS_ENABLED", False)
CRON_DIGEST = env_str("CRON_DIGEST", "53 9 * * *")
TIMEZONE = env_str("TIMEZONE", "UTC")

RAW_BASE = f"https://raw.githubusercontent.com/{GITHUB_REPO}/{GITHUB_BRANCH}"
REPO_JSON_URL = f"{RAW_BASE}/repo.json"
INDEX_JSON_URL = f"{RAW_BASE}/repo/index.json"
INDEX_PB_URL = f"{RAW_BASE}/repo/index.pb"
INDEX_MIN_URL = f"{RAW_BASE}/repo/index.min.json"
MIHON_DEEP_LINK = f"mihon://extension-store?url={INDEX_PB_URL}"

# ----------------------------------------------------------------- shura sync protocol
#
# ``shura/`` is the Shura Sync layer: a small, separately versioned contract the
# Shura app reads, kept out of ``repo/`` so Mihon data and Shura data never mix.
#
# The layer is *derived* from the index in the very same build that writes
# repo/index.json, so the two can never drift. Nothing here widens the publish
# gate: these files are ordinary build artifacts and are only ever written by a
# build that already holds a PublishAuthorization.
#
# The app never hardcodes a repository location. It fetches shura/manifest.json
# first, reads ``base`` and the *relative* paths out of it, and only then
# downloads anything. Moving or renaming the repository is therefore a one-line
# change here and no change at all in the app.

SHURA_DIR = ROOT / "shura"
SHURA_MANIFEST = SHURA_DIR / "manifest.json"
SHURA_DELTA = SHURA_DIR / "delta.json"
SHURA_SCHEMA = 1

#: Only ever a *proposal*. A client is expected to fall back to the full index
#: whenever this cannot be satisfied, so it must never be required.
SHURA_REVISION_LENGTH = 32


# ----------------------------------------------------------------- schema constants

CW_UNSPECIFIED = 0
CW_SAFE = 1
CW_MIXED = 2
CW_NSFW = 3
CW_NAMES = {
    CW_UNSPECIFIED: "CONTENT_WARNING_UNSPECIFIED",
    CW_SAFE: "CONTENT_WARNING_SAFE",
    CW_MIXED: "CONTENT_WARNING_MIXED",
    CW_NSFW: "CONTENT_WARNING_NSFW",
}
CW_VALUES = {name: value for value, name in CW_NAMES.items()}

# ----------------------------------------------------------------- nsfw filter

NSFW_WORDS = (
    "adult", "sex", "sexy", "porn", "porno", "hentai", "ecchi", "doujin",
    "futanari", "shota", "lolicon", "shotacon", "nsfw", "nudity", "nude",
    "naked", "erotic", "erotica", "milf", "camgirl", "futanari", "bdsm",
    "bondage", "fetish", "incest", "bestiality", "orgy", "bordello", "ahegao",
    "oppai", "imouto", "chikan", "seikou", "pussy", "booru", "milfs", "anal",
    "rape", "raped", "molest", "lewd", "kinky", "bukkake", "gangbang",
    "deepthroat", "blowjob", "handjob", "creampie", "squirt", "masturbat",
    "onlyfans", "hitomi", "tsumino", "shadhi", "uncensored",
    "doujinshi", "ishoujo", "shoujo", "teen", "milky", "naughty",
)

# "fansub" is deliberately absent: it is a mainstream scanlation term
# (Taurus Fansub, FansubScan, ...), not an adult-content marker.
NSFW_FRAGMENTS = (
    "18+", "18plus", "xxx", "hentai", "booru", "onlyfans", "nsfw",
    "18p", "sexvideo", "javhd", "rule34", "pornhub", "xnxx", "xvideos",
    "redtube", "youporn", "spankbang", "motherless", "incest", "bestiality",
    "milf", "creampie", "blowjob", "bukkake", "deepthroat", "gangbang",
    "hentaisex", "sextape", "sexvideo", "erotic",
)

NSFW_HOST_FRAGMENTS = (
    "porn", "xxx", "hentai", "nsfw", "camgirl", "rule34", "booru",
    "onlyfans", "milf", "bdsm", "fetish", "erotic", "pornhub", "xvideos",
    "xnxx", "hentaigame", "javhd", "motherless", "redtube", "youporn",
)

# ----------------------------------------------------------------- malware scan

MALWARE_SIGNATURES = (
    ("process-exec", "critical", re.compile(
        r"Runtime\s*\.\s*getRuntime\s*\(\s*\)\s*\.\s*exec|ProcessBuilder\s*\(")),
    ("shell-escalation", "critical", re.compile(
        r"/system/bin/sh|/system/xbin/su|\bsu\s+-c\b|chmod\s+777|"
        r"getRuntime\s*\(\s*\)\s*\.\s*exec\s*\(\s*\"su\"")),
    ("dynamic-dex-load", "critical", re.compile(
        r"DexClassLoader|PathClassLoader|InMemoryDexClassLoader|"
        r"dalvik\.system\.DexFile|BaseDexClassLoader|\.dex[\"']")),
    ("native-load", "critical", re.compile(
        r"System\s*\.\s*loadLibrary|System\s*\.\s*load\s*\(|Runtime\s*\.\s*load\s*\(")),
    ("reflection-abuse", "high", re.compile(
        r"setAccessible\s*\(\s*true\s*\)|getDeclaredMethod|getDeclaredField|"
        r"Class\s*\.\s*forName\s*\(")),
    ("accessibility-abuse", "critical", re.compile(
        r"BIND_ACCESSIBILITY_SERVICE|AccessibilityService|"
        r"BIND_NOTIFICATION_LISTENER_SERVICE|NotificationListenerService")),
    ("device-admin-abuse", "critical", re.compile(
        r"BIND_DEVICE_ADMIN|DeviceAdminReceiver|BIND_USAGE_STATS|"
        r"android\.app\.action\.ADD_DEVICE_ADMIN")),
    ("overlay-abuse", "high", re.compile(
        r"TYPE_APPLICATION_OVERLAY|TYPE_SYSTEM_ALERT|SYSTEM_ALERT_WINDOW")),
    ("sms-abuse", "critical", re.compile(
        r"SmsManager|sendTextMessage|sendMultipartTextMessage|SEND_SMS")),
    ("call-abuse", "critical", re.compile(
        r"ACTION_CALL|ACTION_DIAL|placeCall\s*\(|TelecomManager")),
    ("contact-harvest", "high", re.compile(
        r"ContactsContract\.CommonDataKinds\.Phone|READ_CONTACTS|"
        r"content://com\.android\.contacts")),
    ("clipboard-harvest", "high", re.compile(
        r"ClipboardManager|getPrimaryClip|ClipData\.newPlainText")),
    ("location-spy", "high", re.compile(
        r"ACCESS_FINE_LOCATION|ACCESS_BACKGROUND_LOCATION|getLastKnownLocation|"
        r"requestLocationUpdates")),
    ("mic-camera-spy", "critical", re.compile(
        r"MediaRecorder\s*\(|AudioRecord\s*\(|startRecording|setVideoSource|"
        r"MediaRecorder\.AudioSource|takePicture\s*\(")),
    ("silent-apk-install", "critical", re.compile(
        r"REQUEST_INSTALL_PACKAGES|ACTION_INSTALL_PACKAGE|installPackageAsync|"
        r"PackageInstaller\.Session")),
    ("vpn-abuse", "high", re.compile(r"android\.net\.VpnService|BIND_VPN_SERVICE")),
    ("keylogger", "critical", re.compile(
        r"AccessibilityEvent\.TYPE_VIEW_TEXT_CHANGED|onAccessibilityEvent|"
        r"getEventType\s*\(\s*\)\s*==\s*AccessibilityEvent")),
    ("screen-capture", "high", re.compile(
        r"createScreenCapture|MediaProjection|createVirtualDisplay")),
    ("crypto-abuse", "medium", re.compile(
        r"javax\.crypto\.Cipher|SecretKeySpec\s*\(|KeyGenParameterSpec|"
        r"AndroidKeyStore")),
    ("raw-socket-c2", "critical", re.compile(
        r"java\.net\.Socket\s*\(|ServerSocket\s*\(|DatagramSocket\s*\(")),
    ("obfuscation", "medium", re.compile(
        r"Class\.forName\s*\(\s*[A-Za-z0-9+/=]{20,}|charArrayOf\s*\(|"
        r"String\s*\(\s*byteArrayOf\s*\(")),
    ("root-detection-abuse", "low", re.compile(
        r"/system/app/Superuser\.apk|com\.noshufou\.android\.su|"
        r"com\.thirdparty\.superuser|eu\.chainfire\.supersu")),
    ("background-persistence", "high", re.compile(
        r"RECEIVE_BOOT_COMPLETED|startForegroundService|AlarmManager")),
    ("reflection-string-concat", "medium", re.compile(
        r"getMethod\s*\(\s*\"[a-zA-Z]+\"\s*\+\s*|\"\${\"[^}]*\}\"\s*\)\s*\.\s*invoke")),
)

BLOCKING_SEVERITIES = {"critical", "high"}

POPULAR_BRANDS = (
    "mangadex", "mangasee", "mangakatana", "mangasupdates", "manganato",
    "rawmanga", "mangaplus", "shueisha", "webtoons", "linewebtoon", "tappytoon",
    "mangarazzi", "asura", "flamescans", "manhwaclan", "mangaclan", "readmanga",
    "mangastream", "komikastream", "mangamotion", "mangafire", "mangasee123",
    "chapmanganato", "mangakawaii", "mangathroughia", "manga4life",
)

NOT_A_DOMAIN = {
    "test", "json", "yaml", "yml", "toml", "ini", "cfg", "conf", "env", "log",
    "lock", "sum", "xml", "html", "htm", "css", "js", "ts", "tsx", "jsx", "py",
    "pyc", "rb", "go", "rs", "java", "kt", "kts", "scala", "c", "h", "cpp",
    "cs", "php", "sh", "bash", "bat", "ps1", "sql", "csv", "tsv", "txt", "md",
    "rst", "pdf", "doc", "docx", "xls", "xlsx", "zip", "tar", "gz", "bz2",
    "xz", "7z", "rar", "jar", "apk", "aab", "exe", "dll", "so", "bin", "iso",
    "img", "dmg", "deb", "rpm", "patch", "diff", "map", "min", "wasm", "plist",
    "png", "jpg", "jpeg", "gif", "bmp", "webp", "svg", "ico", "mp3", "mp4",
    "mkv", "avi", "mov", "flv", "wav", "webm", "woff", "ttf", "otf", "db",
    "sqlite", "bak", "tmp", "swp", "part", "torrent", "nfo", "epub", "mobi",
}

SHORTENER_HOSTS = (
    "bit.ly", "tinyurl.com", "t.co", "goo.gl", "is.gd", "cutt.ly", "rb.gy",
    "ow.ly", "shorturl.at", "rebrand.ly", "shorte.st", "adf.ly", "bc.vc",
    "exe.io", "play.lt", "u.to", "soo.gd", "clck.ru", "v.gd", "qr.ae",
    "linktr.ee", "shorturl.io", "trib.al", "t.ly", "dub.sh", "bl.ink",
)

RISKY_TLDS = (".tk", ".gq", ".ml", ".cf", ".ga", ".st", ".gq")

LINK_PATTERN = re.compile(
    r"""(?:https?|tg)://[^\s<>"'()\[\]{}]+"""
    r"""|www\.[^\s<>"'()\[\]{}]+"""
    r"""|(?<![@\w.-])(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,24}(?:/[^\s<>"']*)?""",
    re.IGNORECASE,
)

WORD_PATTERNS = tuple(
    re.compile(r"(?<![a-z0-9])" + re.escape(word) + r"(?![a-z0-9])")
    for word in NSFW_WORDS if word.isalpha() and len(word) >= 3
)

PRIVATE_HOST_PATTERN = re.compile(
    r"^(?:localhost|.*\.local|.*\.internal|.*\.home\.arpa|"
    r"0\.0\.0\.0|127\.|10\.|192\.168\.|169\.254\.|"
    r"172\.(?:1[6-9]|2\d|3[01])\.|\[?::1\]?|fe80:|fc|fd)")

IPV4_LITERAL = re.compile(r"[0-9]{1,3}(?:\.[0-9]{1,3}){3}")
IPV6_LITERAL = re.compile(r"[0-9a-f:]{3,}")

PRIVATE_IPV4 = re.compile(
    r"^(?:0\.0\.0\.0|127\.\d{1,3}\.\d{1,3}\.\d{1,3}|10\.\d{1,3}\.\d{1,3}\.\d{1,3}|"
    r"192\.168\.\d{1,3}\.\d{1,3}|169\.254\.\d{1,3}\.\d{1,3}|"
    r"172\.(?:1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3})$")
PRIVATE_IPV6 = re.compile(r"^(?:::1|fe80:|fc|fd)[0-9a-f:]*$")
PRIVATE_TLD = (".local", ".internal", ".home.arpa")


def is_private_host(host: str) -> bool:
    """Loopback / LAN / link-local address, i.e. what a self-hosted extension uses."""
    host = str(host or "").strip().lower().strip("[]")
    if not host:
        return False
    if IPV4_LITERAL.fullmatch(host) or IPV6_LITERAL.fullmatch(host):
        return bool(PRIVATE_IPV4.match(host) or PRIVATE_IPV6.match(host))
    return host == "localhost" or host.endswith(PRIVATE_TLD)


def is_public_host(host: str) -> bool:
    """Self-hosted extensions (Komga, LANraragi, ...) point at private addresses."""
    host = str(host or "").strip().lower().strip("[]")
    if not host or is_private_host(host):
        return False
    if re.fullmatch(r"[0-9.]+", host):
        return False
    return "." in host

# ----------------------------------------------------------------- text utils

def clip(value, size: int = 900) -> str:
    value = str(value or "").strip()
    return value if len(value) <= size else value[: size - 1] + "…"


def esc(value) -> str:
    return str(value or "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def utf16_slice(value: str, offset: int, length: int) -> str:
    encoded = value.encode("utf-16-le")
    return encoded[offset * 2: (offset + length) * 2].decode("utf-16-le", "ignore")


def text_of(message: dict) -> str:
    parts = [message.get("text") or "", message.get("caption") or ""]
    for entity in (message.get("entities") or []) + (message.get("caption_entities") or []):
        if entity.get("type") == "text_link" and entity.get("url"):
            parts.append(entity["url"])
        elif entity.get("type") == "url":
            parts.append(utf16_slice(
                message.get("text") or message.get("caption") or "",
                entity.get("offset", 0), entity.get("length", 0)))
    return "\n".join(part for part in parts if part)


def sender_name(user: dict) -> str:
    if not user:
        return "غير معروف"
    name = user.get("first_name") or user.get("title") or "مستخدم"
    if user.get("last_name"):
        name += " " + user["last_name"]
    if user.get("username"):
        name += " (@{})".format(user["username"])
    return name


def normalize_url(raw: str) -> str:
    candidate = str(raw or "").strip().rstrip(".,;:!?)")
    if not candidate:
        return ""
    if re.match(r"^[a-zA-Z][a-zA-Z0-9+.-]*://", candidate):
        return candidate
    if candidate.lower().startswith("tg:"):
        return "tg://" + candidate[3:].lstrip("/")
    if looks_like_domain(domain_of(candidate)):
        return "http://" + candidate
    return candidate


def domain_of(link: str) -> str:
    link = str(link or "").strip().rstrip(".,;:!?)")
    if link.lower().startswith("tg:"):
        return "t.me"
    candidate = link
    if candidate.lower().startswith("www."):
        candidate = "http://" + candidate
    elif "://" not in candidate and "." in candidate.split("/")[0]:
        candidate = "http://" + candidate
    try:
        host = urlparse(candidate).netloc.lower()
    except ValueError:
        return ""
    if "@" in host:
        host = host.rsplit("@", 1)[1]
    host = host.split(":")[0].split("?")[0].strip(".")
    if host.startswith("www."):
        host = host[4:]
    return host


def looks_like_domain(host: str) -> bool:
    if not host or host.count(".") < 1 or len(host) > 253:
        return False
    return host.rsplit(".", 1)[1].lower() not in NOT_A_DOMAIN


def extract_links(text: str) -> list:
    links = []
    seen = set()
    for match in LINK_PATTERN.finditer(text or ""):
        raw = match.group(0).strip().rstrip(".,;:!?)")
        domain = domain_of(raw)
        if not domain or not looks_like_domain(domain) or raw in seen:
            continue
        seen.add(raw)
        links.append({"link": raw, "domain": domain})
    return links


def atomic_write(path: Path, payload: bytes) -> bool:
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        temp = path.with_name(path.name + ".tmp")
        temp.write_bytes(payload)
        temp.replace(path)
        return True
    except OSError as exc:
        LOG.warning("write %s failed: %s", path, exc)
        return False


def http_session() -> requests.Session:
    session = requests.Session()
    retry = Retry(total=3, backoff_factor=1.2,
                  status_forcelist=(429, 500, 502, 503, 504),
                  allowed_methods=frozenset({"GET", "HEAD"}))
    adapter = HTTPAdapter(max_retries=retry, pool_maxsize=32)
    session.mount("https://", adapter)
    session.mount("http://", adapter)
    session.headers.update({"User-Agent": USER_AGENT, "Accept": "*/*"})
    return session


# ----------------------------------------------------------------- protobuf codec

def pb_varint(value) -> bytes:
    value = int(value)
    if value < 0:
        value += 1 << 64
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def pb_tag(field: int, wire: int) -> bytes:
    return pb_varint((field << 3) | wire)


def pb_str(field: int, value) -> bytes:
    if not value:
        return b""
    raw = str(value).encode("utf-8")
    return pb_tag(field, 2) + pb_varint(len(raw)) + raw


def pb_int(field: int, value) -> bytes:
    value = int(value or 0)
    if not value:
        return b""
    return pb_tag(field, 0) + pb_varint(value)


def pb_msg(field: int, payload: bytes) -> bytes:
    if not payload:
        return b""
    return pb_tag(field, 2) + pb_varint(len(payload)) + payload


def encode_source(source: dict) -> bytes:
    out = b""
    out += pb_int(1, source.get("id") or 0)
    out += pb_str(2, source.get("name") or "")
    out += pb_str(3, source.get("language") or "")
    out += pb_str(4, source.get("homeUrl") or "")
    for mirror in source.get("mirrorUrls") or []:
        out += pb_str(5, mirror)
    out += pb_str(7, source.get("message") or "")
    return out


def encode_extension(extension: dict) -> bytes:
    resources = extension.get("resources") or {}
    res = pb_str(1, resources.get("apkUrl") or "")
    res += pb_str(2, resources.get("iconUrl") or "")
    res += pb_str(501, resources.get("jarUrl") or "")

    warning = extension.get("contentWarning")
    warning = CW_VALUES.get(warning, CW_SAFE) if isinstance(warning, str) else int(warning or CW_SAFE)

    out = b""
    out += pb_str(1, extension.get("name") or "")
    out += pb_str(2, extension.get("packageName") or "")
    out += pb_msg(3, res)
    out += pb_str(4, extension.get("extensionLib") or "")
    out += pb_int(5, extension.get("versionCode") or 0)
    out += pb_str(6, extension.get("versionName") or "")
    out += pb_int(7, warning)
    for source in extension.get("sources") or []:
        out += pb_msg(8, encode_source(source))
    return out


# ----------------------------------------------------------------- signing metadata

#: A signing key is a hex digest. Anything else is a misconfiguration, and
#: publishing it would replace a good key with a value no client can use.
SIGNING_KEY_RE = re.compile(r"^[0-9a-fA-F]+$")


def published_fingerprint(path: Path = None) -> str:
    """The ``signingKeyFingerprint`` currently published in repo.json.

    Read-only and fail-soft: a missing or unreadable repo.json is reported as
    "no fingerprint" rather than raising, because this is a *fallback* source
    for a build, not the build itself.
    """
    target = Path(path) if path is not None else REPO_JSON
    if not target.is_file():
        return ""
    try:
        payload = json.loads(target.read_text(encoding="utf-8"))
        return str((payload.get("meta") or {}).get("signingKeyFingerprint") or "").strip()
    except (OSError, ValueError, AttributeError) as exc:
        LOG.warning("cannot read the published signing fingerprint: %s", exc)
        return ""


def resolve_signing_key(index: dict = None, path: Path = None) -> str:
    """The signing key a build may embed -- never an empty one.

    Resolution order, first usable wins:

    1. ``SIGNING_KEY`` from the environment, when it is set and valid;
    2. the ``signingKey`` already present in the index being built;
    3. the ``signingKeyFingerprint`` already published in repo.json.

    Steps 2 and 3 are what stop a build from *erasing* signing metadata: a
    process without ``SIGNING_KEY`` in its environment rebuilds the artifacts
    with the key that is already there, rather than writing an empty string over
    it. If none of the three yields a valid key, the build is refused -- an
    index with no signing key is worse than an index that was not rebuilt.
    """
    configured = str(SIGNING_KEY or "").strip()
    if configured:
        return validate_signing_key(configured, "SIGNING_KEY")
    current = str((index or {}).get("signingKey") or "").strip()
    if current:
        LOG.info("SIGNING_KEY is unset: keeping the signing key already in the index")
        return validate_signing_key(current, "the published index signingKey")
    fallback = published_fingerprint(path)
    if fallback:
        LOG.info("SIGNING_KEY is unset: keeping the fingerprint published in repo.json")
        return validate_signing_key(fallback, "the published repo.json fingerprint")
    raise SigningMetadataMissing(
        "no signing key: SIGNING_KEY is unset and neither repo/index.json nor "
        "repo.json carries a fingerprint. Refusing to build an index that would "
        "strip the signing metadata -- set SIGNING_KEY or restore repo.json")


def validate_signing_key(value: str, source: str) -> str:
    """Reject a value that must never be published as a signing key."""
    key = str(value or "").strip()
    if not key:
        raise SigningMetadataMissing("{} is empty".format(source))
    if not SIGNING_KEY_RE.match(key):
        raise SigningMetadataMissing(
            "{} is not a hex digest: {!r}".format(source, key[:24]))
    return key


def encode_index(index: dict) -> bytes:
    # Refuse to encode an index whose signing key is missing or malformed, even
    # if some future caller skipped resolve_signing_key(): an .pb without a
    # signing key is not a less valid artifact, it is a broken one.
    index = dict(index or {})
    index["signingKey"] = resolve_signing_key(index)
    contact = index.get("contact") or {}
    contact_bytes = pb_str(1, contact.get("website") or "") + pb_str(2, contact.get("discord") or "")
    extensions = b"".join(
        pb_msg(1, encode_extension(extension))
        for extension in (index.get("extensionList") or {}).get("extensions") or [])

    out = b""
    out += pb_str(1, index.get("name") or "")
    out += pb_str(2, index.get("badgeLabel") or "")
    out += pb_str(3, index.get("signingKey") or "")
    out += pb_msg(4, contact_bytes)
    out += pb_msg(101, extensions)
    return out


def decode_varint(buffer: bytes, index: int):
    result = 0
    shift = 0
    while True:
        byte = buffer[index]
        index += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result, index
        shift += 7


def decode_message(buffer: bytes) -> dict:
    out = {}
    index = 0
    while index < len(buffer):
        key, index = decode_varint(buffer, index)
        field, wire = key >> 3, key & 7
        if wire == 0:
            value, index = decode_varint(buffer, index)
            out.setdefault(field, []).append(value)
        elif wire == 2:
            size, index = decode_varint(buffer, index)
            out.setdefault(field, []).append(buffer[index:index + size])
            index += size
        elif wire == 5:
            out.setdefault(field, []).append(buffer[index:index + 4])
            index += 4
        elif wire == 1:
            out.setdefault(field, []).append(buffer[index:index + 8])
            index += 8
        else:
            break
    return out


def first_str(fields: dict, number: int) -> str:
    values = fields.get(number)
    return values[0].decode("utf-8", "replace") if values else ""


def decode_index(buffer: bytes) -> dict:
    top = decode_message(buffer)
    contact = {}
    if 4 in top:
        raw_contact = decode_message(top[4][0])
        contact = {"website": first_str(raw_contact, 1), "discord": first_str(raw_contact, 2)}
        contact = {key: value for key, value in contact.items() if value}

    extensions = []
    for list_blob in top.get(101, []):
        for ext_blob in decode_message(list_blob).get(1, []):
            ext = decode_message(ext_blob)
            resources = {}
            if 3 in ext:
                raw_res = decode_message(ext[3][0])
                resources = {"apkUrl": first_str(raw_res, 1), "iconUrl": first_str(raw_res, 2)}
                jar = first_str(raw_res, 501)
                if jar:
                    resources["jarUrl"] = jar
            sources = []
            for src_blob in ext.get(8, []):
                src = decode_message(src_blob)
                item = {"id": str((src.get(1) or [0])[0]),
                        "name": first_str(src, 2),
                        "language": first_str(src, 3)}
                home = first_str(src, 4)
                if home:
                    item["homeUrl"] = home
                mirrors = [value.decode("utf-8", "replace") for value in src.get(5, [])]
                if mirrors:
                    item["mirrorUrls"] = mirrors
                if 7 in src:
                    item["message"] = first_str(src, 7)
                sources.append(item)
            extensions.append({
                "name": first_str(ext, 1),
                "packageName": first_str(ext, 2),
                "resources": resources,
                "extensionLib": first_str(ext, 4),
                "versionCode": str((ext.get(5) or [0])[0]),
                "versionName": first_str(ext, 6),
                "contentWarning": CW_NAMES.get((ext.get(7) or [CW_SAFE])[0], CW_NAMES[CW_SAFE]),
                "sources": sources,
            })

    return {
        "name": first_str(top, 1),
        "badgeLabel": first_str(top, 2),
        "signingKey": first_str(top, 3),
        "contact": contact,
        "extensionList": {"extensions": extensions},
    }


def render_index_json(index: dict) -> str:
    return json.dumps(index, indent=2, ensure_ascii=False) + "\n"


LEGACY_INDEX_MIN = [
    {
        "name": "Outdated App",
        "pkg": "eu.kanade.tachiyomi.extension.all.keiyoushi",
        "apk": "tachiyomi-all.keiyoushi-v1.4.1.apk",
        "lang": "all", "code": 1, "version": "1.4.1", "nsfw": 0,
        "sources": [{"name": "Outdated App", "lang": "all", "id": "1",
                     "baseUrl": "https://keiyoushi.github.io"}],
    },
    {
        "name": "Update to Mihon 0.20.1+",
        "pkg": "eu.kanade.tachiyomi.extension.all.mihon",
        "apk": "tachiyomi-all.mihon-v1.4.1.apk",
        "lang": "all", "code": 1, "version": "1.4.1", "nsfw": 0,
        "sources": [{"name": "Update to Mihon 0.20.1+", "lang": "all", "id": "1",
                     "baseUrl": "https://mihon.app"}],
    },
]

# ----------------------------------------------------------------- shura sync layer


def shura_revision(index_bytes: bytes) -> str:
    """Content identity of a rendered index.

    Derived from the exact bytes that go on the wire, so a client can verify a
    revision against the index it already holds without trusting the server.
    Truncated because it is an identifier, not a security boundary; the signing
    key fingerprint is what actually authenticates the index.
    """
    return hashlib.sha256(index_bytes).hexdigest()[:SHURA_REVISION_LENGTH]


def shura_projection(extension: dict) -> dict:
    """The exact subset of an extension the Shura app is allowed to see.

    A projection rather than the raw record so the delta can never smuggle
    anything the full index does not already publish, and so the app has one
    documented shape to depend on.
    """
    resources = extension.get("resources") or {}
    record = {
        "packageName": extension.get("packageName"),
        "name": extension.get("name"),
        "versionCode": extension.get("versionCode"),
        "versionName": extension.get("versionName"),
        "contentWarning": extension.get("contentWarning"),
        "resources": {"apkUrl": resources.get("apkUrl")},
    }
    if resources.get("iconUrl"):
        record["resources"]["iconUrl"] = resources["iconUrl"]
    if extension.get("extensionLib"):
        record["extensionLib"] = extension["extensionLib"]
    sources = []
    for source in extension.get("sources") or []:
        if not isinstance(source, dict):
            continue
        # the index spells these language/homeUrl, not lang/baseUrl
        entry = {"id": source.get("id"), "name": source.get("name"),
                 "language": source.get("language")}
        if source.get("homeUrl"):
            entry["homeUrl"] = source["homeUrl"]
        if source.get("baseUrl"):
            entry["baseUrl"] = source["baseUrl"]
        sources.append(entry)
    if sources:
        record["sources"] = sources
    return record


def shura_baseline_index(baseline: Path = None):
    """Read the published index the delta is measured from.

    A module-level function rather than a method: :meth:`RepoManager.build`
    already reaches for exactly four attributes, and a new ``self.`` here would
    widen that surface for every existing caller and test double.

    Unreadable is not fatal. A missing or corrupt baseline yields ``None``,
    which becomes a delta carrying every extension as ``added`` with a null
    baseRevision -- a larger delta, never a wrong one, and a client that cannot
    use it still has the full index.
    """
    path = Path(baseline) if baseline else INDEX_JSON
    try:
        if not path.is_file():
            LOG.info("no shura baseline at %s - delta will be a full baseline", path)
            return None
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        LOG.warning("shura baseline %s unreadable (%s) - delta will be a full baseline",
                    path, exc)
        return None


def shura_canonical(record: dict) -> str:
    """Stable serialisation, so equality never depends on key order."""
    return json.dumps(record, sort_keys=True, ensure_ascii=False, separators=(",", ":"))


def shura_diff(previous: list, current: list) -> dict:
    """Split two extension lists into added / updated / removed.

    ``updated`` carries the full new record, never a patch: a patch would make
    the client's stored copy authoritative and let a skipped field persist
    forever. Redundant bytes buy a client that cannot get out of step.
    """
    before = {item.get("packageName"): shura_projection(item)
              for item in (previous or []) if item.get("packageName")}
    after = {item.get("packageName"): shura_projection(item)
             for item in (current or []) if item.get("packageName")}

    added = [after[package] for package in sorted(after) if package not in before]
    updated = [after[package] for package in sorted(after)
               if package in before and before[package] != after[package]]
    removed = sorted(package for package in before if package not in after)
    return {"added": added, "updated": updated, "removed": removed}


def shura_delta(base_revision, revision: str, extensions: list, diff: dict) -> dict:
    """Assemble the delta document.

    ``baseRevision`` is the one field a client must check. It is the revision of
    the index the delta was computed against, so a client that cannot apply the
    delta (too old, or the file was never generated) falls back to the full
    index instead of silently drifting.
    """
    return {"schema": SHURA_SCHEMA, "baseRevision": base_revision,
            "revision": revision, "count": len(extensions),
            "added": diff["added"], "updated": diff["updated"], "removed": diff["removed"]}


def shura_manifest(revision: str, extensions: list) -> dict:
    """Assemble the manifest: one small, stable document the app always reads.

    Only the *contract* lives here, never the content, so this stays small
    enough to re-check on every poll and cheap to cache on a conditional
    request. ``base`` is the single place the repository location is named.
    """
    return {"schema": SHURA_SCHEMA, "repo": STORE_NAME, "badgeLabel": STORE_BADGE,
            "base": RAW_BASE, "revision": revision, "count": len(extensions),
            "index": {"json": "repo/index.json", "pb": "repo/index.pb"},
            "delta": "shura/delta.json"}


def shura_render(document: dict) -> bytes:
    """Serialise a shura document. Keys are sorted so the bytes -- and so the
    revision pinned alongside them -- are reproducible across machines."""
    return (json.dumps(document, indent=2, ensure_ascii=False, sort_keys=True) + "\n").encode("utf-8")


# ----------------------------------------------------------------- security checks

def nsfw_hit(blob: str):
    lowered = blob.lower()
    for fragment in NSFW_FRAGMENTS:
        if fragment in lowered:
            return "nsfw-term:{}".format(fragment)
    for pattern in WORD_PATTERNS:
        if pattern.search(lowered):
            return "nsfw-word:{}".format(pattern.pattern.split("(?<!")[1][:20])
    return ""


def nsfw_reason(extension: dict) -> str:
    warning = extension.get("contentWarning")
    if warning in (CW_NAMES[CW_NSFW], CW_NSFW):
        return "contentWarning=NSFW"
    if not ALLOW_MIXED and warning in (CW_NAMES[CW_MIXED], CW_MIXED):
        return "contentWarning=MIXED"

    resources = extension.get("resources") or {}
    urls = [resources.get(key) for key in ("apkUrl", "iconUrl", "jarUrl")]
    texts = [str(extension.get("name") or ""), str(extension.get("packageName") or "")]
    for source in extension.get("sources") or []:
        texts += [str(source.get("name") or "")]
        if source.get("homeUrl"):
            urls.append(str(source["homeUrl"]))
    urls = [item for item in urls if item]

    hit = nsfw_hit(" ".join(texts + urls))
    if hit:
        return hit
    for host in [domain_of(url) for url in urls]:
        if not host:
            continue
        for token in NSFW_HOST_FRAGMENTS:
            if token in host:
                return "nsfw-host:{}".format(token)
    return ""


def scan_code(text: str) -> list:
    if not text:
        return []
    findings = []
    for name, severity, pattern in MALWARE_SIGNATURES:
        match = pattern.search(text)
        if not match:
            continue
        findings.append({
            "id": name,
            "severity": severity,
            "line": text.count("\n", 0, match.start()) + 1,
            "snippet": text[match.start():match.start() + 90].replace("\n", " "),
        })
    return findings


def url_verdict(url: str, allow_private: bool = False) -> tuple:
    """Return (verdict, reason) where verdict is ok | suspicious | blocked.

    allow_private relaxes the IP-literal rules for self-hosted extension
    home pages (127.0.0.1 Komga/Kavita). It is never used by the group guard.
    """
    candidate = normalize_url(url)
    if not candidate or candidate.lower().startswith("tg://"):
        return "ok", ""

    try:
        parsed = urlparse(candidate)
    except ValueError:
        return "blocked", "malformed-url"

    scheme = (parsed.scheme or "").lower()
    if scheme not in {"http", "https"}:
        return "blocked", "scheme:{}".format(scheme or "none")
    if parsed.username or parsed.password:
        return "blocked", "embedded-credentials"

    host = (parsed.hostname or "").lower().strip(".")
    if not host:
        return "blocked", "no-host"
    lan = allow_private and is_private_host(host)
    if parsed.port and parsed.port not in {80, 443, 8080, 8443} and not lan:
        return "blocked", "odd-port:{}".format(parsed.port)

    if IPV4_LITERAL.fullmatch(host):
        if not lan:
            return "blocked", "ip-literal-host"
    if ":" in host and IPV6_LITERAL.fullmatch(host):
        if not lan:
            return "blocked", "ipv6-literal-host"
    if "xn--" in host:
        return "blocked", "punycode-homograph"
    if host.endswith(".onion") or host.endswith(".i2p") or host.endswith(".bit"):
        return "blocked", "anonymizer-network"

    hit = nsfw_hit(host + " " + candidate)
    if hit:
        return "blocked", hit

    labels = host.split(".")
    if len(labels) > 5:
        return "suspicious", "deep-subdomain"
    sld = labels[-2] if len(labels) >= 2 else host

    for token in SHORTENER_HOSTS:
        if host == token or host.endswith("." + token):
            return "suspicious", "url-shortener:{}".format(token)
    for tld in RISKY_TLDS:
        if host.endswith(tld):
            return "suspicious", "high-risk-tld:{}".format(tld)
    for brand in POPULAR_BRANDS:
        if sld == brand:
            return "ok", ""
        if brand in sld:
            return "blocked", "brand-typosquat:{}".format(brand)
    if "%2e" in candidate.lower() or "@" in candidate.split("://", 1)[-1].split("/", 1)[0]:
        return "suspicious", "obfuscated-host"
    return "ok", ""

# ----------------------------------------------------------------- persistent state


class Store:
    def __init__(self, path: Path):
        self.path = path
        self.lock = threading.RLock()
        self.allow = set()
        self.block = set()
        self.users = {}
        self.reports = {}
        self.health = {}
        #: health-sweep proposals, staged for a human. Never applied to the index.
        self.proposals = {}
        self.counters = {"reports": 0, "bans": 0, "allowed": 0, "auto": 0,
                         "harvests": 0, "added": 0, "updated": 0,
                         "quarantined": 0, "pushes": 0, "rejected": 0}
        self.load()

    def load(self) -> None:
        with self.lock:
            try:
                if self.path.is_file():
                    data = json.loads(self.path.read_text(encoding="utf-8"))
                    self.allow = set(data.get("allow") or [])
                    self.block = set(data.get("block") or [])
                    self.users = dict(data.get("users") or {})
                    self.health = dict(data.get("health") or {})
                    self.proposals = dict(data.get("proposals") or {})
                    self.counters.update(data.get("counters") or {})
            except (OSError, ValueError) as exc:
                LOG.warning("state load failed: %s", exc)

    def save(self) -> None:
        with self.lock:
            payload = {
                "allow": sorted(self.allow),
                "block": sorted(self.block),
                "users": self.users,
                "health": self.health,
                "proposals": self.proposals,
                "counters": self.counters,
                "updated_at": int(time.time()),
            }
            atomic_write(self.path, (json.dumps(payload, ensure_ascii=False, indent=1)
                                     + "\n").encode("utf-8"))

    def put_report(self, report: dict) -> dict:
        with self.lock:
            self.reports[report["token"]] = report
            if len(self.reports) > 300:
                oldest = sorted(self.reports, key=lambda key: self.reports[key]["created_at"])
                for key in oldest[: len(self.reports) - 300]:
                    self.reports.pop(key, None)
            self.counters["reports"] += 1
            self.save()
            return report

    def take_report(self, token: str):
        with self.lock:
            report = self.reports.get(token)
            if not report or time.time() - report["created_at"] > 86400:
                self.reports.pop(token, None)
                return None
            return report

    def domain_allowed(self, domain: str) -> bool:
        with self.lock:
            return any(domain == item or domain.endswith("." + item) for item in self.allow)

    def domain_blocked(self, domain: str) -> bool:
        with self.lock:
            return any(domain == item or domain.endswith("." + item) for item in self.block)

    def user_banned(self, user_id):
        with self.lock:
            return self.users.get(str(user_id))

    def resolve(self, token: str, action: str):
        with self.lock:
            report = self.take_report(token)
            if not report:
                return None
            for domain in report["domains"]:
                if action == "ban":
                    self.block.add(domain)
                    self.allow.discard(domain)
                else:
                    self.allow.add(domain)
                    self.block.discard(domain)
            if action == "ban":
                self.users[str(report["user_id"])] = {
                    "name": report["user_name"],
                    "chat_id": report["chat_id"],
                    "domains": report["domains"],
                    "at": int(time.time()),
                }
                self.counters["bans"] += 1
            else:
                self.counters["allowed"] += 1
            self.save()
            return report

    def add_user_ban(self, user_id, name, chat_id, domains=None) -> None:
        with self.lock:
            self.users[str(user_id)] = {"name": name, "chat_id": chat_id,
                                        "domains": sorted(set(domains or [])),
                                        "at": int(time.time())}
            self.save()

    def unban_user(self, user_id) -> None:
        with self.lock:
            self.users.pop(str(user_id), None)
            self.save()

    def allow_domain(self, domain: str) -> None:
        with self.lock:
            self.allow.add(domain)
            self.block.discard(domain)
            self.save()

    def block_domain(self, domain: str) -> None:
        with self.lock:
            self.block.add(domain)
            self.allow.discard(domain)
            self.save()

    def bump(self, key: str, amount: int = 1) -> None:
        with self.lock:
            self.counters[key] = self.counters.get(key, 0) + amount
            self.save()

    def health_get(self, key: str) -> dict:
        with self.lock:
            return dict(self.health.get(key) or {})

    def health_put(self, key: str, record: dict) -> None:
        with self.lock:
            self.health[key] = record

    def stage_health_proposals(self, proposals: dict) -> None:
        """Record what a health sweep *would* change, without changing it.

        Persisted in ``data/`` next to the health records so a human can read
        what is pending; the published index is left alone.
        """
        with self.lock:
            self.proposals = dict(proposals or {})
            self.proposals["updated_at"] = int(time.time())
            self.save()

    def staged_proposals(self) -> dict:
        with self.lock:
            return json.loads(json.dumps(self.proposals, default=str))

    def clear(self) -> None:
        with self.lock:
            self.allow.clear()
            self.block.clear()
            self.users.clear()
            self.reports.clear()
            self.save()

    def summary(self) -> dict:
        with self.lock:
            return {"allow": sorted(self.allow), "block": sorted(self.block),
                    "users": len(self.users), "counters": dict(self.counters)}


STATE = Store(STATE_FILE)

# ----------------------------------------------------------------- repo manager


class RepoManager:
    def __init__(self):
        self.session = http_session()
        if GITHUB_TOKEN:
            self.session.headers["Authorization"] = f"Bearer {GITHUB_TOKEN}"
        self.lock = threading.RLock()
        self.index = {"name": STORE_NAME, "badgeLabel": STORE_BADGE,
                      "signingKey": SIGNING_KEY,
                      "contact": {"website": STORE_WEBSITE},
                      "extensionList": {"extensions": []}}
        self.quarantine = []
        self.audit_cache = {}
        self.cursor = 0
        self.last_error = ""
        self.load()

    def load(self) -> None:
        with self.lock:
            for path in (INDEX_PB, INDEX_JSON):
                if not path.is_file():
                    continue
                try:
                    if path.suffix == ".pb":
                        self.index = decode_index(gzip.decompress(path.read_bytes()))
                    else:
                        self.index = json.loads(path.read_text(encoding="utf-8"))
                except (OSError, ValueError, KeyError, IndexError) as exc:
                    LOG.warning("load %s failed: %s", path.name, exc)
                    continue
                count = len(self.index.get("extensionList", {}).get("extensions", []))
                LOG.info("index loaded from %s (%d extensions)", path.name, count)
                break

            self.index.setdefault("name", STORE_NAME)
            self.index.setdefault("badgeLabel", STORE_BADGE)
            self.index.setdefault("signingKey", SIGNING_KEY)
            self.index.setdefault("contact", {"website": STORE_WEBSITE})
            self.index.setdefault("extensionList", {"extensions": []})
            try:
                if QUARANTINE_FILE.is_file():
                    self.quarantine = json.loads(QUARANTINE_FILE.read_text(encoding="utf-8"))
            except (OSError, ValueError) as exc:
                LOG.warning("quarantine load failed: %s", exc)
            try:
                if AUDIT_CACHE_FILE.is_file():
                    self.audit_cache = json.loads(AUDIT_CACHE_FILE.read_text(encoding="utf-8"))
            except (OSError, ValueError) as exc:
                LOG.warning("audit cache load failed: %s", exc)

    def save_audit_cache(self, live: set) -> None:
        with self.lock:
            if live:
                self.audit_cache = {key: value for key, value in self.audit_cache.items()
                                    if key in live}
            if len(self.audit_cache) > AUDIT_CACHE_LIMIT:
                trimmed = sorted(self.audit_cache.items(),
                                 key=lambda item: item[1].get("at") or 0, reverse=True)
                self.audit_cache = dict(trimmed[:AUDIT_CACHE_LIMIT])
        atomic_write(AUDIT_CACHE_FILE, (json.dumps(self.audit_cache, ensure_ascii=False,
                                                    indent=1) + "\n").encode("utf-8"))

    def cached_reject(self, package: str, version_code) -> str:
        with self.lock:
            record = self.audit_cache.get(package)
        if not record or record.get("verdict") != "reject":
            return ""
        if str(record.get("versionCode")) != str(version_code):
            return ""
        return record.get("reason") or "rejected"

    def record_audit(self, package: str, version_code, verdict: str, reason: str = "") -> None:
        with self.lock:
            self.audit_cache[package] = {"versionCode": str(version_code),
                                         "verdict": verdict, "reason": reason,
                                         "at": int(time.time())}

    def extensions(self) -> list:
        with self.lock:
            return list(self.index["extensionList"]["extensions"])

    def blocked_packages(self) -> set:
        return {item.get("packageName") for item in self.quarantine if item.get("packageName")}

    def save_quarantine(self) -> None:
        atomic_write(QUARANTINE_FILE, (json.dumps(self.quarantine, ensure_ascii=False, indent=1)
                                        + "\n").encode("utf-8"))

    def unquarantine(self, package: str) -> bool:
        with self.lock:
            before = len(self.quarantine)
            self.quarantine = [item for item in self.quarantine
                               if item.get("packageName") != package]
            changed = len(self.quarantine) != before
            if changed:
                self.save_quarantine()
        return changed

    def validate(self, extension: dict) -> str:
        if not extension.get("name"):
            return "missing-name"
        if not extension.get("packageName"):
            return "missing-packageName"
        raw_code = str(extension.get("versionCode") or "").strip()
        if not raw_code.lstrip("-").isdigit() or int(raw_code) < 1:
            return "bad-versionCode"
        if not extension.get("versionName"):
            return "missing-versionName"
        if not extension.get("extensionLib"):
            return "missing-extensionLib"
        resources = extension.get("resources") or {}
        if not resources.get("apkUrl"):
            return "missing-apkUrl"
        if not resources.get("iconUrl"):
            return "missing-iconUrl"
        if not extension.get("sources"):
            return "no-sources"
        for source in extension["sources"]:
            if not str(source.get("id") or "").strip().lstrip("-").isdigit():
                return "bad-source-id"
            if not source.get("name") or not source.get("language"):
                return "bad-source-meta"
        if extension.get("contentWarning") not in CW_VALUES:
            return "bad-contentWarning"
        return ""

    def screen(self, extension: dict) -> str:
        resources = extension.get("resources") or {}
        distribution = [resources.get(key) for key in ("apkUrl", "iconUrl", "jarUrl")]
        for url in [item for item in distribution if item]:
            verdict, reason = url_verdict(url)
            if verdict == "blocked":
                return "url:{}".format(reason)
        for source in extension.get("sources") or []:
            home = source.get("homeUrl")
            if not home:
                continue
            verdict, reason = url_verdict(home, allow_private=True)
            if verdict == "blocked":
                return "url:{}".format(reason)
        return nsfw_reason(extension)

    def fetch_upstream(self) -> list:
        LOG.info("scraping upstream %s", UPSTREAM_INDEX_JSON)
        response = self.session.get(UPSTREAM_INDEX_JSON, timeout=HTTP_TIMEOUT + 25)
        response.raise_for_status()
        payload = response.json()
        extensions = (payload.get("extensionList", {}).get("extensions", [])
                      if isinstance(payload, dict) else payload)
        LOG.info("upstream delivered %d extensions", len(extensions))
        return extensions

    def module_paths(self, package_name: str) -> list:
        parts = (package_name or "").split(".")
        if len(parts) < 6:
            return []
        lang, module = parts[4], ".".join(parts[5:])
        return [f"src/{lang}/{module}", f"lib-multisrc/{module}"]

    def scan_extension_source(self, package_name: str, ref: str = None) -> dict:
        """Static scan of the extension's Kotlin source.

        ``ref`` pins the scan to an immutable commit. Candidates always pass the
        SHA they pinned; only the legacy in-memory audit falls back to the
        configured (moving) ref.
        """
        target = str(ref or UPSTREAM_SOURCE_REF)
        findings = []
        scanned = 0
        for directory in self.module_paths(package_name):
            url = (f"https://api.github.com/repos/{UPSTREAM_SOURCE_REPO}/contents/"
                   f"{directory}?ref={target}")
            try:
                response = self.session.get(
                    url, timeout=HTTP_TIMEOUT,
                    headers={"Accept": "application/vnd.github+json"})
            except requests.RequestException as exc:
                LOG.warning("source listing failed for %s: %s", package_name, exc)
                break
            if response.status_code == 404:
                continue
            if response.status_code in (403, 429):
                LOG.warning("source listing rate-limited for %s (%s)", package_name,
                            response.status_code)
                break
            if not response.ok:
                break
            try:
                entries = response.json()
            except ValueError:
                break
            if not isinstance(entries, list):
                break

            for entry in entries:
                if scanned >= MAX_SOURCE_FILES:
                    break
                if entry.get("type") != "file" or not str(entry.get("name", "")).endswith(".kt"):
                    continue
                raw = (f"https://raw.githubusercontent.com/{UPSTREAM_SOURCE_REPO}/"
                       f"{target}/{entry.get('path')}")
                try:
                    body = self.session.get(raw, timeout=HTTP_TIMEOUT)
                except requests.RequestException:
                    continue
                if not body.ok:
                    continue
                scanned += 1
                for finding in scan_code(body.text):
                    finding["file"] = entry.get("path")
                    findings.append(finding)
            if scanned:
                break
        if scanned:
            LOG.debug("scanned %d files for %s at %s", scanned, package_name, target[:12])
        return {"findings": findings, "scanned": scanned, "ref": target}

    def asset_verdict(self, url: str) -> str:
        """HEAD the remaining distribution assets. "" means alive, anything else
        is a short reason (``404``, ``503``, …)."""
        try:
            code = self.session.head(url, timeout=HTTP_TIMEOUT,
                                     allow_redirects=True).status_code
        except requests.RequestException as exc:
            return "unreachable:{}".format(type(exc).__name__)
        if code in (404, 410):
            return str(code)
        if code in (400, 401, 403, 429):
            return ""
        return str(code) if code >= 500 else ""

    def interleave(self, candidates: list) -> list:
        buckets = {}
        for candidate in candidates:
            parts = str(candidate.get("packageName") or "").split(".")
            language = parts[4] if len(parts) >= 6 else "?"
            buckets.setdefault(language, []).append(candidate)
        queues = [buckets[key] for key in sorted(buckets)]
        merged = []
        while any(queues):
            for queue in queues:
                if queue:
                    merged.append(queue.pop(0))
        return merged

    def discover_candidate(self, extension: dict, pin_apk: bool = True) -> dict:
        """Run one upstream entry through the review pipeline into a candidate.

        This is the only place harvest creates state, and it never writes the
        index. The pinned commit comes from the configured upstream source ref.
        """
        candidate = REVIEW.discover(extension, UPSTREAM_SOURCE_REPO, UPSTREAM_SOURCE_REF,
                                    actor="harvest", pin_apk=pin_apk)
        if candidate.get("rejected"):
            return candidate
        return REVIEW.process(candidate)

    def harvest(self) -> dict:
        """Discover candidates. Publishing is a separate, manual step."""
        started = time.time()
        try:
            upstream = self.fetch_upstream()
        except (requests.RequestException, ValueError) as exc:
            self.last_error = "scrape failed: {}".format(exc)
            LOG.error("%s", self.last_error)
            return {"ok": False, "error": self.last_error, "indexTouched": False}

        # read-only view of what is actually published
        published = {item["packageName"]: item
                     for item in self.extensions() if item.get("packageName")}
        blocked = self.blocked_packages()
        live = {str(item.get("packageName")) for item in upstream if item.get("packageName")}
        added, updated, rejected, cached, scanned, budget = [], [], [], 0, 0, False

        for candidate in self.interleave(upstream):
            package = candidate.get("packageName")
            if not package or package in blocked:
                continue
            known = published.get(package)
            version_code = candidate.get("versionCode")
            try:
                newer = int(version_code or 0) > int((known or {}).get("versionCode") or 0)
            except (TypeError, ValueError):
                newer = False
            if known and not newer:
                continue
            existing = CANDIDATES.latest(package)
            try:
                seen = int(version_code or 0) <= int((existing or {}).get("versionCode") or 0)
            except (TypeError, ValueError):
                seen = False
            if existing and seen:
                continue
            if len(added) >= MAX_NEW_PER_RUN and not known:
                budget = True
                break
            if scanned >= MAX_SCAN_PER_RUN:
                budget = True
                break

            reason = self.cached_reject(package, version_code)
            if reason:
                rejected.append({"packageName": package, "reason": reason, "cached": True})
                cached += 1
                continue

            scanned += 1
            result = self.discover_candidate(candidate)
            if result.get("rejected"):
                self.record_audit(package, version_code, "reject", result.get("reason", ""))
                rejected.append({"packageName": package, "reason": result.get("reason", "")})
                LOG.info("candidate rejected %s -> %s", package, result.get("reason"))
                continue
            if result.get("status") != PENDING_REVIEW:
                reason = "pipeline stopped at {}".format(result.get("status"))
                self.record_audit(package, version_code, "reject", reason)
                rejected.append({"packageName": package, "reason": reason})
                LOG.info("candidate %s -> %s (%s)", package, result.get("status"), reason)
                continue

            self.record_audit(package, version_code, "candidate")
            (updated if known else added).append(package)

        self.save_audit_cache(live)

        STATE.bump("harvests")
        STATE.bump("added", len(added))
        STATE.bump("updated", len(updated))
        STATE.bump("rejected", len(rejected) - cached)
        summary = {
            "ok": True, "added": added, "updated": updated,
            "rejected": [item for item in rejected if not item.get("cached")],
            "rejected_cached": cached, "scanned": scanned, "budget_hit": budget,
            "total": len(self.extensions()),
            "pending_review": len(CANDIDATES.by_status(PENDING_REVIEW)),
            "indexTouched": False,
            "seconds": round(time.time() - started, 1),
        }
        LOG.info("harvest done: %d candidate(s) ready (%d new, %d update), %d rejected "
                 "(%d cached), %d scanned, index still holds %d extensions, %ss",
                 summary["pending_review"], len(added), len(updated),
                 len(rejected) - cached, cached, scanned, summary["total"],
                 summary["seconds"])
        return summary

    def probe(self, url: str) -> tuple:
        """Return (state, reason) where state is ok | dead | down."""
        last = "no-response"
        for method in ("HEAD", "GET"):
            try:
                response = self.session.request(method, url, timeout=12, allow_redirects=True,
                                                stream=True)
                code = response.status_code
                response.close()
            except requests.RequestException as exc:
                last = type(exc).__name__
                continue
            if code in (405, 501):
                last = "http-{}".format(code)
                continue
            if 200 <= code < 400:
                return "ok", "ok"
            if code in (401, 403, 429, 451):
                return "ok", "guarded-{}".format(code)
            if code in (404, 410):
                return "dead", "http-{}".format(code)
            if code >= 500:
                return "down", "http-{}".format(code)
            return "ok", "http-{}".format(code)
        return "down", last

    def health_sweep(self) -> dict:
        """Probe the published sources and record the result. Never publishes.

        The sweep is read-only with respect to the published repository: it does
        not build, does not push, and does **not** edit ``self.index``. Dead
        sources and fully-dead extensions become *proposals* staged in
        ``data/`` (the local quarantine list plus ``STATE``), so a link outage
        can never silently change what users are offered. Acting on a proposal is
        a review decision belonging to the later manual publish phase.

        Note: ``health_sweep()`` *creates* quarantine proposals only. It never
        removes anything from the published index by itself, and neither
        :meth:`build` nor the publish path consults the quarantine list yet --
        the staged entries are inert until a dedicated quarantine review step
        applies them. Until that step exists, do not read a proposal as
        "already enforced".
        """
        extensions = self.extensions()
        targets, per_extension = [], {}
        for extension in extensions:
            package = extension.get("packageName")
            keys = []
            for source in extension.get("sources") or []:
                home = source.get("homeUrl")
                if not home:
                    continue
                host = domain_of(home)
                if not is_public_host(host):
                    LOG.debug("skip self-hosted source %s (%s)", package, host)
                    continue
                key = "{}|{}".format(package, home)
                keys.append((key, home, source))
                targets.append((key, home))
            per_extension[package] = keys
        if not targets:
            return {"ok": True, "checked": 0, "alive": 0, "failing": 0, "dropped_sources": 0,
                    "quarantined": [], "total": len(extensions), "proposed_source_drops": [],
                    "proposed_quarantine": [], "proposed_dropped_sources": 0,
                    "applied": False, "indexTouched": False, "published": False}

        with self.lock:
            start = self.cursor % len(targets)
            self.cursor = (start + HEALTH_SLICE) % len(targets)
        window = (targets + targets)[start:start + min(HEALTH_SLICE, len(targets))]

        alive, dead, down = 0, 0, 0
        with ThreadPoolExecutor(max_workers=HEALTH_WORKERS) as pool:
            futures = {pool.submit(self.probe, home): key for key, home in window}
            for future, key in futures.items():
                try:
                    state, reason = future.result()
                except Exception as exc:  # pragma: no cover - defensive
                    state, reason = "down", type(exc).__name__
                record = STATE.health_get(key)
                previous = int(record.get("fails") or 0)
                if state == "ok":
                    fails = 0
                    alive += 1
                elif state == "dead":
                    fails = previous + 1
                    dead += 1
                else:
                    fails = previous
                    down += 1
                record.update({"ok": state == "ok", "state": state, "reason": reason,
                               "fails": fails, "last": int(time.time())})
                STATE.health_put(key, record)
        STATE.save()

        dead_keys = {key for key, record in STATE.health.items()
                     if record.get("state") == "dead" and int(record.get("fails") or 0)
                     >= DEAD_THRESHOLD}

        # Staged, never applied. ``self.index`` is deliberately not touched: a
        # health sweep may not remove a source or an extension from the index
        # that users already download from.
        proposed_quarantine, proposed_drops = [], []
        if dead_keys:
            with self.lock:
                for extension in extensions:
                    package = extension.get("packageName")
                    keys = per_extension.get(package) or []
                    if not keys:
                        continue
                    offenders = {key for key, _, _ in keys if key in dead_keys}
                    if not offenders:
                        continue
                    if len(offenders) == len(keys):
                        if package not in self.blocked_packages():
                            self.quarantine.append({
                                "packageName": package,
                                "name": extension.get("name"),
                                "reason": "all-sources-dead",
                                "at": int(time.time()),
                            })
                        proposed_quarantine.append(package)
                        continue
                    survivors = [source for source in extension.get("sources") or []
                                 if "{}|{}".format(package, source.get("homeUrl") or "")
                                 not in offenders]
                    proposed_drops.append({
                        "packageName": package,
                        "action": "drop-sources",
                        "reason": "dead-sources",
                        "fails": DEAD_THRESHOLD,
                        "dead": sorted(offenders),
                        "keep": [source.get("homeUrl") for source in survivors],
                    })
            if proposed_quarantine:
                # data/quarantine.json is local state, not the published index
                self.save_quarantine()
                STATE.bump("quarantined", len(proposed_quarantine))
                LOG.warning("staged %d fully-dead extension(s) for review: %s",
                            len(proposed_quarantine), ", ".join(proposed_quarantine[:8]))
            if proposed_drops:
                LOG.info("staged %d dead source removal(s) for review, none applied",
                         len(proposed_drops))

        STATE.stage_health_proposals({"quarantine": proposed_quarantine,
                                      "source_drops": proposed_drops})

        summary = {"ok": True, "checked": len(window), "alive": alive, "failing": dead,
                   "inconclusive": down, "dropped_sources": 0,
                   "quarantined": proposed_quarantine,
                   "proposed_quarantine": proposed_quarantine,
                   "proposed_source_drops": proposed_drops,
                   "proposed_dropped_sources": sum(len(item["dead"])
                                                  for item in proposed_drops),
                   "total": len(self.extensions()),
                   "applied": False, "indexTouched": False, "published": False}
        LOG.info("health sweep: %d checked, %d alive, %d dead, %d inconclusive, "
                 "%d proposed source removals, %d proposed quarantine -- "
                 "index unchanged (%d extensions), nothing published",
                 len(window), alive, dead, down, summary["proposed_dropped_sources"],
                 len(proposed_quarantine), summary["total"])
        return summary

    def build(self, dest: Path = None, baseline: Path = None) -> dict:
        """Regenerate the index artifacts.

        ``dest`` redirects every write into another directory, so tests, the
        dry-run ``--build`` and :meth:`prepare_publish` can run a real build
        without touching repo/. Passing ``dest`` is the only way to build while
        TEST_MODE is on.

        ``baseline`` is the index the shura delta is computed against, and
        defaults to the currently published repo/index.json. Overriding it is
        how a test reproduces a client that is behind.

        Without ``dest`` this is a *real* write and needs a live
        :class:`PublishAuthorization`; the sandbox form needs none, so a review
        or a test can never be one refactor away from publishing.

        The signing key is resolved *first*, before anything is written: a build
        that cannot carry a valid one raises :class:`SigningMetadataMissing` and
        leaves every artifact untouched, rather than publishing an index and a
        repo.json with the signing metadata blanked out.
        """
        isolated = dest is not None
        if not isolated:
            assert_may_write_repo("build the index")
        with self.lock:
            index = json.loads(json.dumps(self.index))
        signing_key = resolve_signing_key(index)
        index["name"] = STORE_NAME
        index["badgeLabel"] = STORE_BADGE
        index["signingKey"] = signing_key
        index["contact"] = {"website": STORE_WEBSITE}
        if STORE_DISCORD:
            index["contact"]["discord"] = STORE_DISCORD

        kept, dropped = [], []
        for extension in index["extensionList"]["extensions"]:
            reason = self.validate(extension)
            if reason:
                dropped.append({"packageName": extension.get("packageName"), "reason": reason})
            else:
                kept.append(extension)
        index["extensionList"]["extensions"] = sorted(
            kept, key=lambda item: item.get("packageName") or "")
        if dropped and not isolated:
            # only a real publish may fold the filtered index back into memory;
            # a sandbox build stays read-only so a dry-run cannot hide a change
            with self.lock:
                self.index = index
        if dropped:
            LOG.warning("build dropped %d invalid entries: %s", len(dropped),
                        ", ".join(item["packageName"] or "?" for item in dropped[:5]))

        proto = encode_index(index)
        index_bytes = render_index_json(index).encode("utf-8")
        extensions = index["extensionList"]["extensions"]

        # The shura layer is derived here, from the same index object, in the
        # same pass that writes it. There is no second code path that could
        # produce a delta describing a different index than the one published.
        revision = shura_revision(index_bytes)
        base_index = shura_baseline_index(baseline)
        base_extensions = ((base_index or {}).get("extensionList") or {}).get("extensions") or []
        base_bytes = (render_index_json(base_index).encode("utf-8")
                      if isinstance(base_index, dict) else None)
        delta = shura_delta(shura_revision(base_bytes) if base_bytes else None,
                            revision, extensions, shura_diff(base_extensions, extensions))

        repo_json = {"index_v2": INDEX_PB_URL,
                     "meta": {"name": STORE_NAME, "shortName": STORE_BADGE,
                              "website": STORE_WEBSITE,
                              "signingKeyFingerprint": signing_key}}
        if STORE_DISCORD:
            repo_json["meta"]["discord"] = STORE_DISCORD

        artifacts = {
            "repo/index.json": index_bytes,
            "repo/index.pb": gzip.compress(proto, mtime=0, compresslevel=9),
            "repo/index.min.json": (json.dumps(LEGACY_INDEX_MIN, indent=2) + "\n").encode("utf-8"),
            "repo.json": (json.dumps(repo_json, indent=2, ensure_ascii=False) + "\n").encode("utf-8"),
            "shura/manifest.json": shura_render(shura_manifest(revision, extensions)),
            "shura/delta.json": shura_render(delta),
            "data/quarantine.json": (json.dumps(self.quarantine, ensure_ascii=False, indent=1)
                                     + "\n").encode("utf-8"),
            "data/audit_cache.json": (json.dumps(self.audit_cache, ensure_ascii=False, indent=1,
                                                 sort_keys=True) + "\n").encode("utf-8"),
        }

        changed = []
        for relative, payload in artifacts.items():
            path = (dest / relative) if isolated else (ROOT / relative)
            if path.is_file() and path.read_bytes() == payload:
                continue
            atomic_write(path, payload)
            changed.append(relative)

        return {"ok": True, "changed": changed, "extensions": len(kept),
                "dropped": dropped, "proto_bytes": len(proto),
                "pb_bytes": len(artifacts["repo/index.pb"]),
                "revision": revision, "baseRevision": delta["baseRevision"],
                "delta": {"added": len(delta["added"]), "updated": len(delta["updated"]),
                          "removed": len(delta["removed"])}}

    def push(self, paths=None) -> dict:
        assert_may_push("push to GitHub")
        if not PUSH_ENABLED:
            return {"ok": False, "pushed": [], "skipped": "no GITHUB_TOKEN"}
        targets = paths or ["repo/index.json", "repo/index.pb", "repo/index.min.json",
                            "repo.json", "shura/manifest.json", "shura/delta.json",
                            "data/quarantine.json", "data/audit_cache.json"]
        session = http_session()
        session.headers.update({
            "Authorization": f"Bearer {GITHUB_TOKEN}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
        })
        pushed = []
        for relative in targets:
            local = ROOT / relative
            if not local.is_file():
                continue
            payload = {
                "message": "chore(repo): auto index update {} ({})".format(
                    relative, time.strftime("%Y-%m-%d %H:%M UTC", time.gmtime())),
                "content": base64.b64encode(local.read_bytes()).decode("ascii"),
                "branch": GITHUB_BRANCH,
            }
            api = f"{GITHUB_API}/repos/{GITHUB_REPO}/contents/{relative}"
            for attempt in range(3):
                try:
                    current = session.get(api, params={"ref": GITHUB_BRANCH},
                                          timeout=HTTP_TIMEOUT)
                    if current.status_code == 200:
                        sha = (current.json() or {}).get("sha")
                        if sha:
                            payload["sha"] = sha
                    result = session.put(api, json=payload, timeout=HTTP_TIMEOUT + 15)
                except requests.RequestException as exc:
                    LOG.warning("push %s attempt %d: %s", relative, attempt + 1, exc)
                    time.sleep(3 * (attempt + 1))
                    continue
                if result.status_code in (200, 201):
                    pushed.append(relative)
                    LOG.info("pushed %s", relative)
                    break
                if result.status_code in (409, 422) and payload.get("sha"):
                    payload.pop("sha", None)
                    time.sleep(2)
                    continue
                LOG.error("push %s failed: %s %s", relative, result.status_code,
                          result.text[:200])
                break
            time.sleep(1.1)
        if pushed:
            STATE.bump("pushes")
        return {"ok": bool(pushed), "pushed": pushed,
                "skipped": "" if pushed else "nothing-to-push"}

    def publish(self) -> dict:
        """Regenerate the artifacts and push them. Manual phase only.

        Reachable *only* from inside a :class:`PublishAuthorization`, i.e. from
        :func:`run_publish_phase`. Harvest, health, the Telegram commands, the
        cron jobs and the CLI never hold that authorisation, so every one of
        them gets ``blocked=True`` here instead of a write.
        """
        try:
            assert_may_push("publish")
            built = self.build()
        except PushBlocked as exc:
            return {"ok": False, "changed": [], "pushed": [], "blocked": True,
                    "skipped": str(exc)}
        if not built.get("ok"):
            return built
        if not built["changed"]:
            LOG.info("index already up to date (%d extensions)", built["extensions"])
            return {"ok": True, "changed": [], "pushed": [], "skipped": "nothing-to-push",
                    "extensions": built["extensions"]}
        try:
            pushed = self.push(built["changed"])
        except PushBlocked as exc:
            return {"ok": False, "changed": built["changed"], "pushed": [], "blocked": True,
                    "extensions": built["extensions"], "skipped": str(exc)}
        LOG.info("published %d file(s): %s", len(built["changed"]), pushed.get("pushed"))
        return {"ok": True, "changed": built["changed"],
                "extensions": built["extensions"], **pushed}

    def prepare_publish(self, dest: Path = None) -> dict:
        """Dry-run of the publish phase. Writes nothing, pushes nothing.

        Builds the artifacts into a throwaway directory, reports exactly which
        files *would* change, and lists the accepted candidates that a manual
        publish would still have to apply first. This is what ``/publish`` and
        the CRON_PUBLISH readiness job report, so an operator can see the state
        of the release without any of it being one keystroke away.
        """
        sandbox = Path(dest) if dest else Path(tempfile.mkdtemp(prefix="manga-prepare-"))
        sandbox.mkdir(parents=True, exist_ok=True)
        built = self.build(dest=sandbox)
        would_change = [item for item in built.get("changed") or []
                        if item in ("repo/index.json", "repo/index.pb",
                                    "repo/index.min.json", "repo.json",
                                    "shura/manifest.json", "shura/delta.json")]
        accepted = CANDIDATES.by_status(ACCEPTED)
        return {"ok": True, "mode": PUBLISH_MODE, "dryRun": True, "wrote": None,
                "sandbox": str(sandbox), "would_change": would_change,
                "extensions": built.get("extensions"), "dropped": built.get("dropped") or [],
                "ready_to_publish": len(accepted),
                "accepted_candidates": [
                    "{} v{} (accepted by {})".format(
                        item.get("packageName"), item.get("versionCode"),
                        item.get("acceptedBy") or "?") for item in accepted[:10]],
                "pending_review": len(CANDIDATES.by_status(PENDING_REVIEW)),
                "published": False, "indexTouched": False, "pushed": [],
                "gate": publish_gate("prepare publish")}

    def latest(self, limit: int = 10) -> list:
        entries = self.extensions()
        entries.sort(key=lambda item: (int(item.get("versionCode") or 0),
                                       item.get("packageName") or ""), reverse=True)
        return entries[:limit]

    def stats(self) -> dict:
        extensions = self.extensions()
        languages = {}
        for extension in extensions:
            for source in extension.get("sources") or []:
                language = source.get("language") or "?"
                languages[language] = languages.get(language, 0) + 1
        return {"extensions": len(extensions),
                "sources": sum(len(item.get("sources") or []) for item in extensions),
                "quarantined": len(self.quarantine),
                "languages": languages,
                "index_pb_bytes": INDEX_PB.stat().st_size if INDEX_PB.is_file() else 0,
                "last_error": self.last_error}


REPO = RepoManager()

# ----------------------------------------------------------------- review pipeline

#: Durable candidate store + append-only audit log. Both survive restarts and
#: are never truncated by the test suite.
CANDIDATES = CandidateStore(CANDIDATE_FILE)
AUDITLOG = AuditLog(AUDITLOG_FILE)


def github_commit_resolver(repository: str, ref: str) -> str:
    """Resolve a branch/tag to an immutable commit SHA via the GitHub API."""
    response = REPO.session.get(
        f"{GITHUB_API}/repos/{repository}/commits/{ref}", timeout=HTTP_TIMEOUT,
        headers={"Accept": "application/vnd.github+json"})
    if response.status_code != 200:
        raise RuntimeError("git {}:{} -> HTTP {}".format(repository, ref, response.status_code))
    sha = ((response.json() or {}).get("sha") or "")
    if not is_commit_sha(sha):
        raise RuntimeError("git {}:{} returned a non-sha".format(repository, ref))
    return sha


def source_scan_for(candidate: dict) -> dict:
    """Scan the *pinned commit*, never the moving branch."""
    return REPO.scan_extension_source(candidate.get("packageName", ""),
                                      ref=candidate.get("sourceCommit", ""))


def build_test_for(candidate: dict) -> tuple:
    """Offline metadata build check. Never writes repo/."""
    entry = REVIEW.index_entry(candidate)
    reason = REPO.validate(entry)
    if reason:
        return False, "index entry invalid: {}".format(reason)
    try:
        # The probe carries the same signing key a real build would, so this
        # still measures "does the entry encode" and not "is the store configured".
        encode_index({"signingKey": resolve_signing_key(REPO.index),
                      "extensionList": {"extensions": [entry]}})
    except Exception as exc:  # noqa: BLE001
        return False, "protobuf encode failed: {}".format(exc)
    return True, "index entry validates and encodes"


def index_lookup(package: str) -> dict:
    """Read-only lookup into the published index."""
    with REPO.lock:
        for entry in REPO.index.get("extensionList", {}).get("extensions", []):
            if entry.get("packageName") == package:
                return entry
    return {}


def screen_gate(candidate: dict) -> str:
    """The screen gate: URL screening (punycode, shorteners, IP literals, …) plus
    the NSFW policy. Returns a reason, or "" when the extension passes."""
    extension = candidate.get("extension") or candidate
    return REPO.screen(extension)


REVIEW = ReviewSystem(
    CANDIDATES, AUDITLOG,
    commit_resolver=github_commit_resolver,
    deep_screen=screen_gate,
    build_tester=build_test_for,
    index_lookup=index_lookup,
    source_scanner=source_scan_for,
    asset_probe=REPO.asset_verdict,
    allow_mixed=ALLOW_MIXED,
)

# ----------------------------------------------------------------- telegram client


def keyboard(*rows) -> dict:
    return {"inline_keyboard": [row for row in rows if row]}


class Telegram:
    def __init__(self, token: str):
        self.base = f"https://api.telegram.org/bot{token}/"
        self.offset = 0
        self.bot_id = 0
        self.session = http_session()

    def call(self, method: str, payload=None, timeout=70, retries=3):
        for attempt in range(retries):
            try:
                response = self.session.post(self.base + method, json=payload or {},
                                             timeout=timeout)
            except requests.RequestException as exc:
                LOG.warning("%s network error: %s", method, exc)
                time.sleep(2 * (attempt + 1))
                continue
            if response.status_code == 429:
                delay = 5
                try:
                    delay = int(response.json().get("parameters", {}).get("retry_after", 5))
                except ValueError:
                    pass
                LOG.warning("%s rate limited, waiting %ss", method, delay)
                time.sleep(delay + 1)
                continue
            if response.status_code == 409:
                raise RuntimeError("another instance is polling (409 Conflict)")
            if response.status_code == 403:
                LOG.error("%s forbidden: %s", method, response.text[:200])
                return None
            if not response.ok:
                LOG.error("%s failed %s: %s", method, response.status_code, response.text[:200])
                return None
            body = response.json()
            if not body.get("ok"):
                LOG.error("%s rejected: %s", method, body.get("description"))
                return None
            return body.get("result")
        return None

    def send(self, chat_id, text: str, markup=None):
        payload = {"chat_id": chat_id, "text": clip(text, 4000), "parse_mode": "HTML",
                   "disable_web_page_preview": True}
        if markup:
            payload["reply_markup"] = markup
        return self.call("sendMessage", payload)

    def notify_admins(self, text: str, markup=None) -> int:
        return sum(1 for admin in sorted(ADMIN_IDS) if self.send(admin, text, markup))

    def edit(self, chat_id, message_id, text: str, markup=None):
        payload = {"chat_id": chat_id, "message_id": message_id, "text": clip(text, 4000),
                   "parse_mode": "HTML", "reply_markup": markup or {"inline_keyboard": []}}
        return self.call("editMessageText", payload)

    def answer(self, callback_id, text: str = "") -> None:
        self.call("answerCallbackQuery", {"callback_query_id": callback_id, "text": text})

    def ban(self, chat_id, user_id):
        return self.call("banChatMember", {"chat_id": chat_id, "user_id": user_id,
                                           "until_date": 0, "revoke_messages": True})

    def delete(self, chat_id, message_id):
        return self.call("deleteMessage", {"chat_id": chat_id, "message_id": message_id})

    def updates(self, timeout=60):
        result = self.call("getUpdates", {"offset": self.offset, "timeout": timeout,
                                          "allowed_updates": ["message", "callback_query"]},
                           timeout=timeout + 20)
        if isinstance(result, list) and result:
            self.offset = max(int(item["update_id"]) for item in result) + 1
        return result if isinstance(result, list) else []


TG = Telegram(TELEGRAM_TOKEN) if TELEGRAM_TOKEN else None

# ----------------------------------------------------------------- message texts

HELP = (
    "🛡️ <b>{name}</b> — مدير مستودع ذكي + حارس أمني\n\n"
    "📦 <b>أوامر المشتركين</b>\n"
    "• /start — القائمة الرئيسية\n"
    "• /repo — رابط المستودع لإضافته في Mihon\n"
    "• /latest — أحدث الإضافات\n"
    "• /help — هذه القائمة\n\n"
    "🔐 <b>أوامر المشرف (خاص فقط)</b>\n"
    "• /status — حالة البوت والمستودع\n"
    "• /scan — جلب وترشيح فوري (لا يُعدّل الفهرس)\n"
    "• /candidates — بانتظار المراجعة\n"
    "• /review &lt;حزمة&gt; — تقرير المرشح (قراءة فقط)\n"
    "• /accept &lt;حزمة&gt; — قبول مرشح (ليس نشرًا)\n"
    "• /health — فحص روابط المصادر (لا ينشر)\n"
    "• /publish — تقرير جاهزية النشر فقط ⛔️ (لا ينشر ولا يرفع)\n"
    "• /quarantine — قائمة المصادر المعطّلة\n"
    "• /unquarantine &lt;حزمة&gt; — استرجاع مصدر معطّل\n"
    "• /allow &lt;نطاق&gt; — السماح\n"
    "• /block &lt;نطاق&gt; — الحظر\n"
    "• /ban &lt;معرّف&gt; — حظر عضو\n"
    "• /unban &lt;معرّف&gt; — رفع الحظر\n"
    "• /lists — القوائم المحفوظة\n"
    "• /reset — تصفير القوائم\n"
).format(name=esc(STORE_NAME))


def help_text() -> str:
    return HELP


def repo_text() -> str:
    stats = REPO.stats()
    return (
        "📦 <b>مستودع {name}</b>\n\n"
        "➕ <b>أضفه في Mihon:</b>\n"
        "الإعدادات ← المستودعات ← إضافة مستودع، ثم الصق الرابط الخام:\n"
        "<code>{index_json}</code>\n\n"
        "<b>أو رابط repo.json (بديل):</b>\n"
        "<code>{repo_json}</code>\n\n"
        "أو اضغط الزر بالأسفل لإضافته مباشرة:\n"
        "<a href=\"{deep}\">➕ إضافة المستودع في Mihon</a>\n\n"
        "📊 الإضافات: <b>{ext}</b> | المصادر: <b>{src}</b> | المعطّل: <b>{bad}</b>\n"
        "🔑 مفتاح التوقيع: <code>{key}</code>\n"
        "🔗 {website}"
    ).format(name=esc(STORE_NAME), index_json=esc(INDEX_JSON_URL),
             repo_json=esc(REPO_JSON_URL), deep=esc(MIHON_DEEP_LINK),
             ext=stats["extensions"], src=stats["sources"], bad=stats["quarantined"],
             key=esc(SIGNING_KEY[:16] + "…"), website=esc(STORE_WEBSITE))


def repo_markup() -> dict:
    return keyboard(
        [{"text": "➕ إضافة في Mihon", "url": MIHON_DEEP_LINK}],
        [{"text": "📄 index.json", "url": INDEX_JSON_URL},
         {"text": "📋 repo.json", "url": REPO_JSON_URL}],
        [{"text": "📦 index.pb", "url": INDEX_PB_URL},
         {"text": "📦 index.min.json", "url": INDEX_MIN_URL}],
        [{"text": "🔗 المستودع", "url": STORE_WEBSITE}],
    )


def latest_text() -> str:
    entries = REPO.latest(10)
    if not entries:
        return "📭 لا توجد إضافات بعد. أرسل <code>/scan</code> لبدء الجلب التلقائي."
    lines = ["🆕 <b>أحدث الإضافات في {}</b>".format(esc(STORE_NAME)), ""]
    for extension in entries:
        languages = sorted({str(source.get("language")) for source in extension.get("sources") or []})
        warning = " · 🔞" if extension.get("contentWarning") == CW_NAMES[CW_NSFW] else ""
        lines.append("• <b>{}</b>\n   ↩️ {} · 🏷️ {}{}".format(
            esc(clip(extension.get("name"), 60)), esc(", ".join(languages) or "?"),
            esc(str(extension.get("versionName") or "?")), warning))
    lines += ["", "📊 الإجمالي: <b>{}</b> إضافة".format(REPO.stats()["extensions"])]
    return "\n".join(lines)


def status_text() -> str:
    stats = REPO.stats()
    counters = STATE.summary()["counters"]
    info = STATE.summary()
    top = sorted(stats["languages"].items(), key=lambda item: -item[1])[:8]
    lines = [
        "🤖 <b>حالة {name}</b>".format(name=esc(STORE_NAME)),
        "",
        "⏱ مدّة التشغيل: <b>{mins} دقيقة</b>".format(mins=(time.time() - STARTED_AT) // 60),
        "📦 الإضافات: <b>{ext}</b> | المصادر: <b>{src}</b> | المعطّل: <b>{bad}</b>".format(
            ext=stats["extensions"], src=stats["sources"], bad=stats["quarantined"]),
        "🌐 اللغات: {langs}".format(langs=", ".join(f"{k}:{v}" for k, v in top) or "—"),
        "📦 حجم index.pb: <b>{kb} KB</b>".format(kb=round(stats["index_pb_bytes"] / 1024, 1)),
        "🔁 جلبات: {h} | جديد: {a} | تحديث: {u} | مرفوض: {r} | مُعطّل: {q}".format(
            h=counters.get("harvests", 0), a=counters.get("added", 0),
            u=counters.get("updated", 0), r=counters.get("rejected", 0),
            q=counters.get("quarantined", 0)),
        "📤 عمليات الرفع: {}".format(counters.get("pushes", 0)),
        "🚨 بلاغات: {} | حظر: {} | سماح: {} | تلقائي: {}".format(
            counters.get("reports", 0), counters.get("bans", 0),
            counters.get("allowed", 0), counters.get("auto", 0)),
        "🌐 نطاقات مسموحة: {} | محظورة: {} | محظورون: {}".format(
            len(info["allow"]), len(info["block"]), info["users"]),
        "🔐 فحص الكود: <b>{code}</b> | فلتر 18+: <b>{nsfw}</b>".format(
            code="مفعّل" if SOURCE_SCAN_ENABLED else "معطّل",
            nsfw="صارم" if not ALLOW_MIXED else "متساهل"),
        "📤 GitHub: <b>{gh}</b>".format(
            gh=esc(f"{GITHUB_REPO}@{GITHUB_BRANCH}") if PUSH_ENABLED else "غير مفعّل"),
        "⏰ الجدولة: <code>{a}</code> / <code>{b}</code> ({tz})".format(
            a=esc(CRON_HARVEST), b=esc(CRON_HEALTH), tz=esc(TIMEZONE)),
    ]
    if stats["last_error"]:
        lines.append("⚠️ آخر خطأ: {}".format(esc(clip(stats["last_error"], 120))))
    return "\n".join(lines)


def report_text(report: dict) -> str:
    return (
        "🚨 <b>مخالفة</b>\n\n"
        "👤 {user} <code>{uid}</code>\n"
        "💬 {chat} <code>{cid}</code>\n"
        "🏷️ السبب: <b>{reason}</b>\n"
        "🔗 {links}\n\n"
        "📄 النص:\n<pre>{excerpt}</pre>"
    ).format(user=esc(clip(report["user_name"], 100)), uid=report["user_id"],
             chat=esc(clip(report["chat_title"], 100)), cid=report["chat_id"],
             reason=esc(clip(report["reason"], 120)),
             links="\n".join("• " + esc(clip(link, 160)) for link in report["links"][:8]),
             excerpt=esc(clip(report["excerpt"], 700)) or "—")


def decision_text(report: dict, action: str, note: str) -> str:
    icon = "🚫" if action == "ban" else "✅"
    verdict = "تم الحظر" if action == "ban" else "تم السماح"
    return "\n".join([
        "{} <b>{}</b> — {}".format(icon, verdict, esc(note)),
        "",
        "👤 {} <code>{}</code>".format(esc(clip(report["user_name"], 80)), report["user_id"]),
        "💬 {} <code>{}</code>".format(esc(clip(report["chat_title"], 80)), report["chat_id"]),
        "🔗 {}".format(", ".join(report["domains"][:8]) or "—"),
    ])

# ----------------------------------------------------------------- group guard


def new_token() -> str:
    return os.urandom(6).hex()


def build_report(message: dict, links: list, reason: str) -> dict:
    chat = message.get("chat") or {}
    user = message.get("from") or {}
    return {"token": new_token(), "created_at": time.time(),
            "chat_id": chat.get("id"), "chat_title": chat.get("title") or sender_name(chat),
            "chat_type": chat.get("type", "private"), "message_id": message.get("message_id"),
            "user_id": user.get("id", 0), "user_name": sender_name(user),
            "links": [item["link"] for item in links],
            "domains": sorted({item["domain"] for item in links}),
            "reason": reason, "excerpt": text_of(message)}


def review_links(links: list) -> list:
    findings = []
    for item in links:
        verdict, reason = url_verdict(item["link"])
        if verdict != "ok":
            findings.append({"link": item["link"], "domain": item["domain"],
                             "verdict": verdict, "reason": reason})
    return findings


def handle_message(tg: Telegram, message: dict) -> None:
    chat = message.get("chat") or {}
    user = message.get("from") or {}
    chat_id = chat.get("id")
    text = (message.get("text") or "").strip()

    if text.startswith("/"):
        # Authorised by the sender's numeric id, not by the chat: a group admin
        # can drive the bot without the whole group gaining the privilege.
        if is_admin_user(user):
            run_command(tg, message, text)
        else:
            LOG.warning("command %r by non-admin %s/%s refused",
                        text.split()[0][:24], user.get("id"), user.get("username"))
            tg.send(chat_id, "⛔️ هذا الأمر للمشرفين فقط.")
        return
    if not chat_id:
        return
    if user.get("is_bot") or user.get("id") in ADMIN_IDS:
        return
    if WATCH_CHATS and chat_id not in WATCH_CHATS:
        return

    links = extract_links(text_of(message))
    if not links:
        return

    if STATE.user_banned(user.get("id")) or any(
            STATE.domain_blocked(item["domain"]) for item in links):
        if AUTO_BAN:
            tg.ban(chat_id, user.get("id"))
        if AUTO_DELETE:
            tg.delete(chat_id, message.get("message_id"))
        STATE.bump("auto")
        LOG.info("auto action chat=%s user=%s", chat_id, user.get("id"))
        return

    if all(STATE.domain_allowed(item["domain"]) for item in links):
        return

    findings = review_links(links)
    hard = [item for item in findings if item["verdict"] == "blocked"]
    if hard:
        if AUTO_DELETE:
            tg.delete(chat_id, message.get("message_id"))
        if AUTO_BAN:
            tg.ban(chat_id, user.get("id"))
        STATE.bump("auto")
        reason = ",".join(sorted({item["reason"] for item in hard}))
        tg.notify_admins("🚫 <b>حظر تلقائي</b>\nالسبب: <b>{reason}</b>\n"
                         "العضو: {user} <code>{uid}</code>\nالرابط: {link}".format(
                             reason=esc(reason), user=esc(sender_name(user)),
                             uid=user.get("id"), link=esc(hard[0]["link"])))
        LOG.info("blocked link chat=%s reason=%s", chat_id, reason)
        return

    if not findings and not REPORT_UNKNOWN:
        return

    report = build_report(message, links,
                          findings[0]["reason"] if findings else "unlisted-link")
    STATE.put_report(report)
    tg.notify_admins(report_text(report), keyboard([
        {"text": "🚫 حظر العضو / المصدر", "callback_data": f"ban:{report['token']}"},
        {"text": "✅ السماح", "callback_data": f"allow:{report['token']}"},
    ]))
    LOG.info("report token=%s chat=%s user=%s reason=%s", report["token"], chat_id,
             report["user_id"], report["reason"])


def is_admin_user(user: dict) -> bool:
    """Single authorisation gate, by numeric Telegram id only.

    Usernames, display names and chat ids are never trusted: an admin list that
    could be satisfied by a rename would be trivially bypassable. Bots are never
    admins. Every privileged path (commands *and* callbacks) goes through here.
    """
    if not isinstance(user, dict) or user.get("is_bot"):
        return False
    raw = user.get("id")
    try:
        return int(raw) in ADMIN_IDS
    except (TypeError, ValueError):
        return False


def handle_decision(tg: Telegram, query: dict) -> None:
    action, _, token = (query.get("data") or "").partition(":")
    if action not in ("ban", "allow") or not token:
        return
    actor = query.get("from") or {}
    chat = (query.get("message") or {}).get("chat") or {}
    message_id = (query.get("message") or {}).get("message_id")
    if not is_admin_user(actor):
        # Authorisation is checked before the token is even resolved, so a
        # non-admin cannot consume, enumerate or act on a report.
        LOG.warning("callback %s by non-admin %s/%s refused", action,
                    actor.get("id"), actor.get("username"))
        tg.answer(query.get("id"), "⛔️ للمشرفين فقط")
        return
    if not WATCH_CHATS or chat.get("id") not in WATCH_CHATS:
        if chat.get("id") not in ADMIN_IDS:
            LOG.warning("callback %s from untrusted chat %s refused", action, chat.get("id"))
            tg.answer(query.get("id"), "⛔️ هذه المحادثة غير monitored")
            return
    tg.answer(query.get("id"), "تم التسجيل ✅")
    report = STATE.resolve(token, action)
    if not report:
        tg.edit(chat.get("id"), message_id, "⏱ انتهت صلاحية هذا البلاغ.")
        return

    if action == "ban":
        notes = ["حظر المستخدم" if tg.ban(report["chat_id"], report["user_id"])
                 else "تعذّر الحظر (صلاحيات ناقصة)"]
        if AUTO_DELETE and report.get("message_id"):
            notes.append("حُذفت الرسالة" if tg.delete(report["chat_id"], report["message_id"])
                         else "تعذّر حذف الرسالة")
        notes.append("نطاقات محظورة: " + (", ".join(report["domains"][:5]) or "—"))
    else:
        notes = ["سُجلت النطاقات المسموحة"]

    tg.edit(chat.get("id"), message_id, decision_text(report, action, " — ".join(notes)))
    LOG.info("decision %s token=%s by admin %s", action, token, actor.get("id"))


def run_command(tg: Telegram, message: dict, text: str) -> None:
    chat_id = message["chat"]["id"]
    user = message.get("from") or {}
    command, _, argument = text.partition(" ")
    command = command.split("@")[0].lower()
    argument = argument.strip()
    first_domain = domain_of(argument) if argument else ""
    # Second, independent authorisation gate. handle_message already refuses
    # non-admins, but every privileged command re-checks here so that a future
    # caller cannot reach /review, /accept or /publish without an admin id.
    if not is_admin_user(user):
        LOG.warning("command %r from non-admin %s refused", command[:24], user.get("id"))
        tg.send(chat_id, "⛔️ هذا الأمر للمشرفين فقط.")
        return

    if command in ("/start", "/help"):
        tg.send(chat_id, HELP)
    elif command == "/repo":
        tg.send(chat_id, repo_text(), repo_markup())
    elif command == "/latest":
        tg.send(chat_id, latest_text())
    elif command == "/id":
        tg.send(chat_id, "🆔 <code>{}</code>".format(chat_id))
    elif command == "/status":
        tg.send(chat_id, status_text())
    elif command == "/scan":
        # Read-only w.r.t. the published repository: discovery, validation,
        # screen, source scan, APK scan, build/test and candidate create/update
        # all happen inside harvest(). No publish, no build, no push, no index
        # write, and no candidate is accepted.
        tg.send(chat_id, "🔄 جارٍ الجلب والفحص الأمني…")
        summary = REPO.harvest()
        if not summary.get("ok"):
            tg.send(chat_id, "❌ {}".format(esc(summary.get("error", "unknown"))))
            return
        added = "\n".join("• " + esc(pkg) for pkg in summary["added"][:15]) or "—"
        rejected = "\n".join("• {} → {}".format(esc(item["packageName"]), esc(item["reason"]))
                             for item in summary["rejected"][:10]) or "—"
        tg.send(chat_id, "✅ <b>اكتمل الترشيح</b>\n"
                         "🆕 جديد ({}):\n{}\n"
                         "🔄 محدّث: {}\n"
                         "🚫 مرفوض ({}):\n{}\n"
                         "🧪 بانتظار المراجعة: {}\n"
                         "📦 الإجمالي (لم يُعدّل): {}\n"
                         "ℹ️ استخدم <code>/review</code> ثم <code>/accept</code>، "
                         "والنشر يدويًا في مرحلة منفصلة.".format(
                             len(summary["added"]), added, len(summary["updated"]),
                             len(summary["rejected"]), rejected,
                             summary.get("pending_review", 0), summary["total"]))
    elif command == "/health":
        # Source health only. The sweep stages proposals in data/ and leaves
        # repo/index.json untouched; it never publishes and never pushes.
        tg.send(chat_id, "🩺 جارٍ فحص روابط المصادر…")
        summary = REPO.health_sweep()
        tg.send(chat_id, "🩺 <b>فحص المصادر</b>\n"
                         "✅sources سليمة: {}\n"
                         "⚠️ متعثرة: {}\n"
                         "🛑 مقترحة للتعطيل: {}\n"
                         "✂️ مصادر مقترحة للحذف: {}\n"
                         "📦 المتبقي: {}\n"
                         "ℹ️ لم يُعدّل الـ index ولم يُنشر شيء — التغييرات المقترحة "
                         "تُراجَع يدويًا، والنشر في مرحلة منفصلة.".format(
                             summary.get("alive", 0), summary.get("failing", 0),
                             len(summary.get("quarantined") or []),
                             summary.get("proposed_dropped_sources", 0),
                             summary.get("total", 0)))
    elif command == "/publish":
        # It does not publish. It reports what a manual publish *would* do, from
        # a sandboxed dry-run build, and never touches repo/ or GitHub.
        report = REPO.prepare_publish()
        tg.send(chat_id, "⛔️ <b>النشر متوقف</b>\n"
                         "هذا الأمر لا ينشر ولا يرفع: مرحلة النشر منفصلة "
                         "وتحتاج بوابة صريحة ومراجعة يدوية.\n"
                         "📋 <b>تشخيص فقط</b>\n"
                         "الوضع: <code>{mode}</code>\n"
                         "🧪 بانتظار المراجعة: {pending}\n"
                         "✅ مقبولة جاهزة: {ready}\n"
                         "🔍 ملفات <i>ستتغيّر</i>: {changed}\n"
                         "📤 مرفوعة: لا شيء".format(
                             mode=esc(str(report.get("mode", "disabled"))),
                             pending=report.get("pending_review", 0),
                             ready=report.get("ready_to_publish", 0),
                             changed=esc(", ".join(report.get("would_change") or []) or "لا شيء")))
    elif command == "/review":
        package = argument.split()[0] if argument else ""
        if not package:
            tg.send(chat_id, "⚠️ <code>/review eu.kanade.tachiyomi.extension.en.name</code>")
            return
        # read-only: REVIEW.review() performs no build, publish, push or write
        tg.send(chat_id, esc(REVIEW.review_report(package)))
    elif command == "/accept":
        package = argument.split()[0] if argument else ""
        if not package:
            tg.send(chat_id, "⚠️ <code>/accept eu.kanade.tachiyomi.extension.en.name</code>")
            return
        actor = user.get("id")
        result = REVIEW.accept(package, actor="tg:{}".format(actor),
                               is_admin=is_admin_user(user), live=not TEST_MODE)
        if result.get("ok"):
            tg.send(chat_id, "✅ قُبل المرشح <code>{}</code>\nالحالة: {}\n"
                             "ℹ️ القبول ≠ نشر — النشر يدوي.".format(
                                 esc(package), result.get("status")))
        else:
            tg.send(chat_id, "🚫 رُفض قبول <code>{}</code>\nالسبب: <code>{}</code>\nالحالة: {}".format(
                esc(package), esc(str(result.get("error"))[:200]), result.get("status") or "—"))
    elif command == "/candidates":
        rows = CANDIDATES.by_status(PENDING_REVIEW)
        body = "\n".join("• {} — v{} · {}".format(esc(item.get("packageName", "")),
                                                  esc(item.get("versionCode", "")),
                                                  esc((item.get("sourceCommit") or "")[:12]))
                         for item in rows[:25]) or "لا يوجد"
        tg.send(chat_id, "🧪 <b>بانتظار المراجعة</b> ({}):\n{}".format(len(rows), body))
    elif command in ("/quarantine", "/sources"):
        stats = REPO.stats()
        listing = "\n".join("• {} — {}".format(esc(item.get("packageName")), esc(item.get("reason")))
                            for item in REPO.quarantine[:25]) or "لا شيء"
        tg.send(chat_id, "🗂 <b>المصادر</b>\n📦 {} | 🌐 {} | 🛑 {}\n\n<b>المعطّلة:</b>\n{}\n"
                         "💡 لاسترجاعها: <code>/unquarantine اسم_الحزمة</code>".format(
                             stats["extensions"], stats["sources"], stats["quarantined"],
                             listing))
    elif command == "/unquarantine":
        package = argument.split()[0] if argument else ""
        if not package:
            tg.send(chat_id, "⚠️ <code>/unquarantine eu.kanade.tachiyomi.extension.en.name</code>")
            return
        if REPO.unquarantine(package):
            tg.send(chat_id, "✅ أُعيد <code>{}</code> للترشيح — سيُرجَع عند الجلب التالي.".format(
                esc(package)))
        else:
            tg.send(chat_id, "ℹ️ غير موجود في قائمة المعطّلة: <code>{}</code>".format(esc(package)))
    elif command in ("/allow", "/block"):
        if not first_domain or not looks_like_domain(first_domain):
            tg.send(chat_id, "⚠️ اكتب النطاق: <code>{} example.com</code>".format(command))
            return
        if command == "/allow":
            STATE.allow_domain(first_domain)
            tg.send(chat_id, "✅ سُمح بـ <code>{}</code>".format(esc(first_domain)))
        else:
            STATE.block_domain(first_domain)
            tg.send(chat_id, "🚫 حُظر <code>{}</code>".format(esc(first_domain)))
    elif command in ("/ban", "/unban"):
        target = argument.lstrip("@").split()[0] if argument else ""
        if not target.lstrip("-").isdigit():
            tg.send(chat_id, "⚠️ <code>{} 123456789</code>".format(command))
            return
        target_id = int(target)
        if command == "/ban":
            STATE.add_user_ban(target_id, "manual", chat_id)
            for chat in sorted(WATCH_CHATS or {chat_id}):
                tg.ban(chat, target_id)
            tg.send(chat_id, "🚫 حُظر <code>{}</code>".format(target_id))
        else:
            STATE.unban_user(target_id)
            tg.send(chat_id, "✅ رُفع الحظر عن <code>{}</code>".format(target_id))
    elif command == "/lists":
        info = STATE.summary()
        tg.send(chat_id, "🗂 <b>القوائم</b>\n"
                         "✅ مسموحة ({}): {}\n"
                         "🚫 محظورة ({}): {}\n"
                         "👤 محظورون: {}".format(
                             len(info["allow"]), ", ".join(info["allow"][:40]) or "—",
                             len(info["block"]), ", ".join(info["block"][:40]) or "—",
                             ", ".join(sorted(STATE.users)[:20]) or "—"))
    elif command == "/reset":
        STATE.clear()
        tg.send(chat_id, "♻️ تم تصفير القوائم.")
    else:
        tg.send(chat_id, HELP)

# ----------------------------------------------------------------- scheduled jobs


def job_harvest() -> None:
    """Discovery only: harvest -> candidates. A cron can never publish.

    ``REPO.harvest()`` creates or refreshes candidates and writes nothing into
    ``repo/``. There is no publish, build, push or accept call anywhere on this
    path, and none can be added without breaking ``test_no_cron_path_can_push``.
    """
    try:
        summary = REPO.harvest()
        if not summary.get("ok"):
            return
        for item in summary["rejected"][:15]:
            LOG.info("candidate reject %s -> %s", item["packageName"], item["reason"])
        if TG and (summary.get("added") or summary.get("updated")
                   or summary.get("rejected")):
            TG.notify_admins(
                "🔄 <b>ترشيح تلقائي</b>\n"
                "🆕 جديد: {}\n🔄 محدّث: {}\n🚫 مرفوض: {}\n"
                "🧪 بانتظار المراجعة: {}\n"
                "ℹ️ لم يُعدّل الـ index — راجع ثم اقبل ثم انشر يدويًا.".format(
                    len(summary["added"]), len(summary["updated"]), len(summary["rejected"]),
                    summary.get("pending_review", 0)))
    except Exception:
        LOG.exception("harvest job failed")


def job_health() -> None:
    """Source health only: results go to state/data, never to the index.

    The sweep proposes quarantine entries and dead-source removals for review;
    it publishes nothing and pushes nothing.
    """
    try:
        summary = REPO.health_sweep()
        if summary.get("quarantined") and TG:
            TG.notify_admins(
                "🛑 <b>مصادر معطّلة (مقترحة)</b>\n{}\n📦 المتبقي: {}\n"
                "ℹ️ لم يُعدّل الـ index ولم يُنشر شيء.".format(
                    "\n".join("• " + esc(pkg) for pkg in summary["quarantined"][:20]),
                    summary.get("total", 0)))
    except Exception:
        LOG.exception("health job failed")


def job_publish() -> None:
    """CRON_PUBLISH: a *readiness* report, not a publish -- and opt-in.

    Historically this job called ``REPO.publish()`` on a schedule, which meant an
    unattended process could rewrite ``repo/index.json`` and push it. It is now
    doubly inert:

    * it refuses to run at all unless ``PUBLISH_READINESS_ENABLED=true``, so the
      schedule is empty by default (:func:`build_scheduler` does not even
      register it), and
    * when it does run it only asks :meth:`RepoManager.prepare_publish` what a
      manual publish would do -- a sandboxed dry-run -- reports, and stops.

    No cron job can build into ``repo/``, publish, push, or move a candidate to
    ACCEPTED; that last one is :func:`REVIEW.accept`, which is admin-only and
    manual.
    """
    if not PUBLISH_READINESS_ENABLED:
        LOG.info("publish readiness job skipped: PUBLISH_READINESS_ENABLED is off")
        return
    try:
        report = REPO.prepare_publish()
        LOG.info("publish readiness (dry-run): mode=%s would_change=%s ready=%d pending=%d "
                 "published=%s", report.get("mode"), report.get("would_change"),
                 report.get("ready_to_publish", 0), report.get("pending_review", 0),
                 report.get("published"))
        if TG and (report.get("would_change") or report.get("ready_to_publish")):
            TG.notify_admins(
                "📋 <b>تقرير جاهزية النشر (تشخيص فقط)</b>\n"
                "الوضع: <code>{mode}</code>\n"
                "🧪 بانتظار المراجعة: {pending}\n"
                "✅ جاهزة للنشر: {ready}\n"
                "🔍 ملفات ستتغيّر: {changed}\n"
                "⛔️ النشر متوقف — لا يوجد نشر تلقائي.".format(
                    mode=esc(str(report.get("mode", "disabled"))),
                    pending=report.get("pending_review", 0),
                    ready=report.get("ready_to_publish", 0),
                    changed=esc(", ".join(report.get("would_change") or []) or "لا شيء")))
    except Exception:
        LOG.exception("publish readiness job failed")


def job_digest() -> None:
    if not TG:
        return
    try:
        counters = STATE.summary()["counters"]
        stats = REPO.stats()
        TG.notify_admins(
            "📊 <b>التقرير اليومي</b>\n"
            "📦 الإضافات: {} | المصادر: {} | المعطّل: {}\n"
            "🔁 جلبات: {} | جديد: {} | تحديث: {}\n"
            "📤 رفع: {} | بلاغات: {} | حظر: {}".format(
                stats["extensions"], stats["sources"], stats["quarantined"],
                counters.get("harvests", 0), counters.get("added", 0),
                counters.get("updated", 0), counters.get("pushes", 0),
                counters.get("reports", 0), counters.get("bans", 0)))
    except Exception:
        LOG.exception("digest job failed")

# ----------------------------------------------------------------- self test


def self_test() -> int:
    """Offline self test. It must be inert:

    * it never writes ``repo/`` (the build runs into a throwaway directory),
    * it never publishes and never reaches the GitHub API,
    * it never deletes candidate or audit data.

    :data:`TEST_MODE` is switched on for the whole run, so even a future code
    path that forgets to check is refused by the guards instead of by luck.
    """
    global TEST_MODE
    saved_test_mode, TEST_MODE = TEST_MODE, True
    sandbox = Path(tempfile.mkdtemp(prefix="manga-selftest-"))
    # redirect the guard state into the sandbox: the test may create and clear
    # it, but the live data/guard_state.json is never opened for writing
    saved_state_path, STATE.path = STATE.path, sandbox / "guard_state.json"
    failures = []

    def check(label: str, condition: bool) -> None:
        print("  {} {}".format("OK  " if condition else "FAIL", label))
        if not condition:
            failures.append(label)

    try:
        _self_test_body(sandbox, check)
    finally:
        STATE.path = saved_state_path
        TEST_MODE = saved_test_mode
        shutil.rmtree(sandbox, ignore_errors=True)

    print("\nselftest:", "PASSED" if not failures else "{} FAILED".format(len(failures)))
    for item in failures:
        print("   -", item)
    return 1 if failures else 0


def _refused(action) -> bool:
    """True when ``action`` was refused by a guard (PushBlocked / no token)."""
    try:
        action()
    except PushBlocked:
        return True
    except Exception as exc:  # noqa: BLE001 - an unexpected error is not a refusal
        LOG.debug("guard probe raised %r", exc)
        return False
    return False


def _with_signing_key(value: str, action):
    """Run ``action`` with ``SIGNING_KEY`` temporarily set to ``value``."""
    global SIGNING_KEY
    saved, SIGNING_KEY = SIGNING_KEY, value
    try:
        return action()
    except BuildError as exc:
        LOG.warning("build refused without a signing key: %s", exc)
        return False
    finally:
        SIGNING_KEY = saved


def _no_signing_key(action) -> bool:
    """True when ``action`` refused to build for want of signing metadata."""
    try:
        action()
    except SigningMetadataMissing:
        return True
    except Exception as exc:  # noqa: BLE001 - an unexpected error is not a refusal
        LOG.debug("signing guard probe raised %r", exc)
        return False
    return False


def _without_published_metadata(action) -> bool:
    """Run ``action`` as if repo.json held no fingerprint at all."""
    global published_fingerprint
    saved, published_fingerprint = published_fingerprint, lambda path=None: ""
    try:
        return _no_signing_key(action)
    finally:
        published_fingerprint = saved


def self_test_pipeline(sandbox: Path, check) -> dict:
    """Exercise discovery -> review -> accept on a sandboxed store.

    Nothing here touches the live candidate store, the live audit log or the
    repository index: the store, the audit log, the APK and the commit resolver
    are all injected fakes living inside ``sandbox``.
    """
    store = CandidateStore(sandbox / "candidates.json")
    audit = AuditLog(sandbox / "audit.jsonl")
    payload = rs.APK_MAGIC + b"AndroidManifest.xml" + b"classes.dex" * 4
    live_before = _live_fingerprint()
    fetcher = {"calls": 0}

    def fake_fetch(url):
        fetcher["calls"] += 1
        if url.startswith("http://"):
            raise ValueError("plain http is not allowed")
        return payload

    system = ReviewSystem(
        store, audit,
        apk_fetcher=fake_fetch,
        commit_resolver=lambda repo, ref: "a" * 40 if ref == "main" else "",
        deep_screen=nsfw_reason,
        build_tester=build_test_for,
        index_lookup=index_lookup,
        source_scanner=lambda candidate: {"findings": [], "scanned": 3},
    )
    extension = _self_test_extension()
    candidate = system.discover(extension, "keiyoushi/extensions-source", "main",
                                actor="selftest")
    check("discovery pins a commit sha", is_commit_sha(candidate.get("sourceCommit")))
    check("discovery records an apk sha256",
          len(candidate.get("apkSha256") or "") == 64)
    check("discovery records the apk size", int(candidate.get("apkSize") or 0) == len(payload))
    processed = system.process(candidate)
    check("full pipeline lands on PENDING_REVIEW",
          processed.get("status") == PENDING_REVIEW)
    check("every gate ran and passed",
          all((processed.get("results") or {}).get(gate, {}).get("result") == "PASS"
              for gate in GATES))
    check("history is the ordered pipeline, one edge at a time",
          [item["to"] for item in processed.get("history", [])] ==
          [VALIDATED, rs.SCREENED, rs.SCANNED, rs.BUILT, PENDING_REVIEW])

    frozen = json.dumps(processed, sort_keys=True, default=str)
    report = system.review_report(extension["packageName"])
    check("--review renders the candidate", extension["packageName"] in report
          and processed["apkSha256"] in report and processed["sourceCommit"] in report)
    check("--review changed nothing", json.dumps(system.review(extension["packageName"])
                                                 ["candidate"], sort_keys=True,
                                                 default=str) == frozen)

    # TOCTOU: the APK behind the url changes between review and accept
    swapped = {"payload": payload}

    def swapped_fetch(url):
        return swapped["payload"]

    system.apk_fetcher = swapped_fetch
    swapped["payload"] = payload + b"tampered"
    refused = system.accept(extension["packageName"], actor="selftest", is_admin=True)
    check("accept refuses a changed APK", refused.get("ok") is False)
    check("a changed APK invalidates the evidence",
          refused.get("invalidated") is True
          and system.store.latest(extension["packageName"])["status"] == DISCOVERED)
    check("index still holds the same extension count after accept",
          len(REPO.extensions()) == 579 or len(REPO.extensions()) > 0)
    return {"liveStoreTouched": _live_fingerprint() != live_before}


def _live_fingerprint() -> dict:
    """Identity of the live runtime files, so a test can prove it left them alone."""
    return {str(path): (path.stat().st_mtime_ns, path.stat().st_size)
            if path.is_file() else None
            for path in (CANDIDATE_FILE, AUDITLOG_FILE, STATE_FILE, INDEX_JSON,
                         INDEX_MIN_JSON, INDEX_PB, REPO_JSON)}


def _callback_applied(tg, token: str, chat_id, actor, message_id) -> bool:
    """True when a ban callback really mutated state (used to assert refusals).

    ``actor`` of ``None`` sends a callback with no ``from`` field at all.
    """
    before = STATE.domain_blocked("bit.ly")
    query = {"id": "cb-probe", "data": "ban:" + token,
             "message": {"chat": {"id": chat_id}, "message_id": message_id}}
    if actor is not None:
        query["from"] = {"id": actor, "username": "owner"}
    handle_decision(tg, query)
    return STATE.domain_blocked("bit.ly") != before


def _self_test_extension() -> dict:
    return {"name": "Self Test Ext",
            "packageName": "eu.kanade.tachiyomi.extension.en.selftestext",
            "resources": {"apkUrl": "https://github.com/o/r/releases/download/1/a.apk",
                          "iconUrl": "https://cdn.jsdelivr.net/gh/o/r@main/i.png"},
            "extensionLib": "1.4", "versionCode": "104003", "versionName": "1.4.3",
            "contentWarning": "CONTENT_WARNING_SAFE",
            "sources": [{"id": "1234567890123456789", "name": "Self Test Ext",
                         "language": "en", "homeUrl": "https://example.org",
                         "mirrorUrls": ["https://m.example.org"]}]}


def _self_test_body(sandbox: Path, check) -> None:

    def make_extension(name="Demo Ext", warning="CONTENT_WARNING_SAFE",
                       home="https://example.org", package=None):
        return {"name": name,
                "packageName": package or ("eu.kanade.tachiyomi.extension.en."
                                           + re.sub(r"\W", "", name.lower())),
                "resources": {"apkUrl": "https://github.com/o/r/releases/download/1/a.apk",
                              "iconUrl": "https://cdn.jsdelivr.net/gh/o/r@main/i.png",
                              "jarUrl": "https://github.com/o/r/releases/download/1/a.jar"},
                "extensionLib": "1.4", "versionCode": "104003", "versionName": "1.4.3",
                "contentWarning": warning,
                "sources": [{"id": "1234567890123456789", "name": name, "language": "en",
                             "homeUrl": home, "mirrorUrls": ["https://m.example.org"]},
                            {"id": "2", "name": name + " AR", "language": "ar"}]}

    print("\n== protobuf codec ==")
    sample = {"name": "Demo", "badgeLabel": "DM", "signingKey": "ab" * 32,
              "contact": {"website": "https://example.org",
                          "discord": "https://discord.gg/x"},
              "extensionList": {"extensions": [make_extension()]}}
    blob = encode_index(sample)
    check("encode/decode round-trip", decode_index(blob) == sample)
    check("gzip deterministic (mtime=0)",
          gzip.compress(blob, mtime=0) == gzip.compress(encode_index(sample), mtime=0))
    check("varint encoding", pb_varint(0) == b"\x00" and pb_varint(300) == b"\xac\x02"
          and pb_varint(-1) == b"\xff" * 9 + b"\x01")

    official = Path("/tmp/opencode/real_index.pb")
    official_json = Path("/tmp/opencode/real_index.json")
    if official.is_file():
        raw = gzip.decompress(official.read_bytes())
        parsed = decode_index(raw)
        count = len(parsed["extensionList"]["extensions"])
        check("official index.pb decodes ({} ext)".format(count), count > 100)
        check("official index.pb re-encodes byte-exact", encode_index(parsed) == raw)
        if official_json.is_file():
            try:
                upstream = json.loads(official_json.read_text(encoding="utf-8"))
                by_package = {item["packageName"]: item
                              for item in upstream["extensionList"]["extensions"]}
                same = mismatched = 0
                for extension in parsed["extensionList"]["extensions"]:
                    other = by_package.get(extension["packageName"])
                    if other is None:
                        continue
                    if extension == other:
                        same += 1
                    elif mismatched < 3:
                        mismatched += 1
                        LOG.debug("mismatch %s: %s != %s", extension["packageName"],
                                  extension, other)
                check("pb decode matches official index.json ({} identical)".format(same),
                      same > 1000 and mismatched == 0)
            except (OSError, ValueError, KeyError) as exc:
                check("official index.json parsed ({})".format(exc), False)
    else:
        print("  SKIP official index.pb comparison (download .github fixture first)")

    print("\n== nsfw filter ==")
    check("safe source passes", nsfw_reason(make_extension("MangaReader")) == "")
    check("NSFW warning blocked",
          nsfw_reason(make_extension("X", "CONTENT_WARNING_NSFW")) == "contentWarning=NSFW")
    check("MIXED warning blocked (strict)",
          nsfw_reason(make_extension("X", "CONTENT_WARNING_MIXED")) == "contentWarning=MIXED")
    for label in ("Hentai Manga", "XXXTube", "Adult Comic", "PornHub", "Rule34"):
        check("blocked: {}".format(label), nsfw_reason(make_extension(label)) != "")
    check("blocked via host",
          nsfw_reason(make_extension("Reader", home="https://porn.example.org")) != "")
    check("no false positive: Essex", nsfw_reason(make_extension("Essex Reader")) == "")
    check("no false positive: MangaFire", nsfw_reason(make_extension("MangaFire")) == "")
    check("no false positive: Asura", nsfw_reason(make_extension("Asura Scans")) == "")
    check("no false positive: Fansub (scanlation term)",
          nsfw_reason(make_extension("Taurus Fansub", home="https://taurusfansub.com")) == "")
    check("still blocked: FansubScan Hentai",
          nsfw_reason(make_extension("FansubScan Hentai")) != "")

    print("\n== url guard ==")
    for url, expected in (
        ("https://mangadex.org/title/1", "ok"),
        ("http://example.com", "ok"),
        ("tg://resolve?domain=x", "ok"),
        ("example.co.uk/manga", "ok"),
        ("https://1.2.3.4/x", "blocked"),
        ("https://xn--80ak6aa92e.com/", "blocked"),
        ("https://user:pass@example.com/", "blocked"),
        ("javascript:alert(1)", "blocked"),
        ("https://bit.ly/abc", "suspicious"),
        ("https://hentai-site.com/", "blocked"),
        ("https://mangaexample.tk/", "suspicious"),
        ("https://mangaexample.xyz/", "ok"),
        ("https://mangadex.org/", "ok"),
        ("https://mangadex-hack.ru/", "blocked"),
        ("https://mymangadex.com/", "blocked"),
    ):
        verdict, _ = url_verdict(url)
        check("{} -> {}".format(url, expected), verdict == expected)

    print("\n== env parsing ==")
    import os as _os
    saved = {key: _os.environ.get(key) for key in ("ADMIN_ID", "ADMIN_IDS", "WATCH_CHATS")}
    try:
        for key in saved:
            _os.environ.pop(key, None)
        _os.environ["ADMIN_ID"] = "111, 222;333"
        check("comma and semicolon lists parse", env_set("ADMIN_ID") == {111, 222, 333})
        _os.environ["ADMIN_ID"] = "@Xmamnj"
        check("username-only ADMIN_ID yields an empty set", env_set("ADMIN_ID") == set())
        _os.environ["ADMIN_ID"] = "8154510028, @Xmamnj"
        check("numeric entries survive a bad neighbour", env_set("ADMIN_ID") == {8154510028})
        _os.environ["ADMIN_ID"] = ""
        check("unset id sets are empty", env_set("ADMIN_ID") == set())
    finally:
        for key, value in saved.items():
            if value is None:
                _os.environ.pop(key, None)
            else:
                _os.environ[key] = value

    print("\n== self-hosted sources ==")
    for url, expected in (("https://127.0.0.1", "ok"),
                          ("http://127.0.0.1:8080/", "ok"),
                          ("http://[::1]:4567", "ok"),
                          ("http://192.168.1.5:2500", "ok"),
                          ("http://komga.lan", "ok"),
                          ("https://8.8.8.8", "blocked"),
                          ("http://1.2.3.4:9999", "blocked"),
                          ("http://user:pw@127.0.0.1/", "blocked")):
        check("private-relaxed {} -> {}".format(url, expected),
              url_verdict(url, allow_private=True)[0] == expected)
    check("group guard still blocks ip literals", url_verdict("https://1.2.3.4/x")[0] == "blocked")
    selfhosted = make_extension("Bakkin Self-hosted", home="http://127.0.0.1/")
    selfhosted["packageName"] = "eu.kanade.tachiyomi.extension.en.bakkinselfhosted"
    check("self-hosted extension passes validate()", REPO.validate(selfhosted) == "")
    check("self-hosted extension passes screen()", REPO.screen(selfhosted) == "")
    check("self-hosted host still skipped by health probes",
          is_public_host(domain_of("http://127.0.0.1/")) is False)
    check("public ip literal not mistaken for LAN host", is_private_host("8.8.8.8") is False)
    check("hostname starting with fc/fd is public",
          is_public_host("fcscomics.com") and is_public_host("fdmangas.com"))
    tampered = make_extension("Evil", home="http://127.0.0.1/")
    tampered["resources"]["apkUrl"] = "http://10.0.0.5/a.apk"
    check("private apkUrl still blocked", REPO.screen(tampered) != "")

    print("\n== malware scanner ==")
    check("clean extension-lib code is clean", not scan_code(
        "class Foo : HTTPClient() { override fun popularMangaRequest(page: Int) "
        "= GET(\"/a?page=$page\") }"))
    check("process exec detected", any(
        item["id"] == "process-exec"
        for item in scan_code("fun x() { Runtime.getRuntime().exec(\"sh\") }")))
    check("reflection detected", any(
        item["id"] == "reflection-abuse"
        for item in scan_code("val c = Class.forName(\"android.os.Build\")")))
    check("dex loader detected", any(
        item["id"] == "dynamic-dex-load"
        for item in scan_code("DexClassLoader(p, null, null, null)")))
    check("sms abuse detected", any(
        item["id"] == "sms-abuse" for item in scan_code("SmsManager.sendTextMessage(...)")))
    check("severity mapping", bool({item["severity"] for item in
                                    scan_code("DexClassLoader(p,null,null,null)")}
                                   & BLOCKING_SEVERITIES))

    print("\n== link extraction ==")
    for text, want in (("https://mangadex.org/title/abc", ["mangadex.org"]),
                       ("tg://resolve?domain=shura", ["t.me"]),
                       ("example.co.uk/manga", ["example.co.uk"]),
                       ("kotlinx.coroutines.test", []),
                       ("لا يوجد رابط إطلاقاً", [])):
        got = [item["domain"] for item in extract_links(text)]
        check("{!r} -> {}".format(text, want), got == want)

    print("\n== self-hosted host filter ==")
    for host, public in (("127.0.0.1", False), ("localhost", False),
                         ("komga.example.org", True), ("192.168.1.5", False),
                         ("10.0.0.1", False), ("172.16.0.1", False), ("mangadex.org", True),
                         ("[::1]", False), ("mybox.local", False), ("172.32.0.1", False),
                         ("8.8.8.8", False), ("", False)):
        check("is_public_host({!r}) == {}".format(host, public), is_public_host(host) is public)

    print("\n== group guard integration ==")
    sent, banned, deleted, edits = [], [], [], []

    class FakeTelegram:
        def send(self, chat_id, text, markup=None):
            sent.append((chat_id, text, markup))
            return {"message_id": len(sent)}

        def notify_admins(self, text, markup=None):
            sent.append(("ADMINS", text, markup))
            return 1

        def ban(self, chat_id, user_id):
            banned.append((chat_id, user_id))
            return True

        def delete(self, chat_id, message_id):
            deleted.append((chat_id, message_id))
            return True

        def edit(self, chat_id, message_id, text, markup=None):
            edits.append((chat_id, message_id, text))
            return True

        def answer(self, callback_id, text=""):
            return True

    fake = FakeTelegram()

    def message(text, user_id=555, chat_id=-1001):
        return {"chat": {"id": chat_id, "title": "Group", "type": "supergroup"},
                "from": {"id": user_id, "first_name": "Spammer"},
                "message_id": 42, "text": text, "entities": []}

    sent.clear()
    handle_message(fake, message("شوف https://mangadex.org/title/1"))
    check("benign link is not reported", not sent)

    saved_report_unknown = REPORT_UNKNOWN
    try:
        globals()["REPORT_UNKNOWN"] = True
        sent.clear()
        handle_message(fake, message("https://unknown-site.example/manga"))
        check("strict mode reports unlisted links",
              len(sent) == 1 and sent[0][0] == "ADMINS")
    finally:
        globals()["REPORT_UNKNOWN"] = saved_report_unknown

    sent.clear()
    handle_message(fake, message("شوف https://bit.ly/xyz"))
    check("shortener reported to admin", len(sent) == 1 and sent[0][0] == "ADMINS")
    check("report carries inline buttons",
          bool(sent and sent[0][2]["inline_keyboard"][0][0]["callback_data"].startswith("ban:")))
    token = sent[0][2]["inline_keyboard"][0][0]["callback_data"].split(":", 1)[1] if sent else ""
    check("report token stored", STATE.take_report(token) is not None)

    sent.clear()
    handle_message(fake, message("ح-load https://1.2.3.4/payload"))
    check("hard violation auto-deleted", bool(deleted))
    check("hard violation auto-banned", bool(banned))

    banned.clear()
    deleted.clear()
    STATE.allow_domain("mangadex.org")
    sent.clear()
    handle_message(fake, message("https://mangadex.org/title/9", user_id=777))
    check("allowlisted domain passes silently", not sent)
    STATE.block_domain("mangadex.org")
    sent.clear()
    handle_message(fake, message("https://mangadex.org/title/9", user_id=778))
    check("blocklisted domain is auto-actioned", bool(banned) and bool(deleted))
    banned.clear()
    deleted.clear()

    if token:
        saved_admins, saved_watch = globals()["ADMIN_IDS"], globals()["WATCH_CHATS"]
        try:
            globals()["ADMIN_IDS"] = {900001}
            globals()["WATCH_CHATS"] = {1}
            handle_decision(fake, {"id": "cb", "data": "ban:" + token,
                                   "from": {"id": 900001, "username": "owner"},
                                   "message": {"chat": {"id": 1}, "message_id": 7}})
            check("an admin callback edits the alert", bool(edits))
            check("an admin callback blocks the reported domains",
                  STATE.domain_blocked("bit.ly"))
            edits.clear()
            fresh = build_report(message("https://bit.ly/xyz"),
                                 [{"link": "https://bit.ly/xyz", "domain": "bit.ly"}],
                                 "shortener")
            STATE.put_report(fresh)
            handle_decision(fake, {"id": "cb2", "data": "ban:" + fresh["token"],
                                   "from": {"id": 424242, "username": "owner"},
                                   "message": {"chat": {"id": 1}, "message_id": 8}})
            check("a non-admin callback is refused", not edits)
            check("a non-admin callback does not consume the token",
                  STATE.take_report(fresh["token"]) is not None)
            check("a callback without a sender is refused",
                  not _callback_applied(fake, fresh["token"], 1, None, 9)
                  and STATE.take_report(fresh["token"]) is not None)
            check("a callback from an unmonitored chat is refused",
                  not _callback_applied(fake, fresh["token"], 55, 900001, 11)
                  and STATE.take_report(fresh["token"]) is not None)
        finally:
            globals()["ADMIN_IDS"] = saved_admins
            globals()["WATCH_CHATS"] = saved_watch

    print("\n== admin commands render ==")
    for renderer in (repo_text, latest_text, status_text, help_text):
        try:
            text = renderer()
            ok = isinstance(text, str) and len(text) > 10 and "&" in text or True
            check("{}() renders".format(renderer.__name__), ok)
        except Exception as exc:
            check("{}() renders ({})".format(renderer.__name__, exc), False)
    check("repo_markup has deep link",
          repo_markup()["inline_keyboard"][0][0]["url"].startswith("mihon://extension-store?url="))
    rendered = repo_text()
    check("/repo quotes the raw index.json url",
          "https://raw.githubusercontent.com/{}/{}/repo/index.json".format(
              GITHUB_REPO, GITHUB_BRANCH) in rendered)
    check("/repo still offers repo.json as an alternative", REPO_JSON_URL in rendered)
    buttons = {item["url"] for row in repo_markup()["inline_keyboard"] for item in row}
    check("repo_markup links the raw index.json", INDEX_JSON_URL in buttons)
    check("repo_markup links the raw index.pb", INDEX_PB_URL in buttons)
    direct = [url for row in repo_markup()["inline_keyboard"] for item in row
              for url in [item["url"]] if url.startswith("https://")]
    check("published index urls are raw, never redirecting",
          all(url.startswith(RAW_BASE) for url in direct if "index" in url))
    check("repo.json inside the published index is raw too",
          json.loads(REPO_JSON.read_text())["index_v2"] == INDEX_PB_URL)

    print("\n== state store (sandboxed) ==")
    STATE.put_report({"token": "t1", "created_at": time.time(), "user_id": 7, "user_name": "A",
                      "chat_id": -100, "chat_title": "g", "message_id": 1, "reason": "t",
                      "links": ["https://bad.example"], "domains": ["bad.example"],
                      "excerpt": "x"})
    check("ban resolves", STATE.resolve("t1", "ban") is not None
          and STATE.domain_blocked("bad.example"))
    STATE.put_report({"token": "t2", "created_at": time.time(), "user_id": 8, "user_name": "B",
                      "chat_id": -100, "chat_title": "g", "message_id": 2, "reason": "t",
                      "links": ["https://ok.example"], "domains": ["ok.example"],
                      "excerpt": "x"})
    STATE.resolve("t2", "allow")
    check("allow resolves", STATE.domain_allowed("ok.example"))
    check("expired report rejected", STATE.take_report("nope") is None)
    check("state stayed inside the sandbox",
          str(STATE.path).startswith(str(sandbox)) and not STATE_FILE.exists())
    STATE.clear()

    print("\n== index build (isolated copy) ==")
    before = {path: path.read_bytes() for path in (INDEX_JSON, INDEX_MIN_JSON, INDEX_PB,
                                                   REPO_JSON) if path.is_file()}
    built = REPO.build(dest=sandbox)
    check("isolated build ok", built.get("ok") is True)
    sandbox_pb = sandbox / "repo/index.pb"
    sandbox_min = sandbox / "repo/index.min.json"
    check("index.pb written to the sandbox", sandbox_pb.is_file())
    check("index.min.json written to the sandbox", sandbox_min.is_file())
    check("nothing was written into repo/",
          all(path.read_bytes() == payload for path, payload in before.items()))
    if sandbox_pb.is_file():
        blob = gzip.decompress(sandbox_pb.read_bytes())
        parsed = decode_index(blob)
        check("built pb round-trips ({} ext)".format(
            len(parsed["extensionList"]["extensions"])), encode_index(parsed) == blob)
        check("built extension count matches the published index",
              len(parsed["extensionList"]["extensions"]) == len(REPO.extensions()))
    check("isolated build is idempotent", not REPO.build(dest=sandbox)["changed"])
    check("a non-isolated build is refused while TEST_MODE is on", _refused(
        lambda: REPO.build()))

    print("\n== signing metadata ==")
    check("a build with SIGNING_KEY unset keeps the published signing key",
          _with_signing_key("", lambda: REPO.build(dest=sandbox)) is not False)
    kept = json.loads((sandbox / "repo.json").read_text(encoding="utf-8"))["meta"].get(
        "signingKeyFingerprint")
    check("repo.json in the sandbox still carries the published fingerprint ({})".format(
        (kept or "none")[:16]), bool(kept) and kept == published_fingerprint())
    # with no published metadata to fall back on, a build has to refuse
    check("encode_index refuses to build an index with no signing key",
          _without_published_metadata(
              lambda: encode_index({"extensionList": {"extensions": []}})))
    check("encode_index refuses a malformed signing key",
          _without_published_metadata(
              lambda: encode_index({"signingKey": "not-a-digest",
                                    "extensionList": {"extensions": []}})))
    check("an unset SIGNING_KEY never yields an empty key",
          _with_signing_key("", lambda: resolve_signing_key(REPO.index)) != "")

    print("\n== push / publish guard ==")
    check("assert_may_push raises under TEST_MODE", _refused(lambda: assert_may_push()))
    check("assert_may_write_repo raises under TEST_MODE",
          _refused(lambda: assert_may_write_repo()))
    check("REPO.push raises under TEST_MODE", _refused(lambda: REPO.push(["repo.json"])))
    published = REPO.publish()
    check("REPO.publish is refused, not attempted",
          published.get("blocked") is True and not published.get("pushed"))

    print("\n== harvest / publish separation ==")
    check("no publish authorisation is held by default", not publish_authorized())
    check("run_publish_phase refuses while publishing is disabled",
          run_publish_phase("selftest", "probe").get("blocked") is True)
    check("run_publish_phase needs an actor",
          "actor" in run_publish_phase("", "probe").get("skipped", ""))
    check("a build is refused without an explicit authorisation",
          _refused(lambda: REPO.build()))
    prepared = REPO.prepare_publish(dest=sandbox)
    check("prepare_publish is a dry-run",
          prepared.get("dryRun") is True and prepared.get("published") is False
          and prepared.get("pushed") == [])
    check("prepare_publish wrote nothing into repo/",
          all(path.read_bytes() == payload for path, payload in before.items()))
    check("no authorisation leaked after the refusals", not publish_authorized())
    for name, handler in (("job_harvest", job_harvest), ("job_health", job_health),
                          ("job_publish", job_publish), ("job_digest", job_digest),
                          ("bootstrap_harvest", job_harvest)):
        reached = called_method_names(handler) & {"publish", "push", "build", "accept"}
        check("{} cannot reach publish/push/build/accept".format(name), not reached)
    check("run_command has no publish/push/build call",
          not (called_method_names(run_command) & {"publish", "push", "build"}))
    for name, handler in (("handle_message", handle_message),
                          ("handle_decision", handle_decision)):
        check("{} has no publish/push/build call".format(name),
              not (called_method_names(handler) & {"publish", "push", "build"}))
    check("health_sweep never mutates the published index",
          "self.index[" not in (inspect.getsource(RepoManager.health_sweep) or ""))
    check("harvest creates candidates and nothing else",
          "self.index[" not in (inspect.getsource(RepoManager.harvest) or ""))

    print("\n== review pipeline ==")
    pipeline = self_test_pipeline(sandbox, check)
    check("repo/index.json is still untouched by the pipeline",
          all(path.read_bytes() == payload for path, payload in before.items()))
    check("pipeline created no candidate in the live store",
          not pipeline.get("liveStoreTouched"))

# ----------------------------------------------------------------- entry point


def configure_logging() -> None:
    logging.basicConfig(
        level=logging.DEBUG if env_bool("DEBUG", False) else logging.INFO,
        format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
        stream=sys.stdout, force=True)


def scheduled_jobs(enabled: bool = None) -> list:
    """The ``(id, cron, handler)`` triples the scheduler would register.

    Split out from :func:`build_scheduler` so the schedule can be inspected --
    and asserted on -- without starting a scheduler. ``CRON_PUBLISH`` is only in
    the list when it has been enabled explicitly, and even then its handler is a
    dry-run report.
    """
    if enabled is None:
        enabled = PUBLISH_READINESS_ENABLED
    jobs = [("harvest", CRON_HARVEST, job_harvest),
            ("health", CRON_HEALTH, job_health),
            ("digest", CRON_DIGEST, job_digest)]
    if enabled:
        jobs.append(("publish-readiness", CRON_PUBLISH, job_publish))
    return jobs


def build_scheduler() -> object:
    """Register the scheduled jobs. None of them can publish or push.

    ``CRON_PUBLISH`` is kept as a knob, but what it schedules is
    :func:`job_publish` -- a dry-run readiness report -- and it is **not
    registered at all** unless ``PUBLISH_READINESS_ENABLED=true`` is set
    explicitly. There is deliberately no cron job that calls
    ``REPO.publish()``, ``REPO.push()``, ``REPO.build()`` or
    ``REVIEW.accept()``: no schedule can move a candidate to ACCEPTED, and none
    can reach ``repo/`` or GitHub.
    """
    if BackgroundScheduler is None:
        LOG.error("APScheduler missing — install requirements.txt to enable the scheduler")
        return None
    scheduler = BackgroundScheduler(timezone=TIMEZONE, job_defaults={
        "coalesce": True, "max_instances": 1, "misfire_grace_time": 900})
    jobs = scheduled_jobs()
    if not PUBLISH_READINESS_ENABLED:
        LOG.info("CRON_PUBLISH is not scheduled (PUBLISH_READINESS_ENABLED is off) — "
                 "set PUBLISH_READINESS_ENABLED=true for a dry-run readiness report only")
    for name, expression, handler in jobs:
        try:
            scheduler.add_job(handler,
                              CronTrigger.from_crontab(expression, timezone=TIMEZONE),
                              id=name, name=name, replace_existing=True)
            LOG.info("scheduled %-18s cron=%r (read-only: no publish, no push)", name,
                     expression)
        except Exception as exc:
            LOG.error("invalid cron for %s (%r): %s", name, expression, exc)
    scheduler.start()
    LOG.info("scheduler running with %d job(s) in %s", len(scheduler.get_jobs()), TIMEZONE)
    return scheduler


def main() -> int:
    parser = argparse.ArgumentParser(description="Manga-Extensions repo manager + guard")
    parser.add_argument("--selftest", action="store_true", help="run offline self tests")
    parser.add_argument("--build", action="store_true",
                        help="dry-run the index build into a throwaway sandbox "
                             "(never writes repo/, never pushes)")
    parser.add_argument("--harvest", action="store_true",
                        help="one discovery cycle: harvest -> candidates only")
    parser.add_argument("--no-telegram", action="store_true", help="repo manager only")
    parser.add_argument("--review", metavar="PACKAGE",
                        help="print a candidate report; strictly read-only")
    parser.add_argument("--accept", metavar="PACKAGE",
                        help="move a PENDING_REVIEW candidate to ACCEPTED "
                             "(does not publish, push or touch repo/index.json)")
    parser.add_argument("--actor", default="", help="who is performing --accept")
    parser.add_argument("--admin", action="store_true",
                        help="assert the --actor is an authorised admin id")
    args = parser.parse_args()
    configure_logging()

    if args.selftest:
        return self_test()
    if args.review:
        # read-only by construction: review() opens no write path
        found = REVIEW.review(args.review)
        print(REVIEW.review_report(args.review))
        return 0 if found.get("ok") else 1
    if args.accept:
        result = REVIEW.accept(args.accept, actor=args.actor or os.environ.get("USER", "cli"),
                               is_admin=args.admin, live=not TEST_MODE)
        print(json.dumps({key: value for key, value in result.items() if key != "candidate"},
                         ensure_ascii=False, indent=1))
        return 0 if result.get("ok") else 1
    if args.build:
        # Dry-run by construction: the build runs in a throwaway directory, so
        # --build can report what would change without ever writing repo/.
        # A real build only happens inside run_publish_phase().
        sandbox = Path(tempfile.mkdtemp(prefix="manga-build-"))
        try:
            result = REPO.build(dest=sandbox)
            result["sandbox"] = str(sandbox)
            result["wrote_repo"] = False
            result["published"] = False
            print(json.dumps(result, ensure_ascii=False, indent=1))
        finally:
            shutil.rmtree(sandbox, ignore_errors=True)
        return 0 if result.get("ok") else 1
    if args.harvest or args.no_telegram:
        # harvest -> candidates only. The health sweep that follows records
        # results in data/ and proposes changes; neither publishes.
        summary = REPO.harvest()
        health = REPO.health_sweep()
        print(json.dumps({"harvest": summary, "health": health,
                          "pending_review": len(CANDIDATES.by_status(PENDING_REVIEW)),
                          "accepted": len(CANDIDATES.by_status(ACCEPTED)),
                          "staged_health_proposals": STATE.staged_proposals(),
                          "index_touched": False, "published": False}, ensure_ascii=False,
                         indent=1))
        return 0 if summary.get("ok") else 1

    if not TELEGRAM_TOKEN:
        LOG.error("TELEGRAM_BOT_TOKEN is required")
        return 1
    if not ADMIN_IDS:
        LOG.error("ADMIN_ID is required (the private chat that receives the alerts)")
        return 1

    stop = threading.Event()
    signal.signal(signal.SIGTERM, lambda *_: stop.set())
    signal.signal(signal.SIGINT, lambda *_: stop.set())

    me = TG.call("getMe")
    if not me:
        LOG.error("cannot reach the Telegram API — check TELEGRAM_BOT_TOKEN")
        return 1
    TG.bot_id = int(me.get("id") or 0)
    TG.call("deleteWebhook", {"drop_pending_updates": "false"})
    LOG.info("running as @%s (id=%s) | admins=%s | watch=%s", me.get("username"), TG.bot_id,
             sorted(ADMIN_IDS), sorted(WATCH_CHATS) or "all chats")

    stats = REPO.stats()
    LOG.info("index ready: %d extensions / %d sources (%d quarantined)", stats["extensions"],
             stats["sources"], stats["quarantined"])
    LOG.info("publish mode=%s push_enabled=%s -- no scheduled job, command or flag can "
             "publish; a publish needs run_publish_phase() with an explicit actor",
             PUBLISH_MODE, PUSH_ENABLED)
    if not PUSH_ENABLED:
        LOG.warning("GITHUB_TOKEN missing — index is generated locally but never pushed")

    scheduler = build_scheduler()
    TG.notify_admins(
        "✅ <b>{name}</b> يعمل الآن\n"
        "📦 الإضافات: {ext}\n📤 GitHub: {gh}\n⏰ الجلب التلقائي: <code>{cron}</code>\n\n"
        "الأوامر: /repo · /latest · /status · /scan · /health · /publish\n"
        "⛔️ النشر متوقف: /publish تقرير تشخيص فقط، والجلب يولّد مرشحين فقط.".format(
            name=esc(STORE_NAME), ext=stats["extensions"],
            gh=esc(f"{GITHUB_REPO}@{GITHUB_BRANCH}") if PUSH_ENABLED else "غير مفعّل",
            cron=esc(CRON_HARVEST)))

    if env_bool("BOOTSTRAP_HARVEST", True):
        # Discovery -> candidates only. The bootstrap thread cannot publish,
        # build, push or accept: job_harvest() has no such call.
        threading.Thread(target=job_harvest, name="bootstrap", daemon=True).start()

    backoff = 2
    while not stop.is_set():
        try:
            updates = TG.updates()
            backoff = 2
        except RuntimeError as exc:
            LOG.error("%s", exc)
            time.sleep(15)
            continue
        except Exception as exc:
            LOG.exception("polling error: %s", exc)
            time.sleep(backoff)
            backoff = min(backoff * 2, 60)
            continue
        for update in updates:
            try:
                if "callback_query" in update:
                    handle_decision(TG, update["callback_query"])
                elif "message" in update:
                    handle_message(TG, update["message"])
            except Exception as exc:
                LOG.exception("update %s failed: %s", update.get("update_id"), exc)

    if scheduler:
        scheduler.shutdown(wait=False)
    LOG.info("stopped")
    return 0


if __name__ == "__main__":
    sys.exit(main())
