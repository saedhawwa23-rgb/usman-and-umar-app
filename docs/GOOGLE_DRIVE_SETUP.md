# Google Sign-In and Drive setup

## OAuth client mapping

The app's Gradle `applicationId` is `com.usmanandumar.app`. Credential Manager receives the **Web application** OAuth client ID as its `serverClientId`:

`314587321554-7nmt1gk0cotier4dbcrctl1qcv1950u3.apps.googleusercontent.com`

The user also supplied this **Android** OAuth client ID:

`314587321554-car3jm7lgi39qeq8309g679415lk4q.apps.googleusercontent.com`

The two IDs have the same apparent Google Cloud project-number prefix (`314587321554`), which is consistent with them being in the same project. The IDs alone do not prove the client records' types, project ownership, package restriction, or registered SHA-1; this checkout has no authenticated Google Cloud Console access, so those settings have not been independently verified. The Android client ID is not sent as a `serverClientId`: Google matches it using the Android package name and signing certificate.

The current sandbox debug keystore fingerprint is:

`F4:70:B8:D9:CA:1C:A5:A7:62:40:6E:87:16:86:5D:D2:1E:C9:AA:8E`

This is the debug key in this build environment only. Register the SHA-1 of every developer's own debug keystore and the actual release/Play App Signing certificate separately. Do not assume this sandbox fingerprint is the one used by your local Android Studio or production build.

## Google Cloud Console steps still required

These are external project changes and have **not** been performed from this repository:

1. Confirm both client records are in the intended Cloud project. Verify the Android client type/package are `Android` / `com.usmanandumar.app` and its SHA-1 matches the build certificate. Verify the other client is type `Web application` and matches the Web client ID above.
2. Enable **Google Drive API** in that project.
3. Configure the OAuth consent screen (app identity, support contact, audience, and privacy policy). If the app is in testing mode, add the Google accounts that will test it.
4. Add/verify the scopes below. `drive.readonly` is a **restricted** Drive scope; production use may require Google's OAuth verification and supporting disclosures. Do not represent a testing consent screen as public verification.
5. Keep client secrets out of this Android application. No client secret is required or embedded by this implementation.

## Scope selection

The app requests only:

- `https://www.googleapis.com/auth/drive.readonly` — list/search existing files and download/export their content. This is a restricted scope because it can reveal Drive file metadata and contents.
- `https://www.googleapis.com/auth/drive.file` — create and manage files created by, or explicitly opened/shared with, this app. It avoids requesting unrestricted write access.

Drive tokens remain in process memory, are not stored in preferences/database, and are not logged. The Google ID token is used only to identify the selected Credential Manager account in the UI; after Drive authorization the app verifies the actual Drive account via `about.get` and requires its email to match. The app includes a user-initiated account-switch action and `AuthorizationClient.revokeAccess` flow.

## Implemented behavior and current boundaries

The Settings screen now has a Google Drive destination. It implements Google Credential Manager sign-in, separate Drive scope authorization, verified account identity, My Drive folder navigation and filename search, import into the app's private workspace, local-file upload, binary download, and Google Workspace document export to PDF. Transfers report progress and verify upload metadata or downloaded byte counts; 401/403 and service failures receive actionable user-facing messages.

Current deliberately bounded behavior:

- Upload uses Drive multipart upload and accepts files up to 5 MiB; larger/resumable uploads are not yet implemented.
- Upload creates files in **My Drive root**. The app does not upload into arbitrary existing folders.
- Search is applied within the currently opened folder, with up to 100 results returned per request. Pagination beyond that result page is not yet exposed.
- Native Google Workspace files export as PDF. Specialized formats such as Google Vids are not implemented.

## Verification status

Repository unit tests cover Drive query escaping and folder/root filtering. This sandbox has no Android SDK configured, so Gradle compilation and tests could not be run locally (`SDK location not found`). No real OAuth sign-in, consent, Drive API call, upload, download, or export has been tested against the supplied Cloud project yet. The feature must not be described as verified until Cloud Console configuration is complete and the flows are exercised on a signed Android build.

## References

- [Credential Manager — Sign in with Google](https://developer.android.com/identity/sign-in/credential-manager-siwg)
- [Android authorization guidance](https://developer.android.com/identity/authorization)
- [Google AuthorizationClient reference](https://developers.google.com/android/reference/com/google/android/gms/auth/api/identity/AuthorizationClient)
- [Choose Drive API scopes](https://developers.google.com/workspace/drive/api/guides/api-specific-auth)
- [Search for Drive files](https://developers.google.com/workspace/drive/api/guides/search-files)
- [Download and export files](https://developers.google.com/workspace/drive/api/guides/manage-downloads)
- [Upload files](https://developers.google.com/workspace/drive/api/guides/manage-uploads)
