# Security model

- **Encryption at rest in Drive**: every operation payload and snapshot is AES-256-GCM encrypted with a
  random 256-bit household key; 96-bit random nonce per message; routing metadata is bound as AAD.
- **Key distribution / recovery**: the household key is wrapped with a key derived from the recovery
  passphrase (PBKDF2-HMAC-SHA256, 210 000 iterations, 128-bit salt) and stored as
  `metadata/key-envelope.json`. The passphrase is never uploaded or logged. Members get it out-of-band
  from the owner. Lost passphrase + all phones lost = data unrecoverable (stated in Privacy screen).
- **On device**: household key wrapped by a non-exportable Android Keystore AES-GCM key. Android cloud
  backup disabled (`allowBackup=false`, data extraction rules) because a Keystore-wrapped key cannot be
  restored elsewhere. Room DB itself is not SQLCipher-encrypted (relies on app sandbox) – see DECISIONS D-07.
- **Tokens**: Drive access tokens are requested from Google Identity Services per call and never stored.
  DataStore holds only email, display name and Google subject.
- **Authorization of remote data**: author identity = Drive file `owners[0].emailAddress` (server
  attested). Owner = the Drive folder owner (also checked against manifest and key-envelope owners on
  join). Non-owner authors must be household members, not REMOVED at the file's creation time, and
  `actorMemberId` must match. Roles: HOUSEHOLD → owner; MEMBER → owner/admin (admins cannot touch the
  owner record or grant OWNER), plus self-activation by an invited member; categories/budgets/opening
  → owner/admin; transactions → admin/owner any, member own only, viewer none.
  Rejected ops are recorded and never applied. Household id, schema version, entity ids and amounts are
  validated before applying.
- **Joining**: knowing a household id is useless – the account needs Drive access granted by the owner,
  an app-level member record, and the passphrase.
- **Removing members**: deletes the Drive permission and records REMOVED. Already-downloaded data on an
  offline phone cannot be erased remotely (shown in the Members screen).
- **Logging**: no amounts, titles, notes, tokens or keys are logged; OkHttp has no logging interceptor.
- **Residual risks**: a malicious *member* who already holds the key can read all history; they cannot
  forge operations as another member because authorship comes from Drive. Full `drive` scope is broad
  (see D-03).
