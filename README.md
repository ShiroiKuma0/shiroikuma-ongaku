<div align="center">

<img src="music/src/main/ic_launcher-playstore.png" width="120" alt="白い熊 音楽 icon" />

# 白い熊 音楽

**A black-yellow, beat-lit, automation-ready music player.**

A fork of [Felicity Music Player](https://github.com/Hamza417/Felicity) with **major additions**: a full black-yellow theming engine with its own settings page, category export/import of every setting including ratings and playlists (also driveable headlessly for batch backups), a PowerAmp-style full-screen spectrum visualizer, the 音楽端灯 edge-meteor light show driven by a sample-accurate PCM beat tracker, a token-secured automation surface for external workspaces, Android Auto with full library browsing, album-art download/change (automatic, interactive, storage pick, clipboard paste), PowerAmp ratings/art import, and manual playlist reordering.

Installs **side-by-side** with Felicity (app id `shiroikuma.ongaku`).

**📥 Latest release: [`0.0.26_alpha+30`](https://github.com/ShiroiKuma0/shiroikuma-ongaku/releases/latest)** — [all releases & APK downloads »](https://github.com/ShiroiKuma0/shiroikuma-ongaku/releases)

</div>

---

## 🖤💛 白い熊 音楽 UI — the black-yellow theming engine
A dedicated settings page (first row in Settings, or long-press the home-screen cog) controls the whole look: black background, yellow text, yellow borders everywhere by default. Nineteen color slots with two-tier inheritance (change the foundation, everything derives), each edited in a 4-slider RGBA picker with live preview, hex readout, and one-click recent-color boxes. External `.ttf`/`.otf` fonts import via the system picker and every font choice renders in its own glyphs; global text-size and font-weight sliders, corner radius and border width down to 0. Sections carry text-wide underlined headings with in-section live preview cards, so every change shows right where it's made.

## 💾 Export / Import — everything, by category
The first section of the UI page exports every settable item to a chosen directory (queried on open for the latest export) and imports it back by category: theme, edge meteors, automation, app settings, plus ratings and playlists straight from the library database — path-matched, strictly additive, portable across versions. Round-pill panel, black-yellow result dialogs, one-tap restart after import.

## 🗄️ 保存復元 — headless backup on request
The same export runs without ever opening the app: a token-gated broadcast makes it write itself to a directory of the caller's choosing and reply with the path, byte count and category total, so one 白い熊 自由作業盤 run can back up every sister app in a batch and summarise the lot. A companion action lists the selectable categories so the caller can offer a picker without knowing anything about this app, and progress is reported in real counts — `楽曲 1234/8942`, never a percentage. The automation token itself is kept out of the backup.

## 📊 PowerAmp-style visualizer
The player's spectrum reaches over the whole screen: super-thin blue bars (2 dp), upsampled from 40 real frequency bands with progressive height interpolation, overlaying every player element while lyrics float above. While music plays the whole player screen dims PowerAmp-style so the dark-navy bar wall owns the view; pause restores full brightness and eases the bars to zero instead of freezing them. The waveform seekbar matches the visualizer's geometry — 2 dp yellow bars with interpolated bars between the real per-second samples. Color is settable from the UI page.

## 🌠 音楽端灯 — edge meteors
Comet ribbons orbit the screen perimeter, glowing and beat-locked to the music. The beat source is a native tap on the decoder output (no `Visualizer` API, works under BT offload) with onset detection and a tempo phase-lock grid; the renderer is GPU-native layered capsules tuned over weeks. Runs in-app over the player, and optionally as a system-wide overlay window that lights the screen edge over any app while music plays — every knob (counts, speeds, glow, twinkle, palette, reaction) exposed in settings.

## 🤖 Automation surface
Play/pause and track-change broadcasts (with title, artist, favorite state, and a human-readable path), plus a token-secured intent endpoint for external automation: toggle favorite, delete the current track, transport controls, and play-playlist-by-name — optionally starting at a named track — all cold-start-safe.

## 🎵 Player & library
Manual drag-reorder of playlist songs (right-edge drag handle, persisted order that playback honors), keep-screen-on while playing (settable, on by default), black-yellow traced placeholder art, bordered chips and miniplayer, and the trial timer removed — permanent full version.

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
