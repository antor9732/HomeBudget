# Synchronization architecture

## Drive layout (owner's My Drive, shared with members)

```
HomeBudget - <household>/
  household-manifest.json        clear: householdId, name, ownerEmail, currency, schemaVersion, sub-folder ids
  operations/op_<createdAtMillis>_<operationId>.json   one immutable encrypted file per change
  snapshots/snapshot_<ts>_<device>.json                encrypted, SHA-256-verified recovery copies
  metadata/key-envelope.json                           household key wrapped with recovery passphrase
```

Files are never overwritten (except the key envelope on passphrase change), so concurrent offline
devices cannot clobber each other.

## Local write path
`Repository → LocalChangeWriter`: in one Room transaction upsert the entity (revision+1,
lastOperationId = new op id), insert a PENDING `sync_operations` row (full entity snapshot,
`baseOperationId` = previous lastOperationId) and record the op in `applied_operations`. Then a unique
WorkManager job (network-constrained, exponential backoff) runs `SyncEngine.sync`.

## SyncEngine.sync
1. Require cursor (Drive connected), signed-in account, household key.
2. `getFile(folder)` – 403/404 → ACCESS_REVOKED (UI becomes read-only).
3. Pull: list `operations/` with `createdTime > cursor − 10 min` (overlap for Drive listing
   consistency), all pages. Skip op ids already in the ledger without downloading.
4. Download, check format/household/op id, AES-GCM decrypt with metadata as AAD (tampering fails).
   Unreadable/partial → counted as corrupted, not applied.
5. Validate shape (`OperationAuthorizer.validateShape`) and authorize with the **Drive-attested file
   owner email**, not payload fields (see SECURITY.md).
6. Sort by entity dependency order then Drive createdTime; apply each in its own Room transaction via
   `OperationApplier` (idempotent). Missing dependency → `deferred_operations` (retried each sync,
   rejected after 50 attempts). Unknown author → deferred (membership may arrive later).
7. Membership check: non-owner accounts must be an ACTIVE/INVITED member, else ACCESS_REVOKED.
8. Push pending ops as `op_<createdAt>_<opId>.json`. On retry, first look the name up so an upload
   whose response was lost is not duplicated. Mark SYNCED with the Drive file id.
9. Save cursor + last successful sync; trigger budget re-check if anything changed.

## Merge policy (`MergePolicy`, no wall-clock LWW)
| Case | Behaviour |
|---|---|
| unknown record / placeholder rev 0 | apply |
| same op or late-arriving ancestor (causal chain via ledger) | ignore |
| local is tombstone | ignore (never resurrect); edit-vs-delete stored as conflict |
| incoming delete | apply; conflict stored if it raced an edit |
| incoming.base == local.op | apply (causal successor) |
| otherwise concurrent | winner = max(revision, operationId) on every device; loser saved in `conflicts` |

Converges because every device picks the same winner. Users resolve from Sync center: "Keep shown
version" or "Use other version" (re-applied as a new, higher revision, so it propagates).

Budgets / opening balances use deterministic ids per (household, year, month) so two phones setting
the same month's budget collide on purpose and go through the policy above.

## Retention / compaction
v1.0 keeps the full operation log (no compaction). Snapshots are recovery aids only. Compaction must
wait until every active device has acknowledged a verified snapshot (plan §17) – not implemented.

## Restore
New phone / reinstall: sign in → authorize Drive → pick folder → passphrase → manifest + owner
attestation verified → full log replayed → membership confirmed (otherwise local copy wiped).
"Rebuild local data" first verifies every remote op decrypts, refuses with pending local changes, and
replaces local data inside one database transaction.
