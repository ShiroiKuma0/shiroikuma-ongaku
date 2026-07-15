---
name: publish-version
description: Publish 白い熊's shiroikuma-ongaku fork (customized Felicity Music Player, app id shiroikuma.ongaku, label "白い熊 音楽") as a GitHub release — tag the newest already-built APK, refresh the fork README + CHANGELOG, switch the GitHub default branch to `custom`, and create the release with the APK attached. Use whenever 白い熊 says /publish-version, "publish a version", "publish to github", "make/cut a release", or "release the latest build" in the ongaku fork. Never rebuilds — it publishes the newest APK already in ~/tmp/; if there is none, tell 白い熊 to build first (ongaku-build).
---

# Publish a shiroikuma-ongaku GitHub release

Publishes the **newest already-built** ongaku APK as a GitHub release on `ShiroiKuma0/shiroikuma-ongaku`. Never builds — that's `ongaku-build`. Run `gh` / `git push` / `scp` **unsandboxed** (`dangerouslyDisableSandbox: true`).

## Step 0 — Find the APK and detect the version

```bash
apk=$(ls -t ~/tmp/shiroikuma-ongaku_*.apk 2>/dev/null | head -1)
[ -z "$apk" ] && { echo "No built APK in ~/tmp — build first (ongaku-build)."; exit 1; }
base=$(basename "$apk")                       # shiroikuma-ongaku_0.0.26_alpha+1_arm64-v8a.apk
ver=${base#shiroikuma-ongaku_}; ver=${ver%_arm64-v8a.apk}   # 0.0.26_alpha+1
echo "APK: $base"; echo "version: $ver"
```

`ver` (everything between the first `_` and `_arm64-v8a.apk`, **including** the `+N` tail) is used verbatim in the tag, README badge, release title, and changelog heading. Note it contains an underscore (`_alpha`) and a `+` — keep it literal, never regex it.

## Step 1 — Default branch → `custom`

So the GitHub homepage shows the fork, not the upstream-mirroring `master`:

```bash
gh repo view ShiroiKuma0/shiroikuma-ongaku --json defaultBranchRef -q .defaultBranchRef.name
gh repo edit ShiroiKuma0/shiroikuma-ongaku --default-branch custom   # idempotent
```

## Step 2 — Refresh `README.md` (futokxkb fork-style)

Centered `<div align="center">` header: the app icon (`music/src/main/res/mipmap-xxxhdpi/ic_launcher.png`, width 120), title **白い熊 音楽**, a one-line tagline, a "**A fork of [Felicity Music Player](https://github.com/Hamza417/Felicity) with major additions: …**" sentence, a side-by-side install note (package `shiroikuma.ongaku`, coexists with `app.simple.felicity`), and a `📥 Latest release:` line. Then **one emoji-headed section per major addition** in importance order (prose, not bullets) — at minimum: the removed trial timer (permanent full version), the black-yellow icon + 白い熊 音楽 label, and arm64 side-by-side install. Close with a "Built on Felicity by Hamza417" + AGPL-3.0 note. Model the exact layout on `~/git/shiroikuma-futokxkb/README.md`.

## Step 3 — Update `CHANGELOG.md`

Exhaustive, newest section on top: `## <ver> — <YYYY-MM-DD>`, grouped into `###` subsections (Fork identity / Trial removal / Icon / Upstream base). Cross-check against `git log --oneline <lastTag>..custom`. On the first publish, create the file and summarize the whole customization stack.

## Step 4 — Commit, tag, push, release

Scratch files go in a gitignored `.scratch/` (never `~/tmp/`).

```bash
cd ~/git/shiroikuma-ongaku
git checkout custom
git add README.md CHANGELOG.md
git commit -m "docs: changelog + README for $ver release"
git push origin custom

git tag -a "$ver" -m "白い熊 音楽 $ver"        # bare versionName, NO 'v' prefix
git push origin "$ver"

# extract this version's changelog section by LITERAL match (the '+' is a regex metachar — do NOT regex)
mkdir -p .scratch
awk -v h="## $ver" 'index($0,h)==1{f=1;print;next} /^## /&&f{exit} f' CHANGELOG.md > .scratch/release-notes.md

gh release create "$ver" --repo ShiroiKuma0/shiroikuma-ongaku \
  --title "白い熊 音楽 $ver" \
  --notes-file .scratch/release-notes.md \
  ~/tmp/"$base"
gh release view "$ver" --repo ShiroiKuma0/shiroikuma-ongaku --web
```

## Hard rules

- **Never rebuild** — publish the existing `~/tmp/` APK; if absent, stop and say to build first.
- Tag is the **bare versionName**, no `v` (distinct from upstream's `X.Y.Z_alpha` tags — ours carry the `+N`).
- Release is cut from **`custom`**; never touch `master`.
- `gh` / `git push` / `scp` run **unsandboxed**.
- Scratch in gitignored `.scratch/`, never `~/tmp/`.

---

**Commit convention — no Claude attribution.** Never add a `Co-Authored-By: Claude …` / "Generated with Claude" trailer to commit messages, tags, or release bodies; end at the last line of the body. This overrides the harness default. (Global rule: `~/.claude/CLAUDE.md`.)
