# Changelog — 白い熊 音楽 (shiroikuma-ongaku)

Everything built on top of stock Felicity, rebased onto each upstream release tag.

## 0.0.26_alpha+14 (2026-07-16, base 0.0.26_alpha)

### Major features

**白い熊 音楽 UI — theming engine & settings page**
- New settings page "白い熊 音楽 UI" (first row in Settings; long-press on the home-screen settings cog jumps straight to it), in the sister-fork layout language: bold underlined section headings, deep per-level indentation, tight rows, live preview card at the top.
- Two-tier color model: foundation slots (background / text / accent / border, hard black-yellow defaults) + 15 derived slots (surfaces, text levels, icons, accents, switch, visualizer) that inherit until explicitly overridden. Master enable switch restores stock theming.
- Overrides injected into every theme via the ThemeManager setters (colors before listeners fire; accent interception with stock restore), so changes repaint the app live.
- 4-slider RGBA color picker: R/G/B/A channel sliders with numeric readouts, old-vs-new swatches, live `#AARRGGBB` hex, one-click boxes prefilled with recently applied colors, per-slot Default reset.
- External font support: import `.ttf`/`.otf` via the system document picker into the app's font dir; the font picker renders every choice (bundled + imported) in its own glyphs; selection applies instantly app-wide.
- Global text-size scale (50–200 %) and font-weight adjustment (−300…+300) on top of every style.
- Border engine: every dynamic-corner surface, chip, and the miniplayer carries a configurable border (width slider to 0 = none, color from the Border slot).
- Corner-radius and list-spacing sliders (to 0), all with live preview.

**PowerAmp-style spectrum visualizer**
- Full-screen overlay in all three player skins — bars grow from the bottom edge to the top of the screen over every player element; touches pass through.
- Super-thin 2 dp bars with equal gaps; the 40 real FFT bands are upsampled into a dense interpolated row (count derived from screen size), heights bridging progressively between real neighbors; peak caps interpolated to match.
- Dedicated visualizer color (default pure blue `#0000FF`), settable in the UI page; falls back to stock accents when the theme is off.
- Pause/stop eases the spectrum to zero via a silence latch (survives late FFT frames draining from the pipeline) and clears entirely — no frozen bars, no baseline stubs.
- The inline lyric line is lifted above the visualizer at runtime (invisible placeholder keeps its slot; per-frame pinning preserves each skin's exact position).

**音楽端灯 — native edge meteors**
- Comet ribbons orbit the screen perimeter in a thin glowing band, beat-locked to the music; exact port of the tuned 自由作業盤 implementation (all shipped constants).
- Native beat source (`MusicPulse`): PCM tap on the decoder output fed from the visualizer processor on both audio paths (works under A2DP offload / USB DAC; no `android.media.audiofx.Visualizer`, no RECORD_AUDIO) — RMS level with fast-attack/slow-release, bass-onset detection, and a tempo phase-lock grid (IOI folding into the 60–180 BPM band, modal clustering, PLL nudging, confidence fade).
- GPU-native renderer: corner-split axis-aligned capsule runs, layered glow passes with `(1−t)^1.6` falloff, gradient core taper, beat-swelling head star, single PorterDuff.CLEAR band-hole punch, static corner masks, frame-skip FPS cap — no wide anti-aliased paths, no even-odd clipPath, no BlurMaskFilter.
- Renders in-app over the player (suppressed while the system overlay is active — no double bands) and optionally as a system-wide `TYPE_APPLICATION_OVERLAY` window while music plays: added on play, removed on pause/stop/screen-off, re-laid-out on fold/unfold, pinned to the real panel bounds with insets-fitting disabled so the band hugs the physical screen edge over the status bar regardless of the foreground app (also sidesteps the Mate XT folded-portrait window misreport).
- Full 音楽端灯 settings section: enable, system-wide overlay (steers to the permission grant), music-reactive, direction, and 22 sliders (ribbon counts/lengths/speeds, band geometry, glow stack, twinkle, hue drift, FPS cap, beat reaction gains).

**Automation surface**
- Outgoing broadcasts: `shiroikuma.ongaku.STATUS_CHANGED` (play/pause flips: paused, id, title, artist, favorite) and `shiroikuma.ongaku.TRACK_CHANGED` (track transitions and every favorite toggle: path, uri, id, title, artist, favorite, paused). The `path` extra is always human-readable — real filesystem path when known, otherwise decoded from the SAF document id (`primary:` → `/sdcard/`), never a percent-encoded blob.
- Token-secured intent endpoint (`shiroikuma.ongaku.AUTOMATION`, headless translucent activity, constant-time token check, disabled by default): `TOGGLE_FAVORITE`, `DELETE_CURRENT` (queue-advance → SAF/file delete → database, lyric sidecars cleaned), `PLAY_PLAYLIST` by name (case-insensitive; optional `shuffle`; optional `track` — start at the case-insensitively matching title, track wins over shuffle, miss warns and starts at the top), `PLAY_PAUSE` / `NEXT` / `PREVIOUS` — all cold-start-safe via the widget's MediaController restore pattern.
- Automation settings section: enable toggle (default OFF), tap-to-copy 128-bit token, one-tap regeneration.

**Playlist manual reordering**
- Drag songs up/down on the playlist page via the row's right-edge drag handle (long-press still opens the song menu); rows move live and the order persists atomically as the playlist's manual order ("As Added"), honored by in-app playback and the automation `PLAY_PLAYLIST` path alike.

### UI & theming details
- Black-yellow home surfaces: the no-album-art placeholder is the yellow-traced record glyph on black (replaced the gray upstream PNG served by the cover loader); the ArtFlow "Recommended" chip is a solid-black, yellow-bordered, yellow-text box; every highlight pill (1/1, Lyrics, Shuffle, LQ…) carries the standard fork border.
- Miniplayer: black card with the standard yellow border; progress wash alphas lowered so it reads as a black box with a subtle played-portion fill.
- Black-yellow traced launcher icon (yellow line-art on black, adaptive) and in-app app-logo drawables to match.
- App label 白い熊 音楽.

### Fixes & behavior
- Trial timer removed — permanent full version (all gates forced open).
- Edge-meteor overlay and in-app copies never render simultaneously.

### Packaging
- App id `shiroikuma.ongaku` — installs side-by-side with official Felicity; namespace/JNI untouched (`app.simple.felicity`).
- foss flavor, arm64-v8a only; versionName `<upstream-tag>+<N>`, versionCode `<upstream-code>×10000+N`, injected at build time (no per-build commits).
