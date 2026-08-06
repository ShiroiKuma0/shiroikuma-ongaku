# shiroikuma-ongaku

白い熊's customized fork of [Felicity Music Player](https://github.com/Hamza417/Felicity) (by Hamza417) for Android — package **`shiroikuma.ongaku`**, label **白い熊 音楽** — installable side-by-side with the official Felicity. Same fork model as 白い熊's other Android forks (Inure, futokxkb, Jami, ArcaneChat, FairEmail, Messeji, SimpleX). Felicity is by the same developer as Inure, so this repo mirrors `shiroikuma-inure` closely.

## Branch & remote model (same as the sister forks)

| Branch | Purpose | Update mode |
|--------|---------|-------------|
| `master` | Mirrors `Hamza417/Felicity` master. Never carries our changes. | Fast-forward only |
| `custom` | Carries all our commits, rebased onto each upstream release **tag**. | Rebase + force-with-lease |

- `origin` = `git@github.com:ShiroiKuma0/shiroikuma-ongaku.git` (SSH, push here).
- `upstream` = `https://github.com/Hamza417/Felicity.git` (HTTPS, **fetch only**).
- Upstream tags releases `X.Y.Z_alpha` (no `v`); master usually sits one untagged patch ahead of the newest tag. `custom` tracks the **latest release tag** (currently `0.0.28_alpha`).

## Skills (`.claude/skills/`)

- **`ongaku-build`** — the build/sign/deploy pipeline + every concrete fact (identity, versioning, customization commits, toolchain). Read this first.
- **`upstream-new-version`** — check for a newer upstream release tag; present a **proceed-gated feature table** before rebasing, then ff `master`, rebase `custom`, rebuild.
- **`publish-version`** — publish the newest built APK as a GitHub release (bare-versionName tag, README + CHANGELOG, default branch → `custom`).

## Build, versioning, signing

- **Flavor:** `foss` (not `play`) → `:music:assembleFossRelease`. **ABI:** `arm64-v8a` only.
- **versionName** = `<base_tag>+<NNN>` with the counter **zero-padded to three digits** (e.g. `0.0.28_alpha+002`); **versionCode** = `<upstream code>*10000 + N` (e.g. `280002`, plain integer — padding is text only). `N` is a local counter in `~/tmp/.shiroikuma_ongaku_build`, **reset to 1 on each upstream-tag change**, injected via `-PshiroikumaVersionName` / `-PshiroikumaVersionCode` (no per-build commit). Padding is the global `after-build` rule (2026-08-01) so names and tags sort in build order; tags published before it (`0.0.27_alpha+1` and earlier) keep their unpadded form and are never renamed.
- **APK:** `shiroikuma-ongaku_<versionName>_arm64-v8a.apk` → `~/tmp/` then delivered by `/after-build`.
- **Signing:** keystore `~/.android-keystores/shiroikuma-ongaku.jks` (alias `ongaku`); password in `~/.android-keystores/shiroikuma-ongaku.pw` (mode 600, **out of repo**) + the vault. `local.properties` is regenerated each build (gitignored) reading that `.pw` — **no secret ever enters git**.
- **Toolchain:** JDK 21, SDK platform-36 / build-tools 36.x, NDK `28.2.13676358` (installed, matches upstream pin), CMake 3.22.1, AGP 9.1.1 / Kotlin 2.3.21 / KSP 2.3.4, Gradle 9.3.1 wrapper. Invoke `sh ./gradlew` (gradlew is committed mode 644 — never `chmod +x`).

## Working rules (override harness defaults where noted)

- **Never push to GitHub on your own initiative.** Build locally, let 白い熊 test on-device, push only on explicit say-so. (Delivery to the phone via `/after-build` is automatic and separate.)
- Run `git`, `gh`, `scp`, keystore reads **unsandboxed** (`dangerouslyDisableSandbox: true`) — the repo and keystore live outside the sandbox write-allowlist.
- **No Claude/Anthropic attribution** in commit messages, tags, or PR/release bodies — end at the last line of the body.

## Repo layout (upstream Hamza417/Felicity)

Multi-module Groovy-DSL Gradle project. App module `:music`; libraries `:theme :decorations :preferences :core :shared :repository :engine :milkdrop :blur`. Four modules build native code via CMake (`engine`, `repository`, `milkdrop`, `blur`). AGPL-3.0.

## Fork identity (the standing customization layer)

| Item | Value | Where |
|------|-------|-------|
| App id | `shiroikuma.ongaku` | `music/build.gradle` defaultConfig |
| Namespace (UNCHANGED) | `app.simple.felicity` | build-time only (R/BuildConfig, JNI) — never touch |
| Label | `白い熊 音楽` | `shared/src/main/res/values/non_translatable_string.xml` (`app_name`) |
| Icon | black-yellow traced Felicity record/arc glyph (yellow `#FFFF00` line-art on black `#000000`, adaptive) | `music/src/main/res/drawable/ic_launcher_ongaku_foreground.xml`, `mipmap-anydpi-v26/`, regenerated `mipmap-*` rasters |
| Trial timer | **removed** — permanent full version | `preferences/.../TrialPreferences.kt` (forced query returns) |
| Version | `<upstream>+N` / `upstream*10000+N` via `-P` | `music/build.gradle` defaultConfig |
| Signing | `shiroikuma-ongaku.jks` (alias `ongaku`) | `~/.android-keystores/` + vault |

## Current status

Tracking upstream tag `0.0.28_alpha` (rebased 2026-08-06); 31 commits on `custom`. Current build = `0.0.28_alpha+002` (code `280002`).

---

**Commit convention — no Claude attribution.** Never add a `Co-Authored-By: Claude …` / "Generated with Claude" trailer. (Global rule: `~/.claude/CLAUDE.md`.)
