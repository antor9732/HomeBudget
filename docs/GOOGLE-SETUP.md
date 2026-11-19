# Google Cloud / OAuth setup (one time, ~10 minutes)

Needed only for Google sign-in and Drive sharing. Offline features work without it.

1. Open https://console.cloud.google.com → create project **HomeBudget**.
2. *APIs & Services → Library* → enable **Google Drive API** and **Google Picker API**.
3. *Google Auth Platform* (OAuth consent screen):
   - Branding: app name "HomeBudget", your support email.
   - Audience: **External**, publishing status **Testing**. Add **both test Gmail accounts** as test users.
   - Data access: add scopes `openid`, `.../auth/userinfo.email`, `.../auth/userinfo.profile`,
     `https://www.googleapis.com/auth/drive.file` (all non-sensitive).
4. *Clients → Create client → Android*
   - Package name: `com.webcartisan.homebudget`
   - SHA-1: the SHA-1 of **your own** signing key (`keytool -list -v -keystore <your.jks>`).
     No keystore is published in this repo; a build without one uses your local default debug key.
5. *Clients → Create client → Web application* (name e.g. "HomeBudget ID token"). No redirect URIs.
   Copy its **Client ID** (`…apps.googleusercontent.com`). It is not a secret, but keep it out of git.
6. Put it in `local.properties`: `homebudget.webClientId=<client id>` and rebuild
   (`./gradlew assembleDebug`).

Notes
- While in Testing mode only listed test users can sign in. Because only non-sensitive scopes are used,
  the app can later be switched to *In production* for everyone; showing the app name/logo needs brand
  verification (home page + privacy policy on a domain you own – free GitHub Pages is the plan).

## Current project (configured 16 Nov 2026)
- Account: mahmudul47.webcartisan@gmail.com · project `homebudget-514913` · Testing / External
- APIs: Drive, Picker · scopes: openid, email, profile, drive.file
- Android client: `com.webcartisan.homebudget` + the maintainer's old debug SHA-1 (used by 1.3.1 and earlier)
- Web client ID: `758450253091-1aaie8i1mam9u5o23b4j186nac5iqgg6.apps.googleusercontent.com`
- A release build needs its own signing key; add that key's SHA-1 (and the Play App Signing SHA-1) as
  additional Android clients.

## Production status (19 Nov 2026)
- OAuth app published **In production** (External). Scopes: openid, email, profile, `drive.file` (all non-sensitive), so no verification, no test-user list and no 100-user cap apply. No logo is uploaded (a logo would require brand verification).
- Home page: https://antor9732.github.io/ · Privacy policy: https://antor9732.github.io/privacy.html (GitHub Pages repo `antor9732/antor9732.github.io`; copies in `docs/site/`).
- Authorized domain `antor9732.github.io`, ownership verified in Google Search Console (HTML file `google2bb1cb14462a4396.html` — do not delete it).

## Release signing (v1.3.2, 19 Nov 2026)
- Official APKs from 1.3.2 on are release builds (not debuggable) signed with a private release key kept offline by the maintainer. It is never committed.
- Release key SHA-1 `E7:26:5D:F4:99:D3:5D:E9:1C:7B:B2:85:C8:FE:7E:AF:9F:1C:CB:CE`, SHA-256 `E9:AA:06:D2:8A:07:DD:9D:26:CF:1B:56:57:D3:D7:C5:EF:1C:7B:CD:1C:0D:67:90:87:0F:85:A4:57:A2:E9:D8`; registered as Android client "HomeBudget Android (release key)".
- To build a signed release locally add to `local.properties`: `homebudget.releaseStore`, `homebudget.releaseStorePassword`, `homebudget.releaseKeyAlias`, then `./gradlew assembleRelease`.
