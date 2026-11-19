# Milestone 0 – Google Drive shared-folder proof of concept

## Design under test
| Item | Choice |
|---|---|
| Authentication | Credential Manager `GetSignInWithGoogleOption(webClientId)` → Google ID token (email, sub) |
| Authorization | `Identity.getAuthorizationClient().authorize()` with scope `auth/drive.file`, account pinned to signed-in email; consent via PendingIntent |
| Member folder access | Google Picker through `AuthorizationRequest` resource parameters `PICKER_OAUTH_TRIGGER`, `PICKER_ALLOW_FOLDER_SELECTION`, `PICKER_MIMETYPES=folder` (play-services-auth 21.6.0); id read from `tokenResponseParams["picked_file_ids"]` |
| Folder creation | `POST drive/v3/files` (folder mime) in owner's My Drive + `operations/`, `snapshots/`, `metadata/` |
| Invite | `POST files/{folder}/permissions` type=user, role writer (reader for Viewer), notification email |
| Discovery by member | `files.list q="mimeType=folder and name contains 'HomeBudget -' and trashed=false" corpora=user` (includes Shared with me) |
| Read children | `files.list q="'<ops>' in parents and createdTime > T" orderBy=createdTime`, paginated |
| Write | multipart upload of a new JSON file into `operations/` |
| Authorship | `owners(emailAddress)` / `lastModifyingUser(emailAddress)` fields |
| Revocation | `DELETE files/{folder}/permissions/{id}`; member sees 403/404 on `files.get(folder)` |

## What has been verified
Against `FakeDriveServer` (in-memory, multi-account, permission-checked, paginated – `app/src/test/.../testing/Fakes.kt`):
Account A creates + shares, B discovers/reads/writes, both exchange files, duplicate/partial uploads,
revocation detection → read-only, uninvited account refused, new-phone restore. See `TwoDeviceSyncTest`.

## In-app access test
Join screen → *Choose from Google Drive* → *Check Drive access (test)* runs: open folder, list contents, read manifest, read key envelope, list change files, read another account's change file, write a test file. Each line shows ✓/✗ with the error.

## Still to verify with real accounts (cannot be done without the OAuth client + phones)
1. A signs in, Settings → Connect Google Drive → folder appears in A's Drive.
2. A invites B (Members). B receives Google share email.
3. B installs, Join → sign in → Find household folders lists it → passphrase → joins.
4. Both add transactions offline, reconnect, check both see all (plan §14 two-device test).
5. A removes B → B Sync center shows "Access revoked" and app becomes read-only.
6. Record here: any consent-screen quirks, whether `owners` email is returned for files B created
   inside A's folder (expected yes), listing latency observed.

| Check | Result | Date / notes |
|---|---|---|
| 1 | pending | |
| 2 | pending | |
| 3 | pending | |
| 4 | pending | |
| 5 | pending | |
