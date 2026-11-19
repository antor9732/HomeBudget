# Database (Room, version 2)

Schema JSON: `app/schemas/com.homebudget.core.database.AppDatabase/1.json`. Destructive migration is
never enabled; add explicit `Migration` objects to `AppDatabase.MIGRATIONS` and extend `MigrationTest`.

All money columns are `Long` minor units (poisha). Dates: `transactionDate` = `LocalDate.toEpochDay()`.

| Table | Purpose / key fields |
|---|---|
| households | id, name, ownerMemberId, currencyCode, driveFolderId, createdAt, updatedAt, revision, lastOperationId, isDeleted |
| members | id (= UUIDv3("member:hh:email") for invited members), householdId FK, email, googleSubject, displayName, role, status (INVITED/ACTIVE/REMOVED), joinedAt, removedAt, sync metadata |
| budgets | id = UUIDv3("budget:hh:y-m") so devices converge; unique (householdId, year, month); amountMinor |
| opening_balances | same id scheme; `isSet=false` means cleared (not a tombstone, so it can be set again) |
| categories | name, type, iconKey, colorHex, isActive (archive), sortOrder |
| transactions | categoryId FK (RESTRICT), type, title, amountMinor, currencyCode, transactionDate, description, createdBy, deviceId, timestamps; indexes on householdId, transactionDate, categoryId, type |
| transaction_items | id = "<transactionId>:<position>", transactionId FK (CASCADE), position, name, amountMinor. Replaced as a whole on every edit; parent amountMinor = sum (validated locally and on remote apply). Added in v2 (MIGRATION_1_2) |
| sync_operations | outbound op log: operationId, entityType/Id, operationType, deviceId, actorMemberId, baseOperationId, payload (JSON), schemaVersion, createdAt, syncStatus, retryCount, lastError, driveFileId |
| applied_operations | idempotency ledger (LOCAL / REMOTE / REJECTED) + causal parent for ancestor detection |
| deferred_operations | valid remote ops waiting for a dependency (e.g. category) |
| sync_cursors | device-local: Drive folder ids, attested owner email, lastRemoteCursor, lastSuccessfulSyncAt, lastError, accessState |
| household_keys | household AES key wrapped by the Android Keystore |
| conflicts | entity, kept version JSON, other version JSON, revisions, detectedAt, resolutionStatus |

Device-scoped preferences (theme, notification settings, device id, signed-in identity – never tokens)
live in DataStore (`AppPreferences`).

Rules implemented: every shared record has stable UUID + revision + lastOperationId + tombstone;
business write and pending op are saved in one Room transaction (`LocalChangeWriter`); tombstones are
never purged in v1.0.
