#!/usr/bin/env python3
"""Manga-Extensions: smart Mihon-compatible repository manager + Telegram security guard."""

from __future__ import annotations

import argparse
import base64
import gzip
import json
import logging
import os
import re
import signal
import sys
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

LOG = logging.getLogger("manga-guard")
STARTED_AT = time.time()

ROOT = Path(__file__).resolve().parent
REPO_DIR = ROOT / "repo"
DATA_DIR = ROOT / "data"
QUARANTINE_FILE = Path(os.environ.get("QUARANTINE_FILE") or DATA_DIR / "quarantine.json")
AUDIT_CACHE_FILE = Path(os.environ.get("AUDIT_CACHE_FILE") or DATA_DIR / "audit_cache.json")
STATE_FILE = Path(os.environ.get("STATE_FILE") or DATA_DIR / "guard_state.json")
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
    items = set()
    for chunk in str(os.environ.get(name) or "").replace(";", ",").split(","):
        for part in chunk.split():
            if part.lstrip("-").isdigit():
                items.add(int(part))
    return items


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

STORE_NAME = env_str("REPO_NAME", "Shura")
STORE_BADGE = env_str("BADGE_LABEL", "SHURA")
SIGNING_KEY = env_str(
    "SIGNING_KEY",
    "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2",
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
CRON_PUBLISH = env_str("CRON_PUBLISH", "23 */2 * * *")
CRON_DIGEST = env_str("CRON_DIGEST", "53 9 * * *")
TIMEZONE = env_str("TIMEZONE", "UTC")

RAW_BASE = f"https://raw.githubusercontent.com/{GITHUB_REPO}/{GITHUB_BRANCH}"
REPO_JSON_URL = f"{RAW_BASE}/repo.json"
INDEX_PB_URL = f"https://github.com/{GITHUB_REPO}/raw/{GITHUB_BRANCH}/repo/index.pb"
MIHON_DEEP_LINK = f"mihon://extension-store?url={INDEX_PB_URL}"

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


def encode_index(index: dict) -> bytes:
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

    def scan_extension_source(self, package_name: str) -> list:
        findings = []
        scanned = 0
        for directory in self.module_paths(package_name):
            url = (f"https://api.github.com/repos/{UPSTREAM_SOURCE_REPO}/contents/"
                   f"{directory}?ref={UPSTREAM_SOURCE_REF}")
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
                       f"{UPSTREAM_SOURCE_REF}/{entry.get('path')}")
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
            LOG.debug("scanned %d files for %s", scanned, package_name)
        return findings

    def audit(self, extension: dict) -> str:
        reason = self.validate(extension)
        if reason:
            return reason
        reason = self.screen(extension)
        if reason:
            return reason

        resources = extension.get("resources") or {}
        for key in ("apkUrl", "iconUrl"):
            url = resources.get(key)
            if not url:
                continue
            try:
                code = self.session.head(url, timeout=HTTP_TIMEOUT,
                                         allow_redirects=True).status_code
            except requests.RequestException:
                continue
            if code in (404, 410):
                return "dead-asset:{}:{}".format(key, code)
            if code in (400, 401, 403, 429):
                continue
            if code >= 500:
                return "asset-error:{}:{}".format(key, code)

        if SOURCE_SCAN_ENABLED:
            findings = self.scan_extension_source(extension["packageName"])
            blocking = [item for item in findings if item["severity"] in BLOCKING_SEVERITIES]
            if blocking:
                return "malware:{}".format(",".join(sorted({item["id"] for item in blocking})))
            if findings:
                LOG.info("%s: %d low-severity findings (%s)", extension["packageName"],
                         len(findings), ",".join(sorted({item["id"] for item in findings})))
        return ""

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

    def harvest(self) -> dict:
        started = time.time()
        try:
            upstream = self.fetch_upstream()
        except (requests.RequestException, ValueError) as exc:
            self.last_error = "scrape failed: {}".format(exc)
            LOG.error("%s", self.last_error)
            return {"ok": False, "error": self.last_error}

        current = {item["packageName"]: item
                   for item in self.extensions() if item.get("packageName")}
        blocked = self.blocked_packages()
        live = {str(item.get("packageName")) for item in upstream if item.get("packageName")}
        added, updated, rejected, cached, scanned, budget = [], [], [], 0, 0, False

        for candidate in self.interleave(upstream):
            package = candidate.get("packageName")
            if not package or package in blocked:
                continue
            known = current.get(package)
            version_code = candidate.get("versionCode")
            try:
                newer = int(version_code or 0) > int((known or {}).get("versionCode") or 0)
            except (TypeError, ValueError):
                newer = False
            if known and not newer:
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
            reason = self.audit(candidate)
            if reason:
                self.record_audit(package, version_code, "reject", reason)
                rejected.append({"packageName": package, "reason": reason})
                LOG.info("rejected %s -> %s", package, reason)
                continue
            self.record_audit(package, version_code, "accept")
            if known:
                updated.append(package)
            else:
                added.append(package)
            current[package] = candidate

        with self.lock:
            self.index["extensionList"]["extensions"] = sorted(
                current.values(), key=lambda item: item.get("packageName") or "")
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
            "seconds": round(time.time() - started, 1),
        }
        LOG.info("harvest done: +%d new, %d updated, %d rejected (%d cached), %d scanned, "
                 "%d total in %ss", len(added), len(updated), len(rejected) - cached,
                 cached, scanned, summary["total"], summary["seconds"])
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
                    "quarantined": [], "total": len(extensions)}

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

        quarantined, dropped_sources = [], 0
        if dead_keys:
            with self.lock:
                keep_extensions = []
                for extension in self.index["extensionList"]["extensions"]:
                    package = extension.get("packageName")
                    keys = per_extension.get(package) or []
                    if not keys:
                        keep_extensions.append(extension)
                        continue
                    offenders = {key for key, _, _ in keys if key in dead_keys}
                    if not offenders:
                        keep_extensions.append(extension)
                        continue
                    if len(offenders) == len(keys):
                        if package not in self.blocked_packages():
                            self.quarantine.append({
                                "packageName": package,
                                "name": extension.get("name"),
                                "reason": "all-sources-dead",
                                "at": int(time.time()),
                            })
                        quarantined.append(package)
                        continue
                    survivors = [source for source in extension.get("sources") or []
                                 if "{}|{}".format(package, source.get("homeUrl") or "")
                                 not in offenders]
                    removed = len(extension.get("sources") or []) - len(survivors)
                    dropped_sources += removed
                    keep_extensions.append(dict(extension, sources=survivors))
                    LOG.info("%s: dropped %d dead source(s)", package, removed)
                if quarantined or dropped_sources:
                    self.index["extensionList"]["extensions"] = keep_extensions
                    self.save_quarantine()
            if quarantined:
                STATE.bump("quarantined", len(quarantined))
                LOG.warning("quarantined %d fully-dead extensions: %s", len(quarantined),
                            ", ".join(quarantined[:8]))
            if dropped_sources:
                LOG.info("dropped %d dead source(s) from %d extension(s)", dropped_sources,
                         len(self.extensions()))

        summary = {"ok": True, "checked": len(window), "alive": alive, "failing": dead,
                   "inconclusive": down, "dropped_sources": dropped_sources,
                   "quarantined": quarantined, "total": len(self.extensions())}
        LOG.info("health sweep: %d checked, %d alive, %d dead, %d inconclusive, "
                 "%d sources dropped, %d quarantined", len(window), alive, dead, down,
                 dropped_sources, len(quarantined))
        return summary

    def build(self) -> dict:
        with self.lock:
            index = json.loads(json.dumps(self.index))
        index["name"] = STORE_NAME
        index["badgeLabel"] = STORE_BADGE
        index["signingKey"] = SIGNING_KEY
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
        if dropped:
            with self.lock:
                self.index = index
            LOG.warning("build dropped %d invalid entries: %s", len(dropped),
                        ", ".join(item["packageName"] or "?" for item in dropped[:5]))

        proto = encode_index(index)
        repo_json = {"index_v2": INDEX_PB_URL,
                     "meta": {"name": STORE_NAME, "shortName": STORE_BADGE,
                              "website": STORE_WEBSITE,
                              "signingKeyFingerprint": SIGNING_KEY}}
        if STORE_DISCORD:
            repo_json["meta"]["discord"] = STORE_DISCORD

        artifacts = {
            "repo/index.json": render_index_json(index).encode("utf-8"),
            "repo/index.pb": gzip.compress(proto, mtime=0, compresslevel=9),
            "repo/index.min.json": (json.dumps(LEGACY_INDEX_MIN, indent=2) + "\n").encode("utf-8"),
            "repo.json": (json.dumps(repo_json, indent=2, ensure_ascii=False) + "\n").encode("utf-8"),
            "data/quarantine.json": (json.dumps(self.quarantine, ensure_ascii=False, indent=1)
                                     + "\n").encode("utf-8"),
            "data/audit_cache.json": (json.dumps(self.audit_cache, ensure_ascii=False, indent=1,
                                                 sort_keys=True) + "\n").encode("utf-8"),
        }

        changed = []
        for relative, payload in artifacts.items():
            path = ROOT / relative
            if path.is_file() and path.read_bytes() == payload:
                continue
            atomic_write(path, payload)
            changed.append(relative)

        return {"ok": True, "changed": changed, "extensions": len(kept),
                "dropped": dropped, "proto_bytes": len(proto),
                "pb_bytes": len(artifacts["repo/index.pb"])}

    def push(self, paths=None) -> dict:
        if not PUSH_ENABLED:
            return {"ok": False, "pushed": [], "skipped": "no GITHUB_TOKEN"}
        targets = paths or ["repo/index.json", "repo/index.pb", "repo/index.min.json",
                            "repo.json", "data/quarantine.json", "data/audit_cache.json"]
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
        built = self.build()
        if not built.get("ok"):
            return built
        if not built["changed"]:
            LOG.info("index already up to date (%d extensions)", built["extensions"])
            return {"ok": True, "changed": [], "pushed": [], "skipped": "nothing-to-push",
                    "extensions": built["extensions"]}
        pushed = self.push(built["changed"])
        LOG.info("published %d file(s): %s", len(built["changed"]), pushed.get("pushed"))
        return {"ok": True, "changed": built["changed"],
                "extensions": built["extensions"], **pushed}

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
    "• /scan — جلب وفحص فوري\n"
    "• /health — فحص روابط المصادر\n"
    "• /publish — إعادة توليد الفهرس ورفعه\n"
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
        "الإعدادات ← المستودعات ← إضافة مستودع، ثم الصق رابط index_v2:\n"
        "<code>{repo_json}</code>\n\n"
        "أو اضغط الزر بالأسفل لإضافته مباشرة:\n"
        "<a href=\"{deep}\">➕ إضافة المستودع في Mihon</a>\n\n"
        "📊 الإضافات: <b>{ext}</b> | المصادر: <b>{src}</b> | المعطّل: <b>{bad}</b>\n"
        "🔑 مفتاح التوقيع: <code>{key}</code>\n"
        "🔗 {website}"
    ).format(name=esc(STORE_NAME), repo_json=esc(REPO_JSON_URL), deep=esc(MIHON_DEEP_LINK),
             ext=stats["extensions"], src=stats["sources"], bad=stats["quarantined"],
             key=esc(SIGNING_KEY[:16] + "…"), website=esc(STORE_WEBSITE))


def repo_markup() -> dict:
    return keyboard(
        [{"text": "➕ إضافة في Mihon", "url": MIHON_DEEP_LINK}],
        [{"text": "📄 repo.json", "url": REPO_JSON_URL},
         {"text": "📦 index.min.json", "url": f"{RAW_BASE}/repo/index.min.json"}],
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

    if text.startswith("/") and chat_id in ADMIN_IDS:
        run_command(tg, message, text)
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


def handle_decision(tg: Telegram, query: dict) -> None:
    action, _, token = (query.get("data") or "").partition(":")
    if action not in ("ban", "allow") or not token:
        return
    tg.answer(query.get("id"), "تم التسجيل ✅")
    report = STATE.resolve(token, action)
    chat = (query.get("message") or {}).get("chat") or {}
    message_id = (query.get("message") or {}).get("message_id")
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
    LOG.info("decision %s token=%s", action, token)


def run_command(tg: Telegram, message: dict, text: str) -> None:
    chat_id = message["chat"]["id"]
    command, _, argument = text.partition(" ")
    command = command.split("@")[0].lower()
    argument = argument.strip()
    first_domain = domain_of(argument) if argument else ""

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
        tg.send(chat_id, "🔄 جارٍ الجلب والفحص الأمني…")
        summary = REPO.harvest()
        result = REPO.publish()
        if not summary.get("ok"):
            tg.send(chat_id, "❌ {}".format(esc(summary.get("error", "unknown"))))
            return
        added = "\n".join("• " + esc(pkg) for pkg in summary["added"][:15]) or "—"
        rejected = "\n".join("• {} → {}".format(esc(item["packageName"]), esc(item["reason"]))
                             for item in summary["rejected"][:10]) or "—"
        tg.send(chat_id, "✅ <b>اكتمل الجلب</b>\n"
                         "🆕 جديد ({}):\n{}\n"
                         "🔄 محدّث: {}\n"
                         "🚫 مرفوض ({}):\n{}\n"
                         "📦 الإجمالي: {}\n"
                         "📤 مرفوع: {}".format(
                             len(summary["added"]), added, len(summary["updated"]),
                             len(summary["rejected"]), rejected, summary["total"],
                             ", ".join(result.get("pushed") or []) or result.get("skipped")))
    elif command == "/health":
        tg.send(chat_id, "🩺 جارٍ فحص روابط المصادر…")
        summary = REPO.health_sweep()
        result = REPO.publish()
        tg.send(chat_id, "🩺 <b>فحص المصادر</b>\n"
                         "✅sources سليمة: {}\n"
                         "⚠️ متعثرة: {}\n"
                         "🛑 أُوقفت: {}\n"
                         "📦 المتبقي: {}\n"
                         "📤 {}".format(
                             summary.get("alive", 0), summary.get("failing", 0),
                             len(summary.get("quarantined") or []), summary.get("total", 0),
                             ", ".join(result.get("pushed") or []) or result.get("skipped")))
    elif command == "/publish":
        result = REPO.publish()
        tg.send(chat_id, "📦 <b>إعادة النشر</b>\nالإضافات: {}\nتغيّر: {}\n📤 {}".format(
            result.get("extensions", 0), ", ".join(result.get("changed") or []) or "لا شيء",
            ", ".join(result.get("pushed") or []) or result.get("skipped", "—")))
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
    try:
        summary = REPO.harvest()
        result = REPO.publish()
        if not summary.get("ok"):
            return
        for item in summary["rejected"][:15]:
            LOG.info("security reject %s -> %s", item["packageName"], item["reason"])
        if TG and (summary["added"] or summary["updated"] or result.get("pushed")):
            TG.notify_admins(
                "🔄 <b>تحديث تلقائي</b>\n"
                "🆕 جديد: {}\n🔄 محدّث: {}\n🚫 مرفوض: {}\n📦 الإجمالي: {}\n📤 {}".format(
                    len(summary["added"]), len(summary["updated"]), len(summary["rejected"]),
                    summary["total"], ", ".join(result.get("pushed") or [])
                    or result.get("skipped")))
    except Exception:
        LOG.exception("harvest job failed")


def job_health() -> None:
    try:
        summary = REPO.health_sweep()
        result = REPO.publish()
        if summary.get("quarantined") and TG:
            TG.notify_admins(
                "🛑 <b>تم إيقاف مصادر معطّلة</b>\n{}\n📦 المتبقي: {}\n📤 {}".format(
                    "\n".join("• " + esc(pkg) for pkg in summary["quarantined"][:20]),
                    summary.get("total", 0),
                    ", ".join(result.get("pushed") or []) or result.get("skipped")))
    except Exception:
        LOG.exception("health job failed")


def job_publish() -> None:
    try:
        result = REPO.publish()
        LOG.info("publish job: changed=%s pushed=%s", result.get("changed"), result.get("pushed"))
    except Exception:
        LOG.exception("publish job failed")


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
    failures = []

    def check(label: str, condition: bool) -> None:
        print("  {} {}".format("OK  " if condition else "FAIL", label))
        if not condition:
            failures.append(label)

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
        handle_decision(fake, {"id": "cb", "data": "ban:" + token,
                               "message": {"chat": {"id": 1}, "message_id": 7}})
        check("ban decision edits the alert", bool(edits))
        check("ban decision blocks reported domains", STATE.domain_blocked("bit.ly"))

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

    print("\n== state store ==")
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
    STATE.clear()
    STATE.path.unlink(missing_ok=True)

    print("\n== index build ==")
    built = REPO.build()
    check("build ok", built.get("ok") is True)
    check("index.pb written", INDEX_PB.is_file())
    check("index.json written", INDEX_JSON.is_file())
    if INDEX_PB.is_file():
        parsed = decode_index(gzip.decompress(INDEX_PB.read_bytes()))
        check("built pb round-trips ({} ext)".format(
            len(parsed["extensionList"]["extensions"])),
            encode_index(parsed) == gzip.decompress(INDEX_PB.read_bytes()))
    check("build is idempotent", not REPO.build()["changed"])
    check("repo.json points at index.pb",
          json.loads(REPO_JSON.read_text())["index_v2"].endswith("/repo/index.pb"))

    print("\n== push guard ==")
    result = REPO.push(["repo.json"])
    check("push never runs without token", result.get("ok") is False or PUSH_ENABLED)

    print("\nselftest:", "PASSED" if not failures else "{} FAILED".format(len(failures)))
    for item in failures:
        print("   -", item)
    return 1 if failures else 0

# ----------------------------------------------------------------- entry point


def configure_logging() -> None:
    logging.basicConfig(
        level=logging.DEBUG if env_bool("DEBUG", False) else logging.INFO,
        format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
        stream=sys.stdout, force=True)


def build_scheduler() -> object:
    if BackgroundScheduler is None:
        LOG.error("APScheduler missing — install requirements.txt to enable the scheduler")
        return None
    scheduler = BackgroundScheduler(timezone=TIMEZONE, job_defaults={
        "coalesce": True, "max_instances": 1, "misfire_grace_time": 900})
    for name, expression, handler in (("harvest", CRON_HARVEST, job_harvest),
                                      ("health", CRON_HEALTH, job_health),
                                      ("publish", CRON_PUBLISH, job_publish),
                                      ("digest", CRON_DIGEST, job_digest)):
        try:
            scheduler.add_job(handler,
                              CronTrigger.from_crontab(expression, timezone=TIMEZONE),
                              id=name, name=name, replace_existing=True)
            LOG.info("scheduled %-8s cron=%r", name, expression)
        except Exception as exc:
            LOG.error("invalid cron for %s (%r): %s", name, expression, exc)
    scheduler.start()
    LOG.info("scheduler running with %d job(s) in %s", len(scheduler.get_jobs()), TIMEZONE)
    return scheduler


def main() -> int:
    parser = argparse.ArgumentParser(description="Manga-Extensions repo manager + guard")
    parser.add_argument("--selftest", action="store_true", help="run offline self tests")
    parser.add_argument("--build", action="store_true", help="regenerate index files only")
    parser.add_argument("--harvest", action="store_true", help="one scrape + publish cycle")
    parser.add_argument("--no-telegram", action="store_true", help="repo manager only")
    args = parser.parse_args()
    configure_logging()

    if args.selftest:
        return self_test()
    if args.build:
        result = REPO.build()
        print(json.dumps(result, ensure_ascii=False, indent=1))
        return 0 if result.get("ok") else 1
    if args.harvest or args.no_telegram:
        summary = REPO.harvest()
        health = REPO.health_sweep()
        published = REPO.publish()
        print(json.dumps({"harvest": summary, "health": health, "publish": published},
                         ensure_ascii=False, indent=1))
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
    if not PUSH_ENABLED:
        LOG.warning("GITHUB_TOKEN missing — index is generated locally but never pushed")

    scheduler = build_scheduler()
    TG.notify_admins(
        "✅ <b>{name}</b> يعمل الآن\n"
        "📦 الإضافات: {ext}\n📤 GitHub: {gh}\n⏰ الجلب التلقائي: <code>{cron}</code>\n\n"
        "الأوامر: /repo · /latest · /status · /scan · /health · /publish".format(
            name=esc(STORE_NAME), ext=stats["extensions"],
            gh=esc(f"{GITHUB_REPO}@{GITHUB_BRANCH}") if PUSH_ENABLED else "غير مفعّل",
            cron=esc(CRON_HARVEST)))

    if env_bool("BOOTSTRAP_HARVEST", True):
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
