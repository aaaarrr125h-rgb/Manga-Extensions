# Shura Sync — the contract between this repository and the Shura app

This directory is the **Shura Sync layer**. It is a small, separately versioned
contract that the Shura app reads, kept out of `repo/` so Shura data and Mihon
extension data never mix.

Everything here is **derived** from `repo/index.json` in the same build that
writes it. There is no separate pipeline, so the two cannot drift apart.

```
shura/manifest.json   the contract: one small document, read on every poll
shura/delta.json      what changed since a client's revision
```

## What the app reads, and in what order

1. **`shura/manifest.json`** — always, and cheaply. It carries no content, only
   `revision`, `count` and the location of everything else. Fetch it with
   `If-None-Match`; a `304` means nothing changed and the app downloads nothing.
2. **`shura/delta.json`** — only when `manifest.revision` differs from the
   revision the app already has.
3. **`repo/index.json`** (or `repo/index.pb`) — only when the delta cannot be
   applied, see below.

The result is that a routine poll costs a few hundred bytes instead of the
~560 KB full index.

## Location independence

The app must never hardcode a repository location. It reads `manifest.base` and
resolves every other path **relative** to it:

```json
"base": "https://raw.githubusercontent.com/aaaarrr125h-rgb/Manga-Extensions/main",
"index": { "json": "repo/index.json", "pb": "repo/index.pb" },
"delta": "shura/delta.json"
```

Moving or renaming the repository is a one-line change to `manifest.base` in
`bot.py` and **no change at all** in the app.

## Revisions

A revision is `sha256(repo/index.json bytes)`, truncated to 32 hex characters.

It is an *identifier*, not a security boundary — the app can recompute it from
the file it already holds, with no server involved. Authenticity comes from
`signingKeyFingerprint`, exactly as it does for the Mihon index.

## Applying a delta

`delta.baseRevision` is the revision the delta was computed **against**.

```
stored_revision == delta.baseRevision  ->  apply the delta
otherwise                             ->  discard it, refetch the full index
```

The delta carries **whole records, never patches**. A patch would make the
client's stored copy authoritative and let a skipped field persist forever; the
redundant bytes buy a client that cannot get out of step.

```json
{ "schema": 1, "baseRevision": "…", "revision": "…", "count": 579,
  "added":   [ /* complete extension records */ ],
  "updated": [ /* complete extension records */ ],
  "removed": [ "<packageName>" ] }
```

Apply it as a map keyed by `packageName`: add and update overwrite, remove
deletes. The result must equal the new `repo/index.json` — `test_shura_sync.py`
asserts exactly that, and asserts it over a chain of five consecutive publishes.

## The record the app receives

A **projection** of the index entry, so the delta can never carry anything the
published index does not already expose:

| field | purpose |
|---|---|
`packageName` | the primary key — everything is keyed on it |
`name` | display name |
`versionCode` | update detection |
`versionName` | display |
`contentWarning` | safety badge |
`extensionLib` | host API level |
`resources.apkUrl` | download |
`resources.iconUrl` | icon |
`sources[]` | `id`, `name`, `language`, `homeUrl` — load lazily, only when a source is opened |

`data/audit_cache.json` and `data/sources.json` are **bot bookkeeping and are
never served to the app**.

## When there is no baseline

On the first build, or if the previous index is unreadable, `baseRevision` is
`null` and every extension is listed in `added`. That is a larger delta, never a
wrong one — and a client that cannot use it still has the full index. The layer
always fails towards correctness, not towards size.

## What belongs here, and what does not

| In this repository (facts, derived, version-controlled) | In a future backend (per-user, mutable) |
|---|---|
`shura/manifest.json`, `shura/delta.json` | accounts, sessions, sync keys |
`repo/index.json`, `repo/index.pb` | comments, ratings, follows |
 | library sync, reading progress |
 | download counts, telemetry |

The line: anything that is a **static fact derived from the index** is Git;
anything a **user writes** needs a backend. Git has no secrets, no state and no
concurrency — none of which may be needed by an extension catalogue, and all of
which are required by a community.

## Versioning

`schema` is bumped whenever a field changes meaning or disappears. The app must
refuse a `schema` it does not know rather than guess. Adding an optional field
is not a bump.
