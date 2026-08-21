# Lumi ZIP Update Runtime v2 — v3.3 / code 236

Lumi's normal update path is a user-selected ZIP imported from **Lumi Update Center**.
APK Factory is not required for normal Lumi updates.

## Package layout

```
lumi-update.json
lumi-update.sig          # optional for locally selected packages
payload/...
```

Each payload entry is SHA-256 checked before it is written. A package may also carry a manifest signature made with Lumi's app signing key.

## Manifest example

```json
{
  "formatVersion": 1,
  "updateId": "lumi-feature-001",
  "name": "Lumi Feature Update",
  "version": "1",
  "type": "content",
  "minAppVersionCode": 236,
  "maxAppVersionCode": 999999,
  "releaseNotes": "Adds a new Lumi skill and UI definition.",
  "preferences": {
    "runtime_module_epoch": 1
  },
  "files": [
    {
      "path": "payload/skills/example.json",
      "sha256": "<sha256>",
      "target": "skills/example.json"
    },
    {
      "path": "payload/ui/example.json",
      "sha256": "<sha256>",
      "target": "ui/example.json"
    }
  ]
}
```

## ZIP-update targets

- `avatar/home`, `avatar/public`, `avatar/work`, `avatar/travel`, `avatar/lockdown`, `avatar/private`, `avatar/mobius`, `avatar/preview`
- `asset/<relative path>`
- `config/<relative path>`
- `skills/<relative path>`
- `prompts/<relative path>`
- `ui/<relative path>`
- `voice/<relative path>`
- `home/<relative path>`
- `models/<relative path>`
- `migrations/<relative path>`
- `scripts/<relative path>`

All file destinations remain inside Lumi's private app storage. Absolute paths and path traversal are rejected.

## Transaction behavior

1. Import ZIP.
2. Read and validate `lumi-update.json`.
3. Verify optional manifest signature.
4. Verify every declared SHA-256.
5. Check installed Lumi core compatibility.
6. Create a rollback point for every setting/file that will change.
7. Apply the content transaction.
8. MainActivity runs Lumi's core self-test.
9. The Update Center exposes **Roll back last ZIP update** for the most recent successful content package.

If applying the content transaction itself fails, files and preferences are restored immediately.

## APK/core updates

A ZIP may still contain a newer signed Lumi APK for Android-level changes, but this is the exception rather than the normal path. Android requires its normal installer approval. The APK must use Lumi's package name, Lumi's signing certificate, and a newer versionCode.

## Security boundary

ZIP updates do not gain unrestricted access to Android. The runtime only writes to approved preference keys and approved app-private module directories. Android permissions, manifest declarations, native libraries, services, and compiled platform plumbing still require a core APK update.
