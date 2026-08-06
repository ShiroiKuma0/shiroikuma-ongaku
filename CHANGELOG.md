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

## 0.0.26_alpha+19 (2026-07-16, base 0.0.26_alpha)

### Major features
- **Android Auto**: media-app declaration + full library browse tree (Recently added / Favorites / Albums / Artists / Playlists, search, cold-start safe); tapping a song queues its folder; resumption restores the saved queue; artwork served to the car UI.
- **Album-art download — automatic**: "Download missing album art" sweeps albums with no embedded art, resolves them on MusicBrainz (rate-limited) and embeds Cover Art Archive covers into the files' tags, with live progress and an honest tally.
- **Album-art download — interactive**: "Download album art" in the song menu and album page: editable search, up to 8 cover alternatives with release details, larger-preview confirmation, embed only on Apply (single file or whole album, replaces existing art).
- **PowerAmp import**: ratings (.poweramp-backup → favorites, path-matched) and album art (embedded into tags via TagLib), both with live themed progress cards.

### UI & behavior
- Browse pages: cumulative sections (albums/artists/genres carousels) first, songs last — everywhere.
- All flashes/toasts themed: black card, yellow text and border, app font.
- Playlist manual drag-reorder honored by Android Auto and automation playback.

## 0.0.26_alpha+21 (2026-07-16, base 0.0.26_alpha)
- "Album art download/change" (renamed): the art sheet in the song menu and album page adds manual sources — pick an image from storage or paste from the clipboard (image clip, image URL, uri, or path), transcoded to JPEG and embedded only after the preview confirmation, single song or whole album, replacing existing art.

## 0.0.26_alpha+23 (2026-07-16, base 0.0.26_alpha)

### PowerAmp-style player screen
- **Playback dim**: while music plays with the visualizer enabled, the whole player content column (album art, text, chips, seekbar, controls) fades to 30 % over 600 ms so the bar wall owns the screen; pause/stop restores full brightness. The lifted lyric line and the edge meteors sit above the visualizer and stay bright. All three player skins.
- **Subdued bar wall**: visualizer overlay alpha lowered 0.55 → 0.40 — pure-blue bars now composite to a dark PowerAmp-like navy over the black instead of a bright curtain.
- **Seekbar matches the visualizer**: bars slimmed 7 dp → 2 dp with 2 dp gaps, and a new `wsbUpsample` waveform attribute inserts linearly interpolated "computed" bars between the real per-second samples (3× in the player skins) so the row stays dense and the scroll feel unchanged; still yellow, still fraction-exact for seeking/flinging.
- **Toolbar reorder**: the favorite/visualizer/equalizer/search/menu toolbar moves from the very bottom to directly above the count/Lyrics/Shuffle/PCM chip row (carousel and faded-waveform skins), so the transport controls end the screen like PowerAmp.
- **Peak dots removed**: the peak-hold cap pills and the drifting ash particles default to off (their settings toggles still work). Also fixed the bug that made caps immortal: assigning a color to the cap paint resets its alpha to opaque, so every accent/theme/color update silently resurrected disabled caps — the enabled state is now a tracked flag reapplied after every color assignment, and cap drawing is skipped entirely when off.

## 0.0.26_alpha+24 (2026-07-17, base 0.0.26_alpha)

- **Keep screen on while playing**: new switch in the 白い熊 音楽 UI Player section, on by default — the display never times out while music plays and the app is in the foreground; pause/stop (or the toggle, applied live) releases it.
- **Android Auto icon fixed**: the launcher icon is now adaptive-only (all legacy raster mipmaps and the separate round icon deleted — dead weight at minSdk 29). Head units picked up the square legacy raster and plated it on a white disc; with only the adaptive icon they circle-mask the black background full-bleed — a pure black-yellow circle, matching the sister forks.
- The built-in HTTP server's app-icon fallback renders the launcher drawable instead of bitmap-decoding the removed raster.

## 0.0.26_alpha+26 (2026-07-25, base 0.0.26_alpha)

### Export / Import of every setting (Kōjiki flow)
- New first section at the top of the 白い熊 音楽 UI page: an Export/Import row whose status line is refreshed on every page open by querying the configured directory for the newest export ("Last export: <timestamp>", red warnings when no directory or no export yet).
- Settable export directory via the SAF tree picker, persisted with a durable permission grant in a device-local store that is itself never exported; a save-as picker is the fallback while no directory is set.
- Six categories, each a checkbox in the panel (with a bold "Select all" master): 白い熊 UI theme (all theme keys plus font, corner radius, list spacing), 音楽端灯 edge meteors, automation, app settings (every remaining preference key, minus device-local SAF grants and crash state), ratings (favorites), and playlists.
- Export format: a zip named `shiroikuma-ongaku-<version>-export_<timestamp>.zip` holding a manifest plus one type-tagged JSON per category — every SharedPreferences key round-trips with its type, and import is a per-key merge (never a clear), so old exports load into newer builds and vice versa.
- Ratings and playlists are data categories read from the library database and matched by file path on import (exact + NFC-normalized), strictly additively: favorites are never cleared; playlists merge by name with missing songs appended at the end in manual order, unknown names are created with the exported metadata (description, pinned, shuffle, sort); M3U-scanned playlists are excluded (they regenerate from their files); unmatched paths are counted, never invented.
- Panel buttons in the ArcaneChat dialog style: round pill outline buttons, Cancel alone on the left, Import and Export grouped on the right; Export/Import never auto-dismiss the panel.
- Dialog chain: a black-yellow bordered "✓ Export finished" OK dialog — OK closes the info dialog, the panel beneath it, and the UI page itself; "✓ Import finished" shows the per-category restore summary with "Restart now" (relaunches the app) and "Later" (closes the same chain). Failures ("Export failed…", "Import failed…", "No categories selected.") are themed flashes that leave the panel open.

### UI page restyle (kxkb look)
- Section headings 20 sp bold accent with a 2.5 dp underline exactly as wide as the text; sub-headings 17 sp with a 1.5 dp text-wide underline; 1 px accent hairline spacers between sections (none above the first); 36/54/72/90 dp indent cascade.
- The page-top preview box is gone — each section that sets an item now carries its own live preview card directly under its heading: the full color card under Colors, the text tiers under Typography, and a minimal bordered box under Shape & spacing (the card surface itself previews corner radius and border). All previews keep updating live.

## 0.0.26_alpha+30 (2026-07-25, base 0.0.26_alpha)

### 保存復元 — headless state-export automation
- New exported, token-gated broadcast receiver implementing the 白い熊 自由作業盤 保存復元 wire contract, so one 自由作業盤 run backs up this app alongside every sister app and collects the results into a single summary.
- `shiroikuma.ongaku.action.EXPORT_STATE` runs the existing category-zip export headlessly — no Activity, no user interaction — and replies `OK:<absolute path>|<bytes>|<human size>|<n> categories`. `shiroikuma.ongaku.action.LIST_CATEGORIES` answers instantly with one `id⇥label` line per selectable category, so the caller can render a picker without knowing anything about this app; the ids are the same stable ones used as the zip entry names.
- Extras: `token` (required), `path` (absolute directory override), `items` (comma-separated category ids; absent = everything), `progress_action`, `reply_action`, `reply_package`, `reply_id` (echoed back verbatim, never interpreted).
- The reply is a fresh package-targeted broadcast carrying `FLAG_INCLUDE_STOPPED_PACKAGES` and nothing else — no `ResultReceiver`, no `PendingIntent`, no `Messenger`, since EMUI will not reliably carry a live Binder into another app's manifest receiver and severs the ordered-broadcast result channel between third-party apps. `setResultData` is still set when the broadcast is ordered (correct AOSP, and it makes `am broadcast` print the answer) but is never the only reply. Exactly one terminal reply per request, guarded by an `AtomicBoolean` so an async success and a synchronous error can never both fire.
- Directory precedence: `path` extra → the configured export directory → `ERROR:no-directory`. Writing to an arbitrary absolute path needs All-Files-Access, so `MANAGE_EXTERNAL_STORAGE` is declared and a new settings row shows the grant state and opens the system page; without the grant `path` is ignored only when a SAF directory is configured, otherwise `ERROR:no-storage-access`.
- Distinct, debuggable errors: `automation disabled` and `bad token` are reported separately, and `ERROR:unknown category in items: …` echoes what was sent without writing a file.
- Progress broadcasts are numbers-first, never a percentage: `区分 n/m — <category>`, `楽曲 n/m` across the ratings sweep, `プレイリスト n/m`, each carrying `text`, `current`, `total` and `unit`, throttled to at most one per 500 ms with an unthrottled final one at completion.
- The export core gained an optional progress sink so the Export/Import panel and the receiver are two thin callers of one function — the headless path produces a normal, restorable backup, never a special format.

### Fixes & behavior
- **The automation token no longer travels in backups.** The automation category was writing `sk_automation_token` into every export zip in plaintext; it is now excluded on export *and* on import, so a restored zip can no longer clobber the local token either. Backups written by earlier builds contain the secret — delete them and regenerate the token.
- Backup filenames are now `shiroikuma-ongaku_<yyyy-MM-dd_HH-mm-ss>.zip` — the app name and the datetime, with the version and the `-export` marker dropped. The "last export" probe still recognises the older names, so pre-rename backups keep counting.
- Automation moved out of its own settings section into a subgroup of Export / Import, directly under the export row: the token, the All-files grant and the receiver all exist to drive that export.

## 0.0.27_alpha+1 (2026-07-31, base 0.0.27_alpha)

First build on upstream Felicity `0.0.27_alpha`; the whole 29-commit customization stack rebased onto it.

### 保存復元 — the app states its own defaults, and the export can be stopped
- `LIST_CATEGORIES` now answers `id⇥label⇥parent⇥on|off` — the contract's optional fourth field. Whether a backup item starts ticked becomes this app's own statement instead of something the caller's picker has to guess; the third (parent) field stays empty because the category list is flat, but is still sent, since the fourth is positional.
- Every category here answers `on`: nothing this app exports is large, derived *and* re-creatable (the rule exists for things like downloaded map tiles or a regenerable thumbnail cache). A category added later can declare `off` and both pickers follow it.
- The in-app Export / Import sheet seeds its checkboxes from the same flag, so the panel and 保存復元's item editor start from one answer rather than two, and an absent `items` extra now means literally the `on` set.
- **`shiroikuma.ongaku.action.CANCEL_EXPORT`** — a third token-gated action on the same exported receiver, so a running export can be stopped from outside. Extras: `token` (required) and an optional `reply_id` (absent = the export in flight, unambiguous because two at once are forbidden).
- Cancellation is a `@Volatile` flag the export core polls at every entry boundary — between categories, and inside the ratings and playlist sweeps — never a thread interrupt, so a `write()` in flight always completes and the zip unwinds cleanly.
- A cancelled run leaves the backup directory **exactly as it found it**: the partial zip is deleted on the way out, on both the SAF and the plain-file path. The original request then answers `ERROR:cancelled` through its own reply channel, guarded by the same `AtomicBoolean` that prevents a double terminal reply — sent even if nobody is still listening, because it is what proves the run ended rather than continuing unseen.
- The cancel action itself never replies, not even `OK:`. Arriving when nothing is running, after the export already finished, or with a `reply_id` that does not match, it is a silent no-op — safe to send at any time, including a moment too late.

### Upstream 0.0.27_alpha brings
- **Route-based audio sink** replacing the double-clock architecture: an exclusive playback pipeline that halves the CPU cost of playback, saves battery, and addresses audio-underrun artefacts on some devices.
- Playback correctness fixes riding on it: pause latency in both AAudio and Oboe, AAudio not resuming, audio buffer pulsing while paused, sinks ignoring the play/paused state, inconsistent playback state, and a nanosecond-level clock.
- Now Playing gained a toggle to stack the waveform and media controls, a waveform menu, and an option to change the timer position; flex layouts gained margin support, animated stack changes, and a clipping fix.
- Library lists now sort case-insensitively — title, artist, album, name, composer and path previously used UTF-16 code-unit order, which pushed every lowercase-named entry to the end.
- Spanish translations added.

### Fork layer re-anchored on the new base
- Upstream wrapped the seekbar and the media-control row in a new `FlexStackLayout` (`seekbar_container`) in all three player skins. The PowerAmp-style restyle was re-applied onto that structure rather than merged textually: 2 dp seekbar bars with 2 dp gaps and 3× upsampling, the 0.40 visualizer overlay alpha, and the controls-row bottom margin all sit in their new home, and the relocated utility toolbar (favorite / visualizer / equalizer / search / menu, above the chip row) kept its position without leaving a duplicate copy — or duplicate view ids — behind.
- The edge-meteor attach and the lyric lift now run alongside upstream's new `updateMediaControlOverlap()` in the base player's `onViewCreated`.
- Verified after the rebase: app id, arm64-only foss ABI filter, the 白い熊 音楽 label, the launcher icon, and all seven forced trial gates.

## 0.0.28_alpha+002 (2026-08-06, base 0.0.28_alpha)

Upstream sync: the whole 31-commit customization stack rebased onto Felicity `0.0.28_alpha`. No new fork features in this build — it exists to carry the fork onto the new base and to reconcile it where upstream grew into the same ground.

### Upstream 0.0.28_alpha brings
- **USB DAC detach crash fixed** in the player service — unplugging a DAC mid-playback no longer takes the service down.
- **Album-art palettes are cached in the database** (new `AlbumArtColors` model, DAO and migration) instead of being recomputed on every load, so theme reloading off album art is markedly faster.
- **Waveforms move to a DB-first pattern** (new `WaveformData` model and DAO) — computed once per track, reused thereafter.
- **Visualizer bars can be switched off**, with the toggle and its preference exposed in the visualizer tuning dialog, and peak-hold caps gained their own `capsEnabled` property; a bug that re-enabled caps after every track change is fixed.
- The waveform/bookmark menu now opens on **long press instead of tap**, so tapping the waveform seeks.
- **Forward/rewind buttons auto-hide** when the media-control row cannot fit the available width.
- Vertical slide mode for every pager mode (`FelicitySlider` plus new attributes).
- Room schema 20 → 22 with real migrations, so an in-place update keeps the existing library database.
- Missing translations filled in for German, French, Italian, Polish, Russian, Turkish and Chinese.

### Fork layer re-anchored on the new base
- **Two independent `capsEnabled` implementations merged into one.** Upstream added a public `capsEnabled` property with an invalidating setter for the same feature this fork had built at `0.0.26_alpha+23` — a private tracked flag plus `applyCapAlpha()`. Left side by side they would have been a redeclaration and a JVM signature clash. They are now a single public `var capsEnabled` whose setter does both: upstream's `invalidate()` and the fork's alpha reapplication, which is what keeps disabled caps from being resurrected every time a colour assignment resets the cap paint's alpha. Upstream's new `barsEnabled` sits beside it untouched.
- **Upstream's new bars toggle wired into the fork's renderer.** Upstream gated its per-band drawing call with `if (barsEnabled)` — but that loop is exactly what the PowerAmp-style commit replaced with the dense upsampled pass, so the gate would have been dropped along with the code it guarded. It now sits on the fork's own bar-drawing call, and the toggle works as upstream intended. Their `capsEnabled` gate had already landed on the interpolated cap draw unaided.
- Verified after the rebase: app id `shiroikuma.ongaku`, the arm64-only foss ABI filter, the 白い熊 音楽 label, the launcher icon, and all seven forced trial gates.

### Packaging
- The build counter is now **zero-padded to three digits** in the versionName, the APK filename and the release tag (`0.0.28_alpha+002`), so builds sort in build order everywhere. The versionCode stays a plain integer (`280002`). Earlier tags keep their unpadded names and are never renamed.
