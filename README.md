# Cashflow (MoneyPlan)

Offline Android cashflow planner. Kotlin, Jetpack Compose, Material 3, Room. minSdk 26. Currency TND;
amounts are `Long` millimes (1 TND = 1000).

## Layout
- `app/src/main/.../engine` – pure Kotlin (no Android imports): models, `BalanceEngine`, `Recurrence`,
  `DailyIncome` catch-up, `Money` formatting. Unit tested in `app/src/test`.
- `.../data` – Room entities, DAOs, `LedgerRepository` (materializes recurring rules over a 400-day
  horizon and runs the daily-income catch-up on start/resume/midnight).
- `.../ui` – Compose screens (Home, Settings) and view models.
- `app/src/debug` – debug-only "Load sample data" / "Clear all data" and the reference scenario;
  `app/src/release` holds an empty stand-in.

## Build & test
```
./gradlew testDebugUnitTest
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
```
