<div align="center">

<img src="music/src/main/ic_launcher-playstore.png" width="120" alt="白い熊 音楽 icon" />

# 白い熊 音楽

**A black-yellow, beat-lit, automation-ready music player.**

A fork of [Felicity Music Player](https://github.com/Hamza417/Felicity) with **major additions**: a full black-yellow theming engine with its own settings page, category export/import of every setting including ratings and playlists (also driveable headlessly for batch backups), a PowerAmp-style full-screen spectrum visualizer, the 音楽端灯 edge-meteor light show driven by a sample-accurate PCM beat tracker, an automation surface external workspaces drive without a pasted secret, a data door that hands this app's whole backup — settings, ratings and playlists — to 白い熊 応用管理 so it survives a wiped phone, a restore that finishes itself on that wiped phone (the music folder's grant asked for back, favourites and playlists attached the moment the library is scanned, a waiting room for whatever could not be matched), Android Auto with full library browsing, album-art download/change (automatic, interactive, storage pick, clipboard paste), PowerAmp ratings/art import, and manual playlist reordering.

Installs **side-by-side** with Felicity (app id `shiroikuma.ongaku`).

**📥 Latest release: [`0.0.29_alpha+005`](https://github.com/ShiroiKuma0/shiroikuma-ongaku/releases/latest)** — [all releases & APK downloads »](https://github.com/ShiroiKuma0/shiroikuma-ongaku/releases)

</div>

---

## 🖤💛 白い熊 音楽 UI — the black-yellow theming engine
A dedicated settings page (first row in Settings, or long-press the home-screen cog) controls the whole look: black background, yellow text, yellow borders everywhere by default. Nineteen color slots with two-tier inheritance (change the foundation, everything derives), each edited in a 4-slider RGBA picker with live preview, hex readout, and one-click recent-color boxes. External `.ttf`/`.otf` fonts import via the system picker and every font choice renders in its own glyphs; global text-size and font-weight sliders, corner radius and border width down to 0. Sections carry text-wide underlined headings with in-section live preview cards, so every change shows right where it's made.

## 💾 Export / Import — everything, by category
The first section of the UI page exports every settable item to a chosen directory (queried on open for the latest export) and imports it back by category: theme, edge meteors, automation, app settings, plus ratings and playlists straight from the library database — matched by SAF document id with the file path as fallback, strictly additive, portable across versions. Round-pill panel, black-yellow result dialogs, one-tap restart after import.

## 🗄️ 保存復元 — headless backup on request
The same export runs without ever opening the app: a broadcast makes it write itself to a directory of the caller's choosing and reply with the path, byte count and category total, so one 白い熊 自由作業盤 run can back up every sister app in a batch and summarise the lot. A companion action lists the selectable categories — each stating whether it starts ticked, so the caller's picker never has to guess — and progress is reported in real counts (`楽曲 1234/8942`, never a percentage). A running export can be stopped from outside: it unwinds at the next entry boundary, deletes its partial zip so the backup directory is left exactly as it was found, and answers `ERROR:cancelled` for the request it was serving. The automation settings themselves never travel: the token, the master switch and the token-required switch are all excluded from the ZIP, so a backup restored onto another phone cannot demand a secret that phone has never been given, or quietly close the app off. The exclusion is fail-closed for the whole category rather than a name-by-name list, so a setting added later cannot start travelling by omission.

## 🔐 Data door — a backup that survives a clean phone
Automation is on out of the box and the token is opt-in (「Use authorization token?」, off by default) — a pasted secret cannot survive a wipe, and the point of this half is a phone where nothing has been configured yet. A token sent anyway is ignored rather than refused, so a caller configured last year keeps working. In its place a content provider identifies the caller three ways — exact package name (never a prefix), a uid cross-check the kernel answers, and a pinned signing certificate — and the archive moves through a file descriptor the caller opened, so it lands inside 白い熊 応用管理's encryption and checksums instead of beside them. 応用管理 can therefore back this app up *with* its data and put it back on a wiped device — into a never-launched install, deliberately, so nothing merges against first-run defaults.

## 🧳 A restore that finishes itself on a clean phone
A Storage Access Framework grant belongs to an installation, not to its data, so a restored copy knows exactly which folder its music lives in and is not allowed to look. The folder list now travels in the backup, and the app asks for the grant back: the Setup screen names the folder and opens the system picker *at* it, and every later launch that finds a folder the install cannot read raises the same question (「Choose the folder」 / 「Forget it」 / 「Not now」). Re-picking the same folder yields a byte-identical tree URI, so every row works again untouched. Favourites and playlists that find no library row at restore time are not dropped but kept waiting and attached at the end of the next scan — matched by document id first, path second — while a status pill above the mini player says what is happening: 「Scanning the library — 楽曲 1234/8942」, 「Restore waiting for the scan: 341 favorites · 2 playlists (38 songs)」, then 「✓ Restored …」. Whatever still matches nothing sits in a waiting room behind that pill — each entry beside what the library holds under the same file name, with 「Use」, 「Choose…」 and 「Discard」 — so the residue of a restore is settled by a person, never guessed.

## 📊 PowerAmp-style visualizer
The player's spectrum reaches over the whole screen: super-thin blue bars (2 dp), upsampled from 40 real frequency bands with progressive height interpolation, overlaying every player element while lyrics float above. While music plays the whole player screen dims PowerAmp-style so the dark-navy bar wall owns the view; pause restores full brightness and eases the bars to zero instead of freezing them. The waveform seekbar matches the visualizer's geometry — 2 dp yellow bars with interpolated bars between the real per-second samples. Color is settable from the UI page.

## 🌠 音楽端灯 — edge meteors
Comet ribbons orbit the screen perimeter, glowing and beat-locked to the music. The beat source is a native tap on the decoder output (no `Visualizer` API, works under BT offload) with onset detection and a tempo phase-lock grid; the renderer is GPU-native layered capsules tuned over weeks. Runs in-app over the player, and optionally as a system-wide overlay window that lights the screen edge over any app while music plays — every knob (counts, speeds, glow, twinkle, palette, reaction) exposed in settings.

## 🤖 Automation surface
Play/pause and track-change broadcasts (with title, artist, favorite state, and a human-readable path), plus an intent endpoint for external automation (token optional, and ignored when not asked for): toggle favorite, delete the current track, transport controls, and play-playlist-by-name — optionally starting at a named track — all cold-start-safe.

## 🎵 Player & library
Opening the app while music plays lands on the playing view, not the library — the launcher icon and an automation shortcut that starts a song both go straight there, and one press of back returns to browsing. Manual drag-reorder of playlist songs (right-edge drag handle, persisted order that playback honors), keep-screen-on while playing (settable, on by default), black-yellow traced placeholder art, bordered chips and miniplayer, and the trial timer removed — permanent full version.

---

## Built on Felicity
A fork of [Felicity Music Player](https://github.com/Hamza417/Felicity) by [Hamza417](https://github.com/Hamza417) (app id `shiroikuma.ongaku`, so it coexists with the official build). Felicity is a beautifully engineered modern Android music player; this fork carries a personal customization layer rebased onto each upstream release. The code remains under AGPL-3.0.

## Building
```bash
git clone https://github.com/ShiroiKuma0/shiroikuma-ongaku
cd shiroikuma-ongaku
git checkout custom
sh ./gradlew :music:assembleFossRelease
```
Requires JDK 21, Android SDK platform 36, NDK 28.2.13676358, CMake 3.22.1. The release build signs with your own keystore via `local.properties` (`KEYSTORE_PATH`, `SIGNING_*`).
