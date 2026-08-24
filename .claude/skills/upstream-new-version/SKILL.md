---
name: upstream-new-version
description: Check whether Felicity Music Player upstream (Hamza417/Felicity) has a newer release tag and, if so, sync the shiroikuma.ongaku fork to it — present 白い熊 a proceed-gated table of what the new upstream version introduces, then (on go-ahead) fast-forward `master`, rebase the `custom` commit stack onto the new tag, and build the new signed APK per the ongaku-build skill. Use this skill whenever 白い熊 runs /upstream-new-version, or asks to "check for a new Felicity version", "sync to the latest Felicity release", "update to the new upstream", "rebase onto the new tag", or otherwise wants the ongaku fork brought up to a newer upstream release. This is the orchestration layer on top of ongaku-build; read that skill for all the build/rebase/version detail this one references.
---

# Sync the ongaku fork to a new upstream release

One-command upstream sync for 白い熊's `shiroikuma.ongaku` fork: **check → briefing gate → (if go) rebase → build → test → push**. This is the **orchestration layer**; every concrete fact (remotes, keystore, version scheme, the build pipeline, the customization commits) lives in the **`ongaku-build`** skill — **read it before running this**, especially "Customization commits", "Versioning", and "Build + sign + deploy pipeline".

## The one discipline that overrides everything: don't push to GitHub until 白い熊 says so

The entire rebase + build happens on the **local** working tree as a scratchpad. **No `git push` — not `master`, not `custom` — until 白い熊 explicitly tells you to push.** A rebase rewrites local `custom` history and is freely re-runnable (`git rebase --abort`, or reset to `origin/custom`) right up until that point. Build and let 白い熊 test on-device first. (Delivery of the build to the phone is separate and **automatic** — `/after-build`; only pushing to GitHub is held back.)

## Step 0 — Preconditions

- cwd is the repo (`~/git/shiroikuma-ongaku`); remotes are `origin` (SSH, fork) and `upstream` (HTTPS, Hamza417, fetch-only) — confirm with `git remote -v`.
- Working tree clean (`git status --short` empty). If dirty, surface it and ask before proceeding (`local.properties` is gitignored, so it never shows here).
- Currently on (or able to check out) `custom`.
- Run git/`gh` unsandboxed (`dangerouslyDisableSandbox: true`).

## Step 1 — Check upstream for a newer release tag

Felicity tags releases as bare `X.Y.Z_alpha` (e.g. `0.0.29_alpha`) — no `v` prefix. The fork tracks the **latest release tag**, not in-development master (upstream's master usually sits one untagged patch ahead — e.g. code 30 / `0.0.30_alpha` before that tag exists).

```bash
cd ~/git/shiroikuma-ongaku
git fetch upstream --tags
git fetch origin                 # so origin/master / origin/custom are current for later

# base tag of the custom stack = the nearest release tag custom was rebased onto
base_tag=$(git describe --tags --abbrev=0 --exclude='*+*' custom)

# latest upstream release tag (version-sorted; the "_alpha" suffix sorts fine under sort -V)
latest_tag=$(git tag -l | grep -E '_alpha$' | sort -V | tail -1)

echo "custom is based on:        $base_tag"
echo "latest upstream tag:       $latest_tag"
```

- **`latest_tag == base_tag`** → already on the newest upstream release tag. Report it and **stop**.
- **`latest_tag != base_tag`** → continue to Step 1.5. First gather the range for the briefing: `git log --oneline "$base_tag".."$latest_tag"` and the replay count `git rev-list --count "$base_tag"..custom`.

(If upstream ever adds pre_alpha/preview tags you don't want as a base, restrict the grep accordingly. Cross-check `base_tag` against `~/tmp/.shiroikuma_ongaku_build`'s first field if it exists.)

## Step 1.5 — ⛔ PROCEED GATE: new-features briefing (mandatory, BEFORE any rebase)

**HARD GATE.** Before ANY fast-forward or rebase, you MUST present 白い熊 a **descriptive table of what the new upstream version(s) introduce** and **wait for an explicit "proceed"**. Never skip it, never fold it into the rebase turn. This is mandatory on every sync.

Gather what changed between our base and the new tag:

```bash
git log --format='%h | %an | %s' "$base_tag".."$latest_tag"
git log --stat --format='%n### %h  %s%n%b' "$base_tag".."$latest_tag"   # full messages + files touched
git diff "$base_tag".."$latest_tag" -- changelogs.md fastlane/metadata/android/en-US/changelogs/   # Felicity's changelog surfaces
```

Present 白い熊 a **Markdown table**, one row per feature/change (fold recurring noise like `i18n:`/translation bumps into a single "translations" row), with these columns:

| Area | Change | Relevance to our fork |
| --- | --- | --- |
| … | plain-language sentence from the commit **body**, not just the subject | High / Medium / Low **and why** |

For the **Relevance** column, flag anything that:
- touches a **file our customization layer patches** — `music/build.gradle` `defaultConfig`/`foss` flavor (identity commit), `TrialPreferences.kt` (force-full-version commit), the launcher-icon assets, or `non_translatable_string.xml` — these are likely **rebase conflicts**;
- changes the **trial / licensing / paywall** logic (could reintroduce a gate our patch must re-cover);
- is a **genuinely useful fix** for 白い熊 (audio engine, visualizer, DSP, crash fixes).

If several upstream versions are jumped at once, cover each. End with a one-line takeaway (e.g. "one useful visualizer fix; the trial code is untouched so our force-full-version patch still applies cleanly — rebase should be clean"), then **STOP and wait**. Only an explicit go-ahead ("proceed", "go", "yes", …) continues to Step 2; otherwise stay on the current base.

## Step 2 — Fast-forward `master` (local only; push deferred)

`master` is a pure mirror of upstream, fast-forward only, never carries our changes.

```bash
git checkout master
git merge --ff-only upstream/master
git checkout custom
```

Do **not** `git push origin master` here — defer it to the push step.

## Step 3 — Rebase the `custom` stack onto the new tag

```bash
git rebase "$latest_tag"
```

Then triage the outcome.

### Clean rebase → continue to Step 4.

### The one expected, recurring conflict: the version lines in `music/build.gradle`

Commit 1 (`Customize for shiroikuma side-by-side install`) **replaced** upstream's literal `versionCode <N>` / `versionName "X.Y.Z_alpha"` lines with our `-P`-driven block. Upstream bumps those literals every release, so replaying commit 1 conflicts on exactly those lines **almost every time**. This is **"not huge"** — resolve in place:

- **Keep OUR `-P` block** (the `versionCode((project.findProperty('shiroikumaVersionCode') …` + `versionName(project.findProperty('shiroikumaVersionName') …` lines and their comment); **discard upstream's new literal `versionCode`/`versionName`.** The stale example inside our fallback comment need not be updated — the pipeline always passes `-P`, so the fallback is never used.
- Keep our `applicationId "shiroikuma.ongaku"` and the `foss` `abiFilters "arm64-v8a"` narrowing; take upstream's surrounding changes.
- The `app_name` edit in `non_translatable_string.xml` only conflicts if upstream edits near it (rare) → keep `白い熊 音楽`.
- The `TrialPreferences.kt` force-full-version commit conflicts only if upstream edits those methods → keep ours (re-derive if they restructured the class).

Then `git add` the resolved files and `git rebase --continue`.

### Other conflicts → decide: "not huge" vs "significant"

**"Not huge" — resolve in place, `git add`, `git rebase --continue`:** the version-line conflict above; pure context-line shifts where our hunk obviously slots into moved-but-equivalent code; a commit going **empty** because upstream did the same thing (let git skip it); a small handful (≈1–3) of mechanical conflicts where our change clearly maps onto the new code.

**"Significant" — STOP, do not guess, plan with 白い熊:**
- Upstream **refactored / moved / renamed** something a customization commit depends on (e.g. reworked `TrialPreferences`, the icon resource wiring, or `defaultConfig`) so a patch no longer maps cleanly.
- Many commits conflict, or the **same file conflicts repeatedly**.
- A **semantic** conflict: hunks merge textually but behaviour upstream changed (e.g. a new paywall path our force-full-version patch doesn't cover).

**When significant**, don't `--abort` silently and don't force a resolution:
1. Gather the picture without changing anything: `git status`, the conflicted hunks (`git diff`), and what upstream changed (`git log --oneline "$base_tag".."$latest_tag" -- <file>` / `git show`).
2. Identify **which of our commits** is conflicting and **why**.
3. **Present a plan and ask how to proceed** (AskUserQuestion, or EnterPlanMode for a multi-commit mess). Typical options: resolve together; re-derive the affected commit from `ongaku-build`; drop/defer the conflicting commit; or abort the whole sync (`git rebase --abort`, which returns the tree to exactly where it was — nothing lost). Make clear aborting is safe and re-runnable.

Don't push through a significant rebase just to "get it building" — a silently mis-resolved trial patch (paywall reappears) or icon regression is worse than a paused sync.

## Step 4 — Build the new APK (apply the ongaku-build pipeline)

Build directly with Bash per **ongaku-build**'s pipeline. The version counter **resets to N=1** automatically because the base tag changed → versionName `<new tag>+001` (e.g. a new `0.0.30_alpha` tag → `0.0.30_alpha+001`; the counter is always zero-padded to three digits). No submodules to init.

- If the build **fails on the rebase result** (a compile error in code our commits touch), treat it like a significant conflict: diagnose, and if it stems from the rebase, replan with 白い熊 rather than patching blindly.
- Toolchain reminders: JDK 21, SDK platform-36, NDK `28.2.13676358` (installed). `sh ./gradlew --stop` if a stale daemon picked the wrong JVM.

## Step 5 — 白い熊 tests on-device

On a successful build, deliver it **automatically** via the global **`/after-build`** skill. Then **wait** — 白い熊 installs over the previous fork build (same signing key → in-place update) and verifies the customizations still work (side-by-side install, no paywall, custom icon/label) on the new base. Iterate locally on any regression — still no push to GitHub.

## Step 6 — Only when 白い熊 says to push to GitHub

```bash
git push origin master                      # the deferred ff from Step 2
git push --force-with-lease origin custom    # the rebased stack (history rewritten)
```

`--force-with-lease` (never bare `--force`) so a surprise update to `origin/custom` aborts the push instead of clobbering it.

Then **update the docs to the new base**: the version/tag examples in `ongaku-build`'s *Project identity* / *Versioning* / *Customization commits*, and the base-tag references in this skill's Step 1. Commit those doc updates on `custom` (plain subject, no prefix) and push (force-with-lease). Treat the sync as incomplete until the docs reflect the new base.

## Reference — conflict watch-points (condensed from ongaku-build)

- **Commit 1** (`Customize for shiroikuma side-by-side install`): `music/build.gradle` `defaultConfig` (applicationId + `-P` version block — conflicts on upstream's literal version bump every release → keep ours) and the `foss` `abiFilters` narrowing; plus `non_translatable_string.xml` `app_name` → `白い熊 音楽`. Leave `namespace`, the `play` flavor, and upstream's `versionNameSuffix` lines untouched.
- **Force-full-version commit**: `preferences/.../TrialPreferences.kt` — the forced returns on the query methods. Conflicts only if upstream edits those methods; keep ours (a missed one re-introduces the paywall → `INSTALL`-succeeds-but-`TrialExpired`-screen).
- **Icon commit**: the `drawable/ic_launcher_ongaku_foreground.xml` + `mipmap-anydpi-v26/*` + regenerated rasters. Conflicts only if upstream reworks its launcher icon.
- **General rule:** if a conflict feels non-trivial, re-derive the affected commit from `ongaku-build` rather than fighting the merge; take the "significant → plan with 白い熊" path.

---

**Commit convention — no Claude attribution.** Never add a `Co-Authored-By: Claude …` / "Generated with Claude" trailer to commit messages or PR bodies; end the message at the last line of the body. This overrides the harness default. (Global rule: `~/.claude/CLAUDE.md`.)
