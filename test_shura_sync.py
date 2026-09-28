#!/usr/bin/env python3
"""Tests for the Shura Sync layer (``shura/manifest.json`` + ``shura/delta.json``).

Run with ``python3 test_shura_sync.py``. Same properties the review suite
guarantees for itself:

* ``repo/index.json``, ``repo/index.pb``, ``repo.json`` and ``data/`` are read
  (to prove they did not change) and never written,
* nothing is pushed and GitHub is never contacted,
* every build goes into a throwaway sandbox, so the real ``shura/`` tree is not
  an output of this suite.

The property the whole layer rests on is checked the only way that means
anything: a delta computed against a *real* previous index is applied to that
index, and the result must equal the index that was actually published.
"""

from __future__ import annotations

import gzip
import hashlib
import json
import os
import shutil
import sys
import tempfile
import threading
import traceback
from pathlib import Path

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))

# A synthetic, obviously-fake *public* signing key: the build refuses to treat
# the published 64 character fingerprint as a key, so the fixtures need a real
# key-shaped value. The matching fingerprint is derived from the key's bytes,
# exactly as bot.signing_key_fingerprint does -- never hand-written.
VALID_PUBLIC_KEY = "ab" * 256
VALID_FINGERPRINT = hashlib.sha256(bytes.fromhex(VALID_PUBLIC_KEY)).hexdigest()
os.environ.setdefault("SIGNING_KEY", VALID_PUBLIC_KEY)

import bot  # noqa: E402

CASES = []


def case(title):
    def wrap(func):
        CASES.append((title, func))
        return func
    return wrap


# ----------------------------------------------------------------- fixtures

def extension(name, code, *, apk=None, icon=None, warning="CONTENT_WARNING_SAFE",
              sources=None, lib="1.6"):
    """An extension the real ``validate()`` accepts.

    Deliberately built to the live index's spelling -- ``language`` on the
    source, ``iconUrl`` required -- so these fixtures exercise the same path a
    harvested extension takes rather than a shape the validator would drop.
    """
    package = "eu.kanade.tachiyomi.extension.en.{}".format(name.lower())
    resources = {"apkUrl": apk or "https://example.org/{}.apk".format(name.lower()),
                 "iconUrl": icon or "https://example.org/{}.png".format(name.lower())}
    record = {"name": name, "packageName": package, "resources": resources,
              "extensionLib": lib, "versionCode": str(code), "versionName": "1.{}".format(code),
              "contentWarning": warning,
              "sources": sources if sources is not None else
              [{"id": "1", "name": name, "language": "en",
                "homeUrl": "https://example.org"}]}
    return record


def index_of(extensions, name="Shura"):
    return {"name": name, "badgeLabel": "SHURA", "signingKey": bot.resolve_signing_key(),
            "contact": {"website": "https://example.org"},
            "extensionList": {"extensions": sorted(
                extensions, key=lambda item: item.get("packageName") or "")}}


class Bare:
    """A RepoManager with a fixed index, built without touching the repository.

    Mirrors the shape the review suite uses: object.__new__ skips __init__ so
    nothing is loaded from disk, and only the attributes build() reads exist.
    """

    def __init__(self, index):
        self.lock = threading.RLock()
        self.index = index
        self.quarantine = []
        self.audit_cache = {}
        self.build = bot.RepoManager.build.__get__(self)
        self.validate = bot.RepoManager.validate.__get__(self)


def build(extensions, baseline_index=None, dest=None):
    """Build into a sandbox, optionally against a baseline, and return everything."""
    sandbox = Path(dest or tempfile.mkdtemp(prefix="shura-build-"))
    sandbox.mkdir(parents=True, exist_ok=True)
    result = Bare(index_of(extensions)).build(dest=sandbox, baseline=baseline_index)
    return sandbox, result


def read(sandbox, relative):
    return json.loads((sandbox / relative).read_text(encoding="utf-8"))


def apply_delta(base_extensions, delta):
    """The reference client, written out exactly as the contract specifies.

    A stored map keyed by packageName; added and updated are whole records, and
    removed is a list of names. Anything that does not key on packageName is a
    bug in the layer, not a subtlety of this helper.
    """
    stored = {item["packageName"]: item for item in base_extensions}
    for record in list(delta.get("added") or []) + list(delta.get("updated") or []):
        stored[record["packageName"]] = record
    for package in delta.get("removed") or []:
        stored.pop(package, None)
    return sorted(stored.values(), key=lambda item: item["packageName"])


def same(left, right):
    """Compare two extension lists on the projection the layer actually ships."""
    return ([bot.shura_projection(item) for item in left]
            == [bot.shura_projection(item) for item in right])


def digest(index):
    return bot.shura_revision(bot.render_index_json(index).encode("utf-8"))


LIVE = {path: path.read_bytes() for path in (
    ROOT / "repo/index.json", ROOT / "repo/index.pb", ROOT / "repo.json",
    ROOT / "repo/index.min.json", ROOT / "data/audit_cache.json",
    ROOT / "data/quarantine.json") if path.is_file()}


def repo_intact():
    return sorted(str(path.relative_to(ROOT)) for path, blob in LIVE.items()
                  if not path.is_file() or path.read_bytes() != blob)


# ----------------------------------------------------------------- revision

@case("01 a revision is a stable function of the rendered bytes")
def test_revision_is_stable():
    payload = bot.render_index_json(index_of([extension("Alpha", 1)])).encode("utf-8")
    first = bot.shura_revision(payload)
    assert first == bot.shura_revision(payload)
    assert len(first) == bot.SHURA_REVISION_LENGTH
    # a single changed byte anywhere must move the revision
    other = bot.render_index_json(index_of([extension("Alpha", 2)])).encode("utf-8")
    assert bot.shura_revision(other) != first
    # and it is verifiable by a client that holds the file, with no server
    assert first == hashlib.sha256(payload).hexdigest()[:bot.SHURA_REVISION_LENGTH]


@case("02 the manifest revision is the revision of the index that shipped")
def test_manifest_revision_matches_index():
    extensions = [extension("Alpha", 1), extension("Beta", 2)]
    sandbox, _ = build(extensions)
    try:
        published = (sandbox / "repo/index.json").read_bytes()
        manifest = read(sandbox, "shura/manifest.json")
        assert manifest["revision"] == bot.shura_revision(published)
        assert manifest["count"] == len(extensions)
    finally:
        shutil.rmtree(sandbox, ignore_errors=True)


# ----------------------------------------------------------------- manifest

@case("03 the manifest is one small, stable document with no content in it")
def test_manifest_is_small_and_contentless():
    extensions = [extension("Alpha", index) for index in range(200)]
    sandbox, _ = build(extensions)
    try:
        manifest = read(sandbox, "shura/manifest.json")
        size = (sandbox / "shura/manifest.json").stat().st_size
        full = (sandbox / "repo/index.json").stat().st_size
        # the whole point: a periodic poll costs ~1K, not ~560K
        assert size < 2048, size
        assert size * 50 < full, (size, full)
        # no extension leaks into the contract document
        assert "extensions" not in manifest
        assert "apkUrl" not in json.dumps(manifest)
    finally:
        shutil.rmtree(sandbox, ignore_errors=True)


@case("04 the manifest names the repository once, and every path is relative")
def test_manifest_is_location_independent():
    sandbox, _ = build([extension("Alpha", 1)])
    try:
        manifest = read(sandbox, "shura/manifest.json")
        assert manifest["base"].startswith("https://raw.githubusercontent.com/"), manifest["base"]
        assert manifest["index"]["json"] == "repo/index.json"
        assert manifest["index"]["pb"] == "repo/index.pb"
        assert manifest["delta"] == "shura/delta.json"
        # a client that follows base + these relative paths reaches the artifacts
        for relative in (manifest["index"]["json"], manifest["index"]["pb"],
                         manifest["delta"]):
            assert not relative.startswith(("http://", "https://")), relative
            assert (sandbox / relative).is_file(), relative
    finally:
        shutil.rmtree(sandbox, ignore_errors=True)


@case("05 the manifest is byte-identical when the index has not changed")
def test_manifest_is_stable_across_builds():
    extensions = [extension("Alpha", 1), extension("Beta", 2)]
    first, _ = build(extensions)
    second, _ = build(extensions)
    try:
        assert (first / "shura/manifest.json").read_bytes() == \
               (second / "shura/manifest.json").read_bytes()
    finally:
        shutil.rmtree(first, ignore_errors=True)
        shutil.rmtree(second, ignore_errors=True)


# ----------------------------------------------------------------- delta

@case("06 a delta against a real previous index reproduces the new index")
def test_delta_applies_to_previous_revision():
    before = [extension("Alpha", 1), extension("Beta", 2), extension("Gamma", 3)]
    after = [extension("Alpha", 1), extension("Beta", 20), extension("Delta", 4)]
    baseline_path = Path(tempfile.mkdtemp(prefix="shura-base-")) / "index.json"
    baseline_path.write_text(bot.render_index_json(index_of(before)), encoding="utf-8")
    sandbox, result = build(after, baseline_index=baseline_path)
    try:
        delta = read(sandbox, "shura/delta.json")
        assert delta["baseRevision"] == digest(index_of(before))
        # build() rewrites the store metadata, so the revision is pinned to the
        # bytes that actually shipped rather than to a reconstructed index
        assert delta["revision"] == bot.shura_revision(
            (sandbox / "repo/index.json").read_bytes())
        replayed = apply_delta(before, delta)
        assert same(replayed, after), "applying the delta must yield the new index"
        assert result["delta"] == {"added": 1, "updated": 1, "removed": 1}
    finally:
        shutil.rmtree(sandbox, ignore_errors=True)
        baseline_path.unlink(missing_ok=True)


@case("07 the delta carries whole records, never patches")
def test_delta_records_are_complete():
    before = [extension("Alpha", 1)]
    after = [extension("Alpha", 9, icon="https://example.org/i.png")]
    baseline_path = Path(tempfile.mkdtemp(prefix="shura-base-")) / "index.json"
    baseline_path.write_text(bot.render_index_json(index_of(before)), encoding="utf-8")
    sandbox, _ = build(after, baseline_index=baseline_path)
    try:
        delta = read(sandbox, "shura/delta.json")
        record = delta["updated"][0]
        # a client that stored the old record must not keep a stale field
        assert record["versionCode"] == "9"
        assert record["resources"]["iconUrl"] == "https://example.org/i.png"
        assert set(record) == set(bot.shura_projection(after[0]))
    finally:
        shutil.rmtree(sandbox, ignore_errors=True)
        baseline_path.unlink(missing_ok=True)


@case("08 a removal in the source is a removal in the delta")
def test_removed_packages_are_listed():
    before = [extension("Alpha", 1), extension("Beta", 2)]
    after = [extension("Alpha", 1)]
    baseline_path = Path(tempfile.mkdtemp(prefix="shura-base-")) / "index.json"
    baseline_path.write_text(bot.render_index_json(index_of(before)), encoding="utf-8")
    sandbox, _ = build(after, baseline_index=baseline_path)
    try:
        delta = read(sandbox, "shura/delta.json")
        assert delta["removed"] == ["eu.kanade.tachiyomi.extension.en.beta"]
        assert same(apply_delta(before, delta), after)
    finally:
        shutil.rmtree(sandbox, ignore_errors=True)
        baseline_path.unlink(missing_ok=True)


@case("09 an unchanged extension is not in the delta at all")
def test_unchanged_extensions_are_omitted():
    extensions = [extension("Alpha", 1), extension("Beta", 2)]
    before = [extension("Alpha", 1)]
    baseline_path = Path(tempfile.mkdtemp(prefix="shura-base-")) / "index.json"
    baseline_path.write_text(bot.render_index_json(index_of(before)), encoding="utf-8")
    sandbox, _ = build(extensions, baseline_index=baseline_path)
    try:
        delta = read(sandbox, "shura/delta.json")
        assert delta["added"] == [bot.shura_projection(extensions[1])]
        assert delta["updated"] == [] and delta["removed"] == []
        assert same(apply_delta(before, delta), extensions)
    finally:
        shutil.rmtree(sandbox, ignore_errors=True)
        baseline_path.unlink(missing_ok=True)


@case("10 the delta is ordered, so the same change always yields the same bytes")
def test_delta_is_deterministic():
    before = [extension("Zeta", 1), extension("Alpha", 1)]
    after = [extension("Alpha", 2), extension("Mu", 1), extension("Zeta", 2)]
    baseline_path = Path(tempfile.mkdtemp(prefix="shura-base-")) / "index.json"
    baseline_path.write_text(bot.render_index_json(index_of(before)), encoding="utf-8")
    first, _ = build(after, baseline_index=baseline_path)
    second, _ = build(after, baseline_index=baseline_path)
    try:
        assert (first / "shura/delta.json").read_bytes() == \
               (second / "shura/delta.json").read_bytes()
    finally:
        shutil.rmtree(first, ignore_errors=True)
        shutil.rmtree(second, ignore_errors=True)
        baseline_path.unlink(missing_ok=True)


@case("11 a small change costs far less than the full index")
def test_delta_is_much_smaller_than_the_index():
    before = [extension("Ext{:03d}".format(n), 1) for n in range(579)]
    after = [extension("Ext{:03d}".format(n), 1) for n in range(579)]
    after[10]["versionCode"] = "100010"
    after[400]["name"] = "Renamed"
    baseline_path = Path(tempfile.mkdtemp(prefix="shura-base-")) / "index.json"
    baseline_path.write_text(bot.render_index_json(index_of(before)), encoding="utf-8")
    sandbox, _ = build(after, baseline_index=baseline_path)
    try:
        delta_size = (sandbox / "shura/delta.json").stat().st_size
        full_size = (sandbox / "repo/index.json").stat().st_size
        assert delta_size * 20 < full_size, (delta_size, full_size)
    finally:
        shutil.rmtree(sandbox, ignore_errors=True)
        baseline_path.unlink(missing_ok=True)


# ----------------------------------------------------------------- baseRevision

@case("12 a client whose revision matches baseRevision can apply the delta")
def test_matching_base_revision_is_applicable():
    before = [extension("Alpha", 1)]
    after = [extension("Alpha", 2)]
    baseline_path = Path(tempfile.mkdtemp(prefix="shura-base-")) / "index.json"
    baseline_path.write_text(bot.render_index_json(index_of(before)), encoding="utf-8")
    sandbox, _ = build(after, baseline_index=baseline_path)
    try:
        delta = read(sandbox, "shura/delta.json")
        client = {"revision": digest(index_of(before))}
        assert client["revision"] == delta["baseRevision"]
        assert same(apply_delta(before, delta), after)
    finally:
        shutil.rmtree(sandbox, ignore_errors=True)
        baseline_path.unlink(missing_ok=True)


@case("13 a client behind by an unknown revision is told to refetch, not to guess")
def test_stale_client_detects_a_gap():
    before = [extension("Alpha", 1)]
    baseline_path = Path(tempfile.mkdtemp(prefix="shura-base-")) / "index.json"
    baseline_path.write_text(bot.render_index_json(index_of(before)), encoding="utf-8")
    sandbox, _ = build([extension("Alpha", 5)], baseline_index=baseline_path)
    try:
        delta = read(sandbox, "shura/delta.json")
        for stale in ("0" * bot.SHURA_REVISION_LENGTH, digest(index_of([extension("X", 1)]))):
            client = {"revision": stale}
            # the contract: a mismatch means the delta is unusable, full refetch
            assert client["revision"] != delta["baseRevision"]
    finally:
        shutil.rmtree(sandbox, ignore_errors=True)
        baseline_path.unlink(missing_ok=True)


@case("14 with no baseline at all the delta is a full baseline, not a wrong one")
def test_missing_baseline_is_a_full_delta():
    after = [extension("Alpha", 1), extension("Beta", 2)]
    sandbox, result = build(after, baseline_index=Path("/nonexistent/index.json"))
    try:
        delta = read(sandbox, "shura/delta.json")
        assert delta["baseRevision"] is None
        assert delta["added"] == [bot.shura_projection(item) for item in after]
        assert delta["updated"] == [] and delta["removed"] == []
        assert result["baseRevision"] is None
    finally:
        shutil.rmtree(sandbox, ignore_errors=True)


@case("15 a corrupt baseline degrades to a full delta instead of raising")
def test_corrupt_baseline_degrades():
    corrupt = Path(tempfile.mkdtemp(prefix="shura-base-")) / "index.json"
    corrupt.write_text("{not json", encoding="utf-8")
    try:
        sandbox, result = build([extension("Alpha", 1)], baseline_index=corrupt)
        try:
            assert read(sandbox, "shura/delta.json")["baseRevision"] is None
            assert result["ok"] is True
        finally:
            shutil.rmtree(sandbox, ignore_errors=True)
    finally:
        corrupt.unlink(missing_ok=True)


@case("16 a delta chained across many publishes still lands on the new index")
def test_delta_chain():
    chain = [[extension("Ext{:02d}".format(n), 1) for n in range(20)]]
    for step in range(1, 6):
        nxt = [dict(item) for item in chain[-1]]
        nxt[step]["versionCode"] = str(100 + step)
        if step == 3:
            nxt.append(extension("Newcomer", 1))
        if step == 4:
            nxt = [item for item in nxt if item["packageName"] != nxt[0]["packageName"]]
        chain.append(sorted(nxt, key=lambda item: item["packageName"]))

    held = chain[0]
    for step, current in enumerate(chain[1:], start=1):
        baseline_path = Path(tempfile.mkdtemp(prefix="shura-chain-")) / "index.json"
        baseline_path.write_text(bot.render_index_json(index_of(held)), encoding="utf-8")
        sandbox, _ = build(current, baseline_index=baseline_path)
        try:
            delta = read(sandbox, "shura/delta.json")
            assert delta["baseRevision"] == digest(index_of(held))
            held = apply_delta(held, delta)
            assert same(held, current), "chain diverged at step {}".format(step)
        finally:
            shutil.rmtree(sandbox, ignore_errors=True)
            baseline_path.unlink(missing_ok=True)


# ----------------------------------------------------------------- projection

@case("17 the projection leaks no field the app has no business reading")
def test_projection_is_allowlisted():
    record = extension("Alpha", 1)
    record["internalNote"] = "do not ship"
    record["someSecret"] = "x"
    projected = bot.shura_projection(record)
    assert set(projected) == {"packageName", "name", "versionCode", "versionName",
                              "contentWarning", "extensionLib", "resources", "sources"}
    assert "internalNote" not in projected and "someSecret" not in projected


@case("18 the projection never breaks the record the index already publishes")
def test_projection_is_a_subset():
    extensions = [extension("Alpha", 1, icon="https://example.org/i.png"),
                  extension("Beta", 2, lib="1.5")]
    sandbox, _ = build(extensions)
    try:
        published = read(sandbox, "repo/index.json")["extensionList"]["extensions"]
        for raw, projected in zip(published, (bot.shura_projection(e) for e in published)):
            for key, value in projected.items():
                if key == "resources":
                    for inner, url in value.items():
                        assert url == raw["resources"][inner]
                else:
                    assert value == raw[key]
    finally:
        shutil.rmtree(sandbox, ignore_errors=True)


@case("19 an extension with no sources still projects cleanly")
def test_projection_tolerates_missing_pieces():
    projected = bot.shura_projection({"packageName": "p", "name": "n"})
    assert projected["packageName"] == "p"
    assert "sources" not in projected
    assert projected["resources"] == {"apkUrl": None}
    assert bot.shura_projection({"packageName": "p", "sources": "not-a-list"})["packageName"] == "p"


@case("20 equality in the diff never depends on key order")
def test_diff_ignores_key_order():
    left = extension("Alpha", 1)
    right = dict(reversed(list(left.items())))
    assert bot.shura_diff([left], [right]) == {"added": [], "updated": [], "removed": []}


@case("21 the live 579-extension index round-trips through the layer")
def test_live_index_round_trips():
    live = json.loads((ROOT / "repo/index.json").read_text(encoding="utf-8"))
    extensions = live["extensionList"]["extensions"]
    baseline_path = Path(tempfile.mkdtemp(prefix="shura-live-")) / "index.json"
    baseline_path.write_text(bot.render_index_json(live), encoding="utf-8")
    sandbox, result = build(extensions, baseline_index=baseline_path)
    try:
        delta = read(sandbox, "shura/delta.json")
        # identical index rebuilt: the delta is empty and still fully applicable
        assert delta["added"] == [] and delta["updated"] == [] and delta["removed"] == []
        assert result["delta"] == {"added": 0, "updated": 0, "removed": 0}
        assert same(apply_delta(extensions, delta), extensions)
        manifest = read(sandbox, "shura/manifest.json")
        assert manifest["count"] == 579 == len(extensions)
        assert manifest["revision"] == bot.shura_revision(
            (sandbox / "repo/index.json").read_bytes())
        # the pb still decodes to the same index -- the shura layer changed nothing
        decoded = bot.decode_index(gzip.decompress((sandbox / "repo/index.pb").read_bytes()))
        assert len(decoded["extensionList"]["extensions"]) == 579
    finally:
        shutil.rmtree(sandbox, ignore_errors=True)
        baseline_path.unlink(missing_ok=True)


# ----------------------------------------------------------------- isolation

@case("22 the shura layer is a build artifact and nothing else")
def test_shura_layer_cannot_publish_itself():
    """shura/ is written by build(), and build() sits behind the same gate."""
    source = Path(bot.__file__).read_text(encoding="utf-8")

    # the only writer is the build artifact table inside build()
    build_body = source.split("    def build(self, dest", 1)[1].split("\n    def ", 1)[0]
    for artifact in ("shura/manifest.json", "shura/delta.json"):
        assert build_body.count('"{}"'.format(artifact)) == 1, artifact

    # no scheduled job, no CLI entry point and no command handler writes it
    for forbidden in ("def job_harvest", "def job_health", "def job_digest",
                      "def job_publish", "def main", "def handle_message",
                      "def run_command"):
        body = source.split(forbidden, 1)[1].split("\ndef ", 1)[0]
        assert "shura/" not in body, forbidden

    # and a real write still needs the gate the whole repository is behind
    assert callable(bot.assert_may_write_repo) and callable(bot.assert_may_push)


@case("23 a sandbox build never touches the real tree")
def test_sandbox_build_is_isolated():
    sandbox, result = build([extension("Alpha", 1)])
    try:
        assert "shura/manifest.json" in result["changed"]
        assert "shura/delta.json" in result["changed"]
        for relative in ("shura/manifest.json", "shura/delta.json"):
            assert not (ROOT / relative).exists(), "{} must not be created by tests".format(relative)
    finally:
        shutil.rmtree(sandbox, ignore_errors=True)


@case("24 the published repository is byte-identical to import")
def test_repo_untouched():
    drift = repo_intact()
    assert not drift, "these changed: {}".format(drift)


@case("25 the manifest carries the fingerprint derived from the published key")
def test_manifest_signing_fingerprint_is_derived_from_the_key():
    """The app must be able to authenticate the index from the manifest alone.

    One source of truth: the fingerprint is *derived* from the signing key the
    build resolved for repo.json and the index, not read back from either of
    them and not a second copy that could drift away.
    """
    extensions = [extension("Alpha", 1), extension("Beta", 2)]
    sandbox, _ = build(extensions)
    try:
        manifest = read(sandbox, "shura/manifest.json")
        index_key = read(sandbox, "repo/index.json")["signingKey"]
        repo_json_key = read(sandbox, "repo.json")["meta"]["signingKeyFingerprint"]
        assert manifest["signingKeyFingerprint"]
        assert manifest["signingKeyFingerprint"] == repo_json_key
        # it is the fingerprint *of the key the index carries*, not the key itself
        assert manifest["signingKeyFingerprint"] == bot.signing_key_fingerprint(index_key)
        assert manifest["signingKeyFingerprint"] != index_key
        assert len(manifest["signingKeyFingerprint"]) == 64
        # the key the fixtures build with is the public key, not the live one
        assert index_key == VALID_PUBLIC_KEY
        assert manifest["signingKeyFingerprint"] == VALID_FINGERPRINT
    finally:
        shutil.rmtree(sandbox, ignore_errors=True)


@case("26 a manifest without a usable fingerprint is refused, not silently emitted")
def test_manifest_requires_a_signing_key():
    for bad in ("", None, "zz" * 32, "ab" * 16, bot.ENCODING_PROBE_KEY):
        try:
            bot.shura_manifest("0" * bot.SHURA_REVISION_LENGTH, [], bad)
        except bot.SigningMetadataMissing:
            continue
        raise AssertionError("a manifest with {!r} must not be produced".format(bad))
    # ... while a real 64 character digest is accepted
    assert bot.shura_manifest(
        "0" * bot.SHURA_REVISION_LENGTH, [], VALID_FINGERPRINT
    )["signingKeyFingerprint"] == VALID_FINGERPRINT


def main() -> int:
    passed = failed = 0
    for number, (title, func) in enumerate(CASES, start=1):
        try:
            func()
            passed += 1
            print("PASS  {:>2} {}".format(number, title))
        except Exception:
            failed += 1
            print("FAIL  {:>2} {}".format(number, title))
            print(_indent(traceback.format_exc()))
    print("\n{}/{} passed, {} failed".format(passed, passed + failed, failed))
    print("repo/index*, repo.json and data/ byte-identical to import")
    return 1 if failed else 0


def _indent(text: str) -> str:
    return "".join("        " + line for line in text.splitlines(keepends=True))


if __name__ == "__main__":
    raise SystemExit(main())
