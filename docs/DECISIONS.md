# Decisions & approved deviations

| ID | Decision | Reason |
|---|---|---|
| D-01 | Month boundary = calendar month in the device's current time zone | Plan §17 recommended default |
| D-02 | Opening balance: optional per month, **no automatic carry-forward** in v1.0 | Plan requires defining carry-forward before projections; kept explicit/manual |
| D-03 | Drive scope `drive.file` (non-sensitive) + Google Picker for members to open the shared household folder | Approved by owner 16 Nov 2026: app is for the public, without buying a domain. Full `drive` scope is restricted (verification + security assessment). **Open risk:** whether `drive.file` lets a member read/write files inside a folder picked via the Picker – verified with the in-app 'Check Drive access' test (GOOGLE-DRIVE-POC.md). |
| D-04 | One shared, encrypted household file (`HomeBudget - <name>.homebudget.json`) instead of a folder with one file per change | Field test 19 Nov 2026: picking a folder with `drive.file` grants the folder only; the member's 'List folder contents' returned empty, so joining was impossible. Picking a single file works. The file holds the key envelope and a grow-only set of encrypted operations; each phone re-adds its own operations if a concurrent upload dropped them. Trade-off: the operation author is recorded by the app, not attested by Drive per file, so a member running a modified app could mislabel authorship; Drive still enforces who can write at all (Viewers and removed members cannot). Owners on v1.1 are upgraded automatically on first sync. |
| D-04 | Key distribution = recovery passphrase (no per-member public-key exchange) | Plan §17 default; simplest secure option |
| D-05 | Transaction conflicts: deterministic winner + preserved loser + manual review in Sync center | Plan: preserve both versions and require review |
| D-06 | Members (role MEMBER) may edit/delete only transactions they created | Plan lists "add and edit"; restricting to own is the safer reading |
| D-07 | Local Room DB not additionally encrypted | Not required by plan; app sandbox + Keystore-wrapped key |
| D-08 | No log compaction in v1.0; snapshots are recovery copies | Plan: compaction only after all devices acknowledge |
| D-09 | Owner role cannot be transferred in v1.0 | Out of scope; avoids ownership/Drive-ownership mismatch |
| D-10 | UI strings English, inline in Compose | English first; extracting to `strings.xml` for Bengali is a follow-up |
| D-11 | Exact-time reminders not used; daily reminder via WorkManager periodic work | Plan: AlarmManager only if exact time is required |
| D-12 | Bottom tab "Transactions" labelled "History" | Label did not fit 5-tab bar on 411dp phones |

## D-05 Shared bazar lists (27 Nov 2026)
- Lists and items are separate synced entities (SHOPPING_LIST, SHOPPING_ITEM) so one phone can add items while another ticks them. Concurrent edits to the same item resolve silently (newer revision wins); no conflict is shown in Sync center.
- "Finish" creates one itemized EXPENSE per category with id `nameUUID(bazar-expense:<list>:<category>)`, so finishing twice (or on two phones) converges on one transaction.
- Owner/Admin/Member may edit any list; Viewer is read-only. Older app versions mark these operations as rejected; the v3 migration forgets rejected operations so they are applied after the update.
