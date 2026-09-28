#!/usr/bin/env python3
"""Tests for the candidate review / accept pipeline.

Run with ``python3 test_review_pipeline.py`` (no network, no GitHub, no push).
The suite is intentionally a plain script rather than a pytest plugin so it can
run on the same box that runs the bot, with the same interpreter and the same
imports.

Guarantees enforced by the suite itself:

* ``repo/index.json`` is read (to prove it did not change) and never written,
* the GitHub API is never contacted -- a fake token and an unroutable API host
  are exported for the one subprocess test, so a regression that tried to push
  would fail loudly and locally instead of touching GitHub,
* every store, audit log and APK payload lives in a temporary directory.
"""

from __future__ import annotations

import ast
import contextlib
import gzip
import hashlib
import re
import inspect
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import textwrap
import threading
import time
import traceback
from pathlib import Path

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))

import review_system as rs  # noqa: E402
from review_system import (ACCEPTED, ALLOWED_TRANSITIONS, BUILT, DISCOVERED,  # noqa: E402
                           GATES, PENDING_REVIEW, QUARANTINED, REJECTED, SCANNED,
                           SCREENED, VALIDATED, AuditLog, CandidateStore, ReviewSystem,
                           is_commit_sha, is_sha256_digest)

#: A synthetic, *public* signing key, key-shaped: 512 hex characters, the length
#: of an RSA-2048 SubjectPublicKeyInfo in hex. It is not a real key and it is
#: not secret -- a public signing key is public by definition, and the private
#: key that signs APKs has no business in this repository at all.
#:
#: Set before ``bot`` is ever imported (it is imported lazily, inside tests) so
#: that a build has a usable key. The suite must not depend on whatever the
#: published repo/index.json happens to carry: that value is a 64 character
#: fingerprint, which :func:`bot.validate_signing_key` now refuses.
VALID_PUBLIC_KEY = "ab" * 256
VALID_FINGERPRINT = hashlib.sha256(bytes.fromhex(VALID_PUBLIC_KEY)).hexdigest()
os.environ.setdefault("SIGNING_KEY", VALID_PUBLIC_KEY)

COMMIT = "1" * 40
OTHER_COMMIT = "2" * 40
REPO_SLUG = "keiyoushi/extensions-source"
PACKAGE = "eu.kanade.tachiyomi.extension.en.demoext"
APK_MAGIC = b"PK\x03\x04"
CLEAN_APK = APK_MAGIC + b"AndroidManifest.xml" + b"classes.dex" * 4

CASES = []


def case(title):
    def wrap(func):
        CASES.append((title, func))
        return func
    return wrap


# ----------------------------------------------------------------- fixtures

def extension(version="104003", warning="CONTENT_WARNING_SAFE", package=PACKAGE,
              name="Demo Ext", apk_url="https://github.com/o/r/releases/download/1/a.apk",
              version_name="1.4.3"):
    return {"name": name,
            "packageName": package,
            "resources": {"apkUrl": apk_url,
                          "iconUrl": "https://cdn.jsdelivr.net/gh/o/r@main/i.png",
                          "jarUrl": "https://github.com/o/r/releases/download/1/a.jar"},
            "extensionLib": "1.4", "versionCode": version, "versionName": version_name,
            "contentWarning": warning,
            "sources": [{"id": "1234567890123456789", "name": name, "language": "en",
                         "homeUrl": "https://example.org",
                         "mirrorUrls": ["https://m.example.org"]},
                        {"id": "2", "name": name + " AR", "language": "ar"}]}


class Rig:
    """A complete, fully injected pipeline living inside a temp directory."""

    def __init__(self, tmp: Path, *, published=None, allow_mixed=False,
                 build_ok=True, source_findings=None, commit=COMMIT,
                 source_ref="main", apk_url=None, warning="CONTENT_WARNING_SAFE",
                 asset_probe=None, resolve_override=None):
        self.tmp = tmp
        self.store = CandidateStore(tmp / "candidates.json")
        self.audit = AuditLog(tmp / "audit.jsonl")
        self.published = dict(published or {})
        self.apk = {"payload": CLEAN_APK}
        self.commit = {"value": commit}
        self.build_ok = build_ok
        self.source_findings = list(source_findings or [])
        self.apk_url = apk_url
        self.warning = warning
        self.allow_mixed = allow_mixed
        self.resolve_override = resolve_override
        self.system = ReviewSystem(
            self.store, self.audit,
            apk_fetcher=self.fetch,
            commit_resolver=self.resolve,
            deep_screen=self.deep_screen,
            build_tester=self.build_test,
            index_lookup=lambda package: dict(self.published.get(package) or {}),
            source_scanner=self.source_scan,
            asset_probe=asset_probe,
            allow_mixed=allow_mixed,
        )

    # -- injected collaborators
    def fetch(self, url):
        if not str(url).startswith("https://"):
            raise ValueError("plaintext http is refused")
        return self.apk["payload"]

    def resolve(self, repository, ref):
        if self.resolve_override is not None:
            return self.resolve_override(repository, ref)
        if ref == self.commit["value"]:
            return self.commit["value"]
        if ref == "main":
            return self.commit["value"]
        raise RuntimeError("git {}:{} unknown".format(repository, ref))

    def deep_screen(self, ext):
        """Mirrors bot.nsfw_reason, including its ALLOW_MIXED behaviour."""
        warning = str(ext.get("contentWarning") or "").upper()
        if "NSFW" in warning:
            return "contentWarning=NSFW"
        if "MIXED" in warning and not self.allow_mixed:
            return "contentWarning=MIXED"
        return ""

    @staticmethod
    def build_test(candidate):
        if not candidate:
            return False, "no candidate"
        return True, "offline metadata build ok"

    def source_scan(self, candidate):
        return {"findings": list(self.source_findings), "scanned": 4}

    # -- helpers
    def discover(self, **kwargs):
        ext = extension(apk_url=self.apk_url or extension()["resources"]["apkUrl"],
                        warning=self.warning)
        source_ref = kwargs.pop("source_ref", "main")
        candidate = self.system.discover(ext, REPO_SLUG, source_ref, actor="test", **kwargs)
        return self.system.process(candidate) if not candidate.get("rejected") else candidate

    def pending(self, **kwargs):
        candidate = self.discover(**kwargs)
        assert candidate.get("status") == PENDING_REVIEW, candidate
        return candidate

    def snapshot(self):
        files = {}
        for path in sorted(self.tmp.glob("*")):
            if path.is_file():
                files[path.name] = path.read_bytes()
        return files


def deep_screen(ext):
    """Standalone screen used where a bare policy check is enough."""
    warning = str(ext.get("contentWarning") or "").upper()
    if "NSFW" in warning:
        return "contentWarning=NSFW"
    if "MIXED" in warning:
        return "contentWarning=MIXED"
    return ""


def rekey(store, package, version, **changes):
    """Rewrite a stored candidate under a new identity, as a tamperer would."""
    old = CandidateStore.key(package, version)
    with store.lock:
        candidate = json.loads(json.dumps(store.candidates.pop(old)))
        new_package = changes.pop("packageName", candidate["packageName"])
        new_version = changes.pop("versionCode", candidate["versionCode"])
        candidate.update(changes)
        candidate["packageName"] = new_package
        candidate["versionCode"] = new_version
        store.candidates[CandidateStore.key(new_package, new_version)] = candidate
        store.save()
    return candidate


def index_digest():
    return hashlib.sha256((ROOT / "repo/index.json").read_bytes()).hexdigest()


def index_count():
    return len(json.loads((ROOT / "repo/index.json").read_text(encoding="utf-8"))
               ["extensionList"]["extensions"])


def rig(**kwargs):
    tmp = Path(tempfile.mkdtemp(prefix="manga-rig-"))
    Rig.CURRENT = Rig(tmp, **kwargs)
    return Rig.CURRENT


# ----------------------------------------------------------------- 1..24

@case("01 discovery drives a candidate to PENDING_REVIEW")
def test_discovery_to_pending():
    riggy = rig()
    candidate = riggy.pending()
    for field in ("packageName", "name", "versionCode", "versionName", "sourceRepository",
                  "sourceCommit", "apkUrl", "apkSha256", "apkSize", "contentWarning",
                  "validationResult", "screenResult", "sourceSecurityScanResult",
                  "apkBinaryScanResult", "buildTestResult", "discoveredAt", "updatedAt",
                  "status"):
        assert field in candidate, "candidate is missing " + field
    assert candidate["status"] == PENDING_REVIEW
    assert is_commit_sha(candidate["sourceCommit"])
    assert is_sha256_digest(candidate["apkSha256"])
    assert candidate["apkSize"] == len(CLEAN_APK)
    assert candidate["apkSha256"] == hashlib.sha256(CLEAN_APK).hexdigest()
    assert all(candidate["results"][gate]["result"] == "PASS" for gate in GATES)
    assert [step["to"] for step in candidate["history"]] == [
        VALIDATED, SCREENED, SCANNED, BUILT, PENDING_REVIEW]


@case("02 --review is read-only")
def test_review_read_only():
    riggy = rig()
    riggy.pending()
    before_store = (riggy.tmp / "candidates.json").read_bytes()
    before_audit = (riggy.tmp / "audit.jsonl").read_bytes()
    for _ in range(3):
        riggy.system.review(PACKAGE)
        report = riggy.system.review_report(PACKAGE)
    assert PACKAGE in report and "PENDING_REVIEW" in report
    assert (riggy.tmp / "candidates.json").read_bytes() == before_store
    assert (riggy.tmp / "audit.jsonl").read_bytes() == before_audit
    assert riggy.store.latest(PACKAGE)["status"] == PENDING_REVIEW


@case("03 a valid accept moves PENDING_REVIEW -> ACCEPTED")
def test_accept_valid():
    riggy = rig()
    riggy.pending()
    result = riggy.system.accept(PACKAGE, actor="tester", is_admin=True)
    assert result["ok"] is True, result
    assert result["status"] == ACCEPTED
    assert result.get("published") is False
    stored = riggy.store.latest(PACKAGE)
    assert stored["status"] == ACCEPTED and stored["acceptedBy"] == "tester"
    actions = [entry["action"] for entry in riggy.audit.entries()]
    assert actions[-1] == "ACCEPT" and riggy.audit.entries()[-1]["result"] == "PASS"


@case("04 accept refuses a different APK sha256")
def test_apk_sha_drift():
    riggy = rig()
    riggy.pending()
    riggy.apk["payload"] = CLEAN_APK + b"tampered"
    result = riggy.system.accept(PACKAGE, actor="tester", is_admin=True)
    assert result["ok"] is False
    assert "apkSha256-changed" in result["error"], result
    assert riggy.store.latest(PACKAGE)["status"] == DISCOVERED
    assert riggy.store.latest(PACKAGE)["results"] == {}


@case("05 accept refuses a different APK size")
def test_apk_size_drift():
    riggy = rig()
    riggy.pending()
    # a tamperer who also refreshes the reviewed-provenance fingerprint, so only
    # the live comparison against the real file can catch this
    candidate = rekey(riggy.store, PACKAGE, "104003", apkSize=len(CLEAN_APK) + 7)
    candidate["verifiedProvenance"] = rs.provenance_fingerprint(candidate)
    riggy.store.put(candidate)
    result = riggy.system.accept(PACKAGE, actor="tester", is_admin=True)
    assert result["ok"] is False
    assert "apkSize-changed" in result["error"], result
    assert riggy.store.latest(PACKAGE)["status"] == DISCOVERED


@case("06 accept refuses a different sourceCommit")
def test_source_commit_drift():
    riggy = rig()
    riggy.pending()
    # upstream rewrote history: the pinned sha now resolves to a different object
    riggy.resolve_override = lambda repository, ref: OTHER_COMMIT
    result = riggy.system.accept(PACKAGE, actor="tester", is_admin=True)
    assert result["ok"] is False
    assert "sourceCommit-changed" in result["error"], result
    assert riggy.store.latest(PACKAGE)["status"] == DISCOVERED
    assert riggy.store.latest(PACKAGE)["sourceCommit"] == COMMIT

    # and a commit that no longer resolves at all is refused too
    gone = rig()
    gone.pending()

    def vanished(repository, ref):
        raise RuntimeError("404 no such commit")

    gone.resolve_override = vanished
    result = gone.system.accept(PACKAGE, actor="tester", is_admin=True)
    assert result["ok"] is False and "source-unresolvable" in result["error"], result


@case("07 a branch name instead of a commit sha is refused")
def test_branch_instead_of_sha():
    riggy = rig(commit="main")  # the resolver hands back a moving ref
    rejected = riggy.discover()
    assert rejected.get("rejected") is True
    assert rejected["status"] == REJECTED
    assert "unpinned-source" in rejected["reason"]
    assert riggy.store.latest(PACKAGE)["status"] == REJECTED
    assert riggy.system.accept(PACKAGE, actor="tester", is_admin=True)["ok"] is False

    # and a candidate whose stored sourceCommit is a branch can never be accepted
    other = rig(commit=COMMIT)
    other.pending()
    rekey(other.store, PACKAGE, "104003", sourceCommit="main")
    result = other.system.accept(PACKAGE, actor="tester", is_admin=True, live=False)
    assert result["ok"] is False and "sourceCommit-not-a-sha" in result["error"], result


@case("08 accept refuses a different packageName")
def test_package_name_drift():
    riggy = rig()
    riggy.pending()
    rekey(riggy.store, PACKAGE, "104003", packageName="eu.evil.impersonated")
    result = riggy.system.accept("eu.evil.impersonated", actor="tester", is_admin=True)
    assert result["ok"] is False
    assert "provenance-changed" in result["error"], result
    assert riggy.store.latest("eu.evil.impersonated")["status"] == DISCOVERED


@case("09 accept refuses a different versionCode")
def test_version_code_drift():
    riggy = rig()
    riggy.pending()
    rekey(riggy.store, PACKAGE, "104003", versionCode="999999")
    result = riggy.system.accept(PACKAGE, version_code="999999", actor="tester",
                                 is_admin=True)
    assert result["ok"] is False
    assert "provenance-changed" in result["error"], result
    assert riggy.store.latest(PACKAGE)["status"] == DISCOVERED


@case("10 accept refuses a missing build/test result")
def test_missing_build():
    riggy = rig()
    riggy.pending()
    drop_gate(riggy, "buildTest")
    result = riggy.system.accept(PACKAGE, actor="tester", is_admin=True)
    assert result["ok"] is False and "missing-gate:buildTest" in result["error"], result


@case("11 accept refuses a missing source security scan")
def test_missing_source_scan():
    riggy = rig()
    riggy.pending()
    drop_gate(riggy, "sourceScan")
    result = riggy.system.accept(PACKAGE, actor="tester", is_admin=True)
    assert result["ok"] is False and "missing-gate:sourceScan" in result["error"], result


@case("12 accept refuses a missing APK binary scan")
def test_missing_apk_scan():
    riggy = rig()
    riggy.pending()
    drop_gate(riggy, "apkScan")
    result = riggy.system.accept(PACKAGE, actor="tester", is_admin=True)
    assert result["ok"] is False and "missing-gate:apkScan" in result["error"], result


def drop_gate(riggy, gate):
    candidate = riggy.store.latest(PACKAGE)
    candidate["results"].pop(gate, None)
    riggy.store.put(candidate)


@case("13 an NSFW extension is rejected")
def test_nsfw_rejected():
    riggy = rig(warning="CONTENT_WARNING_NSFW")
    candidate = riggy.discover()
    assert candidate["status"] == REJECTED
    assert "contentWarning=NSFW" in candidate["results"]["screen"]["detail"]
    assert riggy.system.accept(PACKAGE, actor="tester", is_admin=True)["ok"] is False


@case("14 a MIXED extension is rejected (strict policy)")
def test_mixed_rejected():
    riggy = rig(warning="CONTENT_WARNING_MIXED")
    candidate = riggy.discover()
    assert candidate["status"] == REJECTED
    assert "contentWarning=MIXED" in candidate["results"]["screen"]["detail"]
    assert riggy.system.accept(PACKAGE, actor="tester", is_admin=True)["ok"] is False
    # ...and the same extension is acceptable when the operator opts in
    lenient = rig(warning="CONTENT_WARNING_MIXED", allow_mixed=True)
    assert lenient.discover()["status"] == PENDING_REVIEW


@case("15 an unauthorised Telegram user cannot drive /accept or /review")
def test_unauthorised_telegram_user():
    import bot
    sent = []

    class FakeTelegram:
        def send(self, chat_id, text, markup=None):
            sent.append((chat_id, text))

        def answer(self, callback_id, text=""):
            return True

    calls = []
    saved = (bot.REVIEW.accept, bot.REVIEW.review_report, bot.ADMIN_IDS)
    bot.ADMIN_IDS = {900001}
    bot.REVIEW.accept = lambda *a, **k: calls.append("accept") or {"ok": True}
    bot.REVIEW.review_report = lambda *a, **k: calls.append("review") or "report"
    try:
        for command in ("/accept " + PACKAGE, "/review " + PACKAGE, "/publish", "/scan"):
            sent.clear()
            bot.handle_message(FakeTelegram(), telegram_message(command, user_id=424242))
            assert sent and "للمشرفين" in sent[0][1], (command, sent)
        assert calls == [], "a non-admin reached the pipeline: " + repr(calls)

        # the same command from the configured admin id does reach the pipeline
        sent.clear()
        bot.handle_message(FakeTelegram(), telegram_message("/review " + PACKAGE, user_id=900001))
        assert calls == ["review"], calls

        # usernames never authorise, only the numeric id
        calls.clear()
        bot.handle_message(FakeTelegram(), telegram_message("/review " + PACKAGE, user_id=1,
                                                            username="owner"))
        assert calls == [], calls
    finally:
        bot.REVIEW.accept, bot.REVIEW.review_report, bot.ADMIN_IDS = saved


@case("16 an unauthorised callback cannot ban/allow/quarantine")
def test_unauthorised_callback():
    import bot
    saved_path, bot.STATE.path = bot.STATE.path, Path(tempfile.mkdtemp()) / "state.json"
    saved_admins, saved_watch = bot.ADMIN_IDS, bot.WATCH_CHATS
    bot.ADMIN_IDS, bot.WATCH_CHATS = {900001}, {-1001}
    try:
        bot.STATE.put_report({"token": "tok", "created_at": time.time(), "user_id": 7,
                              "user_name": "spammer", "chat_id": -1001, "chat_title": "g",
                              "message_id": 5, "reason": "t",
                              "links": ["https://bad.example"],
                              "domains": ["bad.example"], "excerpt": "x"})
        for actor in ({"id": 424242, "username": "owner"}, {"id": 1}, {}, None,
                      {"id": 900001, "is_bot": True}):
            query = {"id": "cb", "data": "ban:tok",
                     "message": {"chat": {"id": -1001}, "message_id": 9}}
            if actor is not None:
                query["from"] = actor
            bot.handle_decision(FakeTelegramCallbacks(), query)
            assert not bot.STATE.domain_blocked("bad.example"), actor
            assert bot.STATE.take_report("tok") is not None, actor
        # only the numeric admin id works
        bot.handle_decision(FakeTelegramCallbacks(),
                            {"id": "cb", "data": "ban:tok", "from": {"id": 900001},
                             "message": {"chat": {"id": -1001}, "message_id": 9}})
        assert bot.STATE.domain_blocked("bad.example")
    finally:
        bot.STATE.clear()  # still pointed at the sandbox: never writes the live file
        bot.STATE.path = saved_path
        bot.ADMIN_IDS, bot.WATCH_CHATS = saved_admins, saved_watch


class FakeTelegramCallbacks:
    def __init__(self):
        self.answers, self.edits = [], []

    def answer(self, callback_id, text=""):
        self.answers.append(text)
        return True

    def edit(self, chat_id, message_id, text, markup=None):
        self.edits.append(text)
        return True

    def ban(self, chat_id, user_id):
        return True

    def delete(self, chat_id, message_id):
        return True


def telegram_message(text, user_id, username="someone"):
    return {"chat": {"id": -1001, "title": "Group", "type": "supergroup"},
            "from": {"id": user_id, "username": username, "first_name": "X"},
            "message_id": 3, "text": text, "entities": []}


@case("17 accept does not touch repo/index.json")
def test_accept_leaves_index():
    import bot
    before = index_digest()
    riggy = rig(published={PACKAGE: extension(version="100000", version_name="1.0.0")})
    riggy.pending()
    result = riggy.system.accept(PACKAGE, actor="tester", is_admin=True)
    assert result["ok"] is True, result
    assert result.get("published") is False
    assert index_digest() == before
    assert riggy.published[PACKAGE]["versionCode"] == "100000"
    # nothing in the shipped repository files changed either
    assert bot.INDEX_JSON.read_bytes() == (ROOT / "repo/index.json").read_bytes()


@case("18 accept does not build or publish")
def test_accept_does_not_publish():
    import bot
    calls = []
    saved = (bot.REPO.build, bot.REPO.publish, bot.REPO.push)
    bot.REPO.build = lambda *a, **k: calls.append("build") or {"ok": True, "changed": []}
    bot.REPO.publish = lambda *a, **k: calls.append("publish") or {"ok": True}
    bot.REPO.push = lambda *a, **k: calls.append("push") or {"ok": True}
    try:
        riggy = rig()
        riggy.pending()
        riggy.system.review_report(PACKAGE)
        assert riggy.system.accept(PACKAGE, actor="tester", is_admin=True)["ok"] is True
        assert calls == [], calls
    finally:
        bot.REPO.build, bot.REPO.publish, bot.REPO.push = saved


@case("19 accept does not push to GitHub")
def test_accept_does_not_push():
    import bot
    calls = []
    saved = (bot.REPO.push, bot.REPO.publish)
    bot.REPO.push = lambda *a, **k: calls.append("push") or {"ok": True, "pushed": []}
    bot.REPO.publish = lambda *a, **k: calls.append("publish") or {"ok": True, "pushed": []}
    bot.TEST_MODE = True  # a real push would raise instead of leaving the process
    try:
        riggy = rig()
        riggy.pending()
        assert riggy.system.accept(PACKAGE, actor="tester", is_admin=True)["ok"] is True
        assert calls == [], calls
    finally:
        bot.REPO.push, bot.REPO.publish = saved
        bot.TEST_MODE = env_test_mode()


@case("20 --selftest does not modify repo/index.json")
def test_selftest_leaves_index():
    before = (index_digest(), index_count())
    result = run_selftest()
    assert result.returncode == 0, result.stdout[-3000:] + result.stderr[-2000:]
    assert "selftest: PASSED" in result.stdout
    assert (index_digest(), index_count()) == before


@case("21 --selftest never pushes")
def test_selftest_does_not_push():
    result = run_selftest(fake_token=True)
    output = result.stdout + result.stderr
    assert result.returncode == 0
    for marker in ("pushed repo/", "published 0 file(s)", "published 1 file(s)",
                   "push repo/index.json attempt", "push repo.json attempt",
                   "Traceback", "Max retries exceeded", "Connection refused"):
        assert marker not in output, marker
    assert "refused" in result.stdout


@case("22 candidates survive a restart")
def test_candidate_persistence():
    riggy = rig()
    riggy.pending()
    path = riggy.tmp / "candidates.json"
    revived = CandidateStore(path)
    stored = revived.latest(PACKAGE)
    assert stored["status"] == PENDING_REVIEW
    assert stored["apkSha256"] == hashlib.sha256(CLEAN_APK).hexdigest()
    assert stored["sourceCommit"] == COMMIT
    assert all(stored["results"][gate]["result"] == "PASS" for gate in GATES)
    # and the revived store still enforces the pipeline
    assert revived.latest(PACKAGE)["results"]["apkScan"]["digest"]
    riggy.apk["payload"] = CLEAN_APK + b"x"
    result = riggy.system.accept(PACKAGE, actor="tester", is_admin=True)
    assert result["ok"] is False
    assert CandidateStore(path).latest(PACKAGE)["status"] == DISCOVERED


@case("23 the audit log survives a restart and holds no secrets")
def test_audit_persistence():
    riggy = rig()
    riggy.pending()
    riggy.system.accept(PACKAGE, actor="tg:900001", is_admin=True)
    revived = AuditLog(riggy.tmp / "audit.jsonl")
    entries = revived.entries()
    assert entries, "audit log is empty"
    for entry in entries:
        for field in ("timestamp", "action", "packageName", "versionCode", "sourceCommit",
                      "apkSha256", "actor", "result", "reason"):
            assert field in entry, field
    assert entries[0]["timestamp"] <= time.time()
    actions = [entry["action"] for entry in entries]
    assert actions[0] == "DISCOVER" and actions[-1] == "ACCEPT"
    assert entries[-1]["action"] == "ACCEPT" and entries[-1]["actor"] == "tg:900001"
    assert entries[-1]["apkSha256"] == hashlib.sha256(CLEAN_APK).hexdigest()

    # a secret smuggled through a reason must never be written to disk
    riggy.audit.record("TEST", PACKAGE, "1", COMMIT, "", "actor",
                       "PASS", "token=ghp_" + "A" * 30 + " secret=123456789:AA" + "b" * 35)
    body = (riggy.tmp / "audit.jsonl").read_text(encoding="utf-8")
    assert "ghp_" not in body and "123456789:AA" not in body
    assert "[REDACTED]" in body
    assert [item for item in AuditLog(riggy.tmp / "audit.jsonl").entries()
            if item["action"] == "TEST"]


@case("24 an already published package gets its own candidate, never an overwrite")
def test_existing_package_separate_candidate():
    published = extension(version="100000", version_name="1.0.0")
    riggy = rig(published={PACKAGE: published})
    older = riggy.discover(source_ref="main")
    assert older["status"] == PENDING_REVIEW
    newer_ext = extension(version="105000", version_name="1.5.0")
    newer = riggy.system.discover(newer_ext, REPO_SLUG, "main", actor="test")
    newer = riggy.system.process(newer)
    assert newer["status"] == PENDING_REVIEW
    assert newer["replacesExisting"] is True
    assert newer["existingVersionCode"] == "100000"
    keys = sorted(riggy.store.candidates)
    assert keys == [CandidateStore.key(PACKAGE, "104003"), CandidateStore.key(PACKAGE, "105000")]
    assert riggy.published[PACKAGE]["versionCode"] == "100000"  # the index view is untouched

    # the new version is acceptable ...
    assert riggy.system.accept(PACKAGE, version_code="105000", actor="tester",
                               is_admin=True)["ok"] is True
    # ...and acceptance still did not rewrite anything
    assert riggy.published[PACKAGE]["versionCode"] == "100000"
    # an older/equal version is refused even if it reaches PENDING_REVIEW
    stale = riggy.discover()
    assert stale["status"] == PENDING_REVIEW
    result = riggy.system.accept(PACKAGE, version_code="104003", actor="tester",
                                 is_admin=True)
    assert result["ok"] is False and "not newer" in result["error"], result


# ----------------------------------------------------------------- extras

@case("25 the state machine refuses DISCOVERED -> ACCEPTED and any other skip")
def test_no_state_skipping():
    for source, target in ((DISCOVERED, ACCEPTED), (DISCOVERED, PENDING_REVIEW),
                           (DISCOVERED, BUILT), (VALIDATED, ACCEPTED),
                           (SCREENED, PENDING_REVIEW), (SCANNED, PENDING_REVIEW),
                           (BUILT, ACCEPTED), (REJECTED, ACCEPTED),
                           (QUARANTINED, ACCEPTED), (ACCEPTED, PENDING_REVIEW),
                           (ACCEPTED, BUILT)):
        candidate = rs.blank_candidate()
        candidate["status"] = source
        candidate["results"] = {gate: {"result": "PASS", "digest": "0" * 64} for gate in GATES}
        try:
            rs.SM.transition(candidate, target, "test")
        except rs.IllegalTransition:
            continue
        raise AssertionError("{} -> {} was allowed".format(source, target))
    assert ACCEPTED not in ALLOWED_TRANSITIONS[DISCOVERED]
    assert PENDING_REVIEW in ALLOWED_TRANSITIONS[BUILT]


@case("26 tampered gate evidence is detected before accept")
def test_gate_tamper_detected():
    riggy = rig()
    riggy.pending()
    candidate = riggy.store.latest(PACKAGE)
    candidate["results"]["apkScan"]["detail"] = "0 findings (edited by hand)"
    riggy.store.put(candidate)
    result = riggy.system.accept(PACKAGE, actor="tester", is_admin=True)
    assert result["ok"] is False and "modified after it ran" in result["error"], result


@case("27 accept requires an admin and an actor")
def test_accept_requires_actor():
    riggy = rig()
    riggy.pending()
    assert riggy.system.accept(PACKAGE, actor="x", is_admin=False)["ok"] is False
    assert riggy.system.accept(PACKAGE, actor="", is_admin=True)["ok"] is False
    assert riggy.store.latest(PACKAGE)["status"] == PENDING_REVIEW
    refused = [entry for entry in riggy.audit.entries()
               if entry["action"] == "ACCEPT" and entry["result"] == "REJECT"]
    assert len(refused) == 2, refused


@case("28 a rejected candidate cannot be resurrected by accept")
def test_rejected_is_terminal():
    riggy = rig(warning="CONTENT_WARNING_NSFW")
    riggy.discover()
    for _ in range(2):
        riggy.system.process(riggy.store.latest(PACKAGE))
    result = riggy.system.accept(PACKAGE, actor="tester", is_admin=True)
    assert result["ok"] is False and result["status"] == REJECTED


@case("29 an APK over plain http is refused at discovery")
def test_http_apk_refused():
    riggy = rig(apk_url="http://example.org/a.apk")
    candidate = riggy.discover()
    assert candidate.get("rejected") is True
    assert "https" in candidate["reason"] or "apk-integrity" in candidate["reason"]
    assert riggy.store.latest(PACKAGE)["status"] == REJECTED


@case("30 the published index is unchanged by the whole suite")
def test_index_untouched():
    assert index_count() == 579, index_count()
    assert index_digest() == INDEX_SHA_AT_IMPORT, "repo/index.json changed during the run"
    payload = json.loads((ROOT / "repo/index.json").read_text(encoding="utf-8"))
    assert payload["signingKey"] == SIGNING_KEY_AT_IMPORT
    for path, stamp in LIVE_FILES.items():
        now = (path.stat().st_mtime_ns, path.stat().st_size) if path.is_file() else None
        assert now == stamp, "{} was touched by the suite".format(path)


@case("31 a dead asset and a blocked URL each reject the candidate")
def test_asset_and_url_gates():
    import bot
    # the production screen gate is URL screening + NSFW, not NSFW alone
    assert bot.screen_gate({"extension": extension(apk_url="https://10.0.0.5/a.apk")}) == \
        "url:ip-literal-host"
    assert bot.screen_gate({"extension": extension(
        apk_url="https://mangadex-hack.ru/a.apk")}).startswith("url:brand-typosquat")
    assert bot.screen_gate({"extension": extension()}) == ""

    tmp = Path(tempfile.mkdtemp(prefix="manga-rig-"))
    Rig.CURRENT = riggy = Rig(tmp, asset_probe=lambda url: "404")
    candidate = riggy.discover()
    assert candidate.get("rejected") is True, candidate
    assert "asset-iconUrl" in candidate["reason"], candidate
    assert riggy.store.latest(PACKAGE)["status"] == REJECTED
    assert riggy.system.accept(PACKAGE, actor="tester", is_admin=True)["ok"] is False


# ----------------------------------------------------------------- 32..42
# The legacy paths. Each one used to be able to reach repo/index.json or GitHub
# without going through Candidate -> Review -> manual accept; these cases pin
# that they can no longer do it, and they do it by *running* the path, not by
# reading it.

def _fake_upstream(package=PACKAGE, version="999999"):
    return [dict(extension(), packageName=package, versionCode=version)]


def _fake_discovery(bot):
    def discover(entry, **kwargs):
        return bot.CANDIDATES.put(fake_candidate(entry["packageName"],
                                                 entry["versionCode"]))
    return discover


def _published_files(bot):
    return {path: path.read_bytes() for path in (bot.INDEX_JSON, bot.INDEX_MIN_JSON,
                                                 bot.INDEX_PB, bot.REPO_JSON)
            if path.is_file()}


def _raise_no_network():
    raise AssertionError("a request left the process")


class _StoreShim:
    """Adapts the rig's store to the store API ``bot``'s helpers expect.

    ``prepare_publish()`` reads the live ``bot.CANDIDATES`` to report what is
    ready, so a rig test points the module at the rig's own candidates rather
    than at the (sandboxed, empty) live store.
    """

    def __init__(self, store):
        self.store = store

    def by_status(self, status):
        return self.store.by_status(status)

    def latest(self, package):
        return self.store.latest(package)

    def get(self, package, version_code=None):
        return self.store.get(package, version_code)

    def list(self):
        return self.store.list()


class _TripwireSession:
    """Stands in for ``requests.Session`` and reports *any* request.

    ``bot.http_session()`` is patched to return one of these, so a regression that
    got past the guards would be caught here: the call is recorded in ``seen``
    and then handed to ``trap``, which fails the test. A silently-skipped
    tripwire would prove nothing, so the session is deliberately tiny and total.
    """

    def __init__(self, trap, seen):
        self.trap = trap
        self.seen = seen
        self.headers = {}

    def _method(self, verb):
        def call(url, *args, **kwargs):
            self.seen.append((verb, str(url)))
            return self.trap(verb, str(url))
        return call

    def __getattr__(self, name):
        verb = name.upper()
        if verb in ("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD"):
            return self._method(verb)
        raise AttributeError(name)


def _cli_flags():
    """Every ``--flag`` bot.py's argparse actually defines, read from the source.

    Derived from the AST rather than from ``main()``'s help text, so it is the
    literal set of flags a user could type -- and so ``--publish`` cannot appear
    in it without the test noticing.
    """
    tree = ast.parse((ROOT / "bot.py").read_text(encoding="utf-8"))
    flags = set()
    for node in ast.walk(tree):
        if (isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
                and node.func.attr == "add_argument"):
            for arg in node.args:
                if isinstance(arg, ast.Constant) and isinstance(arg.value, str):
                    if arg.value.startswith("--"):
                        flags.add(arg.value)
    return flags


@case("32 job_harvest creates candidates and never publishes")
def test_job_harvest_never_publishes():
    import bot
    calls = []
    before = _published_files(bot)
    with sandbox_state(), RecordingRepo(calls):
        with patched(bot.REPO, fetch_upstream=lambda: _fake_upstream(),
                     discover_candidate=_fake_discovery(bot)):
            bot.job_harvest()
            # the job really did run: a candidate is waiting for review ...
            stored = bot.CANDIDATES.latest(PACKAGE)
            assert stored["status"] == PENDING_REVIEW, stored
            assert bot.STATE.counters["harvests"] == 1
            # ... and it reached neither build, publish nor push
            assert_no_publish(calls)
            assert calls == [], calls
            assert all(path.read_bytes() == blob for path, blob in before.items())
            assert index_digest() == INDEX_SHA_AT_IMPORT
    assert index_digest() == INDEX_SHA_AT_IMPORT


@case("33 job_health records health and never publishes")
def test_job_health_never_publishes():
    import bot
    calls = []
    before = _published_files(bot)
    with sandbox_state(), RecordingRepo(calls), patched(bot, DEAD_THRESHOLD=1):
        in_memory = json.dumps(bot.REPO.index, sort_keys=True)
        with patched(bot.REPO, probe=lambda url: ("dead", "http-404")):
            bot.job_health()
        assert_no_publish(calls)
        assert calls == [], calls
        # results went to state, and the sweep staged a proposal instead of acting
        assert bot.STATE.health, "no health record was stored"
        assert bot.STATE.staged_proposals(), "no proposal was staged for review"
        assert json.dumps(bot.REPO.index, sort_keys=True) == in_memory
        assert all(path.read_bytes() == blob for path, blob in before.items())
    assert index_digest() == INDEX_SHA_AT_IMPORT


@case("34 job_publish is disabled: a dry-run readiness report, not a publish")
def test_job_publish_disabled():
    import bot
    calls = []
    before = _published_files(bot)
    with sandbox_state(), RecordingRepo(calls):
        # off by default: the job does not even build
        bot.job_publish()
        assert calls == [], calls
        # switched on it is still only a sandboxed dry-run
        with patched(bot, PUBLISH_READINESS_ENABLED=True):
            bot.job_publish()
        assert_no_publish(calls)
        assert calls == ["build-sandbox"], calls
        # the only publish entry point in the process refuses, and reaches nothing
        refused = bot.run_publish_phase("tester", "probe")
        assert refused.get("blocked") is True and refused.get("pushed") == [], refused
        assert calls == ["build-sandbox", "publish_phase"], calls
        assert bot.publish_authorized() is False
        assert all(path.read_bytes() == blob for path, blob in before.items())

    # and the scheduler does not register CRON_PUBLISH while the flag is off
    assert bot.PUBLISH_READINESS_ENABLED is False
    scheduled = [name for name, _, _ in _scheduled_jobs(bot, False)]
    assert "publish-readiness" not in scheduled, scheduled
    assert "publish-readiness" in [name for name, _, _ in _scheduled_jobs(bot, True)]
    assert _scheduled_jobs(bot, True)[-1][2] is bot.job_publish
    assert _scheduled_jobs(bot, True)[-1][1] == bot.CRON_PUBLISH

    # the real publish() is refused by the authorisation, not merely by TEST_MODE,
    # and so is a build
    with sandbox_state(), patched(bot, PUBLISH_MODE="allow", PUSH_ENABLED=True,
                                  TEST_MODE=False):
        result = bot.REPO.publish()
        assert result.get("blocked") is True and not result.get("pushed"), result
        assert "authorisation" in result.get("skipped", ""), result
        try:
            bot.REPO.push(["repo.json"])
            raise AssertionError("push was not refused")
        except bot.PushBlocked as exc:
            assert "authorisation" in str(exc), exc
        try:
            bot.REPO.build()
            raise AssertionError("a real build was not refused")
        except bot.PushBlocked as exc:
            assert "authorisation" in str(exc), exc
        # holding the key opens exactly one door, and only for the manual phase
        with bot.PublishAuthorization("tester", "manual phase"):
            assert bot.publish_authorized() is True
            bot.assert_may_push("probe")           # the only probe: it sends nothing
            bot.assert_may_write_repo("probe")
        assert bot.publish_authorized() is False
        try:
            bot.assert_may_push("probe")
            raise AssertionError("the key was not lowered on exit")
        except bot.PushBlocked:
            pass
        assert all(path.read_bytes() == blob for path, blob in before.items())
    assert index_digest() == INDEX_SHA_AT_IMPORT


@case("35 /scan is read-only for the published repository")
def test_scan_never_modifies_published_index():
    import bot
    calls = []
    tg = FakeTelegram()
    before = _published_files(bot)
    with sandbox_state(), RecordingRepo(calls):
        saved_admins = bot.ADMIN_IDS
        bot.ADMIN_IDS = {900001}
        try:
            with patched(bot.REPO, fetch_upstream=lambda: _fake_upstream(),
                         discover_candidate=_fake_discovery(bot)):
                bot.run_command(tg, telegram_message("/scan", 900001), "/scan")
        finally:
            bot.ADMIN_IDS = saved_admins
        stored = bot.CANDIDATES.latest(PACKAGE)
        assert stored["status"] == PENDING_REVIEW, stored
        assert_no_publish(calls)
        assert calls == [], calls
        assert all(path.read_bytes() == blob for path, blob in before.items())
        assert index_digest() == INDEX_SHA_AT_IMPORT
    assert "لم يُعدّل" in " ".join(tg.bodies())
    assert "repos" not in " ".join(tg.bodies()).lower()


@case("36 /health stages proposals in state/data and never touches the index")
def test_health_never_modifies_published_index():
    import bot
    calls = []
    tg = FakeTelegram()
    before = _published_files(bot)
    with sandbox_state(), RecordingRepo(calls), patched(bot, DEAD_THRESHOLD=1):
        in_memory = json.dumps(bot.REPO.index, sort_keys=True)
        saved_admins = bot.ADMIN_IDS
        bot.ADMIN_IDS = {900001}
        try:
            with patched(bot.REPO, probe=lambda url: ("dead", "http-404")):
                bot.run_command(tg, telegram_message("/health", 900001), "/health")
        finally:
            bot.ADMIN_IDS = saved_admins
        # the sweep found dead sources and recorded them ...
        assert bot.STATE.health, "no health record was stored"
        proposals = bot.STATE.staged_proposals()
        assert proposals["quarantine"] or proposals["source_drops"], proposals
        # ... without changing the published index in memory or on disk
        assert json.dumps(bot.REPO.index, sort_keys=True) == in_memory
        assert_no_publish(calls)
        assert calls == [], calls
        assert all(path.read_bytes() == blob for path, blob in before.items())
        assert index_digest() == INDEX_SHA_AT_IMPORT
    assert "لم يُعدّل" in " ".join(tg.bodies())


@case("37 --harvest on the CLI produces candidates and never publishes")
def test_cli_harvest_never_publishes():
    import bot
    calls = []
    before = _published_files(bot)
    saved_argv, saved_tg = sys.argv, bot.TG
    bot.TG = None
    buffer = io.StringIO()
    try:
        with sandbox_state(), RecordingRepo(calls):
            with patched(bot.REPO, fetch_upstream=lambda: _fake_upstream(),
                         discover_candidate=_fake_discovery(bot),
                         probe=lambda url: ("ok", "ok")):
                sys.argv = ["bot.py", "--harvest"]
                with contextlib.redirect_stdout(buffer):
                    code = bot.main()
            assert code == 0
            assert calls == [], calls
            assert bot.CANDIDATES.latest(PACKAGE)["status"] == PENDING_REVIEW
            assert all(path.read_bytes() == blob for path, blob in before.items())
    finally:
        sys.argv, bot.TG = saved_argv, saved_tg
    out = buffer.getvalue()
    payload = json.loads(out[out.index('{\n "harvest"'):])
    assert payload["published"] is False and payload["index_touched"] is False, payload
    assert payload["harvest"]["indexTouched"] is False, payload["harvest"]
    assert payload["health"]["indexTouched"] is False, payload["health"]
    assert payload["health"]["published"] is False, payload["health"]
    assert index_digest() == INDEX_SHA_AT_IMPORT


@case("38 --build is a sandbox dry-run and never writes repo/")
def test_cli_build_never_writes_repo():
    import bot
    before = _published_files(bot)
    saved_argv = sys.argv
    buffer = io.StringIO()
    try:
        with sandbox_state():
            sys.argv = ["bot.py", "--build"]
            with contextlib.redirect_stdout(buffer):
                code = bot.main()
        assert code == 0
        assert all(path.read_bytes() == blob for path, blob in before.items())
        assert index_digest() == INDEX_SHA_AT_IMPORT
    finally:
        sys.argv = saved_argv
    out = buffer.getvalue()
    payload = json.loads(out[out.index("{\n \"ok\""):])
    assert payload["wrote_repo"] is False and payload["published"] is False, payload
    assert payload["extensions"] == 579, payload


@case("39 BOOTSTRAP_HARVEST only discovers into candidates")
def test_bootstrap_harvest_never_publishes():
    import bot
    calls = []
    before = _published_files(bot)
    with sandbox_state(), RecordingRepo(calls):
        with patched(bot.REPO, fetch_upstream=lambda: _fake_upstream(),
                     discover_candidate=_fake_discovery(bot)):
            # the exact target main() starts when BOOTSTRAP_HARVEST is on
            bot.job_harvest()
            assert_no_publish(calls)
            assert calls == [], calls
            assert bot.CANDIDATES.latest(PACKAGE)["status"] == PENDING_REVIEW
        assert all(path.read_bytes() == blob for path, blob in before.items())

    # main() wires the bootstrap to the harvest job and to nothing else
    source = inspect.getsource(bot.main)
    assert "BOOTSTRAP_HARVEST" in source
    assert "threading.Thread(target=job_publish" not in source
    reached = bot.called_method_names(bot.main)
    # main() may run a sandboxed --build and a manual --accept, never a publish
    assert not reached & {"publish", "push", "prepare_publish"}, reached
    assert "REPO.build(dest=sandbox)" in source and "REPO.build()" not in source
    # the only thread main() ever starts is the harvest job
    tree = ast.parse(textwrap.dedent(source))
    targets = [keyword.value.id for node in ast.walk(tree) if isinstance(node, ast.Call)
               and isinstance(node.func, ast.Attribute) and node.func.attr == "Thread"
               for keyword in node.keywords
               if keyword.arg == "target" and isinstance(keyword.value, ast.Name)]
    assert targets == ["job_harvest"], targets
    assert index_digest() == INDEX_SHA_AT_IMPORT


@case("40 no scheduled job can publish, push, build or accept")
def test_no_cron_path_can_push():
    import bot
    scheduled = ("job_harvest", "job_health", "job_publish", "job_digest")
    for name in scheduled:
        reached = bot.called_method_names(getattr(bot, name))
        assert not reached & {"publish", "push", "build", "accept"}, (name, reached)
    # the scheduler registers no publish or push call of its own
    scheduled_calls = bot.called_method_names(bot.build_scheduler)
    assert not scheduled_calls & {"publish", "push", "build", "accept"}, scheduled_calls

    # whole-module allowlist, resolved by AST so a docstring cannot satisfy it
    calls = module_call_map()
    allowed = {"publish": {"run_publish_phase", "_self_test_body"},
               "push": {"publish", "_self_test_body"}}
    for method, permitted in allowed.items():
        holders = {name for name, reached in calls.items() if method in reached}
        assert holders <= permitted, (method, sorted(holders - permitted))
    assert "publish" in calls["run_publish_phase"] and "push" in calls["publish"]

    # no entry point of the running process may promote a candidate or publish
    for name in ("main", "run_command", "handle_message", "handle_decision", "job_harvest",
                 "job_health", "job_publish", "job_digest", "harvest", "discover_candidate",
                 "process", "accept", "advance", "reverify", "reverify_live", "review",
                 "review_report", "quarantine", "invalidate", "reverify_live"):
        owner = (getattr(bot, name, None) or getattr(bot.REPO, name, None)
                 or getattr(rs.ReviewSystem, name, None))
        if owner is None or not callable(owner):
            continue
        # main()'s only build is the sandboxed --build, asserted in case 39
        wanted = {"publish", "push"} if name == "main" else {"publish", "push", "build"}
        reached = bot.called_method_names(owner) & wanted
        assert not reached, (name, reached)
    # the reviewed states stay where they are: a cron cannot reach ACCEPTED either
    assert not (bot.called_method_names(bot.REVIEW.process)
                & {"accept", "publish", "push"})
    assert not (bot.called_method_names(bot.REPO.harvest)
                & {"accept", "publish", "push", "build"})
    assert index_digest() == INDEX_SHA_AT_IMPORT


@case("41 a candidate must pass every gate and be reviewed before accept")
def test_candidate_review_is_required_before_accept():
    for status in (DISCOVERED, VALIDATED, SCREENED, SCANNED, BUILT, REJECTED, QUARANTINED,
                   ACCEPTED):
        riggy = rig()
        candidate = riggy.discover()
        candidate["status"] = status
        riggy.store.put(candidate)
        result = riggy.system.accept(PACKAGE, actor="tester", is_admin=True)
        assert result["ok"] is False, (status, result)
    # every gate is mandatory, and dropping one invalidates the evidence
    for gate in GATES:
        riggy = rig()
        riggy.pending()
        drop_gate(riggy, gate)
        result = riggy.system.accept(PACKAGE, actor="tester", is_admin=True)
        assert result["ok"] is False, (gate, result)
        assert riggy.store.latest(PACKAGE)["status"] != ACCEPTED
    # an admin and an actor are required on top of the gates
    riggy = rig()
    riggy.pending()
    assert riggy.system.accept(PACKAGE, actor="tester", is_admin=False)["ok"] is False
    assert riggy.system.accept(PACKAGE, actor="", is_admin=True)["ok"] is False
    assert riggy.store.latest(PACKAGE)["status"] == PENDING_REVIEW
    # and only then does the reviewed candidate become ACCEPTED
    assert riggy.system.accept(PACKAGE, actor="tester", is_admin=True)["ok"] is True
    assert riggy.store.latest(PACKAGE)["status"] == ACCEPTED


@case("42 accept never pushes, whatever the gate is set to")
def test_accept_never_pushes():
    import bot
    calls = []
    before = _published_files(bot)
    with RecordingRepo(calls), patched(bot, PUBLISH_MODE="allow", PUSH_ENABLED=True,
                                      TEST_MODE=False):
        riggy = rig()
        riggy.pending()
        result = riggy.system.accept(PACKAGE, actor="tester", is_admin=True)
    assert result["ok"] is True and result.get("published") is False, result
    assert calls == [], calls
    assert bot.publish_authorized() is False
    # by AST: the accept path itself cannot reach push, publish or build
    reached = bot.called_method_names(rs.ReviewSystem.accept)
    assert not reached & {"publish", "push", "build"}, reached
    # and no review_system.py function pushes
    system_calls = module_call_map(ROOT / "review_system.py")
    for name, methods in system_calls.items():
        assert not methods & {"publish", "push"}, (name, sorted(methods))
    assert all(path.read_bytes() == blob for path, blob in before.items())
    assert index_digest() == INDEX_SHA_AT_IMPORT


@case("43 accept never publishes, whatever the gate is set to")
def test_accept_never_publishes():
    import bot
    calls = []
    before = _published_files(bot)
    published_digest = json.dumps(bot.REPO.index, sort_keys=True)
    with RecordingRepo(calls), patched(bot, PUBLISH_MODE="allow", PUSH_ENABLED=True,
                                      TEST_MODE=False):
        riggy = rig(published={PACKAGE: extension(version="100000",
                                                   version_name="1.0.0")})
        riggy.pending()
        result = riggy.system.accept(PACKAGE, actor="tester", is_admin=True)
        # a second accept is refused too: acceptance is not a repeatable shortcut
        again = riggy.system.accept(PACKAGE, actor="tester", is_admin=True)
    assert result["ok"] is True and result.get("published") is False, result
    assert again["ok"] is False, again
    assert calls == [], calls
    assert json.dumps(bot.REPO.index, sort_keys=True) == published_digest
    assert riggy.published[PACKAGE]["versionCode"] == "100000"
    assert bot.publish_authorized() is False
    reached = bot.called_method_names(rs.ReviewSystem.accept)
    assert not reached & {"publish", "push", "build"}, reached
    # accept is the last stop before a *manual* publish, and it stops there
    assert all(path.read_bytes() == blob for path, blob in before.items())
    assert index_digest() == INDEX_SHA_AT_IMPORT
    with sandbox_state(), patched(bot, PUBLISH_MODE="allow", PUSH_ENABLED=True,
                                  TEST_MODE=False):
        blocked = bot.REPO.publish()
    assert blocked.get("blocked") is True and not blocked.get("pushed"), blocked


@case("44 a direct REPO.publish() without an authorisation is blocked")
def test_direct_publish_without_authorization_is_blocked():
    """Runtime proof, not a source scan: the real method, the real guard.

    The gate is deliberately wide open (PUBLISH_MODE=allow, a token, TEST_MODE
    off) so the *only* thing that can stop the publish is the missing
    PublishAuthorization. Push is also stubbed at the HTTP boundary, so a
    regression that got through would fail here instead of reaching GitHub.
    """
    import bot
    before = _published_files(bot)
    with sandbox_state(), patched(bot, PUBLISH_MODE="allow", PUSH_ENABLED=True,
                                  TEST_MODE=False, GITHUB_API="http://127.0.0.1:9"):
        assert bot.publish_authorized() is False
        # push() raises, so publish() can only have failed *before* reaching it
        try:
            bot.REPO.push(["repo/index.json"])
            raise AssertionError("push() was not blocked")
        except bot.PushBlocked:
            pass
        result = bot.REPO.publish()
        assert result["blocked"] is True, result
        assert result["pushed"] == [] and result["changed"] == [], result
        assert "authorisation" in result["skipped"], result
        assert all(path.read_bytes() == blob for path, blob in before.items())
    assert index_digest() == INDEX_SHA_AT_IMPORT
    assert index_count() == 579
    # the same holds with the environment's real defaults
    with sandbox_state():
        assert bot.REPO.publish()["blocked"] is True
        assert bot.REPO.publish()["pushed"] == []


@case("45 a direct REPO.push() without an authorisation is blocked")
def test_direct_push_without_authorization_is_blocked():
    import bot
    before = _published_files(bot)
    attempted = []
    for gate in ({"PUBLISH_MODE": "allow", "PUSH_ENABLED": True, "TEST_MODE": False},
                 {"PUBLISH_MODE": "disabled", "PUSH_ENABLED": True, "TEST_MODE": False},
                 {"PUBLISH_MODE": "allow", "PUSH_ENABLED": True, "TEST_MODE": True},
                 {}):
        with sandbox_state(), patched(bot, GITHUB_API="http://127.0.0.1:9", **gate):
            def trap(*args, **kwargs):
                attempted.append(args)
                raise AssertionError("an HTTP write was attempted")
            with patched(bot, http_session=lambda: _TripwireSession(trap, attempted)):
                try:
                    bot.REPO.push(["repo/index.json", "repo.json", "repo/index.pb"])
                    raise AssertionError("push() was not blocked: {}".format(gate))
                except bot.PushBlocked as exc:
                    assert "authorisation" in str(exc) or "TEST_MODE" in str(exc), (gate, exc)
        assert not attempted, (gate, attempted)
        assert all(path.read_bytes() == blob for path, blob in before.items())
    assert index_digest() == INDEX_SHA_AT_IMPORT
    assert index_count() == 579


@case("46 the publish authorisation is closed again when the phase ends")
def test_publish_authorization_expires_or_closes():
    import bot
    before = _published_files(bot)

    class PhaseProbe:
        """Stands in for REPO so the phase runs for real but writes nothing.

        The authorisation, the gate and the ``with`` block are bot's own code --
        only the work the phase would do is replaced, so nothing is built and
        nothing is pushed, while the lifecycle under test is the production one.
        """

        def __init__(self):
            self.authorized_inside = None
            self.calls = 0

        def publish(self):
            self.calls += 1
            self.authorized_inside = bot.publish_authorized()
            return {"ok": True, "changed": [], "pushed": []}

    with sandbox_state(), patched(bot, PUBLISH_MODE="allow", PUSH_ENABLED=True,
                                  TEST_MODE=False):
        # closed before...
        assert bot.publish_authorized() is False
        try:
            bot.assert_may_push("probe")
            raise AssertionError("the door was open with no authorisation")
        except bot.PushBlocked:
            pass

        with bot.PublishAuthorization("tester", "manual phase"):
            assert bot.publish_authorized() is True
            bot.assert_may_push("probe")        # probes only, sends nothing
        # ...and closed again the moment the block is left
        assert bot.publish_authorized() is False
        try:
            bot.assert_may_push("probe")
            raise AssertionError("the key outlived its block")
        except bot.PushBlocked:
            pass
        try:
            bot.REPO.push(["repo.json"])
            raise AssertionError("push() worked after the phase ended")
        except bot.PushBlocked:
            pass

        # the real entry point, with the gate wide open: the phase holds the key
        # while it works and has dropped it the moment it returns
        probe = PhaseProbe()
        with patched(bot, REPO=probe):
            assert bot.publish_authorized() is False
            result = bot.run_publish_phase("tester", "phase over")
        assert probe.calls == 1 and probe.authorized_inside is True, probe.__dict__
        assert result["pushed"] == [], result
        assert bot.publish_authorized() is False, "the phase leaked its authorisation"
        try:
            bot.REPO.push(["repo.json"])
            raise AssertionError("push() worked after run_publish_phase() returned")
        except bot.PushBlocked:
            pass

        # the same phase against the shipped defaults is refused outright, and
        # still leaves nothing behind
        with patched(bot, REPO=PhaseProbe(), PUBLISH_MODE="disabled", PUSH_ENABLED=False):
            refused = bot.run_publish_phase("tester", "gate closed")
        assert refused["blocked"] is True and refused["pushed"] == [], refused
        assert bot.publish_authorized() is False

        # an exception inside the block lowers it too
        try:
            with bot.PublishAuthorization("tester", "raising"):
                assert bot.publish_authorized() is True
                raise RuntimeError("boom")
        except RuntimeError:
            pass
        assert bot.publish_authorized() is False
        # and it is thread-local: another thread never inherits it
        seen = []
        thread = threading.Thread(target=lambda: seen.append(bot.publish_authorized()))
        thread.start()
        thread.join(5)
        assert seen == [False], seen
        # nested blocks restore the previous state rather than leaking
        with bot.PublishAuthorization("outer", "outer"):
            with bot.PublishAuthorization("inner", "inner"):
                assert bot.publish_authorized() is True
            assert bot.publish_authorized() is True
        assert bot.publish_authorized() is False
        assert all(path.read_bytes() == blob for path, blob in before.items())
    assert index_digest() == INDEX_SHA_AT_IMPORT
    assert index_count() == 579


@case("47 accept does not open a publish authorisation")
def test_accept_does_not_enable_publish_authorization():
    import bot
    before = _published_files(bot)
    observed = []
    for gate in ({"PUBLISH_MODE": "allow", "PUSH_ENABLED": True, "TEST_MODE": False},
                 {"PUBLISH_MODE": "disabled", "PUSH_ENABLED": False, "TEST_MODE": False}):
        with sandbox_state(), patched(bot, **gate):
            riggy = rig()
            riggy.pending()
            assert bot.publish_authorized() is False
            original = bot.PublishAuthorization.__enter__

            def spy(self):
                observed.append(self.actor)
                return original(self)

            with patched(bot.PublishAuthorization, __enter__=spy):
                result = riggy.system.accept(PACKAGE, actor="tester", is_admin=True)
            assert result["ok"] is True and result.get("published") is False, result
            # the candidate moved, the authorisation was never even requested
            assert riggy.store.latest(PACKAGE)["status"] == ACCEPTED
            assert observed == [], observed
            assert bot.publish_authorized() is False
            # and it changed nothing outside the candidate store and the audit log
            assert all(path.read_bytes() == blob for path, blob in before.items())
            entries = [item["action"] for item in riggy.audit.entries()]
            assert entries[0] == "DISCOVER" and entries[-1] == "ACCEPT", entries
            assert {item["result"] for item in riggy.audit.entries()} == {"PASS"}, entries
    assert index_digest() == INDEX_SHA_AT_IMPORT
    assert index_count() == 579


@case("48 candidate -> review -> accept -> prepare publish changes nothing")
def test_candidate_to_prepare_publish_is_inert():
    """The full reviewed release path, end to end, with no write at the end.

    Discovery, the five gates, the report, the accept and the dry-run publish
    preparation all run; afterwards repo/, repo.json and the signing identity are
    byte-identical, and no HTTP write was even attempted.
    """
    import bot
    before = _published_files(bot)
    key_before = json.loads((ROOT / "repo/index.json").read_text(encoding="utf-8"))["signingKey"]
    fingerprint_before = json.loads((ROOT / "repo.json").read_text(encoding="utf-8"))["meta"][
        "signingKeyFingerprint"]
    writes = []
    published_before = json.dumps(bot.REPO.index, sort_keys=True)

    with sandbox_state(), patched(bot, PUBLISH_MODE="allow", PUSH_ENABLED=True,
                                  TEST_MODE=False, GITHUB_API="http://127.0.0.1:9"):
        with patched(bot, http_session=lambda: _TripwireSession(
                lambda *a, **k: writes.append(a) or _raise_no_network(), writes)):
            riggy = rig(published={PACKAGE: extension(version="100000",
                                                       version_name="1.0.0")})
            # 1. candidate
            candidate = riggy.pending()
            assert candidate["status"] == PENDING_REVIEW
            # 2. review (read-only)
            report = riggy.system.review_report(PACKAGE)
            assert PACKAGE in report and candidate["apkSha256"] in report
            # 3. accept (manual, admin, with a live re-verification)
            accepted = riggy.system.accept(PACKAGE, actor="tester", is_admin=True)
            assert accepted["ok"] is True and accepted.get("published") is False
            assert riggy.store.latest(PACKAGE)["status"] == ACCEPTED
            # 4. prepare publish -- sandboxed, and a prepare on the *live* manager
            with patched(bot, CANDIDATES=_StoreShim(riggy.store)):
                prepared = bot.REPO.prepare_publish()
            assert prepared["dryRun"] is True and prepared["published"] is False
            assert prepared["pushed"] == [] and prepared["would_change"] is not None
            # the one accepted candidate is reported as ready, and nothing else is
            assert prepared["ready_to_publish"] == 1, prepared
            assert prepared["pending_review"] == 0, prepared
            assert PACKAGE in prepared["accepted_candidates"][0], prepared
            assert prepared["gate"]["ok"] is True, prepared["gate"]
            assert prepared["wrote"] is None and prepared["sandbox"] != str(ROOT)

        # nothing was written anywhere, and no request was ever attempted
        assert writes == [], writes
        assert all(path.read_bytes() == blob for path, blob in before.items())
        assert json.dumps(bot.REPO.index, sort_keys=True) == published_before
        assert bot.publish_authorized() is False

    key_after = json.loads((ROOT / "repo/index.json").read_text(encoding="utf-8"))["signingKey"]
    fingerprint_after = json.loads((ROOT / "repo.json").read_text(encoding="utf-8"))["meta"][
        "signingKeyFingerprint"]
    assert key_after == key_before == SIGNING_KEY_AT_IMPORT
    assert fingerprint_after == fingerprint_before
    assert index_digest() == INDEX_SHA_AT_IMPORT
    assert index_count() == 579


@case("49 candidate -> accept -> publish without the phase is refused")
def test_accept_then_publish_is_refused():
    """The tempting shortcut: accept the candidate, then publish straight away.

    ACCEPTED is not a publish: the low-level publish, push and real build all
    refuse without a live authorisation, the HTTP boundary is never even reached,
    and nothing in the accept path ever asks for the phase. The one door,
    ``run_publish_phase``, is manual and is not reachable from here.
    """
    import bot
    before = _published_files(bot)
    published_before = json.dumps(bot.REPO.index, sort_keys=True)
    writes = []
    phases = []
    with sandbox_state(), patched(bot, PUBLISH_MODE="allow", PUSH_ENABLED=True,
                                  TEST_MODE=False, GITHUB_API="http://127.0.0.1:9"):
        with patched(bot, http_session=lambda: _TripwireSession(
                lambda *a, **k: writes.append(a) or _raise_no_network(), writes)):
            riggy = rig(published={PACKAGE: extension(version="100000",
                                                       version_name="1.0.0")})
            riggy.pending()
            real_phase = bot.run_publish_phase

            def spy(actor, reason):
                phases.append((actor, reason))
                return real_phase(actor, reason)

            with patched(bot, run_publish_phase=spy):
                assert riggy.system.accept(PACKAGE, actor="tester",
                                           is_admin=True)["ok"] is True
            # the accepted candidate is promotion-ready and still unpublished
            assert riggy.store.latest(PACKAGE)["status"] == ACCEPTED
            assert riggy.published[PACKAGE]["versionCode"] == "100000"

            # (a) the low-level method, called directly
            assert bot.REPO.publish()["blocked"] is True
            # (b) the low-level push, called directly
            for call in (lambda: bot.REPO.push(["repo/index.json"]),
                         lambda: bot.REPO.push()):
                try:
                    call()
                    raise AssertionError("a push slipped through after accept")
                except bot.PushBlocked:
                    pass
            # (c) a real (non-sandbox) build
            try:
                bot.REPO.build()
                raise AssertionError("a real build slipped through after accept")
            except bot.PushBlocked:
                pass
            # (d) accept never reached for the phase in the first place
            assert phases == [], phases
            # (e) and the only door refuses on its own terms without an actor
            assert bot.run_publish_phase("", "no actor")["blocked"] is True
            # (f) the CLI has no flag for it either
            assert "publish" not in _cli_flags(), _cli_flags()
            assert writes == [], writes
            assert all(path.read_bytes() == blob for path, blob in before.items())
            assert json.dumps(bot.REPO.index, sort_keys=True) == published_before
    assert bot.publish_authorized() is False
    assert index_digest() == INDEX_SHA_AT_IMPORT
    assert index_count() == 579


@case("50 run_publish_phase is the only door, and review_system.py is inert")
def test_run_publish_phase_is_the_only_door():
    import bot
    # every method call in the module, resolved by AST, with a closed allowlist
    calls = module_call_map()
    holders = {name for name, reached in calls.items() if "publish" in reached}
    assert holders <= {"run_publish_phase", "_self_test_body"}, sorted(holders)
    assert "run_publish_phase" in calls and "publish" in calls["run_publish_phase"]
    pusher = {name for name, reached in calls.items() if "push" in reached}
    assert pusher <= {"publish", "_self_test_body"}, sorted(pusher)
    # the only GitHub write in the whole process is one session PUT to the
    # contents API, and it lives inside RepoManager.push() behind assert_may_push
    tree = ast.parse((ROOT / "bot.py").read_text(encoding="utf-8"))
    # resolve name -> assigned value so an endpoint held in a variable counts
    endpoints = {}
    for node in ast.walk(tree):
        if (isinstance(node, ast.Assign) and len(node.targets) == 1
                and isinstance(node.targets[0], ast.Name)):
            endpoints.setdefault(node.targets[0].id, []).append(ast.dump(node.value))
    writers = []
    for node in ast.walk(tree):
        if not (isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
                and node.func.attr in ("put", "post", "patch", "delete")):
            continue
        target = node.args[0] if node.args else None
        reachable = ast.dump(target) if target is not None else ""
        if isinstance(target, ast.Name):
            reachable += " ".join(endpoints.get(target.id, []))
        if "contents" in reachable or "api.github" in reachable:
            writers.append(node)
    assert len(writers) == 1, [ast.dump(item)[:160] for item in writers]
    assert writers[0].func.attr == "put", ast.dump(writers[0])[:160]
    push_source = inspect.getsource(bot.RepoManager.push)
    assert push_source.index("assert_may_push") < push_source.index("session.put")
    # the push source names the contents API and nothing else that could write
    assert "contents" in push_source and "create_git" not in push_source
    # publish() and build(dest=None) go through the same door
    assert "assert_may_push" in inspect.getsource(bot.RepoManager.publish)
    assert "assert_may_write_repo" in inspect.getsource(bot.RepoManager.build)
    # prepare_publish is the dry-run twin: by AST it calls no publish and no push
    prepared_calls = bot.called_method_names(bot.RepoManager.prepare_publish)
    assert not prepared_calls & {"publish", "push", "prepare_publish"}, sorted(prepared_calls)
    assert prepared_calls <= {"build", "by_status", "format", "get", "mkdir", "mkdtemp"}, (
        sorted(prepared_calls))
    assert "prepare_publish" not in push_source
    # the one generic request() is the health probe, and it is a read-only pair
    probe_source = inspect.getsource(bot.RepoManager.probe)
    assert '("HEAD", "GET")' in probe_source, probe_source[:400]

    # ---- review_system.py: state only. No bot, no publish, no GitHub, no repo/.
    body = (ROOT / "review_system.py").read_text(encoding="utf-8")
    tree = ast.parse(body)
    imported = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            imported.update(alias.name.split(".")[0] for alias in node.names)
        elif isinstance(node, ast.ImportFrom) and node.module:
            imported.add(node.module.split(".")[0])
    # no bot import, and nothing that could reach the network, a shell or a file
    # beyond the two state files it is given
    assert "bot" not in imported, sorted(imported)
    assert imported <= {"__future__", "hashlib", "hmac", "json", "os", "re", "threading",
                        "time", "requests", "pathlib", "typing"}, sorted(imported)
    assert not {"subprocess", "socket", "urllib", "shutil", "asyncio"} & imported
    # no publish, no push, no build -- by AST, so a docstring is not a violation
    for name, methods in module_call_map(ROOT / "review_system.py").items():
        assert not methods & {"publish", "push", "build", "prepare_publish"}, (
            name, sorted(methods))
    # no GitHub write endpoint and no git-data plumbing, by name
    for forbidden in ("api.github.com", "contents/", "create_git", "update_file",
                      "delete_file", "git/trees", "git/refs", "PUBLISH_MODE",
                      "PublishAuthorization", "assert_may_push", "assert_may_write_repo",
                      "run_publish_phase", "REPO."):
        assert forbidden not in body, forbidden
    # the only HTTP verb it can reach for is a read: resolve the receiver, so
    # CandidateStore.put() (a dict insert) is not mistaken for an HTTP PUT
    http_clients = {"requests", "session", "http", "httpx", "urllib", "urlopen"}
    verbs = set()
    for node in ast.walk(tree):
        if not (isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
                and node.func.attr in ("get", "post", "put", "patch", "delete", "head")):
            continue
        root = node.func.value
        while isinstance(root, ast.Attribute):
            root = root.value
        if isinstance(root, ast.Name) and root.id in http_clients:
            verbs.add(node.func.attr)
    assert verbs <= {"get"}, sorted(verbs)
    # it never writes a published artifact. Checked against string literals in
    # code, not the raw text: the module docstring is allowed to *name* the file
    # it deliberately does not touch.
    docstrings = {ast.get_docstring(node, clean=False)
                  for node in ast.walk(tree)
                  if isinstance(node, (ast.Module, ast.ClassDef, ast.FunctionDef,
                                       ast.AsyncFunctionDef))}
    docstrings.discard(None)
    literals = {node.value for node in ast.walk(tree)
                if isinstance(node, ast.Constant) and isinstance(node.value, str)
                and node.value not in docstrings}
    for forbidden in ("repo/index.json", "repo/index.pb", "repo/index.min.json",
                      "repo.json", "index_v2", "signingKey", "signingKeyFingerprint"):
        assert not [item for item in literals if forbidden in item], forbidden
    # the only file it writes atomically is the candidate store it was handed;
    # the audit log is append-only, and nothing else is opened for writing
    written = {ast.dump(node.args[0]) for node in ast.walk(tree)
               if isinstance(node, ast.Call) and isinstance(node.func, ast.Name)
               and node.func.id == "atomic_write" and node.args}
    assert written == {"Attribute(value=Name(id='self', ctx=Load()), "
                       "attr='path', ctx=Load())"}, written
    SELF_PATH = ("Attribute(value=Name(id='self', ctx=Load()), attr='path', ctx=Load())")
    opened = [(ast.dump(node.func.value), [ast.dump(a) for a in node.args])
              for node in ast.walk(tree)
              if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
              and node.func.attr == "open"]
    assert opened == [(SELF_PATH, ["Constant(value='a')"])], opened
    # every remaining filesystem call is a mkdir, or atomic_write's own
    # write-temp-then-rename on the same path -- no delete, truncate or rename
    # of anything else
    destinations = set()
    for node in ast.walk(tree):
        if not (isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)):
            continue
        if node.func.attr in ("write_bytes", "replace"):
            destinations.add(ast.dump(node.func.value))
        assert node.func.attr not in ("unlink", "rmdir", "removedirs", "truncate",
                                     "rename", "symlink", "chmod", "write_text"), (
            node.func.attr, ast.dump(node)[:120])
    assert destinations == {"Name(id='temp', ctx=Load())",
                            "Name(id='text', ctx=Load())"}, sorted(destinations)
    atomic = rs.atomic_write
    probe = Path(tempfile.mkdtemp(prefix="manga-atomic-")) / "state.json"
    try:
        assert atomic(probe, b"{}") is True and probe.read_bytes() == b"{}"
        # and it is a real atomic swap: a temp sibling, no leftovers
        assert [item.name for item in probe.parent.iterdir()] == ["state.json"]
    finally:
        shutil.rmtree(probe.parent, ignore_errors=True)
    # accept() reaches only the candidate store, the audit log and its own
    # re-verification -- never a write, a request or the published repository
    accept_calls = bot.called_method_names(rs.ReviewSystem.accept)
    assert accept_calls == {"get", "latest", "put", "record", "transition", "invalidate",
                            "reverify", "reverify_live", "_published_floor",
                            "format", "join"}, sorted(accept_calls)
    assert not accept_calls & {"publish", "push", "build", "open", "write_bytes",
                               "write_text", "write", "replace", "mkdir", "get_session",
                               "post", "put_file", "save"}, sorted(accept_calls)
    assert index_digest() == INDEX_SHA_AT_IMPORT


# ----------------------------------------------------------------- 51..55 signing

class _BareRepo:
    """A RepoManager whose index has no signing key, built without touching disk.

    ``object.__new__`` skips ``__init__`` so nothing is loaded from the real
    repository; only the four attributes :meth:`build` reads are supplied.
    """

    def __init__(self, bot, signing_key):
        self.lock = threading.RLock()
        self.index = {"name": "Demo", "badgeLabel": "DM", "signingKey": signing_key,
                      "contact": {"website": "https://example.org"},
                      "extensionList": {"extensions": []}}
        self.quarantine = []
        self.audit_cache = {}
        self.build = bot.RepoManager.build.__get__(self)
        self.validate = bot.RepoManager.validate.__get__(self)


@contextlib.contextmanager
def _sandbox_repo_json(fingerprint: str):
    """Point the published-fingerprint fallback at a throwaway repo.json."""
    tmp = Path(tempfile.mkdtemp(prefix="manga-repojson-"))
    path = tmp / "repo.json"
    path.write_text(json.dumps({"index_v2": "https://example.org/index.pb",
                                "meta": {"name": "Demo", "shortName": "DM",
                                         "website": "https://example.org",
                                         "signingKeyFingerprint": fingerprint}}),
                    encoding="utf-8")
    import bot
    saved = bot.published_fingerprint

    def reader(target=None):
        return saved(path)

    try:
        with patched(bot, published_fingerprint=reader):
            yield path
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


@case("51 a build with no SIGNING_KEY preserves the existing signing metadata")
def test_build_without_signing_key_preserves_existing_metadata():
    """The exact scenario that once wiped the store: no SIGNING_KEY in the env.

    The key already published must survive the rebuild untouched, in all three
    artifacts, and nothing may be written outside the sandbox.
    """
    import bot
    before = _published_files(bot)
    sandbox = Path(tempfile.mkdtemp(prefix="manga-signing-"))
    try:
        with sandbox_state(), patched(bot, SIGNING_KEY="", TEST_MODE=True):
            assert bot.SIGNING_KEY == ""
            # the key lives in the index being built -- a real, key-shaped one
            built = _BareRepo(bot, VALID_PUBLIC_KEY).build(dest=sandbox)
        assert built["ok"] is True, built

        rendered = json.loads((sandbox / "repo/index.json").read_text(encoding="utf-8"))
        meta = json.loads((sandbox / "repo.json").read_text(encoding="utf-8"))["meta"]
        decoded = bot.decode_index(gzip.decompress((sandbox / "repo/index.pb").read_bytes()))

        assert rendered["signingKey"] == VALID_PUBLIC_KEY, rendered["signingKey"]
        assert decoded["signingKey"] == VALID_PUBLIC_KEY, decoded["signingKey"]
        # repo.json carries the *fingerprint*, derived from the key -- never the key
        assert meta["signingKeyFingerprint"] == VALID_FINGERPRINT, meta
        assert meta["signingKeyFingerprint"] != VALID_PUBLIC_KEY, "key leaked into repo.json"
        # and the real repository is untouched
        assert all(path.read_bytes() == blob for path, blob in before.items())
    finally:
        shutil.rmtree(sandbox, ignore_errors=True)
    assert index_digest() == INDEX_SHA_AT_IMPORT
    assert index_count() == 579


@case("52 a build with no signing key at all is refused")
def test_build_without_signing_key_without_existing_metadata_fails():
    """Nothing anywhere to take a key from, so the build must stop.

    Crucially a *published fingerprint* is not a key: repo.json can carry a
    perfectly valid fingerprint and the build still has to refuse, because a
    fingerprint cannot verify a signature.
    """
    import bot
    before = _published_files(bot)
    sandbox = Path(tempfile.mkdtemp(prefix="manga-signing-"))
    try:
        with sandbox_state(), patched(bot, SIGNING_KEY="", TEST_MODE=True):
            bare = _BareRepo(bot, "")
            # (a) nothing anywhere to fall back on
            with _sandbox_repo_json(""):
                try:
                    bare.build(dest=sandbox)
                    raise AssertionError("a build with no signing key was allowed")
                except bot.SigningMetadataMissing as exc:
                    assert "SIGNING_KEY" in str(exc), exc
            # the refusal happened before anything was written
            assert sorted(item.name for item in sandbox.iterdir()) == [], list(
                sandbox.iterdir())

            # (b) a valid *fingerprint* published in repo.json is still not a key.
            #     This is the regression that made a digest stand in for one.
            with _sandbox_repo_json(VALID_FINGERPRINT):
                assert bot.published_fingerprint() == VALID_FINGERPRINT
                try:
                    bare.build(dest=sandbox)
                    raise AssertionError("a published fingerprint was used as a signing key")
                except bot.SigningMetadataMissing as exc:
                    assert "fingerprint" in str(exc).lower(), exc
            assert sorted(item.name for item in sandbox.iterdir()) == [], list(
                sandbox.iterdir())
        assert all(path.read_bytes() == blob for path, blob in before.items())
    finally:
        shutil.rmtree(sandbox, ignore_errors=True)
    assert index_digest() == INDEX_SHA_AT_IMPORT
    assert index_count() == 579


@case("53 an empty signing key never reaches any artifact")
def test_empty_signing_key_never_writes_empty_metadata():
    import bot
    before = _published_files(bot)
    sandbox = Path(tempfile.mkdtemp(prefix="manga-signing-"))
    try:
        with sandbox_state(), patched(bot, SIGNING_KEY="", TEST_MODE=True):
            for value in ("", "   ", None):
                with _sandbox_repo_json(""):
                    try:
                        bot.resolve_signing_key({"signingKey": value})
                        raise AssertionError("resolve returned {!r}".format(value))
                    except bot.SigningMetadataMissing:
                        pass
            # a missing key is a refusal, never a silent borrow from repo.json
            for probe in ({"signingKey": ""}, {}, {"signingKey": None}):
                with _sandbox_repo_json(VALID_FINGERPRINT):
                    try:
                        bot.resolve_signing_key(probe)
                        raise AssertionError("resolve borrowed a key from {0}".format(probe))
                    except bot.SigningMetadataMissing:
                        pass
            # and a full build never emits an empty value in any artifact
            built = _BareRepo(bot, VALID_PUBLIC_KEY).build(dest=sandbox)
            rendered = json.loads((sandbox / "repo/index.json").read_text(encoding="utf-8"))
            decoded = bot.decode_index(gzip.decompress(
                (sandbox / "repo/index.pb").read_bytes()))
            assert rendered["signingKey"] == VALID_PUBLIC_KEY, rendered["signingKey"]
            assert decoded["signingKey"] == VALID_PUBLIC_KEY, decoded["signingKey"]
            for relative in ("repo/index.json", "repo.json", "shura/manifest.json"):
                text = (sandbox / relative).read_text(encoding="utf-8")
                assert '"signingKey": ""' not in text, relative
                assert '"signingKeyFingerprint": ""' not in text, relative
            meta = json.loads((sandbox / "repo.json").read_text(encoding="utf-8"))["meta"]
            assert meta["signingKeyFingerprint"] == VALID_FINGERPRINT, meta
        assert all(path.read_bytes() == blob for path, blob in before.items())
    finally:
        shutil.rmtree(sandbox, ignore_errors=True)
    assert index_digest() == INDEX_SHA_AT_IMPORT
    assert index_count() == 579


@case("54 a published fingerprint never overrides the real signing key")
def test_existing_signing_fingerprint_never_gets_wiped():
    """The fingerprint is derived from the key; the key is what is authoritative.

    Even when repo.json already publishes a fingerprint -- even a *different*
    one -- a build reports the fingerprint of the key it is actually publishing.
    Otherwise the store would verify extensions against a key nobody signs with.
    """
    import bot
    before = _published_files(bot)
    sandbox = Path(tempfile.mkdtemp(prefix="manga-signing-"))
    stale = "9" * 64
    try:
        with sandbox_state(), patched(bot, SIGNING_KEY="", TEST_MODE=True):
            # the fingerprint on disk disagrees with the key in the index
            bare = _BareRepo(bot, VALID_PUBLIC_KEY)
            with _sandbox_repo_json(stale):
                built = bare.build(dest=sandbox)
            assert built["ok"] is True, built
            meta = json.loads((sandbox / "repo.json").read_text(encoding="utf-8"))["meta"]
            rendered = json.loads((sandbox / "repo/index.json").read_text(encoding="utf-8"))
            manifest = json.loads((sandbox / "shura/manifest.json").read_text(encoding="utf-8"))
            assert meta["signingKeyFingerprint"] == VALID_FINGERPRINT, meta
            assert manifest["signingKeyFingerprint"] == VALID_FINGERPRINT, manifest
            assert rendered["signingKey"] == VALID_PUBLIC_KEY

            # a configured key still wins over the published fingerprint
            shutil.rmtree(sandbox, ignore_errors=True)
            sandbox = Path(tempfile.mkdtemp(prefix="manga-signing-"))
            fresh = "cd" * 256
            with _sandbox_repo_json(stale), patched(bot, SIGNING_KEY=fresh):
                _BareRepo(bot, "").build(dest=sandbox)
            meta = json.loads((sandbox / "repo.json").read_text(encoding="utf-8"))["meta"]
            assert meta["signingKeyFingerprint"] == hashlib.sha256(
                bytes.fromhex(fresh)).hexdigest(), meta

            # and a published fingerprint is never read back as empty
            assert bot.published_fingerprint() == FINGERPRINT_AT_IMPORT
            assert bot.published_fingerprint(sandbox / "missing.json") == ""
        assert all(path.read_bytes() == blob for path, blob in before.items())
    finally:
        shutil.rmtree(sandbox, ignore_errors=True)
    assert index_digest() == INDEX_SHA_AT_IMPORT
    assert index_count() == 579


@case("55 a failed signing validation modifies nothing")
def test_failed_signing_validation_does_not_modify_repo():
    """A malformed key must stop the build *before* the first byte is written."""
    import bot
    before = _published_files(bot)
    sandbox = Path(tempfile.mkdtemp(prefix="manga-signing-"))
    try:
        with sandbox_state(), patched(bot, TEST_MODE=True):
            for bad in ("not-a-digest", "zz" * 32, "0" * 63 + "g"):
                with _sandbox_repo_json(VALID_FINGERPRINT):
                    with patched(bot, SIGNING_KEY=bad):
                        try:
                            _BareRepo(bot, "").build(dest=sandbox)
                            raise AssertionError("a malformed key was published: "
                                                 "{!r}".format(bad))
                        except bot.SigningMetadataMissing as exc:
                            assert "hex" in str(exc) or "empty" in str(exc), exc
                    # the index copy is validated too, not just the env value
                    with patched(bot, SIGNING_KEY=""):
                        try:
                            _BareRepo(bot, bad).build(dest=sandbox)
                            raise AssertionError("a malformed index key was published")
                        except bot.SigningMetadataMissing:
                            pass
                    try:
                        with patched(bot, SIGNING_KEY=""):
                            bot.encode_index({"signingKey": bad,
                                              "extensionList": {"extensions": []}})
                        raise AssertionError("encode_index accepted {!r}".format(bad))
                    except bot.SigningMetadataMissing:
                        pass
                assert sorted(item.name for item in sandbox.iterdir()) == [], (
                    bad, list(sandbox.iterdir()))
        # nothing at all moved, in the sandbox or in the repository
        assert all(path.read_bytes() == blob for path, blob in before.items())
        assert json.loads((ROOT / "repo.json").read_text(encoding="utf-8"))["meta"][
            "signingKeyFingerprint"] == FINGERPRINT_AT_IMPORT
    finally:
        shutil.rmtree(sandbox, ignore_errors=True)
    assert index_digest() == INDEX_SHA_AT_IMPORT
    assert index_count() == 579


@case("57 a fingerprint can never be published as a signing key")
def test_fingerprint_is_refused_as_a_signing_key():
    """The whole point of the fix, stated as one test.

    A 64 character SHA-256 is what repo.json publishes as
    ``signingKeyFingerprint`` and what the live index currently carries in
    ``signingKey``. It must be refused everywhere a key is expected.
    """
    import bot
    digest = FINGERPRINT_AT_IMPORT
    assert len(digest) == 64 and bot.SIGNING_DIGEST_RE.match(digest)

    # as the environment value
    with patched(bot, SIGNING_KEY=digest):
        try:
            bot.resolve_signing_key({})
            raise AssertionError("a fingerprint was accepted as SIGNING_KEY")
        except bot.SigningMetadataMissing as exc:
            assert "fingerprint" in str(exc), exc

    # as the value in the index
    with patched(bot, SIGNING_KEY=""):
        try:
            bot.resolve_signing_key({"signingKey": digest})
            raise AssertionError("a fingerprint was accepted as the index signingKey")
        except bot.SigningMetadataMissing as exc:
            assert "fingerprint" in str(exc), exc

    # through the encoder, and therefore through every artifact. The env is
    # cleared so the index's digest is the only candidate.
    with patched(bot, SIGNING_KEY=""):
        try:
            bot.encode_index({"signingKey": digest, "extensionList": {"extensions": []}})
            raise AssertionError("encode_index published a fingerprint as a signing key")
        except bot.SigningMetadataMissing:
            pass

    # a full build carrying one is refused, and writes nothing
    sandbox = Path(tempfile.mkdtemp(prefix="manga-digest-"))
    try:
        with sandbox_state(), patched(bot, SIGNING_KEY=""):
            try:
                _BareRepo(bot, digest).build(dest=sandbox)
                raise AssertionError("a build published a fingerprint as a signing key")
            except bot.SigningMetadataMissing:
                pass
        assert sorted(item.name for item in sandbox.iterdir()) == [], list(
            sandbox.iterdir())
    finally:
        shutil.rmtree(sandbox, ignore_errors=True)


@case("58 the published fingerprint is derived from the key, never read back")
def test_fingerprint_is_derived_not_read_back():
    import bot
    # derived from the key's bytes, not from its hex text, and not from a file
    assert bot.signing_key_fingerprint(VALID_PUBLIC_KEY) == VALID_FINGERPRINT
    assert bot.signing_key_fingerprint(VALID_PUBLIC_KEY) == \
        hashlib.sha256(bytes.fromhex(VALID_PUBLIC_KEY)).hexdigest()
    # a fingerprint, a too-short value and a non-hex value are all refused
    for wrong in (FINGERPRINT_AT_IMPORT, "ab" * 32, "ab" * 50, "", None, "zz" * 32):
        try:
            bot.validate_signing_key(wrong, "probe")
            raise AssertionError("validate accepted {!r}".format(wrong))
        except bot.SigningMetadataMissing:
            pass
    # ... while a key-shaped value of any length above the floor is accepted
    for good in (VALID_PUBLIC_KEY, "ab" * bot.SIGNING_KEY_MIN_HEX, "cd" * 100):
        assert bot.validate_signing_key(good, "probe") == good
    # the shura manifest refuses a key, an empty value and a non-digest
    for wrong in (VALID_PUBLIC_KEY, "", "zz" * 32, "ab" * 16, None):
        try:
            bot.shura_manifest("0" * bot.SHURA_REVISION_LENGTH, [], wrong)
            raise AssertionError("shura_manifest accepted {!r} as a fingerprint".format(wrong))
        except bot.SigningMetadataMissing:
            pass
    # ... but any real 64-character digest is a valid fingerprint
    assert bot.shura_manifest(
        "0" * bot.SHURA_REVISION_LENGTH, [], VALID_FINGERPRINT
    )["signingKeyFingerprint"] == VALID_FINGERPRINT


@case("59 no private signing key exists anywhere in the repository")
def test_no_private_key_material_in_the_repo():
    """A public signing key is public. A private one must never be committed.

    Looks for key *material* -- a PEM block -- not for the words "private key",
    because the secret scanner in review_system.py legitimately contains those
    words in a regex that detects exactly this.
    """
    import bot
    pem = re.compile(r"-----BEGIN [A-Z ]*(PRIVATE|ENCRYPTED)[A-Z ]*KEY-----"
                     r".*?-----END [A-Z ]*(PRIVATE|ENCRYPTED)[A-Z ]*KEY-----", re.S)
    files = ("bot.py", "review_system.py", "test_review_pipeline.py",
             "test_shura_sync.py", "shura/README.md", "repo.json", "repo/index.json",
             "repo/index.min.json")
    checked = 0
    for name in files:
        path = ROOT / name
        if not path.is_file():
            continue
        checked += 1
        text = path.read_text(encoding="utf-8", errors="replace")
        assert not pem.search(text), "{} contains a PEM private key block".format(name)
    assert checked == len(files), checked

    # what the repository *does* publish is a fingerprint: a public digest,
    # and specifically not a key-shaped value
    fingerprint = json.loads((ROOT / "repo.json").read_text(encoding="utf-8"))["meta"][
        "signingKeyFingerprint"]
    assert len(fingerprint) == 64 and bot.SIGNING_DIGEST_RE.match(fingerprint)
    assert len(fingerprint) < bot.SIGNING_KEY_MIN_HEX, "repo.json should not hold a key"

    # the key the tests use is synthetic, obviously fake, and public
    assert VALID_PUBLIC_KEY == "ab" * 256
    assert len(VALID_PUBLIC_KEY) >= bot.SIGNING_KEY_MIN_HEX
    assert not pem.search(VALID_PUBLIC_KEY)


@case("56 accept() without an admin proof is denied by default")
def test_accept_defaults_to_denied():
    """A forgotten argument must fail closed, not open."""
    # the default is the safe one, by construction
    default = inspect.signature(ReviewSystem.accept).parameters["is_admin"].default
    assert default is False, default

    riggy = rig()
    riggy.pending()

    # accept(candidate) -- no proof of admin at all -- is refused
    result = riggy.system.accept(PACKAGE, actor="tester")
    assert result["ok"] is False, result
    assert result["error"] == "unauthorised", result
    assert riggy.store.latest(PACKAGE)["status"] == PENDING_REVIEW

    # the negative control: the very same call, with proof, does succeed. Without
    # this the assertion above would pass even if accept() were simply broken.
    assert riggy.system.accept(PACKAGE, actor="tester", is_admin=True)["ok"] is True
    assert riggy.store.latest(PACKAGE)["status"] == ACCEPTED

    # an explicit False is refused exactly like the default
    riggy = rig()
    riggy.pending()
    assert riggy.system.accept(PACKAGE, actor="tester",
                               is_admin=False)["ok"] is False
    assert riggy.store.latest(PACKAGE)["status"] == PENDING_REVIEW

    # every refusal is on the record, and the refusal left the store untouched
    refused = [entry for entry in riggy.audit.entries()
               if entry["action"] == "ACCEPT" and entry["result"] == "REJECT"]
    assert len(refused) == 1, refused
    assert refused[0]["reason"] == "not an administrator", refused

    # the flag is the *caller's* proof, so the guard against a forgotten
    # argument has to be on every call site: none of them may rely on the
    # default. Both production sites assert it explicitly -- the Telegram one
    # from the sender id, the CLI one from --admin.
    tree = ast.parse((ROOT / "bot.py").read_text(encoding="utf-8"))
    production_calls = [node for node in ast.walk(tree)
                        if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
                        and node.func.attr == "accept"
                        and isinstance(node.func.value, ast.Name)
                        and node.func.value.id == "REVIEW"]
    assert len(production_calls) == 2, len(production_calls)
    for node in production_calls:
        keywords = {keyword.arg for keyword in node.keywords}
        assert "is_admin" in keywords, (keywords, ast.dump(node)[:120])
    assert any(keyword.arg == "is_admin" and
               isinstance(keyword.value, ast.Call) and
               keyword.value.func.id == "is_admin_user"
               for node in production_calls for keyword in node.keywords), (
        "the telegram accept does not derive is_admin from the sender")
    assert any(keyword.arg == "is_admin" and isinstance(keyword.value, ast.Attribute)
               and keyword.value.attr == "admin"
               for node in production_calls for keyword in node.keywords), (
        "the cli accept does not derive is_admin from --admin")


@case("57 a missing build tester never passes a candidate")
def test_missing_build_tester_never_passes():
    """PENDING_REVIEW is only reachable behind a real build+test run."""
    tmp = Path(tempfile.mkdtemp(prefix="manga-notester-"))
    try:
        store = CandidateStore(tmp / "candidates.json")
        audit = AuditLog(tmp / "audit.jsonl")
        # every collaborator is present *except* the build tester
        system = ReviewSystem(
            store, audit,
            apk_fetcher=lambda url: CLEAN_APK,
            commit_resolver=lambda repository, ref: COMMIT,
            deep_screen=lambda ext: "",
            source_scanner=lambda candidate: {"findings": [], "scanned": 4},
        )
        assert system.build_tester is None

        candidate = system.discover(extension(), REPO_SLUG, "main", actor="test")
        candidate = system.process(candidate)

        # no metadata-only shortcut: the candidate dies at the build gate
        assert candidate["status"] == REJECTED, candidate
        assert candidate["results"]["buildTest"]["result"] == "FAIL"
        assert "no build tester" in candidate["results"]["buildTest"]["detail"]
        assert store.latest(PACKAGE)["status"] == REJECTED
        assert system.accept(PACKAGE, actor="tester", is_admin=True,
                             live=False)["ok"] is False

        # the gate evidence cannot be laundered into a pass by hand either
        try:
            system.machine.require_gates(candidate, PENDING_REVIEW)
            raise AssertionError("review was reachable with a failed buildTest")
        except rs.MissingEvidence as exc:
            assert "buildTest" in str(exc), exc
        try:
            system.machine.transition(candidate, PENDING_REVIEW, "manual")
            raise AssertionError("a rejected candidate was hand-walked to review")
        except rs.ReviewError as exc:
            assert "not a legal transition" in str(exc), exc
        assert store.latest(PACKAGE)["status"] == REJECTED

        # the same three outcomes, with a tester wired in, for contrast: a
        # passing tester passes, a failing tester fails, neither is inferred
        # from anything but the tester's own verdict
        for verdict, expected in ((True, PENDING_REVIEW), (False, REJECTED)):
            store = CandidateStore(tmp / "candidates.json")
            audit = AuditLog(tmp / "audit.jsonl")
            with_tester = ReviewSystem(
                store, audit,
                apk_fetcher=lambda url: CLEAN_APK,
                commit_resolver=lambda repository, ref: COMMIT,
                deep_screen=lambda ext: "",
                source_scanner=lambda candidate: {"findings": [], "scanned": 4},
                build_tester=lambda candidate, ok=verdict: (ok, "verdict={}".format(ok)),
            )
            walked = with_tester.process(
                with_tester.discover(extension(), REPO_SLUG, "main", actor="test"))
            assert walked["status"] == expected, (verdict, walked["status"])
            assert walked["results"]["buildTest"]["result"] == (
                "PASS" if verdict else "FAIL")
            # the negative control for require_gates above: with a real tester
            # that passed, review really is reachable
            if verdict:
                with_tester.machine.require_gates(walked, PENDING_REVIEW)
            else:
                try:
                    with_tester.machine.require_gates(walked, PENDING_REVIEW)
                    raise AssertionError("a failing tester still opened review")
                except rs.MissingEvidence:
                    pass
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


class _FakeResponse:
    """The slice of a streaming HTTP response ``fetch_apk`` actually uses."""

    def __init__(self, url, payload):
        self.url = url
        self.status_code = 200
        self._payload = payload
        self.closed = False

    def raise_for_status(self):
        return None

    def iter_content(self, size):
        yield self._payload

    def close(self):
        self.closed = True


def _redirecting(final_url, payload=CLEAN_APK, seen=None):
    """An opener that answers the original URL from ``final_url`` after a redirect."""
    def opener(url):
        if seen is not None:
            seen.append(url)
        return _FakeResponse(final_url, payload)
    return opener


@case("58 an apkUrl that redirects to http is refused")
def test_apk_redirect_to_http_rejected():
    seen = []
    result = rs.fetch_apk("https://good.example/a.apk",
                          opener=_redirecting("http://evil.example/a.apk",
                                              seen=seen))
    assert result["ok"] is False, result
    assert "https" in result["error"], result
    assert "sha256" not in result and "payload" not in result
    # the request was made, then thrown away: no payload ever comes back
    assert seen == ["https://good.example/a.apk"], seen
    # the negative control: the same payload over https is fine
    assert rs.fetch_apk("https://good.example/a.apk",
                        opener=_redirecting("https://cdn.example/a.apk"))["ok"] is True

    # an http origin is refused before a request is even made
    seen = []
    assert rs.fetch_apk("http://good.example/a.apk",
                        opener=_redirecting("https://cdn.example/a.apk",
                                            seen=seen))["ok"] is False
    assert seen == [], seen


@case("59 an apkUrl that redirects to a non-https scheme is refused")
def test_apk_redirect_to_non_https_rejected():
    for final in ("ftp://evil.example/a.apk",
                  "file:///etc/passwd",
                  "gopher://evil.example/a.apk",
                  "//evil.example/a.apk",
                  "javascript:alert(1)",
                  "HTTPS_PLACEHOLDER"):
        result = rs.fetch_apk("https://good.example/a.apk",
                              opener=_redirecting(final))
        assert result["ok"] is False, (final, result)
        assert "sha256" not in result and "payload" not in result, final

    # the guard itself only ever accepts a literal https:// URL
    for good in ("https://good.example/a.apk", "HTTPS://GOOD.EXAMPLE/a.apk"):
        assert rs.is_https_url(good) is True, good
    for bad in ("http://x/a.apk", "ftp://x/a.apk", "file:///x", "//x/a.apk",
                "x https://x", "", None, 7):
        assert rs.is_https_url(bad) is False, bad


@case("60 an https redirect is allowed and still pins size and digest")
def test_apk_https_redirect_allowed():
    seen = []
    response_holder = {}

    def opener(url):
        seen.append(url)
        holder = _FakeResponse("https://cdn.example/objects/a.apk", CLEAN_APK)
        response_holder["response"] = holder
        return holder

    result = rs.fetch_apk("https://good.example/a.apk", opener=opener)
    assert result["ok"] is True, result
    assert seen == ["https://good.example/a.apk"]
    assert response_holder["response"].closed is True
    # the redirect is followed, and the bytes are pinned exactly as before
    assert result["sha256"] == hashlib.sha256(CLEAN_APK).hexdigest()
    assert result["size"] == len(CLEAN_APK)
    assert result["payload"] == CLEAN_APK

    # a same-host https redirect, and a redirect that ends in an https URL with
    # a query string, are both fine
    for final in ("https://good.example/final/a.apk",
                  "https://cdn.example/a.apk?token=x"):
        assert rs.fetch_apk("https://good.example/a.apk",
                            opener=_redirecting(final))["ok"] is True, final

    # and the pipeline itself still pins the same digest through a redirect
    riggy = rig()
    pending = riggy.pending()
    assert pending["apkSha256"] == hashlib.sha256(CLEAN_APK).hexdigest()
    assert pending["apkSize"] == len(CLEAN_APK)


def _live_state() -> dict:
    paths = [ROOT / "repo/index.json", ROOT / "repo/index.min.json", ROOT / "repo/index.pb",
             ROOT / "repo.json", ROOT / "data/quarantine.json", ROOT / "data/audit_cache.json",
             ROOT / "data/sources.json", ROOT / "data/guard_state.json",
             ROOT / "data/candidates.json", ROOT / "data/audit_log.jsonl"]
    return {path: ((path.stat().st_mtime_ns, path.stat().st_size) if path.is_file() else None)
            for path in paths}


# ----------------------------------------------------------------- publish-gate rig

@contextlib.contextmanager
def patched(obj, **attrs):
    """Temporarily set attributes on any object (a module or the REPO singleton)."""
    saved = {name: getattr(obj, name) for name in attrs}
    try:
        for name, value in attrs.items():
            setattr(obj, name, value)
        yield obj
    finally:
        for name, value in saved.items():
            setattr(obj, name, value)


class RecordingRepo:
    """Wraps REPO so every publish/push/build call is recorded instead of made.

    ``calls`` is empty after a safe path has run, and non-empty the moment any
    of the three is reached -- which is exactly the assertion the legacy paths
    failed. A sandboxed ``build(dest=...)`` is the one legitimate build, so it is
    recorded as ``build-sandbox`` and a real one as ``build-live``.
    """

    FORBIDDEN = ("publish", "push", "publish_phase", "build-live")

    def __init__(self, calls):
        self.calls = calls

    def __enter__(self):
        import bot
        self.bot = bot
        self.saved = (bot.REPO.publish, bot.REPO.push, bot.REPO.build,
                      bot.run_publish_phase)
        bot.REPO.publish = self._note("publish")
        bot.REPO.push = self._note("push")
        bot.REPO.build = self._note_build()
        bot.run_publish_phase = self._note("publish_phase")
        return self

    def _note(self, label):
        def call(*args, **kwargs):
            self.calls.append(label)
            return {"ok": False, "blocked": True, "changed": [], "pushed": [],
                    "skipped": "recorded by the test: {}".format(label)}
        return call

    def _note_build(self):
        def build(dest=None, *args, **kwargs):
            self.calls.append("build-sandbox" if dest is not None else "build-live")
            return {"ok": True, "changed": [], "extensions": 0, "dropped": [],
                    "sandbox": str(dest) if dest is not None else None}
        return build

    def assert_no_publish(self):
        reached = [item for item in self.calls if item in self.FORBIDDEN]
        assert not reached, "a legacy path reached {}: {}".format(reached, self.calls)

    def __exit__(self, *exc):
        (self.bot.REPO.publish, self.bot.REPO.push, self.bot.REPO.build,
         self.bot.run_publish_phase) = self.saved
        return False


@contextlib.contextmanager
def sandbox_state():
    """Point every live state file at a throwaway directory.

    The store, the audit log, the guard state, the quarantine list and the audit
    cache are process globals in ``bot``; a path test that runs harvest or the
    health sweep has to redirect all of them, or it would write into the real
    ``data/``.
    """
    import bot
    tmp = Path(tempfile.mkdtemp(prefix="manga-state-"))
    names = ("CANDIDATE_FILE", "AUDITLOG_FILE", "STATE_FILE", "QUARANTINE_FILE",
             "AUDIT_CACHE_FILE")
    saved = {name: getattr(bot, name) for name in names}
    saved_state = (bot.STATE.path, dict(bot.STATE.health), dict(bot.STATE.proposals),
                   dict(bot.STATE.counters))
    saved_repo = (list(bot.REPO.quarantine), bot.REPO.cursor, dict(bot.REPO.audit_cache))
    saved_candidates, saved_auditlog = bot.CANDIDATES, bot.AUDITLOG
    try:
        bot.CANDIDATE_FILE = tmp / "candidates.json"
        bot.AUDITLOG_FILE = tmp / "audit_log.jsonl"
        bot.STATE_FILE = tmp / "guard_state.json"
        bot.QUARANTINE_FILE = tmp / "quarantine.json"
        bot.AUDIT_CACHE_FILE = tmp / "audit_cache.json"
        bot.STATE.path = bot.STATE_FILE
        bot.STATE.health, bot.STATE.proposals = {}, {}
        bot.STATE.counters = dict(saved_state[3])
        bot.STATE.counters.update({"reports": 0, "bans": 0, "allowed": 0, "auto": 0,
                                   "harvests": 0, "added": 0, "updated": 0,
                                   "quarantined": 0, "pushes": 0, "rejected": 0})
        bot.REPO.quarantine, bot.REPO.cursor = [], 0
        bot.REPO.audit_cache = {}
        bot.CANDIDATES = CandidateStore(bot.CANDIDATE_FILE)
        bot.AUDITLOG = AuditLog(bot.AUDITLOG_FILE)
        yield tmp
    finally:
        for name, value in saved.items():
            setattr(bot, name, value)
        (bot.STATE.path, bot.STATE.health, bot.STATE.proposals,
         bot.STATE.counters) = saved_state
        (bot.REPO.quarantine, bot.REPO.cursor, bot.REPO.audit_cache) = saved_repo
        bot.CANDIDATES, bot.AUDITLOG = saved_candidates, saved_auditlog
        shutil.rmtree(tmp, ignore_errors=True)


class FakeTelegram:
    """Records everything a Telegram handler sends; touches no network."""

    def __init__(self):
        self.sent = []
        self.edited = []
        self.notified = []

    def send(self, chat_id, text, markup=None):
        self.sent.append((chat_id, text))
        return {"message_id": len(self.sent)}

    def notify_admins(self, text, markup=None):
        self.notified.append(text)
        return 1

    def edit(self, chat_id, message_id, text, markup=None):
        self.edited.append((chat_id, message_id, text))
        return True

    def answer(self, callback_id, text=""):
        return True

    def ban(self, chat_id, user_id):
        return True

    def delete(self, chat_id, message_id):
        return True

    def bodies(self):
        return [text for _, text in self.sent]


def as_admin(bot, user_id=900001):
    """Patch the Telegram admin set so run_command() is reachable."""
    import bot as _bot
    saved = _bot.ADMIN_IDS
    _bot.ADMIN_IDS = {user_id}
    return saved


def module_call_map(path=ROOT / "bot.py") -> dict:
    """Map each function in a file to the methods it actually *calls*.

    The AST is walked, so a docstring or a comment that mentions ``REPO.publish()``
    is documentation of the rule, not a violation of it.
    """
    tree = ast.parse(Path(path).read_text(encoding="utf-8"))
    calls = {}

    class Walker(ast.NodeVisitor):
        def __init__(self):
            self.stack = ["<module>"]

        def visit_FunctionDef(self, node):
            self.stack.append(node.name)
            self.generic_visit(node)
            self.stack.pop()

        visit_AsyncFunctionDef = visit_FunctionDef

        def visit_Call(self, node):
            if isinstance(node.func, ast.Attribute):
                calls.setdefault(self.stack[-1], set()).add(node.func.attr)
            self.generic_visit(node)

    Walker().visit(tree)
    return calls


def assert_no_publish(calls):
    """No publish, no push, no publish phase and no real (non-sandbox) build."""
    reached = [item for item in calls if item in RecordingRepo.FORBIDDEN]
    assert not reached, "a legacy path reached {}: {}".format(reached, calls)


def _scheduled_jobs(bot, enabled):
    """The job table the scheduler would build, without starting a scheduler."""
    return bot.scheduled_jobs(enabled)


def fake_candidate(package, version, status=PENDING_REVIEW, commit=COMMIT):
    """A minimal but well-formed candidate, as harvest would leave it in the store."""
    candidate = {
        "packageName": package, "versionCode": version, "name": "Demo Ext",
        "status": status, "sourceCommit": commit, "apkSha256": "0" * 64,
        "apkSize": len(CLEAN_APK), "discoveredAt": time.time(), "history": [],
        "results": {gate: {"result": "PASS", "digest": "0" * 64, "at": time.time()}
                    for gate in GATES},
    }
    return candidate


INDEX_SHA_AT_IMPORT = index_digest()
SIGNING_KEY_AT_IMPORT = json.loads((ROOT / "repo/index.json").read_text(encoding="utf-8"))[
    "signingKey"]
FINGERPRINT_AT_IMPORT = json.loads((ROOT / "repo.json").read_text(encoding="utf-8"))["meta"][
    "signingKeyFingerprint"]
LIVE_FILES = _live_state()
#: byte-for-byte copies of everything a publish would rewrite, taken at import
PUBLISHED_FILES = {path: path.read_bytes() for path in (
    ROOT / "repo/index.json", ROOT / "repo/index.min.json", ROOT / "repo/index.pb",
    ROOT / "repo.json", ROOT / "data/quarantine.json", ROOT / "data/audit_cache.json")
    if path.is_file()}


def repo_intact() -> list:
    """Which of the published artifacts, if any, no longer match import."""
    return [str(path.relative_to(ROOT)) for path, blob in PUBLISHED_FILES.items()
            if not path.is_file() or path.read_bytes() != blob]


# ----------------------------------------------------------------- helpers

def env_test_mode():
    import bot
    return os.environ.get("TEST_MODE", "").strip().lower() in {"1", "true", "yes", "on", "y"}


def run_selftest(fake_token=False):
    """Run ``bot.py --selftest`` in a subprocess against throwaway state files."""
    sandbox = Path(tempfile.mkdtemp(prefix="manga-selftest-run-"))
    env = dict(os.environ)
    env.update({
        "CANDIDATE_FILE": str(sandbox / "candidates.json"),
        "AUDITLOG_FILE": str(sandbox / "audit.jsonl"),
        "STATE_FILE": str(sandbox / "state.json"),
        "PYTHONDONTWRITEBYTECODE": "1",
    })
    if fake_token:
        # a token that would make PUSH_ENABLED true, pointed at a closed port:
        # if a push were ever attempted the run would fail here and here only.
        env["GITHUB_TOKEN"] = "ghp_" + "0" * 36
        env["PUSH_ENABLED"] = "true"
        env["GITHUB_API"] = "http://127.0.0.1:9"
    return subprocess.run([sys.executable, "bot.py", "--selftest"], cwd=str(ROOT), env=env,
                          capture_output=True, text=True, timeout=600)


@contextlib.contextmanager
def sandbox_writes():
    """Make a write into the real repository impossible, whatever the gate says.

    Several cases deliberately open ``PUBLISH_MODE=allow`` with ``TEST_MODE`` off,
    so that the *only* thing standing between a publish and the disk is the
    missing authorisation. That is the right thing to test, but it means a test
    bug -- or a regression that slips past the guards -- would rewrite
    ``repo/``, ``repo.json`` and ``data/`` for real. (An earlier revision of this
    suite did exactly that, and a build with an empty ``SIGNING_KEY`` blanked
    ``signingKeyFingerprint``.)

    So the file layer itself is fenced: ``atomic_write`` is replaced for the
    duration of every case and may not touch anything inside the project tree.
    A sandboxed ``build(dest=...)`` still works, because that is what a dry-run
    is; a real build cannot even start. The fence is restored afterwards either
    way, and :func:`repo_intact` re-checks the bytes when the run ends.
    """
    import bot
    real_write = bot.atomic_write
    project = ROOT.resolve()
    escaped = []

    def fenced(path, payload):
        target = Path(path).resolve()
        if target == project or project in target.parents:
            escaped.append(str(target))
            raise AssertionError("the suite tried to write into the project tree: "
                                 "{}".format(target))
        return real_write(target, payload)

    with patched(bot, atomic_write=fenced):
        try:
            yield
        finally:
            assert not escaped, escaped


def main() -> int:
    print("review pipeline test suite — {} cases\n".format(len(CASES)))
    failed = []
    for title, func in CASES:
        started = time.time()
        try:
            with sandbox_writes():
                func()
        except Exception:  # noqa: BLE001 - the report is the point
            failed.append(title)
            print("FAIL  {}  ({:.2f}s)".format(title, time.time() - started))
            print(textwrap_indent(traceback.format_exc()))
        else:
            print("PASS  {}  ({:.2f}s)".format(title, time.time() - started))
    # last line of defence: a case that aborted early must not be able to leave a
    # rewritten repository behind unnoticed
    damaged = repo_intact()
    print("\n{}/{} passed, {} failed".format(len(CASES) - len(failed), len(CASES), len(failed)))
    for title in failed:
        print("   - " + title)
    if damaged:
        print("FAIL  the published repository is not byte-identical to import: "
              + ", ".join(damaged))
        return 1
    print("repo/index*, repo.json and data/ are byte-identical to import "
          "({} extensions, signingKeyFingerprint unchanged)".format(index_count()))
    return 1 if failed else 0


def textwrap_indent(text):
    return "".join("        " + line for line in text.splitlines(keepends=True))


if __name__ == "__main__":
    sys.exit(main())
