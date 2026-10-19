# Release rules (standing; every session follows them)

These rules apply to every release of Cashflow. They are also pointed to from `CLAUDE.md`.

## 1. Shipping rule
A release ships only when ALL of these hold:
- the JVM unit tests pass (`testDebugUnitTest`, run by CI);
- the CI build is green (preview APK **and** Lab APK, signing certificate = the committed keystore, not debuggable);
- the emulator launch check passes (real app and Lab);
- the geometry gate (`scripts/geometry.py`) passes.

Builds run only on GitHub Actions. Releases are published from `main` to the pre-release tags `latest-debug` (real app) and
`latest-lab` (Cashflow Lab). Merge to `main` with a merge commit, push `main` directly (no pull request), wait for the build
and emulator jobs on `main`, then verify the release (APK downloads unauthenticated, sha256 equals `version.json`, certificate
equals the committed keystore, `debuggable=no`, the expected `versionName`).

Screenshot review is bounded: ONE smoke pass (dark theme, normal font), viewed once; fix only clear defects in ONE combined
commit; at most ONE extra CI cycle. No schedules and no mid-task reports: ONE final report per release.

Commits end with the two attribution lines given for the session (`Co-Authored-By: ...` and `Claude-Session: ...`). Never put
tokens or credentials in the repository.

## 2. Permanent history
A payment whose date has passed, or that was accepted (CONFIRMED), stays on the Calendar and in the balance history forever and
can never be deleted or altered through any series operation. Past waiting (overdue) items stay until the user answers them.
Delete, end and edit only touch WAITING rows dated today or later. Deleting a series keeps the past rows as standalone items
(amounts, statuses, dates and names kept). The repository refuses to delete or alter confirmed or past rows even if a caller
tries (`HistoryGuard`). `HistoryInvariantTest` (random operation sequences) and the targeted tests per path must stay green;
a new path that removes or rewrites rows must be added to them.

## 3. Geometry gate for tap targets
Every tap target the gate knows (bottom navigation, bell, Upcoming tabs and repeat toggle, wizard buttons, series rows) must be at
least 48 dp tall (and wide where it is a button) and inside the screen. `scripts/geometry.py` measures them on the emulator with
polling (repeated dumps every 500 ms up to 15 s, never one dump after a fixed wait). If the gate fails for real, fix the app, never
the assertion. A new control gets an assertion in the same release.

## 4. Database changes
No change to the main database schema without a real Room migration, a bumped `AppDatabase.SCHEMA_VERSION`, an exported schema
and the automatic "Before update" snapshot (made before Room opens). Never edit an existing schema version.

## 5. Cashflow Lab: every release updates its scenarios, and a check never asks for effort
Cashflow Lab (`com.cashflow.app.lab`) is a second installable app built from the same code with completely separate data. Its
scenario catalogue is `app/src/debug/java/com/cashflow/app/debug/lab/LabScenarios.kt` (pure seed builders, JVM tests in
`LabScenariosTest`). **Every release must add or update Lab scenarios for exactly what it changes** (each scenario: id, title,
one line, its steps, a pure seed builder, an optional clock), keep the tests that prove the facts in the questions, and **list the
scenarios added or changed in the final report**.

**Zero-effort rule.** A Lab scenario never asks the person to type, create, edit or delete anything. It seeds its data relative to the
Lab clock (system notifications off), navigates itself to the exact screen, sheet or picker to inspect (a `UiTarget`: a day list, an item
sheet, a Move date picker, the add wizard on its Name step, the purchase result, the Backups screen, ...) and shows the compact check
panel with 2 to 4 yes/no questions per screen and two buttons, "Looks right" and "Problem". A scenario that needs a different clock moves
it by itself (`clockShiftDays`). The panel sits in the layout above the navigation bar (and inside sheets), never over content, and its
buttons are at least 48 dp tall (the geometry gate measures them). The Lab home has "Run all checks" at the top and the scenario list;
the Results screen shows a tick or a cross per scenario and is stored only in the Lab's own storage.

**The phone test of every release is: open Lab, Run all checks, mark each scenario.**

Lab rules: all lab-only code lives in `app/src/lab/java`; every other build type gets no-op stand-ins from `app/src/labStub/java`,
so the real app contains no lab code (CI checks the real APK's dex for lab-only strings). The real app must behave exactly as before.
Ids that must not collide (notification channels, work names, FileProvider authority, update action) derive from `AppIds` /
`${applicationId}`. The real app refuses a Lab backup; the Lab accepts real backups (imported as a copy).
