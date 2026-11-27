# NeonPlayer

<p align="center">
  <img src="assets/PixelPlayerOSS.svg" alt="App Icon" height="200"/>
</p>

NeonPlayer is an Android music player built and maintained by
[@addynoven](https://github.com/addynoven). It is a GPLv3 fork of
[PixelPlayerOSS](https://github.com/PixelPlayerHQ/PixelPlayerOSS) — itself the FOSS
successor of PixelPlayer — and it tracks that upstream release line.

This branch is a **port onto the current PixelPlayerOSS code base** (upstream `main`,
2026-11-21, `1664e8e63`). The previous neonplayer tree was based on the archived
`PixelPlayerHQ/PixelPlayer` repository, which stopped receiving fixes; its only later
commits touch the README and the logo. Everything of ours that matters was carried over
on top of the new base instead of merged, because the two repositories share no history.

## What NeonPlayer adds on top of PixelPlayerOSS

| Area | Change |
| --- | --- |
| YouTube Music | Search YouTube Music, stream through the local proxy, save results to the library. Built on NewPipeExtractor plus InnerTube clients, with six client fallbacks (ANDROID_SDKLESS, IOS, TVHTML5, WEB_REMIX, ANDROID_VR, WEB_EMBEDDED_PLAYER) |
| Library search | Local results are combined with YouTube results, de-duplicated by song id |
| Playback | `youtube://` is a first-class cloud scheme in the player engine: warmed, resolved, cached, and re-resolved inline on cache miss like the Navidrome/Jellyfin proxies |
| Connectivity | `NET_CAPABILITY_VALIDATED` is no longer required to consider the device online, which used to strand captive-portal and VPN users offline |
| Lyrics | Embedded tag reads are skipped for proxied schemes instead of failing on an unreadable URI |
| Branding | NeonPlayer name, launcher icons, About screen and attribution |

## Upstream relationship

| Question | Answer |
| --- | --- |
| Upstream | [`PixelPlayerHQ/PixelPlayerOSS`](https://github.com/PixelPlayerHQ/PixelPlayerOSS), branch `main` |
| Base commit of this port | `1664e8e63` (2026-11-21) |
| Previous base | `PixelPlayerHQ/PixelPlayer` @ `cf56a2cf` (2026-07-13), archived upstream |
| License | GPL-3.0, inherited from PixelPlayerOSS |
| Attribution | `LICENSE`, `THIRD_PARTY_NOTICES.md`, `app/src/main/assets/licenses/` |

`main` is a normal git descendant of upstream: its history is `PixelPlayerOSS/main`
(`1664e8e63`) plus 24 NeonPlayer commits, so newer upstream releases merge in — no
re-porting. (`master` is something else: the archived pre-port fork of
`PixelPlayerHQ/PixelPlayer`, whose history really is unrelated and is kept only as a
backup.)

Check whether upstream has anything new:

```sh
git fetch oss --prune                 # oss = PixelPlayerHQ/PixelPlayerOSS
git rev-list --count main..oss/main   # commits we are behind
git log --oneline main..oss/main      # what would come in
```

Sync when that count is not zero — either merge the weekly `upstream-sync` pull request
opened by `.github/workflows/upstream-sync.yml`, or run it locally:

```sh
./scripts/sync-upstream.sh            # merges into sync/upstream-YYYYmmdd-HHMM
```

Both end in the same check: `./gradlew :app:assembleDebug :app:testDebugUnitTest`, then
play one YouTube track past the one-minute mark. The files both sides edit, the Room
schema rule and the NewPipeExtractor pin are in
[`docs/UPSTREAM_SYNC.md`](docs/UPSTREAM_SYNC.md) — read it before resolving a conflict.

Last checked 2026-11-28: 24 commits ahead, 0 behind; upstream tip `1664e8e63`
(2026-11-21), so there was nothing to merge.

## Features

Local playback, self-hosted streaming, and the Material 3 UI come from upstream: Media3
playback engine with FFmpeg, gapless playback and crossfade; local library scanning,
albums, artists, genres, folders, favorites, playlists, stats and metadata editing;
Navidrome/Subsonic and Jellyfin sync, streaming and private offline downloads; embedded,
`.lrc` and LRCLIB lyrics; artwork palette extraction; MusicBrainz matching; Glance widgets;
backup and restore.

## Requirements

| Requirement | Version |
| --- | --- |
| Android | 11 or newer, API 30+ |
| JDK | 21 |
| Android SDK | compile/target 37 |

## Build from source

```sh
git clone https://github.com/addynoven/NeonPlayer.git
cd NeonPlayer
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:assembleDebug
```

Universal debug APK:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:assembleDebug -Ppixelplayer.enableAbiSplits=false
```

Unit tests:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:testDebugUnitTest
```

Version and update metadata live in `gradle.properties` (`APP_VERSION_NAME`,
`APP_VERSION_CODE`). Sources stay in the upstream package
`com.lostf1sh.pixelplayeross` to keep the port mechanical, while the application id is
`com.theveloper.pixelplay` so existing NeonPlayer installs upgrade in place.

## Project structure

```text
app/src/main/java/com/lostf1sh/pixelplayeross/
- data/             Room, repositories, preferences, services, workers (+ data/youtube)
- di/               Hilt modules and qualifiers
- presentation/     Compose screens, components, navigation, ViewModels
- ui/               Theme and Glance widgets
- utils/            Shared utilities

baselineprofile/      Macrobenchmark and baseline profile generation
```

## License

NeonPlayer is distributed under the GNU General Public License v3.0; see [`LICENSE`](LICENSE).
It is a derivative work of PixelPlayerOSS (GPL-3.0) and PixelPlayer, and it ships the
upstream attribution assets unchanged. If you distribute a build, you must also make the
corresponding source available under the same license.
