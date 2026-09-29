# Shura — Mihon Extension Repository Manager

A single-file Python service that keeps a curated, **strictly SFW** Mihon/Tachiyomi
extension repository up to date, and doubles as a security guard for Telegram groups.

It has two halves, both in [`bot.py`](bot.py):

| | |
|---|---|
| **Repo manager** | Scrapes the [keiyoushi](https://github.com/keiyoushi/extensions) index, audits every new extension, health-checks live sources, and regenerates `repo/index.pb` for Mihon — then commits it back to GitHub. |
| **Group guard** | Watches group messages, deletes and bans phishing/malware links, and asks admins to approve or block anything suspicious via inline buttons. |

No database, no Docker, no build step. Three Python dependencies.

---

## How the pipeline works

```
upstream ──► harvest ──► candidate ──► /review ──► /accept ──► ACCEPTED
               │  (data/ only)      (read-only)   (manual, admin)     │
               │                                                          ▼
               └─── health ──► proposals (data/ only)          manual publish phase
                                                                          │
                                              repo/index.json ◄──────────┤
                                              repo/index.pb  ◄───────────┤
                                              GitHub ──► Mihon ◄──────────┘
```

**Nothing reaches the published repository except the manual publish phase.**
A scrape only ever produces a **candidate** in `data/candidates.json`; a health
sweep only ever produces **proposals** in `data/`; `--accept` only ever moves a
state. `repo/index.json` is written in exactly one place in the code base — the
index build — and that build is unreachable without an explicit publish
authorisation held by a named actor.

1. **Harvest** (`--harvest`, `/scan`, `CRON_HARVEST`, `BOOTSTRAP_HARVEST`) pulls the
   upstream index and creates a candidate for any extension that is new or has a
   higher `versionCode`. The commit it pins and the APK fingerprint are captured
   at creation time. It never builds, publishes, pushes, or accepts anything.
2. **The pipeline** runs one gate at a time over the candidate, and the state
   machine refuses any step that is not backed by passing evidence:

   | State | Requires |
   |---|---|
   | `DISCOVERED` | — |
   | `VALIDATED` | schema complete: name, apk/icon, sources, `versionCode`, `contentWarning` |
   | `SCREENED` | NSFW policy + URL screening (`/review_links`, `--screen`) |
   | `SCANNED` | source-code scan **and** APK binary scan |
   | `BUILT` | offline build/test of the would-be index entry |
   | `PENDING_REVIEW` | all five gates `PASS` with intact digests |
   | `ACCEPTED` | re-verified provenance (see below), set by `--accept` only |

   `REJECTED` and `QUARANTINED` are terminal until a new candidate is created.
   `DISCOVERED → ACCEPTED` is not an edge: the only way in is
   `PENDING_REVIEW → ACCEPTED`, and only through an admin-authorised accept.

   A candidate stores, at minimum: `packageName`, `name`, `versionCode`,
   `versionName`, `sourceRepository`, `sourceCommit`, `apkUrl`, `apkSha256`,
   `apkSize`, `contentWarning`, the five `*Result` fields, `discoveredAt`,
   `updatedAt` and `status`.
3. **Health sweep** (`/health`, `CRON_HEALTH`) probes a rolling window of
   `homeUrl`s and records every result in `data/guard_state.json`. After
   `DEAD_THRESHOLD` consecutive failures it **stages a proposal** — a quarantine
   entry and/or a dead-source removal — and applies nothing to the index: a link
   outage must never silently change what users are offered. Proposals are read
   with `STATE.staged_proposals()` and acted on in the manual publish phase.
   Self-hosted extensions (Komga, Kavita, Bakkin…) are skipped — they
   legitimately point at `127.0.0.1` or a LAN address, which is why `screen()`
   screens source URLs with `allow_private=True` while keeping distribution URLs
   strict.

   > **Quarantine is staged, not enforced (by design, for now).**
   > `health_sweep()` only *creates* proposals. It does not edit
   > `repo/index.json`, and `build()` does not consult `blocked_packages()`
   > either, so a staged quarantine entry currently has **no** effect on what
   > gets built or published. That is deliberate: a link outage must not remove
   > a working extension on its own. Applying proposals is a separate
   > *quarantine review* step that is **not implemented yet** — until it lands,
   > a proposal in `data/quarantine.json` means "a human should look at this",
   > never "this is already disabled".
4. **Publish** is a *separate, manual phase* and is currently closed. Two things
   must both be true before anything is written or pushed:

   - `PUBLISH_MODE=allow` **and** `PUSH_ENABLED` with a real `GITHUB_TOKEN`
     (configuration only — an operator sets it by hand), **and**
   - a live `PublishAuthorization`, opened by `run_publish_phase(actor, reason)`
     for that one call.

   `RepoManager.publish()`, `RepoManager.push()` and a non-sandboxed
   `RepoManager.build()` all call `assert_may_push()` / `assert_may_write_repo()`,
   which require the authorisation and raise `PushBlocked` without it. The key is
   thread-local and is dropped on the way out, so no cron thread, command,
   callback or CLI flag can inherit it.

   Everything that *looks* like publishing is therefore a dry-run:
   `/publish` and `CRON_PUBLISH` run `RepoManager.prepare_publish()`, which
   builds into a throwaway directory and reports what *would* change;
   `--build` is a sandbox build; `/scan`, `/health` and `--harvest` write to
   `data/` only. Index output is deterministic: identical input produces a
   byte-identical `index.pb`.

### Provenance and TOCTOU

A candidate is only reviewable if its provenance is pinned and immutable:

- **`sourceCommit`** must be a full 40/64-hex commit SHA. A branch (`main`) or a
  tag is resolved to a SHA at discovery; if no SHA can be obtained the candidate
  is stored as `REJECTED` and can never be accepted. `main` and `master` are not
  acceptable references on their own.
- **APK integrity**: the APK is fetched over HTTPS only, and its SHA-256, byte
  size, URL and verification timestamp are recorded. A candidate without a
  SHA-256 is never created.
- Each gate writes a self-describing digest of its own record, and
  `verifiedProvenance` is a SHA-256 over `packageName`, `versionCode`,
  `versionName`, `sourceRepository`, `sourceCommit`, `apkUrl`, `apkSha256` and
  `apkSize`. Any drift in one of those fields, or any hand-edited gate record,
  is detected.
- `--accept` re-verifies everything *live* immediately before accepting: the
  commit is re-resolved, and the APK is downloaded again and compared by digest
  and size. If anything moved, the accept is refused, all scan evidence is
  dropped and the candidate returns to `DISCOVERED` for a full re-scan.

Rejections are remembered in `data/audit_cache.json` (keyed by package +
`versionCode`) so the same dead candidate is never re-audited, quarantines in
`data/quarantine.json`, and every decision in `data/audit_log.jsonl`
(timestamp, action, package, version, commit, APK digest, actor, result, reason)
with credentials redacted.

### The `index.pb` writer

Mihon wants a gzipped protobuf, not JSON. `bot.py` implements a minimal
protobuf/gzip codec (~180 lines) that is verified byte-exact against the official
keiyoushi `index.pb` in the self-test, so the published index is guaranteed
readable by Mihon.

---

## Requirements

- Python **3.8+** (developed and tested on 3.14)
- A Telegram bot token (from [@BotFather](https://t.me/BotFather))
- A GitHub token with **Contents: read+write** on this repository (optional — the
  bot runs fine without it, it just won't push)

```bash
pip install -r requirements.txt
```

---

## Configuration

Every setting is an environment variable; `.env.example` lists all of them with
their defaults. The bot reads `os.environ` directly and does **not** parse a
`.env` file, so export the variables yourself before starting it:

```bash
cp .env.example .env        # then edit it
set -a; . ./.env; set +a    # load them into the environment
python bot.py
```

On Railway you do not need this — variables are injected by the platform.

### Required

| Variable | Meaning |
|---|---|
| `TELEGRAM_BOT_TOKEN` | Token from @BotFather. Without it only the CLI modes work. |
| `ADMIN_ID` | Your numeric Telegram id, or a comma-separated list. Alerts go here. |

### GitHub publishing (optional)

| Variable | Default | Meaning |
|---|---|---|
| `GITHUB_TOKEN` | – | Fine-grained token with `Contents: read+write`. |
| `GITHUB_REPO` | `aaaarrr125h-rgb/Manga-Extensions` | `owner/name`. |
| `GITHUB_BRANCH` | `main` | Target branch. |
| `PUSH_ENABLED` | auto | Force-disable pushing entirely. |
| `PUBLISH_MODE` | `disabled` | `disabled` \| `dry-run` \| `allow`. Necessary but **not** sufficient: a publish also needs a live `PublishAuthorization` from `run_publish_phase()`. |

### Repository identity

| Variable | Default | Meaning |
|---|---|---|
| `REPO_NAME` | `Shura` | Store name shown in Mihon. |
| `BADGE_LABEL` | `SHURA` | Short label on the repo card. |
| `REPO_WEBSITE` | repo URL | Shown as the repo contact. |
| `REPO_DISCORD` | – | Optional Discord invite. |
| `SIGNING_KEY` | see `.env.example` | The store's signing key: the 64 character SHA-256 digest of the certificate the extension APKs are signed with. Clients hash that certificate out of the APK and compare it against this value, so the same value is republished in `repo.json` and `shura/manifest.json`. |

### Upstream scraping

| Variable | Default | Meaning |
|---|---|---|
| `UPSTREAM_INDEX_JSON` | keiyoushi index | Where extensions are scraped from. |
| `UPSTREAM_SOURCE_REPO` / `_REF` | `keiyoushi/extensions-source` / `main` | Source used for the malware scan. |
| `SOURCE_SCAN` | `true` | Turn the Kotlin source scan off to halve harvest time. |
| `MAX_SOURCE_FILES` | `12` | Files read per extension during the scan. |
| `MAX_SCAN_PER_RUN` | `50` | Audit budget per harvest run. |
| `MAX_NEW_PER_RUN` | `40` | Cap on newly admitted extensions per run. |

### Security policy

| Variable | Default | Meaning |
|---|---|---|
| `ALLOW_MIXED` | `false` | `true` admits `CONTENT_WARNING_MIXED` extensions. |
| `AUTO_DELETE` / `AUTO_BAN` | `true` | Guard auto-enforcement. |
| `REPORT_UNKNOWN` | `false` | Report every non-allowlisted link (noisy review mode). |
| `WATCH_CHATS` | all | Restrict the guard to specific chat ids. |

### Health & schedule

| Variable | Default | Meaning |
|---|---|---|
| `HEALTH_SLICE` / `HEALTH_WORKERS` | `60` / `12` | Probes per sweep, and concurrency. |
| `DEAD_THRESHOLD` | `3` | Consecutive failures before a quarantine **proposal** is staged. |
| `CRON_HARVEST` | `17 */6 * * *` | Scrape + candidate creation (no publish). |
| `CRON_HEALTH` | `41 */3 * * *` | Source health sweep. |
| `CRON_PUBLISH` | `23 */2 * * *` | Readiness **report** only (dry-run). It never builds into `repo/`, never pushes, and never accepts. |
| `CRON_DIGEST` | `53 9 * * *` | Daily digest to admins. |
| `TIMEZONE` | `UTC` | Timezone the cron expressions use. |

---

## Running

```bash
python bot.py                 # full service: Telegram polling + scheduler
python bot.py --selftest      # offline checks; never writes repo/, never pushes
python bot.py --build         # sandbox dry-run build; never writes repo/
python bot.py --harvest       # one discovery + health cycle, then exit
python bot.py --no-telegram   # identical to --harvest; kept for clarity
python bot.py --review <pkg>  # print a candidate report; strictly read-only
python bot.py --accept <pkg> --admin --actor <who>
                             # PENDING_REVIEW -> ACCEPTED; no build, publish or push
python test_review_pipeline.py   # the review/accept test suite (offline, no push)
```

`--selftest` is the fastest way to check a change: it covers the protobuf
round-trip against the real keiyoushi index, the NSFW and URL filters, the
malware scanner, the group-guard integration (with a fake Telegram client) and a
full index build. It runs with `TEST_MODE` forced on, builds into a throwaway
directory and asserts that `repo/index.json` and the candidate store are byte
identical afterwards, so running it can never publish anything.

`--review` only reads: it prints the candidate, every gate result, the pinned
`sourceCommit`, the APK SHA-256/size and the current status, and it does not
build, publish, push or touch the index. `--accept` requires `--admin` (and an
`--actor` for the audit trail) and means exactly one thing:
`PENDING_REVIEW → ACCEPTED`.

### Adding the repository to Mihon

In Mihon: **Settings → Repositories → Add repository**, then paste:

```
https://raw.githubusercontent.com/aaaarrr125h-rgb/Manga-Extensions/main/repo.json
```

The bot's `/repo` command returns the same URL plus a one-tap deep link.

---

## Telegram commands

Available to everyone: `/start`, `/repo`, `/latest`, `/help`, `/id`.

Admins only (by numeric Telegram user id, never by username): `/status`,
`/scan`, `/review <package>`, `/accept <package>`, `/candidates`, `/health`,
`/publish`, `/quarantine`, `/unquarantine <package>`, `/allow <domain>`,
`/block <domain>`, `/ban <id>`, `/unban <id>`, `/lists`, `/reset`.

`/review` is read-only. `/scan` and `/health` are read-only with respect to the
published repository. `/accept` only moves a candidate to `ACCEPTED` after
re-verifying it live — acceptance is not publishing. `/publish` publishes
nothing: it runs a sandboxed dry-run and reports what a manual publish *would*
change.

Guard behaviour on a group message:

| Link | Action |
|---|---|
| `https://1.2.3.4/payload` | deleted + sender banned, no admin needed |
| `https://bit.ly/…` (shortener) | reported to admins with approve/block buttons |
| `mangadex-hack.ru` (typosquat) | deleted + banned |
| a known manga site | passes silently |

Blocking a domain from a report is permanent; allow/block lists and user bans are
persisted in `data/guard_state.json`.

To enforce the guard, add the bot to a group and promote it to admin with
**Delete messages** and **Ban users** enabled — without those permissions the
delete/ban API calls fail and the bot only reports. It also needs to be able to
read messages, so either disable privacy mode or make the bot an admin.

---

## Deploying to Railway

`railway.json` and `Procfile` are already in the repo — Railway detects the worker
and starts `python bot.py`. No build configuration is required (NIXPACKS detects
`requirements.txt` and installs it).

1. **Push the code** to GitHub, then in Railway choose **New Project → Deploy from
   GitHub repo** and pick this repository. No build or start command needs to be
   typed — both are read from `railway.json`.
2. **Add the environment variables.** In the service's **Variables** tab, add at
   minimum:
   - `TELEGRAM_BOT_TOKEN`
   - `ADMIN_ID`
   - `GITHUB_TOKEN` (a *new* token, with `Contents: read+write`)
   - `GITHUB_REPO` = `aaaarrr125h-rgb/Manga-Extensions`
   - `GITHUB_BRANCH` = `main`
   - `PUSH_ENABLED` = `true`
3. **Generate a persistent state.** The container filesystem is ephemeral, so
   `data/guard_state.json`, `data/quarantine.json`, `data/audit_cache.json`,
   `data/candidates.json` and `data/audit_log.jsonl` vanish on every redeploy. Add
   a **volume** mounted at `/app/data` (the default Railway service directory plus
   `/data`). Without it the bot simply refills its state on the next harvest, but
   guard bans, allowlists, pending candidates and the audit trail are lost.
4. **Deploy**, then check the logs. A healthy start looks like:
   ```
   running as @yourbot (id=…) | admins=[…] | watch=all chats
   index ready: 579 extensions / 830 sources (1 quarantined)
   scheduler running with 4 job(s) in UTC
   ```
5. **Open a chat with your bot** and send `/start`. The service only runs the
   Telegram loop when both `TELEGRAM_BOT_TOKEN` and `ADMIN_ID` are set.

To verify the repository itself without deploying anything, run
`python bot.py --selftest` locally.

---

## Repository layout

```
bot.py                  the whole service
review_system.py        candidate schema, state machine, gates, audit log
test_review_pipeline.py review/accept test suite (offline, never pushes)
repo.json               repository descriptor Mihon reads (index_v2 URL + key)
repo/index.pb           gzipped protobuf index, consumed by Mihon
repo/index.json         human-readable copy of the same data
repo/index.min.json     legacy minimal index
data/candidates.json    review candidates with their gate evidence (git-ignored)
data/audit_log.jsonl    append-only decision log (git-ignored)
data/quarantine.json     quarantine proposals staged by the health sweep (not yet enforced)
data/audit_cache.json   remembered audit verdicts, keyed by package+version
data/guard_state.json   guard allow/block lists and user bans (git-ignored)
data/sources.json       last successful scrape of the upstream index
```

`repo/`, `repo.json`, `data/quarantine.json` and `data/audit_cache.json` are
generated artifacts — the bot rewrites and commits them, so hand edits will be
overwritten. Change the source of truth (`bot.py`) instead.

## Security notes

- Never commit `.env`; it is git-ignored. Rotate any token that gets pasted into a
  chat, a log, or a screenshot.
- The GitHub token is only ever used against the Contents API for the configured
  repository.
- Every privileged Telegram path (commands *and* inline-keyboard callbacks) is
  authorised by the sender's numeric user id in `ADMIN_ID`. Usernames are never
  trusted, bots are never admins, and a callback is refused before its report
  token is even resolved. A non-admin also cannot drive `/review` or `/accept`.
- `data/audit_log.jsonl` is append-only and every field is passed through a
  redactor, so `GITHUB_TOKEN`, `TELEGRAM_BOT_TOKEN`, signing keys and anything
  else that looks like a credential are replaced with `[REDACTED]` before they
  reach disk.
- The guard's block/ban state is intentionally simple and file-backed; it is not
  designed to resist a coordinated attack on the bot account itself.
