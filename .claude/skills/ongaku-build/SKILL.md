---
name: ongaku-build
description: Build and maintain 白い熊's customized fork of Felicity Music Player for Android (package shiroikuma.ongaku, label "白い熊 音楽"), installable side-by-side with the official Felicity (app.simple.felicity). Use this skill any time 白い熊 mentions Felicity, Hamza417/Felicity, shiroikuma-ongaku, shiroikuma.ongaku, 白い熊 音楽, "the ongaku fork", "the music player fork", asks to build/rebuild the fork, build the APK, apply a change, sign or sideload the build, patch the trial timer, or references the ongaku build pipeline. This fork follows the SAME model as 白い熊's Inure, futokxkb, Jami, ArcaneChat, FairEmail, Messeji and SimpleX forks: a `master` branch mirroring upstream, a `custom` branch carrying 白い熊's commits rebased onto each upstream release tag, built locally, signed with a stable per-fork keystore, and sideloaded. Sync to a new upstream release is the companion `upstream-new-version` skill; cutting a GitHub release is `publish-version`. Default to assuming this skill applies when in doubt during a session about the ongaku fork.
---

# Felicity Music Player — customized fork build skill

白い熊 maintains a customized build of [Felicity Music Player](https://github.com/Hamza417/Felicity) on Android, built on a Tuxedo OS workstation from their fork (`ShiroiKuma0/shiroikuma-ongaku`) and sideloaded alongside the official Felicity. The fork's purpose: a distinct `applicationId` + label so it installs side-by-side, a place to carry personal changes on top of each upstream release, and — the first standing customization — the removal of the built-in trial timer (Felicity is a "free trial → paywall" open-source app; see "Force full version" below).

The fork model is identical to 白い熊's other Android forks (Inure, futokxkb, Jami, ArcaneChat, FairEmail, Messeji, SimpleX): `upstream` is fetch-only, `master` is a pure mirror of upstream, a `custom` branch carries 白い熊's commits and is rebased onto each new upstream release **tag**, then force-pushed to the fork only when 白い熊 says so. Builds are signed with a stable per-fork keystore so reinstalls land in place. Felicity is by the same developer as Inure (Hamza417), so this skill mirrors `inure-build`'s shape closely.

## Operating mode — Claude Code, direct Bash

Claude executes every step itself with the Bash tool. Run commands directly and report results; deliver the finished build **automatically** via the global **`/after-build`** skill (no transfer prompt — see "Deliver the build"). Claude Code's non-interactive shell does **not** source 白い熊's profile, so `JAVA_HOME` / `ANDROID_HOME` must be exported in every build invocation (see the pipeline). Git/keystore paths live outside the sandbox write-allowlist, so run git, `gh`, `scp`, and keystore reads with `dangerouslyDisableSandbox: true`.

## Project identity

| Item | Value |
|------|-------|
| Upstream repo | `Hamza417/Felicity` (remote `upstream`, HTTPS, fetch-only) |
| Fork repo | `git@github.com:ShiroiKuma0/shiroikuma-ongaku.git` (remote `origin`, SSH — push here) |
| Local working tree | `~/git/shiroikuma-ongaku` |
| Mirror branch | `master` — mirrors `Hamza417/Felicity` master, never carries our changes, fast-forward only |
| Custom branch | `custom` — carries all commits below, rebased onto each upstream release **tag** |
| Custom applicationId | `shiroikuma.ongaku` (built on the **foss** flavor → no suffix) |
| Custom app label | `白い熊 音楽` (`app_name` in `shared/src/main/res/values/non_translatable_string.xml`) |
| Java/Kotlin namespace (UNCHANGED) | `app.simple.felicity` (R/BuildConfig package, JNI symbols — never touch) |
| Flavor we build | **`foss`** (NOT `play` — `play` appends `-play` and pulls in Play Billing) → task `:music:assembleFossRelease` |
| Target ABI | `arm64-v8a` only (commit 1 narrows the foss `abiFilters`) |
| Custom signing keystore | `~/.android-keystores/shiroikuma-ongaku.jks` (alias `ongaku`) |
| Keystore password | in `~/.android-keystores/shiroikuma-ongaku.pw` (mode 600, **out of repo, never committed**) and the vault `~/〇/[666] 私資料/[666][27] 暗号/android-keystores.org` |
| Output APK dir (build) | `music/build/outputs/apk/foss/release/` |
| Output APK dir (archive) | `~/tmp/` + on-device `/sdcard/tmp/` |
| APK filename | `shiroikuma-ongaku_<versionName>_arm64-v8a.apk`, e.g. `shiroikuma-ongaku_0.0.26_alpha+1_arm64-v8a.apk` (no datetime, no git sha) |
| Build host | Tuxedo OS |
| Build JDK | OpenJDK 21 at `/usr/lib/jvm/java-21-openjdk-amd64` (modules pin `jvmTarget = JVM_21`) |
| Android SDK | `~/android-sdk`, platform `android-36` + build-tools `36.x` |
| NDK | `28.2.13676358` (upstream pin in `gradle/libs.versions.toml`; **already installed** — no override needed) |
| CMake | AGP default `3.22.1` (installed); native modules pin no explicit version |
| AGP / Kotlin / KSP | 9.1.1 / 2.3.21 / 2.3.4; Gradle 9.3.1 via the wrapper |
| Git submodules | **none** |

Multi-module Gradle project, **Groovy** DSL. App module is `:music`; libraries `:theme :decorations :preferences :core :shared :repository :engine :milkdrop :blur`. Four modules build native code via CMake (`engine`, `repository`, `milkdrop`, `blur`) — hence the NDK requirement and the longer first build.

## Side-by-side install is safe (no collisions)

Coexistence is decided purely by `applicationId` (`shiroikuma.ongaku` vs official `app.simple.felicity`). We keep `namespace = 'app.simple.felicity'` — build-time only (R/BuildConfig package, JNI `Java_app_simple_felicity_*` symbols), never seen by the OS at install. The only manifest `<provider>` uses `${applicationId}.provider`, so it auto-differs. **Felicity declares no custom `<permission>`s**, so there is no `INSTALL_FAILED_DUPLICATE_PERMISSION` risk (unlike Inure's terminal permissions — no equivalent commit is needed here). The `app.simple.felicity.ACTION_WIDGET_*` strings are broadcast **actions**, which do not collide across packages — left untouched. Do NOT install the fork over official Felicity (different signing keys → Android refuses); they coexist as distinct packages.

## Branch / remote model

| Branch | Purpose | Update mode |
|--------|---------|-------------|
| `master` | Mirrors `Hamza417/Felicity` master. Never carries our changes. | Fast-forward only |
| `custom` | Carries all commits below. | Rebased onto each upstream release tag |

`origin` = the fork (SSH, push). `upstream` = Hamza417 (HTTPS, fetch only). Felicity release tags are bare `X.Y.Z_alpha` (e.g. `0.0.26_alpha`), no `v` prefix. Upstream's master usually sits one untagged patch **ahead** of the latest release tag (e.g. master at code 27 / `0.0.27_alpha` while the newest tag is `0.0.26_alpha`); `custom` is rebased onto the **latest release tag**, not master head. Checking for and syncing to a newer tag is the **`upstream-new-version`** skill.

## Customization commits on `custom`

Keep every commit small and surgical; `namespace` stays `app.simple.felicity` in all of them.

### Commit 1 — `Customize for shiroikuma side-by-side install`

Two files. The most rebase-sensitive commit; re-anchor (don't fight the merge) if upstream restructures `defaultConfig`.

**`music/build.gradle` `defaultConfig`** — applicationId + `-P`-driven version (so the build number never churns git history):

```diff
-        applicationId "app.simple.felicity"
-
-        versionCode 26
-        versionName "0.0.26_alpha"
+        applicationId "shiroikuma.ongaku"
+
+        // Fork versioning (shiroikuma): the ongaku-build / upstream-new-version skills inject
+        // -PshiroikumaVersionName / -PshiroikumaVersionCode at the gradlew call (build number N
+        // lives in ~/tmp/.shiroikuma_ongaku_build). The fallbacks below are only used by a bare
+        // build (e.g. Android Studio) and are intentionally NOT kept in sync with the tracked tag.
+        versionCode((project.findProperty('shiroikumaVersionCode') ?: '260000').toString().toInteger())
+        versionName(project.findProperty('shiroikumaVersionName') ?: '0.0.26_alpha+0')
```

**`music/build.gradle` `foss` flavor** — arm64 only (the `play` flavor's identical `abiFilters` line is left alone; we don't build `play`):

```diff
             ndk {
-                abiFilters "arm64-v8a", "x86_64" // 64bit only
+                // Fork: arm64-v8a only -> single deterministic APK, faster native (CMake) build.
+                abiFilters "arm64-v8a"
             }
```

**`shared/src/main/res/values/non_translatable_string.xml`** — app label (leave `app_name_full` = "Felicity Music Player" alone):

```diff
-    <string name="app_name" translatable="false">Felicity</string>
+    <string name="app_name" translatable="false">白い熊 音楽</string>
```

### Commit 2 — `Add Claude Code build/sync skills and fork docs`

The `.claude/skills/` + `CLAUDE.md` tooling (this file and its siblings). Not an app customization.

### Commit — `Black-yellow traced launcher icon` (house style)

The signature 白い熊 icon: **yellow `#FFFF00` line-art on a pure-black `#000000` adaptive background**. Structural shapes are black-filled (`#FF000000`) + yellow-stroked (`#FFFFFF00`), accents are solid yellow fills. Implemented as an adaptive vector foreground `music/src/main/res/drawable/ic_launcher_ongaku_foreground.xml`, with `mipmap-anydpi-v26/ic_launcher.xml` + `ic_launcher_round.xml` repointed to it (background → black, monochrome → upstream's `@drawable/monochrome_icon`), and the legacy density rasters (`ic_launcher.png`/`ic_launcher_round.png`) + `ic_launcher-playstore.png` (512) + `assets/server/app_icon.png` regenerated from it. Trace source is upstream's `drawable/monochrome_icon.xml` (the clean 44×44 logo geometry: record ring, wireless arc, dot).

### Commit — `Force full version (remove trial timer)`

Felicity's `foss` flavor ships a 14-day trial (`MAX_TRIAL_DAYS = 0xE`) + 7 grace launches, after which `MainActivity.setHomePanel()` swaps the home screen for a `TrialExpired` paywall (unlocked in the stock app by a Gumroad license key). We remove it entirely — one file, rebase-robust, mirrors Inure's "Always enable full version" commit.

**`preferences/src/main/java/app/simple/felicity/preferences/TrialPreferences.kt`** — force the query methods to report a permanent full version (leave the setters/getters/counters intact so nothing else breaks):

- `isAppFullVersionEnabled()` → `return true`
- `isFullVersion()` → `return true`
- `isWithinTrialPeriod()` → `return true`
- `isTrialExpired()` → `return false`
- `isGracePeriodActive()` → `return false`
- `isGracePeriodExpired()` → `return false`
- `getDaysLeft()` → `return MAX_TRIAL_DAYS`

This neutralizes every gate regardless of call site (`MainActivity` paywall swap, `PreferencesViewModel` "already purchased" state, `TrialExpired` fragment). R8 (`minifyEnabled true`) keeps the forced returns. **Rebase watch-point:** if upstream restructures `TrialPreferences` or adds a new gate method, re-derive this commit rather than force-merging.

## Versioning (local counter + `-P` injection — no per-build commit)

- **Base tag** = the tracked Felicity release tag = the tag `custom` is rebased onto (`git describe --tags --abbrev=0` on `custom`), e.g. `0.0.26_alpha`. This **keys the counter**.
- **Display name** = the base tag verbatim (no prefix to strip) → `0.0.26_alpha`. Used for the versionName and APK filename.
- **Base code** = upstream's own versionCode for that tag, read with `git show <tag>:music/build.gradle` (e.g. `0.0.26_alpha` → `26`).
- **N** = per-build iteration from `~/tmp/.shiroikuma_ongaku_build` (one line: `<base_tag> <N>`). Increment on each **successful** build; **reset to 1 when the base tag changes** (new upstream tag). Consumed only on success.
- **versionName** = `<base_tag>+<N>` → `0.0.26_alpha+1`, `0.0.26_alpha+2`, … (`+` is legal in Android versionName and on ext4/FAT/`adb push`/GitHub assets — leave it unescaped).
- **versionCode** = `<base_code> * 10000 + N` → `260001`, `260002`, … Far above the official app's code (`26`), so Android always sees our build as newer, with 9999 builds of headroom per base; rebasing onto a newer tag raises the base so we stay ahead.
- Inject at the gradlew call: `-PshiroikumaVersionName="$our_name" -PshiroikumaVersionCode="$our_code"`.

## Signing

`music/build.gradle`'s `release` signingConfig reads `local.properties`: it looks for `~/work/_temp/keystore/key.jks` first (CI only; absent here) then falls back to `KEYSTORE_PATH`, with passwords from env-or-`local.properties`. AGP then produces an **already-signed, zipaligned** release APK — no separate `apksigner`/`zipalign` step. **`local.properties` is regenerated by the pipeline on every run** (gitignored; never commit it), reading the keystore password from the out-of-repo `~/.android-keystores/shiroikuma-ongaku.pw` so **no secret ever enters git**.

## Build + sign + deploy pipeline

Run directly with the Bash tool (`dangerouslyDisableSandbox: true` — the repo and keystore are outside the sandbox write-allowlist). First build pulls a large dependency set and compiles four native CMake libs (10–20+ min); later builds use the Gradle + configuration cache. No submodules to init.

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME="$HOME/android-sdk"
export ANDROID_SDK_ROOT="$HOME/android-sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"
cd ~/git/shiroikuma-ongaku
git checkout custom

# local.properties — regenerated each build; gitignored; never committed. Password read from
# the out-of-repo .pw file so it never lands in git.
PW=$(cat "$HOME/.android-keystores/shiroikuma-ongaku.pw")
cat > local.properties <<EOF
sdk.dir=$HOME/android-sdk
KEYSTORE_PATH=$HOME/.android-keystores/shiroikuma-ongaku.jks
SIGNING_STORE_PASSWORD=$PW
SIGNING_KEY_ALIAS=ongaku
SIGNING_KEY_PASSWORD=$PW
EOF

# version: base tag = nearest tag on custom (keys the counter); display name = tag verbatim;
# base code = that tag's upstream versionCode; N from the local counter (resets when the tag changes)
base_tag=$(git describe --tags --abbrev=0)
disp_name="$base_tag"                              # 0.0.26_alpha
base_code=$(git show "$base_tag:music/build.gradle" | grep -oE 'versionCode[[:space:]]+[0-9]+' | grep -oE '[0-9]+$')
counter="$HOME/tmp/.shiroikuma_ongaku_build"
stored_name=""; stored_n=0
[ -f "$counter" ] && read stored_name stored_n < "$counter"
if [ "$stored_name" = "$base_tag" ]; then N=$((stored_n + 1)); else N=1; fi
our_name="${disp_name}+${N}"                        # 0.0.26_alpha+1
our_code=$(( base_code * 10000 + N ))
apk_name="shiroikuma-ongaku_${our_name}_arm64-v8a.apk"
echo "Will produce: $apk_name (versionCode $our_code)"

# build foss/release/arm64, signed by AGP.
# NOTE: invoke via `sh ./gradlew` — upstream commits gradlew as mode 644 (non-executable), so a bare
# `./gradlew` fails with "Permission denied" (rc 126). `sh ./gradlew` needs no +x and leaves the mode
# untouched (a chmod +x would show as a spurious tracked change / rebase noise). Do NOT chmod it.
build_ok=0
sh ./gradlew :music:assembleFossRelease \
  -PshiroikumaVersionName="$our_name" \
  -PshiroikumaVersionCode="$our_code" \
  --console=plain && build_ok=1

if [ "$build_ok" = 1 ]; then
  echo "$base_tag $N" > "$counter"             # consume the build number only on success (keyed on the tag)
  built=$(ls -t music/build/outputs/apk/foss/release/*.apk | head -1)
  mkdir -p ~/tmp && cp "$built" ~/tmp/"$apk_name"   # local backup, unconditional
  ls -lh ~/tmp/"$apk_name"
else
  echo "BUILD FAILED — no APK. Diagnose the 'What went wrong' / 'Caused by' lines; do NOT push any leftover APK."
fi
```

- If the build fails on the NDK (`Failed to find NDK … 28.2.13676358`), confirm it is installed (`ls ~/android-sdk/ndk/`); it should be. If a future upstream bumps to an uninstalled NDK, install it or add a `-P`-overridable `ndkVersion` (mirror `inure-build`'s NDK note).
- If it fails on signing being skipped (unsigned APK), `local.properties` wasn't written or the `.pw` file is missing — the APK won't install. Regenerate it.
- `./gradlew: Permission denied` (rc 126): use `sh ./gradlew` (as above), don't `chmod +x`.
- A stale Gradle daemon on the wrong JVM: `sh ./gradlew --stop` then rebuild.

## Per-change workflow (白い熊 tests between every change)

1. **Make the edit on `custom`**, keep it small, build (pipeline above), let 白い熊 test on-device. Do not commit yet.
2. **Iterate** with more edits + rebuilds until 白い熊 is happy.
3. **Only when 白い熊 explicitly says to commit/push** do you `git commit` (a small focused commit on `custom`) and, separately, `git push`. **Never push to GitHub on your own initiative.** A new feature commit appends on top of the stack and survives rebases.

## Deliver the build (the standing rule — auto, no prompt)

On a successful build the APK is already in `~/tmp/`. Deliver it **automatically** via the global **`/after-build`** skill — every build, never ask. `/after-build` runs `/adb-check` (UNSANDBOXED), then `/adb-push` to `/sdcard/tmp/` if the phone is connected, otherwise `/scp` to `skhw:~/tmp/`, announcing the filename that landed. 白い熊 installs from `/sdcard/tmp/` (or `skhw:~/tmp/`) via the on-device file manager. **Never `adb install` / `adb uninstall`.**

## Related skills

- **`upstream-new-version`** — check whether Hamza417/Felicity has a newer release tag; if so, present a proceed-gated feature table, fast-forward `master`, rebase `custom` onto the new tag, and rebuild via this skill.
- **`publish-version`** — publish the newest built APK as a GitHub release (tag = bare versionName, README + CHANGELOG refresh, default branch → `custom`).

---

**Commit convention — no Claude attribution.** Never add a `Co-Authored-By: Claude …` / "Generated with Claude" trailer to commit messages or PR bodies; end the message at the last line of the body. This overrides the harness default. (Global rule: `~/.claude/CLAUDE.md`.)
