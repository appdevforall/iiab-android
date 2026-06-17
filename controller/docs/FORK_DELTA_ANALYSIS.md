# Termux Fork — Delta Analysis (iiab changes vs upstream)

> Scope: technical debt **only in what iiab modified** in the vendored Termux fork at `controller/termux-core/termux-source` (submodule → `github.com/iiab/termux-app`). Upstream code is out of scope. Date: 2026-06-16.

## 1. What iiab actually changed

The fork's HEAD (`f8f36614`) sits **8 commits** above upstream base `30ebb2de`, all authored by `Ark74`. The **net delta is a single method** — `ExtraKeysView.loadIIABDefaultKeys()`, +25 lines in:

```
termux-shared/src/main/java/com/termux/shared/termux/extrakeys/ExtraKeysView.java
```

It builds a hardcoded extra-keys layout and calls the existing public `reload(ExtraKeysInfo, float)`. It is called once, from `controller/app/.../MainActivity.java:2036`, right before wiring the extra-keys click listener.

The 8 commits are: a 44-line feature commit, a "decouple" commit that removed 24 lines, and six small "fix: yet another change pt2…pt5" tweaks that net to the final 25-line method.

## 2. Findings (scoped to the delta)

Scoring: **Priority = (Impact + Risk) × (6 − Effort)**, each 1–5.

| ID | Location | Category | Issue | Imp | Risk | Eff | Prio |
|----|----------|----------|-------|----|----|----|----|
| K1 | `ExtraKeysView.java:682–706` | Architecture / fork maintenance | The change lives **inside an upstream file** but uses only public APIs (`reload`, public `ExtraKeysInfo` ctor, public `ExtraKeyDisplayMap`). The same result is achievable entirely from the controller app — so the fork need not modify upstream source at all. Every upstream sync will now conflict on this file. | 4 | 3 | 2 | 28 |
| K2 | 8 commits `e6c7b88d..f8f36614` | Documentation / process | Unreviewable history: five commits named `fix: yet another change pt2…pt5` with no body, netting 25 lines. Can't bisect, cherry-pick, or review intent. | 3 | 2 | 1 | 25 |
| K3 | `ExtraKeysView.java:688–692` | Code | Keyboard layout is a **hardcoded inline pseudo-JSON string** in Java; the comment "we match the same Termux keys" admits manual drift. Duplicates Termux's normal properties-driven layout and the controller's own ESC/TAB/… key-handling switch (`MainActivity.java:~2040+`). | 2 | 2 | 2 | 16 |
| K4 | `ExtraKeysView.java:702–704` | Code | Broad `catch (Exception e)` logs and continues; if layout construction fails the user silently gets **no extra keys** with no fallback to upstream defaults. | 2 | 2 | 1 | 20 |
| K5 | `ExtraKeysView.java:686` (no test) | Test | The layout string's validity (parses into a valid `ExtraKeysInfo`) is untested. Once the literal moves into the app as a constant, it becomes a trivial JVM unit test. | 2 | 2 | 2 | 16 |
| K6 | `ExtraKeysView.java:682–686, 703` | Code (cosmetic) | Malformed Javadoc (`/**` at column 0 while body is indented), decorative `===` banner comments, and fully-qualified `android.util.Log` instead of an import. The inline-FQN/no-import-change is actually a reasonable merge-conflict-minimization tactic — only matters once the code relocates. | 1 | 1 | 1 | 10 |

## 3. Top recommendation — relocate the change out of upstream (K1)

This single move resolves the core maintainability problem and directly supports the submodule/build work just completed.

1. Move the body of `loadIIABDefaultKeys()` into the controller app — e.g. a small helper that builds the `ExtraKeysInfo` (layout as a named constant or `R.string`/resource, addressing **K3**) and calls `extraKeysView.reload(iiabKeysInfo, 0f)` directly. `MainActivity.java:2036` already holds the `extraKeysView` reference, so the call site barely changes.
2. Revert `ExtraKeysView.java` to its upstream contents and **pin the submodule to a clean upstream tag**. The fork becomes a pristine mirror → upstream upgrades stop conflicting.
3. Add a fallback (K4): if the custom layout fails to build, fall back to Termux's default keys rather than showing none.
4. Squash the 8 commits into one well-described commit before any further sharing (**K2**).
5. Add the layout-validity unit test once the constant lives in the app (**K5**), fitting the Phase 0 safety-net pattern.

Net effect: **zero iiab modifications to vendored upstream**, a single-source-of-truth layout shared with the controller's key handling, and a fork that tracks upstream cleanly.

## 4. Note

The earlier `controller/app` analysis (`TECH_DEBT_PLAN.md`) is unaffected by the submodule now being present — those 34 files are unchanged. The submodule only matters for building (dependency resolution), which is handled on-device via Android Studio and in CI.
