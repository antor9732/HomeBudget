# Changelog

## 1.4.0 — 2026-11-27
- New **Bazar** tab: shared shopping lists. Write items (with optional quantity and category) at home; at the market tick each item and enter its price; "Finish bazar" adds one itemized expense per category for today and can move unbought items to a new list.
- Lists and items sync to every phone in the household (new synced entity types SHOPPING_LIST and SHOPPING_ITEM; viewers are read-only). Finishing the same list on two phones cannot double-count (deterministic expense ids).
- Categories moved to Settings → Categories.
- Room v3 migration; operations an older version could not read are read again after updating.

## 1.3.2 — 2026-11-19
- Official release build: smaller APK (about 14 MB), signed with the official release key. SHA-256 of the APK is listed in the release notes.
- Upgrading from 1.3.1: one-time reinstall needed. On the phone, sync first, then uninstall 1.3.1 and install 1.3.2 (data comes back from Google Drive on sign-in).
- Docs: no keystore is published; setup guide explains using your own key.

## 1.3.1 — 2026-11-19
- Text updates for the single household file; README with install, invite and usage guide (Bangla).
- Build works without the maintainer keystore (falls back to the default debug key).

## 1.3.0 — 2026-11-19
- One-tap start: 'Continue with Google' signs in, connects Drive and creates the household automatically; on a new phone it restores the account's household automatically.
- Invited members: 'I was invited' → tap the shared file → joined. No passphrase by default (household key kept in the shared file, protected by Drive sharing). Owners can add an optional recovery passphrase, or turn an existing one off, in Backup & restore.

## 1.2.0 — 2026-11-19
- Household data now lives in one shared, encrypted Drive file. Members join by tapping that file in Google Drive (Shared with me); picking a folder could not give access to its contents under the `drive.file` scope.
- Owners on 1.1 are upgraded automatically on the next sync; existing members are re-shared the new file and join again.
- Snapshots are saved to the account's own My Drive.

## 1.1.0 (2026-11-17)
- Itemized transactions: optional list of items (name + price) per income/expense; total is the sum.
  "Paste list" accepts one item per line with the price last (Bangla digits supported).
- Items shown on the detail screen, item count in lists, history search matches item names.
- Room v2 with explicit migration 1→2 (`transaction_items`), migration test with data.
- Items travel inside the encrypted transaction operation (older app versions ignore them).
- Startup repairs a stale session pointing at a missing household.

## 1.0.1 (2026-11-16)
- drive.file scope + Google Picker join flow; in-app Drive access check.

## 1.0.0-dev (2026-11-16)
- M1 Offline foundation: Compose app, Hilt, Room v1 schema with sync metadata, DataStore settings.
- M2 Finance core: households, members/roles, categories (defaults, custom, archive), income/expense,
  monthly budgets, opening balances, poisha arithmetic, §6 calculator.
- M3 Dashboard (budget, income/expense, net, available cash, donut + 6-month bars, recent, sync chip),
  history with search/filter/sort, detail/edit/delete, reports (monthly, comparison, yearly),
  local budget notifications via WorkManager.
- M4 Credential Manager sign-in, AuthorizationClient Drive consent, shared folder creation, invites,
  role changes, removal, join/restore.
- M5 Encrypted immutable operation log sync, idempotency ledger, deterministic merge with conflict
  history, deferred dependencies, partial-upload recovery, revocation detection.
- M6 Passphrase-wrapped household key, Keystore protection, backup verification, verified rebuild,
  encrypted snapshots, passphrase change.
- Tests: 59 JVM/Robolectric tests incl. simulated two-device sync and UI smoke test.
