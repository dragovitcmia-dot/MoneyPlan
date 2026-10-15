# Cashflow (native Android app)

Kotlin, Jetpack Compose, Material 3, Room (main database schema v2, never change it without a migration), a pure-Kotlin engine
(`app/src/main/java/com/cashflow/app/engine`), TND amounts as `Long` millimes, offline, one-tap updater. Builds run only on
GitHub Actions (`.github/workflows/build.yml`).

**Read `docs/RELEASE_RULES.md` before doing any release work.** In short: shipping rule (unit tests, build, launch check and
geometry gate must pass), the permanent-history invariant, the geometry gate for tap targets, no database schema change without a
real migration and a Before-update snapshot, and every release adds or updates Cashflow Lab scenarios for exactly what it changes
and lists them in the final report.
