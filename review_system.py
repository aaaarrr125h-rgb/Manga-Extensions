#!/usr/bin/env python3
"""Review / accept pipeline for Manga-Extensions.

Phase 1 splits harvesting from publishing. Discovery produces *candidates* that
live outside ``repo/index.json``. A candidate only reaches the index through an
explicit, admin-authorised ``accept`` -- and even then, ``accept`` only moves
state; publishing is a separate, manual step.

The module is deliberately free of any import of ``bot.py`` so that it stays
importable (and testable) without network stack, credentials or a repository
checkout. Everything the pipeline needs from the outside world is injected.
"""

from __future__ import annotations

import hashlib
import hmac
import json
import os
import re
import threading
import time
from pathlib import Path

# ----------------------------------------------------------------- states

DISCOVERED = "DISCOVERED"
VALIDATED = "VALIDATED"
SCREENED = "SCREENED"
SCANNED = "SCANNED"
BUILT = "BUILT"
PENDING_REVIEW = "PENDING_REVIEW"
ACCEPTED = "ACCEPTED"
REJECTED = "REJECTED"
QUARANTINED = "QUARANTINED"

#: Ordered happy path. ``DISCOVERED`` is the reset target for any drift.
PIPELINE = (DISCOVERED, VALIDATED, SCREENED, SCANNED, BUILT, PENDING_REVIEW, ACCEPTED)

BLOCKING_STATES = frozenset({REJECTED, QUARANTINED})

#: Every gate a state depends on. A state is only reachable when all of its
#: gates are present and PASS -- enforced by :meth:`StateMachine.transition`.
GATES = ("validation", "screen", "sourceScan", "apkScan", "buildTest")

STATE_GATES = {
    DISCOVERED: (),
    VALIDATED: ("validation",),
    SCREENED: ("validation", "screen"),
    SCANNED: ("validation", "screen", "sourceScan", "apkScan"),
    BUILT: ("validation", "screen", "sourceScan", "apkScan", "buildTest"),
    PENDING_REVIEW: GATES,
    ACCEPTED: GATES,
    REJECTED: (),
    QUARANTINED: (),
}

#: Real edges. Anything absent here is refused, which is what makes
#: DISCOVERED -> ACCEPTED and REJECTED -> ACCEPTED impossible.
ALLOWED_TRANSITIONS = {
    DISCOVERED: frozenset({VALIDATED, REJECTED, QUARANTINED}),
    VALIDATED: frozenset({SCREENED, REJECTED, QUARANTINED}),
    SCREENED: frozenset({SCANNED, REJECTED, QUARANTINED}),
    SCANNED: frozenset({BUILT, REJECTED, QUARANTINED}),
    BUILT: frozenset({PENDING_REVIEW, REJECTED, QUARANTINED}),
    # a pending candidate may be invalidated back to the start of the pipeline
    PENDING_REVIEW: frozenset({ACCEPTED, DISCOVERED, REJECTED, QUARANTINED}),
    # accepted is terminal for *this* version; a new version is a new candidate
    ACCEPTED: frozenset({DISCOVERED}),
    REJECTED: frozenset({DISCOVERED, QUARANTINED}),
    QUARANTINED: frozenset({DISCOVERED, REJECTED}),
}

#: Drift in any of these fields invalidates the scan evidence collected so far.
PROVENANCE_FIELDS = (
    "packageName",
    "versionCode",
    "versionName",
    "sourceRepository",
    "sourceCommit",
    "apkUrl",
    "apkSha256",
    "apkSize",
)

#: Which stage must re-run when a given provenance field drifts. The APK
#: digest and the pinned commit are creation-phase artefacts, so a change to
#: either forces a full re-scan rather than a partial one.
DRIFT_RESET_STAGE = {
    "packageName": DISCOVERED,
    "versionCode": DISCOVERED,
    "versionName": DISCOVERED,
    "apkUrl": DISCOVERED,
    "apkSha256": DISCOVERED,
    "apkSize": DISCOVERED,
    "sourceRepository": DISCOVERED,
    "sourceCommit": DISCOVERED,
}

SCHEMA_VERSION = 1
MAX_APK_BYTES = 64 * 1024 * 1024

SHA1_RE = re.compile(r"^[0-9a-f]{40}$")
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
HEX_DIGEST_RE = re.compile(r"^[0-9a-f]{64}$")
PACKAGE_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$")
REPO_RE = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")


class ReviewError(Exception):
    """Base class for pipeline refusals."""


class IllegalTransition(ReviewError):
    pass


class MissingEvidence(ReviewError):
    pass


# ----------------------------------------------------------------- secrets

_SECRET_ENV_HINTS = ("TOKEN", "SECRET", "PASSWORD", "PASSWD", "KEY", "CREDENTIAL", "COOKIE")

_SECRET_PATTERNS = (
    re.compile(r"gh[pousr]_[A-Za-z0-9]{16,}"),
    re.compile(r"github_pat_[A-Za-z0-9_]{20,}"),
    re.compile(r"\b\d{8,12}:[A-Za-z0-9_-]{30,}"),
    re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----", re.S),
    re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
    re.compile(r"AIza[0-9A-Za-z_\-]{35}"),
)

REDACTED = "[REDACTED]"


def _secret_values() -> list:
    """Values of env vars that look like credentials, longest first."""
    found = set()
    for name, value in os.environ.items():
        if len(value) < 8 or not value.strip():
            continue
        if any(hint in name.upper() for hint in _SECRET_ENV_HINTS):
            found.add(value.strip())
    return sorted(found, key=len, reverse=True)


def redact(value) -> str:
    """Strip anything that looks like a credential out of a value bound for disk."""
    text = value if isinstance(value, str) else json.dumps(value, ensure_ascii=False, default=str)
    for secret in _secret_values():
        if secret and secret in text:
            text = text.replace(secret, REDACTED)
    for pattern in _SECRET_PATTERNS:
        text = pattern.sub(REDACTED, text)
    return text


# ----------------------------------------------------------------- helpers

def now_ts() -> int:
    return int(time.time())


def canonical(payload) -> str:
    return json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"),
                      default=str)


def digest(payload) -> str:
    return hashlib.sha256(canonical(payload).encode("utf-8")).hexdigest()


def is_commit_sha(value) -> bool:
    """True only for a full 40 hex char SHA-1 (or 64 for SHA-256) commit id.

    Branch names, tags, short shas and anything else are refused, which is the
    whole point: a candidate must pin an immutable object.
    """
    return bool(value) and bool(SHA1_RE.match(str(value)) or SHA256_RE.match(str(value)))


def is_sha256_digest(value) -> bool:
    return bool(value) and bool(HEX_DIGEST_RE.match(str(value)))


def atomic_write(path: Path, payload: bytes) -> bool:
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_name(path.name + ".tmp")
    temp.write_bytes(payload)
    temp.replace(path)
    return True


# ----------------------------------------------------------------- audit log

class AuditLog:
    """Append-only JSONL log. Never rewritten, never truncated by tests."""

    FIELDS = ("timestamp", "action", "packageName", "versionCode", "sourceCommit",
              "apkSha256", "actor", "result", "reason")

    def __init__(self, path: Path):
        self.path = Path(path)
        self.lock = threading.RLock()

    def record(self, action: str, package: str = "", version_code="", source_commit="",
               apk_sha256="", actor="", result="", reason="") -> dict:
        entry = {
            "timestamp": now_ts(),
            "action": redact(str(action)),
            "packageName": redact(str(package or "")),
            "versionCode": redact(str(version_code or "")),
            "sourceCommit": redact(str(source_commit or "")),
            "apkSha256": redact(str(apk_sha256 or "")),
            "actor": redact(str(actor or "")),
            "result": redact(str(result or "")),
            "reason": redact(str(reason or "")),
        }
        line = canonical(entry) + "\n"
        with self.lock:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            with self.path.open("a", encoding="utf-8") as handle:
                handle.write(line)
                handle.flush()
                os.fsync(handle.fileno())
        return entry

    def entries(self) -> list:
        if not self.path.is_file():
            return []
        out = []
        for line in self.path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                out.append(json.loads(line))
            except ValueError:
                continue
        return out

    def for_package(self, package: str) -> list:
        return [item for item in self.entries() if item.get("packageName") == package]


# ----------------------------------------------------------------- state machine

class StateMachine:
    """Refuses any edge that is not in :data:`ALLOWED_TRANSITIONS`."""

    def transition(self, candidate: dict, target: str, reason: str = "") -> str:
        current = candidate.get("status")
        if target not in STATE_GATES:
            raise IllegalTransition("unknown state {!r}".format(target))
        if target not in ALLOWED_TRANSITIONS.get(current, frozenset()):
            raise IllegalTransition("{} -> {} is not a legal transition".format(current, target))
        if current == target:
            return current
        if current == DISCOVERED and target == ACCEPTED:
            # explicit, belt-and-braces: never let a discovery jump to accept
            raise IllegalTransition("DISCOVERED -> ACCEPTED is forbidden")
        self.require_gates(candidate, target)
        candidate["status"] = target
        candidate.setdefault("history", []).append(
            {"at": now_ts(), "from": current, "to": target, "reason": redact(reason)})
        return target

    @staticmethod
    def gates(candidate: dict) -> dict:
        return candidate.get("results") or {}

    def require_gates(self, candidate: dict, target: str) -> None:
        for gate in STATE_GATES.get(target, ()):
            record = self.gates(candidate).get(gate)
            if not record:
                raise MissingEvidence("gate {!r} has not run".format(gate))
            if record.get("result") != "PASS":
                raise MissingEvidence("gate {!r} did not pass ({})".format(
                    gate, record.get("result")))
            if not is_sha256_digest(record.get("digest")):
                raise MissingEvidence("gate {!r} has no integrity digest".format(gate))

    @staticmethod
    def set_gate(candidate: dict, gate: str, result: str, detail: str = "",
                 extra: dict = None) -> dict:
        record = {"result": result, "at": now_ts(), "detail": redact(detail)}
        if extra:
            record.update({key: value for key, value in extra.items()})
        record["digest"] = digest({key: value for key, value in record.items() if key != "digest"})
        results = candidate.setdefault("results", {})
        results[gate] = record
        # a fresh gate invalidates every later gate, since they were computed
        # against the now-stale evidence
        order = list(GATES)
        if gate in order:
            for later in order[order.index(gate) + 1:]:
                results.pop(later, None)
        return record

    def verify_gate_digests(self, candidate: dict) -> str:
        """Detect tampering with a stored gate record after the fact."""
        for gate, record in self.gates(candidate).items():
            if not isinstance(record, dict) or "digest" not in record:
                return "gate {!r} lost its digest".format(gate)
            expected = digest({key: value for key, value in record.items() if key != "digest"})
            if not hmac.compare_digest(str(record["digest"]), expected):
                return "gate {!r} was modified after it ran".format(gate)
        return ""


SM = StateMachine()


# ----------------------------------------------------------------- candidate

def blank_candidate() -> dict:
    stamp = now_ts()
    return {
        "schemaVersion": SCHEMA_VERSION,
        "packageName": "",
        "name": "",
        "versionCode": "",
        "versionName": "",
        "sourceRepository": "",
        "sourceCommit": "",
        "apkUrl": "",
        "apkSha256": "",
        "apkSize": 0,
        "contentWarning": "",
        "validationResult": "",
        "screenResult": "",
        "sourceSecurityScanResult": "",
        "apkBinaryScanResult": "",
        "buildTestResult": "",
        "discoveredAt": stamp,
        "updatedAt": stamp,
        "status": DISCOVERED,
        "results": {},
        "history": [],
        "verifiedProvenance": "",
        "apkVerifiedAt": 0,
    }


def build_candidate(extension: dict, source_repository: str, source_commit: str,
                    apk_sha256: str, apk_size: int) -> dict:
    """Assemble a candidate from an upstream extension plus pinned provenance."""
    resources = extension.get("resources") or {}
    candidate = blank_candidate()
    candidate.update({
        "packageName": str(extension.get("packageName") or ""),
        "name": str(extension.get("name") or ""),
        "versionCode": str(extension.get("versionCode") or ""),
        "versionName": str(extension.get("versionName") or ""),
        "sourceRepository": str(source_repository or ""),
        "sourceCommit": str(source_commit or ""),
        "apkUrl": str(resources.get("apkUrl") or ""),
        "apkSha256": str(apk_sha256 or ""),
        "apkSize": int(apk_size or 0),
        "contentWarning": str(extension.get("contentWarning") or ""),
        "apkVerifiedAt": now_ts(),
    })
    candidate["extension"] = extension
    return candidate


def provenance_of(candidate: dict) -> dict:
    return {field: candidate.get(field) for field in PROVENANCE_FIELDS}


def provenance_fingerprint(candidate: dict) -> str:
    return digest(provenance_of(candidate))


def candidate_completeness_errors(candidate: dict) -> list:
    """Structural problems that make a candidate unacceptable at any stage."""
    errors = []
    if not PACKAGE_RE.match(str(candidate.get("packageName") or "")):
        errors.append("bad-packageName")
    if not str(candidate.get("name") or "").strip():
        errors.append("missing-name")
    raw_code = str(candidate.get("versionCode") or "").strip()
    if not raw_code.lstrip("-").isdigit() or int(raw_code or 0) < 1:
        errors.append("bad-versionCode")
    if not str(candidate.get("versionName") or "").strip():
        errors.append("missing-versionName")
    if not REPO_RE.match(str(candidate.get("sourceRepository") or "")):
        errors.append("bad-sourceRepository")
    if not is_commit_sha(candidate.get("sourceCommit")):
        errors.append("sourceCommit-not-a-sha")
    if str(candidate.get("apkUrl") or "").lower().startswith("https://"):
        pass
    else:
        errors.append("apkUrl-not-https")
    if not is_sha256_digest(candidate.get("apkSha256")):
        errors.append("missing-apkSha256")
    if int(candidate.get("apkSize") or 0) <= 0:
        errors.append("missing-apkSize")
    if not int(candidate.get("apkVerifiedAt") or 0):
        errors.append("apk-not-verified")
    return errors


# ----------------------------------------------------------------- apk scanning

APK_MAGIC = b"PK\x03\x04"

APK_BINARY_SIGNATURES = (
    ("dex-dynamic-load", "critical", re.compile(rb"DexClassLoader|PathClassLoader|"
                                                rb"InMemoryDexClassLoader|BaseDexClassLoader")),
    ("root-shell", "critical", re.compile(rb"/system/bin/sh|/system/xbin/su|/system/xbin/busybox")),
    ("su-binary-ref", "critical", re.compile(rb"supersu|superuser|eu\.chainfire")),
    ("native-loader", "high", re.compile(rb"System\.loadLibrary|dalvik\.system\.NativeLibrary")),
    ("device-admin", "critical", re.compile(rb"BIND_DEVICE_ADMIN|BIND_ACCESSIBILITY_SERVICE|"
                                            rb"DeviceAdminReceiver")),
    ("tls-trust-all", "high", re.compile(rb"ALLOW_ALL_HOSTNAME_VERIFIER|"
                                         rb"X509TrustManager|checkServerTrusted")),
    ("reflection", "medium", re.compile(rb"setAccessible|Ljava/lang/reflect/Method;")),
)

BLOCKING_SEVERITIES = frozenset({"critical"})


def scan_apk_binary(payload: bytes) -> dict:
    """Static scan of the downloaded APK bytes. No execution, no unpacking."""
    findings = []
    if not payload[:4] == APK_MAGIC:
        findings.append({"id": "not-a-zip", "severity": "critical",
                         "detail": "APK does not start with a ZIP local file header"})
        return {"findings": findings, "ok": False, "hasDex": False, "hasManifest": False}
    names = set()
    for chunk in re.findall(rb"[A-Za-z0-9_/.-]{4,120}\.(?:dex|so|xml|META-INF/[A-Z0-9_.-]+)", payload):
        names.add(chunk.decode("utf-8", "ignore"))
    has_dex = any(name.endswith(".dex") for name in names)
    has_manifest = "AndroidManifest.xml" in names or b"AndroidManifest.xml" in payload
    if not has_manifest:
        findings.append({"id": "missing-manifest", "severity": "critical",
                         "detail": "AndroidManifest.xml not found"})
    for name, severity, pattern in APK_BINARY_SIGNATURES:
        if pattern.search(payload):
            findings.append({"id": name, "severity": severity, "detail": "binary signature"})
    blocking = [item for item in findings if item["severity"] in BLOCKING_SEVERITIES]
    return {"findings": findings, "ok": not blocking,
            "hasDex": has_dex, "hasManifest": has_manifest}


def is_https_url(value) -> bool:
    """True only for a literal ``https://`` URL.

    Deliberately strict: ``http://``, ``ftp://``, ``file://`` and any other
    scheme are all rejected, and the comparison is case-insensitive because
    schemes are. No scheme-less or relative value passes.
    """
    return str(value or "").strip().lower().startswith("https://")


def apk_opener(url: str, timeout: int = 60):
    """Open ``url`` following redirects and return the live response."""
    import requests  # noqa: PLC0415 - optional dependency, network path only
    return requests.get(url, stream=True, timeout=timeout, allow_redirects=True)


def fetch_apk(url: str, fetcher=None, opener=None) -> dict:
    """Download an APK over HTTPS and pin its size + SHA-256.

    HTTPS is enforced twice: once on the URL before the request leaves, and
    once on the *final* URL after the redirect chain has been followed. A
    server that answers 302 towards ``http://``, ``ftp://`` or ``file://`` is
    refused, so the download cannot be downgraded or pointed at another scheme
    after the fact. ``opener`` is the injectable form of the network call; it
    is what the real request goes through, so redirect handling is testable
    without a socket.

    Returns a dict with ``ok`` and either ``sha256``/``size`` or ``error``.
    """
    if not is_https_url(url):
        return {"ok": False, "error": "apkUrl is not https"}
    try:
        if fetcher is not None:
            payload = fetcher(url)
        else:
            response = (opener or apk_opener)(url)
            try:
                response.raise_for_status()
                final_url = str(getattr(response, "url", "") or url)
                if not is_https_url(final_url):
                    return {"ok": False,
                            "error": "apk redirect left https: {}".format(
                                redact(final_url))}
                buffer = bytearray()
                for block in response.iter_content(65536):
                    buffer.extend(block)
                    if len(buffer) > MAX_APK_BYTES:
                        return {"ok": False, "error": "apk exceeds size cap"}
                payload = bytes(buffer)
            finally:
                close = getattr(response, "close", None)
                if callable(close):
                    close()
    except Exception as exc:  # noqa: BLE001 - surfaced as a rejection reason
        return {"ok": False, "error": redact("apk download failed: {}".format(exc))}
    if not payload:
        return {"ok": False, "error": "empty apk payload"}
    if len(payload) > MAX_APK_BYTES:
        return {"ok": False, "error": "apk exceeds size cap"}
    return {"ok": True, "sha256": hashlib.sha256(payload).hexdigest(),
            "size": len(payload), "payload": payload}


def resolve_source_commit(repository: str, ref: str, resolver=None) -> dict:
    """Pin ``ref`` (may be a branch) to an immutable commit SHA."""
    if not REPO_RE.match(str(repository or "")):
        return {"ok": False, "error": "bad-sourceRepository"}
    if is_commit_sha(ref):
        return {"ok": True, "commit": str(ref).lower(), "wasRef": False}
    if not str(ref or "").strip():
        return {"ok": False, "error": "empty source ref"}
    if resolver is None:
        return {"ok": False, "error": "no source resolver available"}
    try:
        commit = resolver(repository, str(ref))
    except Exception as exc:  # noqa: BLE001
        return {"ok": False, "error": redact("source resolve failed: {}".format(exc))}
    if not is_commit_sha(commit):
        return {"ok": False, "error": "resolver returned a non-sha ref {!r}".format(commit)}
    return {"ok": True, "commit": str(commit).lower(), "wasRef": True}


# ----------------------------------------------------------------- store

class CandidateStore:
    """Durable candidate storage. Loading never truncates existing data."""

    def __init__(self, path: Path):
        self.path = Path(path)
        self.lock = threading.RLock()
        self.candidates = {}
        self.load()

    def load(self) -> None:
        if not self.path.is_file():
            return
        try:
            payload = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as exc:
            raise RuntimeError("candidate store unreadable: {}".format(exc))
        if not isinstance(payload, dict):
            raise RuntimeError("candidate store has an unexpected shape")
        with self.lock:
            for key, value in payload.get("candidates", {}).items():
                if isinstance(value, dict):
                    self.candidates[key] = value

    def save(self) -> None:
        with self.lock:
            payload = {"schemaVersion": SCHEMA_VERSION,
                       "candidates": self.candidates}
            atomic_write(self.path, (json.dumps(payload, ensure_ascii=False, indent=1)
                                     + "\n").encode("utf-8"))

    @staticmethod
    def key(package: str, version_code) -> str:
        return "{}@{}".format(package, version_code)

    @staticmethod
    def _copy(candidate: dict) -> dict:
        """Detached copy: a reader can never mutate stored state by accident."""
        return json.loads(json.dumps(candidate))

    def get(self, package: str, version_code=None) -> dict:
        if version_code is None:
            for candidate in self.list():
                if candidate.get("packageName") == package:
                    return candidate
            return {}
        with self.lock:
            found = self.candidates.get(self.key(package, version_code))
            return self._copy(found) if found else {}

    def latest(self, package: str) -> dict:
        candidates = [item for item in self.list() if item.get("packageName") == package]
        if not candidates:
            return {}
        return max(candidates, key=lambda item: int(item.get("versionCode") or 0))

    def list(self) -> list:
        with self.lock:
            return [dict(value) for value in self.candidates.values()]

    def packages(self) -> list:
        with self.lock:
            return sorted({item.get("packageName") for item in self.candidates.values()
                           if item.get("packageName")})

    def put(self, candidate: dict) -> dict:
        with self.lock:
            key = self.key(candidate.get("packageName"), candidate.get("versionCode"))
            candidate["updatedAt"] = now_ts()
            self.candidates[key] = candidate
            self.save()
        return candidate

    def by_status(self, status: str) -> list:
        return [item for item in self.list() if item.get("status") == status]


# ----------------------------------------------------------------- review system

class ReviewSystem:
    """Owns the candidate lifecycle. Deliberately has no repository handle, so
    nothing here can build, publish or push."""

    def __init__(self, store: CandidateStore, audit: AuditLog, *,
                 apk_fetcher=None, commit_resolver=None, deep_screen=None,
                 build_tester=None, index_lookup=None, source_scanner=None,
                 asset_probe=None, allow_mixed: bool = False, machine: StateMachine = SM):
        self.store = store
        self.audit = audit
        self.apk_fetcher = apk_fetcher
        self.commit_resolver = commit_resolver
        self.deep_screen = deep_screen
        self.build_tester = build_tester
        self.index_lookup = index_lookup or (lambda package: None)
        self.source_scanner = source_scanner
        self.asset_probe = asset_probe
        self.allow_mixed = allow_mixed
        self.machine = machine

    # -- discovery ------------------------------------------------------

    def discover(self, extension: dict, source_repository: str, source_ref: str,
                 actor: str = "harvest", pin_apk: bool = True) -> dict:
        """Turn an upstream extension into a candidate. Never touches the index.

        A candidate that cannot be pinned (Phase 2) or whose APK cannot be
        fingerprinted (Phase 3) is still *stored* -- as REJECTED, so the refusal
        is visible in the queue and in the audit log instead of vanishing.
        """
        package = str(extension.get("packageName") or "")
        version = str(extension.get("versionCode") or "")
        if not package or not version:
            return self._reject(package, version, "malformed-upstream-entry", actor=actor)

        pin = resolve_source_commit(source_repository, source_ref, self.commit_resolver)
        if not pin["ok"]:
            # Phase 2: no immutable commit means the candidate can never be
            # accepted, so it is rejected outright rather than parked.
            reason = "unpinned-source:{}".format(pin["error"])
            candidate = build_candidate(extension, source_repository, str(source_ref or ""),
                                        "", 0)
            return dict(self.reject(candidate, reason, actor=actor),
                        rejected=True, reason=redact(reason))

        commit = pin["commit"]
        resources = extension.get("resources") or {}
        apk_url = str(resources.get("apkUrl") or "")
        integrity = {"ok": False, "error": "apk not fetched"}
        if pin_apk:
            integrity = fetch_apk(apk_url, self.apk_fetcher)
            if not integrity["ok"]:
                reason = "apk-integrity:{}".format(integrity["error"])
                candidate = build_candidate(extension, source_repository, commit, "", 0)
                return dict(self.reject(candidate, reason, actor=actor),
                            rejected=True, reason=redact(reason))

        candidate = build_candidate(extension, source_repository, commit,
                                    integrity.get("sha256", ""), integrity.get("size", 0))
        errors = candidate_completeness_errors(candidate)
        if errors and pin_apk:
            reason = ",".join(errors)
            return dict(self.reject(candidate, reason, actor=actor),
                        rejected=True, reason=reason)

        existing = self.index_lookup(package)
        candidate["replacesExisting"] = bool(existing)
        candidate["existingVersionCode"] = str((existing or {}).get("versionCode") or "")
        candidate["existingVersionName"] = str((existing or {}).get("versionName") or "")

        # the APK was downloaded, so it is alive by construction; the remaining
        # distribution assets are probed explicitly. Optional: without a probe
        # (tests, offline runs) discovery still pins a complete candidate.
        probe = self._probe_assets(candidate)
        if probe:
            return dict(self.reject(candidate, probe, actor=actor),
                        rejected=True, reason=redact(probe))

        self.store.put(candidate)
        self.audit.record("DISCOVER", package, version, commit,
                          candidate["apkSha256"], actor, "PASS",
                          "candidate created (new)" if not existing else
                          "candidate created (replaces existing on accept)")
        return candidate

    # -- pipeline -------------------------------------------------------

    def _probe_assets(self, candidate: dict) -> str:
        """Cheap liveness probe for the distribution assets that are not
        downloaded. Returns "" when everything is fine or no probe is wired."""
        if self.asset_probe is None:
            return ""
        for key in ("iconUrl", "jarUrl"):
            url = ((candidate.get("extension") or {}).get("resources") or {}).get(key)
            if not url:
                continue
            try:
                verdict = self.asset_probe(url)
            except Exception as exc:  # noqa: BLE001
                return "asset-probe-error:{}:{}".format(key, redact(str(exc)))
            if verdict:
                return "asset-{}:{}:{}".format(key, verdict, url)
        return ""

    def run_validation(self, candidate: dict, errors=None) -> dict:
        errors = list(errors if errors is not None
                      else candidate_completeness_errors(candidate))
        ok = not errors
        self.machine.set_gate(candidate, "validation", "PASS" if ok else "FAIL",
                              ",".join(errors) if errors else "schema complete")
        if not ok:
            self.reject(candidate, "validation:{}".format(",".join(errors)))
        return candidate

    def run_screen(self, candidate: dict, allow_mixed: bool = False) -> dict:
        warning = str(candidate.get("contentWarning") or "").upper()
        if "NSFW" in warning:
            return self._fail_gate(candidate, "screen", "contentWarning=NSFW")
        if "MIXED" in warning and not allow_mixed:
            return self._fail_gate(candidate, "screen", "contentWarning=MIXED")
        if self.deep_screen is not None:
            try:
                reason = self.deep_screen(candidate.get("extension") or candidate)
            except Exception as exc:  # noqa: BLE001
                return self._fail_gate(candidate, "screen", redact("screen error: {}".format(exc)))
            if reason:
                return self._fail_gate(candidate, "screen", reason)
        self.machine.set_gate(candidate, "screen", "PASS", "content policy ok")
        return candidate

    def run_source_scan(self, candidate: dict, findings=None, scanned: int = 0) -> dict:
        findings = list(findings or [])
        blocking = [item for item in findings
                    if str(item.get("severity")) in BLOCKING_SEVERITIES]
        detail = "{} file(s) scanned, {} finding(s)".format(scanned, len(findings))
        if blocking:
            self.machine.set_gate(candidate, "sourceScan", "FAIL", detail,
                                  extra={"findings": [item.get("id") for item in blocking]})
            self.reject(candidate, "sourceScan:{}".format(
                ",".join(sorted({str(item.get("id")) for item in blocking}))))
            return candidate
        self.machine.set_gate(candidate, "sourceScan", "PASS", detail,
                              extra={"findings": [item.get("id") for item in findings],
                                     "scanned": scanned})
        return candidate

    def run_apk_scan(self, candidate: dict, payload=None) -> dict:
        if payload is None:
            fetched = fetch_apk(candidate.get("apkUrl"), self.apk_fetcher)
            if not fetched["ok"]:
                return self._fail_gate(candidate, "apkScan", fetched["error"])
            payload = fetched["payload"]
        report = scan_apk_binary(payload)
        detail = "{} finding(s)".format(len(report["findings"]))
        if not report["ok"]:
            self.machine.set_gate(candidate, "apkScan", "FAIL", detail,
                                  extra={"findings": [item["id"] for item in report["findings"]]})
            self.reject(candidate, "apkScan:{}".format(
                ",".join(sorted({item["id"] for item in report["findings"]}))))
            return candidate
        self.machine.set_gate(candidate, "apkScan", "PASS", detail,
                              extra={"findings": [item["id"] for item in report["findings"]],
                                     "hasDex": report["hasDex"]})
        return candidate

    def run_build_test(self, candidate: dict) -> dict:
        """SCREENED -> PENDING_REVIEW, but only behind a real build test.

        A missing tester is a failure, never a pass. There is no metadata-only
        shortcut into PENDING_REVIEW: without a tester that builds the APK and
        installs the native libraries, the candidate has no evidence that it
        works on a device, so it is rejected rather than waved through.
        """
        if self.build_tester is None:
            return self._fail_gate(candidate, "buildTest",
                                   "no build tester configured")
        try:
            ok, detail = self.build_tester(candidate)
        except Exception as exc:  # noqa: BLE001
            ok, detail = False, redact("build test error: {}".format(exc))
        if not ok:
            return self._fail_gate(candidate, "buildTest", detail)
        self.machine.set_gate(candidate, "buildTest", "PASS", detail)
        return candidate

    def _fail_gate(self, candidate: dict, gate: str, detail: str) -> dict:
        self.machine.set_gate(candidate, gate, "FAIL", detail)
        self.reject(candidate, "{}:{}".format(gate, detail))
        return candidate

    def advance(self, candidate: dict, target: str, reason: str = "") -> dict:
        """Walk the candidate to ``target`` one legal edge at a time."""
        while candidate.get("status") != target:
            current = candidate.get("status")
            if current == target:
                break
            nxt = self._next_step(current)
            if nxt is None:
                raise IllegalTransition("no legal step out of {}".format(current))
            if nxt == target:
                self.machine.transition(candidate, nxt, reason)
                break
            self.machine.transition(candidate, nxt, reason)
        return candidate

    @staticmethod
    def _next_step(state: str):
        order = list(PIPELINE)
        if state not in order:
            return None
        position = order.index(state)
        if position + 1 >= len(order):
            return None
        nxt = order[position + 1]
        if nxt not in ALLOWED_TRANSITIONS.get(state, frozenset()):
            return None
        return nxt

    def mark_pending(self, candidate: dict) -> dict:
        """Run nothing; just require the evidence to exist and move to review."""
        try:
            self.machine.transition(candidate, PENDING_REVIEW, "pipeline complete")
        except ReviewError as exc:
            self.reject(candidate, "cannot-enter-review: {}".format(exc))
            return candidate
        candidate["verifiedProvenance"] = provenance_fingerprint(candidate)
        self.store.put(candidate)
        return candidate

    def process(self, candidate: dict) -> dict:
        """Run the full gate sequence and land on PENDING_REVIEW.

        Each gate is written *before* the state machine is asked for the edge,
        so an edge can never be taken on absent evidence.
        """
        if candidate.get("status") in (REJECTED, QUARANTINED, ACCEPTED):
            return candidate

        if candidate.get("status") == DISCOVERED:
            self.run_validation(candidate)
            if candidate.get("status") == DISCOVERED:
                self.advance(candidate, VALIDATED, "validation passed")

        if candidate.get("status") == VALIDATED:
            self.run_screen(candidate, allow_mixed=self.allow_mixed)
            if candidate.get("status") == VALIDATED:
                self.advance(candidate, SCREENED, "screen passed")

        if candidate.get("status") == SCREENED:
            findings, scanned = [], 0
            if self.source_scanner is not None:
                try:
                    report = self.source_scanner(candidate)
                    findings = list(report.get("findings") or [])
                    scanned = int(report.get("scanned") or 0)
                except Exception as exc:  # noqa: BLE001
                    return self._fail_gate(candidate, "sourceScan",
                                           redact("source scan error: {}".format(exc)))
            self.run_source_scan(candidate, findings=findings, scanned=scanned)
            if candidate.get("status") == SCREENED:
                self.run_apk_scan(candidate)
            if candidate.get("status") == SCREENED:
                self.advance(candidate, SCANNED, "security scans passed")

        if candidate.get("status") == SCANNED:
            self.run_build_test(candidate)
            if candidate.get("status") == SCANNED:
                self.advance(candidate, BUILT, "build test passed")

        if candidate.get("status") == BUILT:
            self.mark_pending(candidate)
        return candidate

    def reject(self, candidate: dict, reason: str, actor: str = "pipeline") -> dict:
        if candidate.get("status") != REJECTED:
            try:
                self.machine.transition(candidate, REJECTED, reason)
            except ReviewError:
                candidate["status"] = REJECTED
        self.store.put(candidate)
        self.audit.record("REJECT", candidate.get("packageName", ""),
                          candidate.get("versionCode", ""), candidate.get("sourceCommit", ""),
                          candidate.get("apkSha256", ""), actor, "REJECT", reason)
        return candidate

    def quarantine(self, candidate: dict, reason: str, actor: str = "pipeline") -> dict:
        try:
            self.machine.transition(candidate, QUARANTINED, reason)
        except ReviewError:
            candidate["status"] = QUARANTINED
        self.store.put(candidate)
        self.audit.record("QUARANTINE", candidate.get("packageName", ""),
                          candidate.get("versionCode", ""), candidate.get("sourceCommit", ""),
                          candidate.get("apkSha256", ""), actor, "REJECT", reason)
        return candidate

    # -- TOCTOU ---------------------------------------------------------

    def invalidate(self, candidate: dict, reason: str, actor: str = "system") -> dict:
        """Drop all scan evidence and send the candidate back to DISCOVERED."""
        stage = DRIFT_RESET_STAGE.get(reason, DISCOVERED)
        candidate["results"] = {}
        candidate["verifiedProvenance"] = ""
        try:
            if candidate.get("status") != DISCOVERED:
                self.machine.transition(candidate, DISCOVERED, reason)
        except ReviewError:
            candidate["status"] = DISCOVERED
        candidate["invalidatedReason"] = redact(reason)
        candidate["resetStage"] = stage
        self.store.put(candidate)
        self.audit.record("INVALIDATE", candidate.get("packageName", ""),
                          candidate.get("versionCode", ""), candidate.get("sourceCommit", ""),
                          candidate.get("apkSha256", ""), actor, "REJECT", reason)
        return candidate

    def reverify(self, candidate: dict) -> list:
        """Full TOCTOU sweep. Returns a list of problems; empty means intact."""
        problems = []
        # 1. structural gate integrity
        tampered = self.machine.verify_gate_digests(candidate)
        if tampered:
            problems.append(tampered)
        # 2. required evidence present and passing
        for gate in GATES:
            record = (candidate.get("results") or {}).get(gate)
            if not record:
                problems.append("missing-gate:{}".format(gate))
            elif record.get("result") != "PASS":
                problems.append("gate-not-passed:{}".format(gate))
        # 3. provenance fingerprint still matches what was reviewed
        if candidate.get("verifiedProvenance") != provenance_fingerprint(candidate):
            problems.append("provenance-changed")
        # 4. the pinned commit is still an immutable sha
        if not is_commit_sha(candidate.get("sourceCommit")):
            problems.append("sourceCommit-not-a-sha")
        # 5. apk digest is well formed
        if not is_sha256_digest(candidate.get("apkSha256")):
            problems.append("apkSha256-invalid")
        if int(candidate.get("apkSize") or 0) <= 0:
            problems.append("apkSize-invalid")
        return problems

    def reverify_live(self, candidate: dict) -> list:
        """Re-derive provenance from the outside world and compare.

        The pinned commit is asked for *by its own sha*: a resolver that answers
        with a different sha means the object moved (force push, rewritten
        history, a mirror that re-pointed) and the candidate is no longer the
        thing that was reviewed.
        """
        problems = []
        commit = str(candidate.get("sourceCommit") or "").lower()
        repository = str(candidate.get("sourceRepository") or "")
        if not is_commit_sha(commit):
            problems.append("sourceCommit-not-a-sha")
        elif self.commit_resolver is not None:
            try:
                resolved = self.commit_resolver(repository, commit)
            except Exception as exc:  # noqa: BLE001
                problems.append("source-unresolvable:{}".format(redact(str(exc))))
            else:
                if not is_commit_sha(resolved):
                    problems.append("source-resolver-returned-a-ref")
                elif str(resolved).lower() != commit:
                    problems.append("sourceCommit-changed")
        else:
            pin = resolve_source_commit(repository, commit, None)
            if not pin["ok"]:
                problems.append("source-unresolvable:{}".format(pin["error"]))
        # re-download the APK and compare digest + size
        fetched = fetch_apk(candidate.get("apkUrl"), self.apk_fetcher)
        if not fetched["ok"]:
            problems.append("apk-unavailable:{}".format(fetched["error"]))
        else:
            if not hmac.compare_digest(str(fetched["sha256"]),
                                       str(candidate.get("apkSha256") or "")):
                problems.append("apkSha256-changed")
            if int(fetched["size"]) != int(candidate.get("apkSize") or 0):
                problems.append("apkSize-changed")
        return problems

    # -- review / accept ------------------------------------------------

    def review(self, package: str, version_code=None) -> dict:
        """Read-only. Reports the candidate, changes nothing."""
        candidate = (self.store.get(package, version_code) if version_code is not None
                     else self.store.latest(package))
        if not candidate:
            return {"ok": False, "error": "no candidate for {}".format(package)}
        return {"ok": True, "candidate": candidate, "readOnly": True}

    def review_report(self, package: str, version_code=None) -> str:
        found = self.review(package, version_code)
        if not found["ok"]:
            return "no candidate for {}".format(redact(package))
        candidate = found["candidate"]
        results = candidate.get("results") or {}

        def gate(name: str) -> str:
            record = results.get(name)
            if not record:
                return "— not run"
            return "{} · {}".format(record.get("result"), redact(record.get("detail") or ""))

        lines = [
            "📋 {}".format(candidate.get("packageName")),
            "status: {}".format(candidate.get("status")),
            "name: {}".format(candidate.get("name")),
            "version: {} ({})".format(candidate.get("versionName"), candidate.get("versionCode")),
            "contentWarning: {}".format(candidate.get("contentWarning")),
            "sourceRepository: {}".format(candidate.get("sourceRepository")),
            "sourceCommit: {}".format(candidate.get("sourceCommit")),
            "apkUrl: {}".format(candidate.get("apkUrl")),
            "apkSha256: {}".format(candidate.get("apkSha256")),
            "apkSize: {}".format(candidate.get("apkSize")),
            "discoveredAt: {}".format(candidate.get("discoveredAt")),
            "updatedAt: {}".format(candidate.get("updatedAt")),
            "replacesExisting: {} ({} / {})".format(
                bool(candidate.get("replacesExisting")),
                candidate.get("existingVersionCode") or "—",
                candidate.get("existingVersionName") or "—"),
            "",
            "validation: {}".format(gate("validation")),
            "screen: {}".format(gate("screen")),
            "sourceScan: {}".format(gate("sourceScan")),
            "apkScan: {}".format(gate("apkScan")),
            "buildTest: {}".format(gate("buildTest")),
        ]
        return "\n".join(lines)

    def accept(self, package: str, version_code=None, actor: str = "",
               is_admin: bool = False, live: bool = True) -> dict:
        """PENDING_REVIEW -> ACCEPTED. Never builds, publishes or pushes.

        ``is_admin`` defaults to **False**: an accept that carries no proof of
        administrator is refused, so a caller that forgets the argument cannot
        promote anything. The authorisation itself belongs to the caller's
        authorisation layer -- in the bot that is ``is_admin_user(user)`` for a
        Telegram sender and the ``--admin``/``--actor`` pair on the CLI -- and
        this flag only records the outcome of that check. There is no way to
        reach ACCEPTED without a caller deliberately asserting an admin.
        """
        if not is_admin:
            self.audit.record("ACCEPT", package, version_code or "", "", "", actor,
                              "REJECT", "not an administrator")
            return {"ok": False, "error": "unauthorised", "status": REJECTED}
        if not actor:
            self.audit.record("ACCEPT", package, version_code or "", "", "", actor,
                              "REJECT", "no actor supplied")
            return {"ok": False, "error": "actor required", "status": REJECTED}

        candidate = (self.store.get(package, version_code) if version_code is not None
                     else self.store.latest(package))
        if not candidate:
            self.audit.record("ACCEPT", package, version_code or "", "", "", actor,
                              "REJECT", "no candidate")
            return {"ok": False, "error": "no candidate", "status": REJECTED}

        package_name = candidate.get("packageName")
        version = candidate.get("versionCode")

        if candidate.get("status") != PENDING_REVIEW:
            reason = "status is {} not PENDING_REVIEW".format(candidate.get("status"))
            self.audit.record("ACCEPT", package_name, version, candidate.get("sourceCommit", ""),
                              candidate.get("apkSha256", ""), actor, "REJECT", reason)
            return {"ok": False, "error": reason, "status": candidate.get("status")}

        # structural re-verification
        problems = self.reverify(candidate)
        # outside-world re-verification
        if live and not problems:
            problems = self.reverify_live(candidate)

        if problems:
            detail = ",".join(problems)
            self.invalidate(candidate, detail, actor=actor)
            self.audit.record("ACCEPT", package_name, version,
                              candidate.get("sourceCommit", ""), candidate.get("apkSha256", ""),
                              actor, "REJECT", detail)
            return {"ok": False, "error": detail, "status": candidate.get("status"),
                    "invalidated": True}

        # An existing extension is only replaced by a strictly newer version, and
        # the bar is the highest *accepted* version too, never just the published
        # one: accepting 105000 and then 104003 would be a silent downgrade.
        floor, floor_source = self._published_floor(package_name, version)
        if floor is not None and int(version or 0) <= floor:
            reason = "versionCode {} is not newer than {} ({})".format(
                version, floor, floor_source)
            self.audit.record("ACCEPT", package_name, version,
                              candidate.get("sourceCommit", ""),
                              candidate.get("apkSha256", ""), actor, "REJECT", reason)
            return {"ok": False, "error": reason, "status": candidate.get("status")}

        try:
            self.machine.transition(candidate, ACCEPTED, "accepted by {}".format(actor))
        except ReviewError as exc:
            self.audit.record("ACCEPT", package_name, version, candidate.get("sourceCommit", ""),
                              candidate.get("apkSha256", ""), actor, "REJECT", str(exc))
            return {"ok": False, "error": str(exc), "status": candidate.get("status")}

        candidate["acceptedBy"] = str(actor)
        candidate["acceptedAt"] = now_ts()
        self.store.put(candidate)
        self.audit.record("ACCEPT", package_name, version, candidate.get("sourceCommit", ""),
                          candidate.get("apkSha256", ""), actor, "PASS", "accepted for review")
        return {"ok": True, "status": ACCEPTED, "candidate": candidate, "published": False}

    def _published_floor(self, package: str, own_version):
        """Highest versionCode this candidate must beat, and where it came from.

        Returns ``(None, "")`` when the package is brand new.
        """
        floor, source = None, ""
        published = self.index_lookup(package) or {}
        if published:
            try:
                floor = int(str(published.get("versionCode") or 0))
                source = "published"
            except (TypeError, ValueError):
                return None, ""
        for other in self.store.list():
            if other.get("packageName") != package or other.get("status") != ACCEPTED:
                continue
            if str(other.get("versionCode")) == str(own_version):
                continue
            try:
                code = int(other.get("versionCode") or 0)
            except (TypeError, ValueError):
                continue
            if floor is None or code > floor:
                floor, source = code, "accepted candidate"
        return floor, source

    def _reject(self, package: str, version: str, reason: str, actor: str = "pipeline",
                source_commit: str = "", apk_sha256: str = "") -> dict:
        self.audit.record("REJECT", package, version, source_commit, apk_sha256, actor,
                          "REJECT", reason)
        return {"rejected": True, "packageName": package, "versionCode": version,
                "reason": redact(reason), "status": REJECTED}

    # -- promotion (read-only) -------------------------------------------

    def published(self, package: str) -> dict:
        """The live index entry for ``package``, or ``{}``. Read-only."""
        return self.index_lookup(package) or {}

    def ready(self) -> list:
        return self.store.by_status(PENDING_REVIEW)

    def index_entry(self, candidate: dict) -> dict:
        """Build the index entry an ACCEPTED candidate *would* produce.

        Pure: it reads the stored upstream entry and never writes a file, so it
        is safe to call from tests and from the read-only review path.
        """
        extension = candidate.get("extension") or {}
        entry = json.loads(json.dumps(extension))
        entry["packageName"] = candidate.get("packageName")
        entry["versionCode"] = candidate.get("versionCode")
        entry["versionName"] = candidate.get("versionName")
        entry["name"] = candidate.get("name")
        entry["contentWarning"] = candidate.get("contentWarning")
        resources = entry.setdefault("resources", {})
        resources["apkUrl"] = candidate.get("apkUrl")
        return entry
