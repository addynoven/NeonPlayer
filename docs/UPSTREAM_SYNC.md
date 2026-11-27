# Keeping NeonPlayer in sync with PixelPlayerOSS

NeonPlayer is a downstream fork: `PixelPlayerHQ/PixelPlayerOSS` is the base app, our
work rides on top. Upstream ships fixes every few weeks, and we want those fixes in
our app without re-doing our own changes by hand. That is what this document is for.

## The shape of the repository

```
oss/main   ●──●──●──●──●        upstream releases (read-only mirror)
                    \
main                 ●──●──●     our app: merges from upstream + our features
```

Rules that keep this cheap:

- **Always merge, never rebase or cherry-pick.** Each upstream change is reconciled
  with ours once, permanently. Rebasing replays our commits onto a new base and
  re-resolves the same conflicts on every sync; cherry-picking upstream commits
  breaks the next merge.
- **Never commit directly on the upstream mirror.** `main` is ours, `oss/main` is
  theirs. Do not push to `oss`.
- **Do the merge on a throwaway branch** (`sync/upstream-*`). If it turns into a
  swamp, `git merge --abort` costs nothing and `main` was never dirty.
- **Tag our releases** (`neon-v0.9.0`); leave upstream tags alone so we can still
  diff against a release.

### Is there anything to sync?

```bash
git fetch oss --prune --tags
git rev-list --count main..oss/main   # 0 = up to date
git log --oneline main..oss/main      # what would come in
```

`scripts/sync-upstream.sh` starts with this check and exits without touching anything
when the count is zero, so running it is always safe.

`master` is **not** the parent: it is the archived pre-port fork of
`PixelPlayerHQ/PixelPlayer` (a history unrelated to `oss/main`), kept only as history and
as the `backup-pre-oss-port` tag. Never merge from it.

Status on 2026-11-28: `main` is 24 commits ahead of `oss/main` and 0 behind; upstream
tip `1664e8e63` (2026-11-21), so nothing was due.

## Branch protection

The ruleset ("Protect Master") covers `main` and `master` only: it forbids deleting
those two and force pushing to them. Ordinary commits and pushes are untouched, and
working branches (`sync/**`, feature branches) are free to be amended, rebased or
deleted.

Practically, on `main` you push normally; what gets rejected is `git push --force`,
rewriting a commit that is already pushed, and deleting the branch. `master` is the
pre-port fork, kept as history and also preserved by the `backup-pre-oss-port` tag.

## One-time setup (per clone)

```bash
git remote add oss https://github.com/PixelPlayerHQ/PixelPlayerOSS.git  # if missing
git config rerere.enabled true      # remember how a conflict was resolved and replay it
git config rerere.autoupdate true   # stage those replays automatically
git config merge.conflictStyle zdiff3  # conflicts show the common ancestor inline
git config pull.rebase false        # merging is the default, never rebase
```

`rerere` matters here: our branding and version lines conflict with upstream in the
same shape every few weeks, and git learns the answer once.

## Routine sync

Automated: `.github/workflows/upstream-sync.yml` runs every Monday, merges
`PixelPlayerOSS/main` into a `sync/upstream` branch and opens a pull request (or
files an issue naming the conflicting files when the merge does not apply cleanly).
Review the diff, let the debug build go green, merge.

Manual, same thing locally:

```bash
./scripts/sync-upstream.sh
```

It fetches, prints the incoming commits and any hot files both sides touched, merges
onto a dated branch, then either reports success (with the exact commands to advance
`main`) or leaves you on the branch with the conflict list.

## Hot files

These are edited by both sides. Expect a conflict here and keep our side minimal:

| File | Our change |
| --- | --- |
| `app/build.gradle.kts` | `applicationId = "com.theveloper.pixelplay"`, namespace |
| `gradle.properties` | `APP_VERSION_NAME` / `APP_VERSION_CODE` |
| `gradle/libs.versions.toml` | NewPipeExtractor pinned to `v0.26.5` (SABR workaround) |
| `app/src/main/AndroidManifest.xml` | branding, exported components |
| `presentation/navigation/AppNavigation.kt` | YouTube routes |
| `data/database/**` | Room v6 schema, YouTube song source |
| `presentation/screens/settings/AboutScreen.kt` | repo/issue links |

Everything else we add lives in files upstream does not have (`data/youtube/`,
`presentation/youtube/`, `data/stream/`), so those can never conflict. Prefer adding
a file over editing theirs.

## Room / database rule

Room versions must increase monotonically and both sides edit the same entities and
database class. On every sync: **take upstream's schema as ground truth**, re-apply
our entity changes on top, bump the version again. Never merge migration code
blindly, and never lower the version because that is what our line used last time.

## Playback rule

YouTube playback depends on NewPipeExtractor `v0.26.5`, whose SABR workaround
(NewPipeExtractor #1508) is the only source we found that yields URLs without a
PoToken. If upstream bumps that version and the merge wants to take theirs:

1. take theirs, rebuild;
2. play one YouTube track past the one-minute mark and seek;
3. if it stalls, re-pin `v0.26.5` and note the date in the comment above the pin.

This is the one behavioural check a successful compile cannot give you.

## Workflows we intentionally replaced

Upstream's `.github/workflows` run on their secrets (Play Store publishing, GitLab
mirror, nightly release keystore, PR builds signed with `CI_KEYSTORE_B64`), so they
cannot pass here. We ship two instead:

- `build-apk.yml` - compiles on push to `main` and on pull requests, uploads a debug APK.
- `upstream-sync.yml` - weekly merge pull request.

If a future merge re-adds `alpha-release.yml`, `gitlab-mirror.yml`, `claude.yml`,
`nightly-apk.yml` or `pr-build.yml`, delete them again as part of the merge commit.

## Publishing

The app is GPLv3, so shipping a build obliges us to keep the source of that build
public - this repository, at the tag that produced the APK.