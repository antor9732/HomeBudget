# Test cases

Run: `./gradlew testDebugUnitTest` → 66 tests, all passing (17 Nov 2026).

| Suite | Covers |
|---|---|
| BudgetCalculatorTest (8) | every §6 formula, plan example, negative remaining with positive cash, deleted excluded, poisha precision |
| MoneyTest (2) | parsing/formatting in poisha, Indian digit grouping |
| BudgetAlertEvaluatorTest (6) | 80/90 %, configurable thresholds, over-budget + daily schedule, cancel when back under, disabled = nothing, no duplicate per household/month/day |
| CryptoTest (5) | AES-GCM round trip, AAD tamper rejection, nonce uniqueness, passphrase wrap/unwrap, wrong/short passphrase |
| MergePolicyTest (10) | every merge rule incl. convergence of concurrent edits, tombstones, ancestors |
| FinanceRepositoryTest (9) | §14 single-device matrix (add ৳5,000 expense, ৳10,000 income, set ৳30,000 budget, spend ৳35,000 → overrun ৳5,000, delete, edit budget, switch month), offline sync no-op, atomic op log, archive, validation, viewer denied |
| MigrationTest (1) | exported schema matches entities and opens with registered migrations |
| TwoDeviceSyncTest (17) | **mandatory two-device test (৳1,000+৳500 / ৳2,000+৳700 → ৳4,200 on both, no duplicates)**, A→B and B→A, duplicate op, lost upload response, concurrent edit + conflict record, conflict resolution propagation, delete-vs-edit, concurrent budget, member removal → read-only, uninvited join refused, wrong passphrase, restore on new phone, forged op rejected, backup verify/rebuild/snapshot, corrupted backup refused, payloads encrypted, viewer read-only join |
| UiSmokeTest (1) | real app (Hilt, Room, DataStore) under Robolectric: welcome → create household → dashboard → add expense/income → over-budget state → all tabs → members → detail; screenshots in `app/build/screenshots` |

## Not yet verified (requires physical devices / OAuth client)
- Real Google sign-in, Drive consent, real Drive API calls (see GOOGLE-DRIVE-POC.md).
- Two physical phones end-to-end; notification display, reboot, battery restrictions, Android 8–15 matrix.
- Release (R8) build at runtime – it compiles, but has not been run on a device.
- TalkBack pass, large-font layout, dashboard < 1 s on a mid-range phone.
