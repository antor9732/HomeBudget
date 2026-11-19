# Product requirements

The baseline is *HomeBudget – Developer Requirements & Execution Plan v1.0 (16 Nov 2026)*. Approved
deviations are recorded in `DECISIONS.md`. Summary of what v1.0 implements:

- Android only, offline-first; Room is the source of truth on each device.
- One household per device UI (data model household-scoped). Currency BDT stored in poisha.
- Roles Owner / Admin / Member / Viewer, enforced locally and re-validated on every remote operation.
- Income & expense transactions with default + custom categories (archive, never delete).
- One spending budget per calendar month; optional opening cash balance per month.
- Dashboard metrics kept separate: remaining budget, overrun, net income, available cash.
- Reports: monthly totals, category %, month-to-month comparison, yearly summary, filtered history.
- Local notifications at 80 % / 90 % / over budget + daily over-budget reminder (configurable).
- Google sign-in + separate Drive authorization; shared normal Drive folder (not appDataFolder).
- Encrypted, immutable, operation-based sync with idempotency, tombstones and conflict history.
- Recovery passphrase, verify backup, verified rebuild, encrypted snapshots.

Out of scope (v1.0): bank/SMS import, OCR, AI advice, recurring payments, multi-currency, iOS/web,
PDF/CSV export.
