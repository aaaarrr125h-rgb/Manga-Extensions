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
keiyoushi/index.json ──► harvest ──► audit ──► index.json ──► index.pb ──► GitHub ──► Mihon
                                        │                                     ▲
                                        └────────── quarantine ◄── health ────┘
```

1. **Harvest** (`--harvest`) pulls the upstream index and considers any extension that
   is new or has a higher `versionCode`.
2. **Audit** rejects a candidate if it fails any of:
   - *Schema* — missing name, apk/icon, sources, bad `versionCode`, unknown `contentWarning`.
   - *NSFW policy* — `contentWarning` of `NSFW` (and `MIXED` while `ALLOW_MIXED=false`),
     plus keyword/host screening across the name, package and site URLs.
   - *URL screening* — punycode homographs, `.onion`, url-shorteners, high-risk
     TLDs, brand typosquats, and (for the distribution URLs) IP-literal hosts,
     odd ports and embedded credentials.
   - *Live asset probe* — `HEAD` on the APK and icon; 404/410 or a 5xx rejects.
   - *Source-code scan* (optional) — fetches the extension's Kotlin files from
     `keiyoushi/extensions-source` and greps them for ~25 malware signatures
     (`DexClassLoader`, `Runtime.exec`, `SmsManager`, accessibility abuse, …).
     Anything at `critical`/`high` severity blocks the extension.
3. **Health sweep** (`/health`, or every `CRON_HEALTH`) probes a rolling window
   of `homeUrl`s. After `DEAD_THRESHOLD` consecutive failures an extension is
   quarantined (and can be restored with `/unquarantine`). Self-hosted extensions
   (Komga, Kavita, Bakkin…) are skipped — they legitimately point at `127.0.0.1`
   or a LAN address, which is why `screen()` screens source URLs with
   `allow_private=True` while keeping distribution URLs strict.
4. **Publish** rebuilds `repo/index.json`, `repo/index.min.json` and the gzipped
   protobuf `repo/index.pb`, then pushes the changed files to GitHub through the
   Contents API. Output is deterministic: identical input produces byte-identical
   `index.pb`.

Rejections are remembered in `data/audit_cache.json` (keyed by package +
`versionCode`) so the same dead candidate is never re-audited, and quarantines in
`data/quarantine.json`.

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
| `PUSH_ENABLED` | auto | Force-disable publishing entirely. |

### Repository identity

| Variable | Default | Meaning |
|---|---|---|
| `REPO_NAME` | `Shura` | Store name shown in Mihon. |
| `BADGE_LABEL` | `SHURA` | Short label on the repo card. |
| `REPO_WEBSITE` | repo URL | Shown as the repo contact. |
| `REPO_DISCORD` | – | Optional Discord invite. |
| `SIGNING_KEY` | see `.env.example` | Extension signing key; the fingerprint is published in `repo.json`. |

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
| `DEAD_THRESHOLD` | `3` | Consecutive failures before quarantine. |
| `CRON_HARVEST` | `17 */6 * * *` | Scrape + audit + publish. |
| `CRON_HEALTH` | `41 */3 * * *` | Source health sweep. |
| `CRON_PUBLISH` | `23 */2 * * *` | Rebuild and push. |
| `CRON_DIGEST` | `53 9 * * *` | Daily digest to admins. |
| `TIMEZONE` | `UTC` | Timezone the cron expressions use. |

---

## Running

```bash
python bot.py                 # full service: Telegram polling + scheduler
python bot.py --selftest      # 99 offline checks, no network writes
python bot.py --build         # regenerate the index files only
python bot.py --harvest       # one scrape + health + publish cycle, then exit
python bot.py --no-telegram   # identical to --harvest; kept for clarity
```

`--selftest` is the fastest way to check a change: it covers the protobuf
round-trip against the real keiyoushi index, the NSFW and URL filters, the
malware scanner, the group-guard integration (with a fake Telegram client) and a
full index build.

### Adding the repository to Mihon

In Mihon: **Settings → Repositories → Add repository**, then paste:

```
https://raw.githubusercontent.com/aaaarrr125h-rgb/Manga-Extensions/main/repo.json
```

The bot's `/repo` command returns the same URL plus a one-tap deep link.

---

## Telegram commands

Available to everyone: `/start`, `/repo`, `/latest`, `/help`, `/id`.

Admins only: `/status`, `/scan`, `/health`, `/publish`, `/quarantine`,
`/unquarantine <package>`, `/allow <domain>`, `/block <domain>`, `/ban <id>`,
`/unban <id>`, `/lists`, `/reset`.

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
   `data/guard_state.json`, `data/quarantine.json` and `data/audit_cache.json`
   vanish on every redeploy. Add a **volume** mounted at `/app/data` (the default
   Railway service directory plus `/data`). Without it the bot simply refills its
   state on the next harvest, but guard bans and allowlists are lost.
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
repo.json               repository descriptor Mihon reads (index_v2 URL + key)
repo/index.pb           gzipped protobuf index, consumed by Mihon
repo/index.json         human-readable copy of the same data
repo/index.min.json     legacy minimal index
data/quarantine.json     extensions disabled by the health sweep
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
- The guard's block/ban state is intentionally simple and file-backed; it is not
  designed to resist a coordinated attack on the bot account itself.
