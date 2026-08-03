# Approved plan: Binary asset translations

Status: Architect and Critic approved
Mode: RALPLAN-DR deliberate
Context: `docs/plan/binary-asset-translations-20260802T145742Z.md`

## Outcome

Add a project-level **Assets** area, separate from translation keys, where an editor uploads one source binary and translators upload one localized binary for each permitted project language. PSD, audio, video, and unknown formats are stored opaquely and round-trip byte-for-byte.

**MVP product boundary:** the feature is an **in-platform asset localization workflow** (create, translate, status, authenticated download, project backup/restore). It is **not** a runtime content-delivery system. Apps, game builds, and CDNs do not pull localized binaries from Tolgee in MVP; operators download or use project export/import. Runtime/CDN publish is an explicit follow-up.

## Requirements and MVP boundary

### Included

- One project-owned asset with `name`, optional `description`, stored source language, and one current source file.
- Zero or one current localized file for each active non-source project language.
- Derived `MISSING`, `CURRENT`, and `OUTDATED` statuses.
- Source revision increments on source replacement; localized files record the source revision they target.
- Searchable/paginated Assets list and an asset detail page with source plus language rows.
- Upload, replace, authenticated download, and delete.
- Existing project and per-language permissions, activity history, project/language deletion, and exact project export/import.
- Bounded-memory upload/download/storage/export/import paths suitable for realistically large media.
- Project **API key / PAT access** on the same endpoints (`@AllowApiAccess`), using the same scopes as the UI, so CI and automation can upload and download without inventing a second auth path.

### Deliberate defaults

- The source language defaults to the project's base language but is persisted on the asset.
- Missing language slots are derived from active project languages; no empty translation rows are stored.
- Original filename, reported MIME type, byte count, SHA-256, uploader, and timestamps are metadata. Storage keys are generated UUID paths.
- A target file may use a different filename, extension, or MIME type from the source.
- Existing `tolgee.max-upload-file-size` remains the single configurable upload ceiling; do not add a second size setting (`TolgeeProperties.kt:85-91`, `WebConfiguration.kt:82`). The **upstream default (50 MiB)** is too small for realistic PSD/video — production must raise it (see Deployment defaults). Until that knob and the reverse-proxy body limit are raised, large-media claims are invalid.
- Assets are **project-global** and do not follow Tolgee branches. UI empty/help text must state this so branch-aware teams do not assume branch isolation.
- Asset `name` is required and unique per project (case-sensitive unique index on `(project_id, name)`). Rename must reject collisions with a clear validation error.
- Permission reuse of `keys.*` / `translations.*` is **temporary MVP debt** (see Permissions). Dedicated `assets.*` scopes are a follow-up when the feature leaves internal-only trust or needs separation from key management.

### Excluded from MVP

- Browser preview, byte-range playback, transcoding, PSD inspection, text extraction, machine translation, and content conversion.
- Multiple files per language, file history, approvals, comments, tasks, notifications, translator bulk ZIP workflows, public/CDN publish URLs, and branch-specific assets.
- **Runtime / app content delivery:** no Tolgee Content Delivery publish of binaries, no public signed CDN URLs for end-user apps, no SDK “fetch localized asset” API beyond the authenticated project APIs above. Follow-up after the CMS workflow ships.
- Dedicated `assets.view` / `assets.edit` (or similar) scopes — deferred; MVP reuses key/translation scopes.
- Antivirus scanning for the current trusted internal deployment. It becomes a release gate before untrusted/public upload access.
- Per-project byte quotas and billing.
- Resumable / chunked upload protocols (tus, multipart S3 complete from browser). MVP is a single HTTP multipart request within configured size and timeout limits.

## RALPLAN-DR

### Principles

1. Model assets as first-class project content, not disguised translation keys.
2. Reuse Tolgee's existing permission and language-access semantics.
3. Treat files as untrusted opaque bytes and never expose storage paths publicly.
4. Keep memory use independent of file size and make stored content recoverable.
5. Minimize fork divergence by extending existing storage, HATEOAS, activity, and export/import patterns.

### Decision drivers

1. The separate Assets workflow and one-file-per-language progress model.
2. Safe support for PSD/audio/video sizes without JVM heap amplification.
3. Correct language-scoped access and backup/restore on the current self-hosted deployment.

### Options

#### A. First-class assets with streaming storage and project transfer — recommended

- **Approach:** Add asset and localized-file entities, project APIs/UI, and small streaming extensions to Tolgee's existing storage and project export/import facilities.
- **Pros:** Meets the requested UX exactly; retains existing auth/storage deployment choices; supports large opaque files; files remain part of project backup/restore.
- **Cons:** Cross-cuts backend, storage providers, export/import, frontend, and E2E tests; creates a maintained fork surface.

#### B. First-class assets capped at the existing 50 MiB buffered storage path

- **Approach:** Add the same domain/API/UI but reuse current `ByteArray` storage and project-transfer primitives unchanged.
- **Pros:** Smallest initial patch; reuses every existing storage implementation directly.
- **Cons:** A few concurrent files can exhaust heap; 50 MiB is unsuitable for common PSD/video assets; project export/import compounds memory use. Viable only as a prototype, not the requested production feature.

#### C. External digital-asset manager with Tolgee metadata links

- **Approach:** Tolgee stores project/language/status metadata while S3/DAM owns upload, download, versions, previews, and lifecycle.
- **Pros:** Mature large-object handling and optional previews/versioning; smallest blob burden on Tolgee.
- **Cons:** Adds another product, identity/authorization boundary, backup contract, and operator dependency; no longer self-contained in a Tolgee project.

### Decision

Choose **A**. Option B invalidates the large-media claim and Option C expands operations beyond the requested fork. Keep the MVP UI as metadata plus download; streaming here means bounded-memory transfer, not media playback.

## Product and domain design

### Tables

Add two additive tables through the existing Liquibase generation flow (`backend/data/src/main/resources/db/changelog/schema.xml:5778`, `build.gradle:119`).

1. `binary_asset`, following the project-child/audit pattern in `model/contentDelivery/ContentDeliveryConfig.kt:31`:
   - `id`, `project_id`, `name`, nullable `description`
   - `source_language_id`, `source_revision` starting at 1
   - source `storage_key`, `original_filename`, `content_type`, `byte_size`, `sha256`
   - nullable uploader reference plus `created_at`, `updated_at`
   - no long upload transaction; repository `PESSIMISTIC_WRITE` lock on the asset row only for the short metadata swap
   - unique constraint on `(project_id, name)` and indexes on `project_id`, searchable name, and source language

2. `binary_asset_translation`, following the per-language uniqueness pattern in `model/translation/Translation.kt:44-55`:
   - `id`, `asset_id`, `language_id`
   - `source_revision`, `storage_key`, `original_filename`, `content_type`, `byte_size`, `sha256`
   - nullable uploader reference plus `created_at`, `updated_at`
   - unique `(asset_id, language_id)` and indexes for asset/language cleanup

Do not persist missing rows. For a visible target language:

- no row -> `MISSING`
- row `source_revision == asset.source_revision` -> `CURRENT`
- row `source_revision < asset.source_revision` -> `OUTDATED`

List progress is `current / visible target language count`, with a separate outdated count. Project admins see every target language; restricted users receive only language rows permitted by their existing view scope.

### File lifecycle

- Use immutable generated keys such as `binary-assets/{projectId}/{assetUuid}/{blobUuid}`; never include a user filename (`LocalFileStorage.kt:62-70`).
- Stream to a new key while counting bytes and computing SHA-256. Reject empty data, the configured size ceiling, control characters, path components, and overlong filenames.
- Only after successful storage, perform a short locked transaction to insert or swap metadata. A localized upload must carry the `translatedAgainstSourceRevision` shown when the translator opened/downloaded the source. Persist that exact revision after validating `1 <= translatedAgainstSourceRevision <= current source revision`; never infer it from commit time. If the source changed during upload, the accepted file is immediately and correctly `OUTDATED` rather than mislabeled `CURRENT`.
- If storage or DB update fails, leave the prior referenced file untouched and delete the new key best-effort.
- After commit, delete the replaced key. Log and count cleanup failures; the MVP accepts a documented crash-only orphan window but never a metadata row pointing to a partially written replacement.
- Local storage writes through a temporary file and atomic move when supported. S3/Azure uploads do not expose an object until the provider completes the put.
- Asset, target-language, and project deletion remove active metadata and blobs. Add a synchronous source-asset preflight at the start of `LanguageService.deleteLanguage` before the existing soft delete/permission removal (`LanguageService.kt:98-120`); a referenced source language fails with a specific error so soft-delete never starts.
- Target-language cleanup must run in the **hard-delete pipeline**, not only at soft-delete time: wire removal of `binary_asset_translation` rows into `LanguageHardDeleter` (and any related delete path), then delete those blobs only after the DB transaction commits. Soft-delete already hides the language; hard-delete owns physical cleanup. Injected blob-delete failure after commit leaves DB consistent and emits cleanup telemetry.
- Project cleanup remains wired through `ProjectHardDeletingService.kt:66` so every asset and localized blob for the project is removed with other project-owned content.

## Streaming foundation

Extend `backend/data/src/main/kotlin/io/tolgee/component/fileStorage/FileStorage.kt:5` with input/output operations that have compatibility defaults for existing anonymous/custom implementations, including `ContentDeliveryFileStorageProvider.kt:92-137`. Add a capability flag that defaults to `false`; Local/S3/Azure override it to `true`. Every binary-asset upload/download/export/import path checks the flag and fails closed with a configuration error when it is false. `ByteArray` defaults remain legacy-only and are never an allowed binary-asset data path. Implement true bounded-memory primitives in:

- `LocalFileStorage.kt:16`
- `S3FileStorage.kt:21`
- `AzureBlobFileStorage.kt`
- `backend/development/src/main/kotlin/io/tolgee/util/InMemoryFileStorage.kt:22` only as a small-file legacy compatibility test double; binary-asset controller tests use a temp-directory LocalFileStorage fixture instead

The binary controller must not reuse `ImageStorageController.kt:78-128`. Use authenticated project endpoints and close storage streams inside the response callback. No range support is required in MVP.

### Upload path contract (multipart + proxy)

Streaming storage alone is insufficient if the servlet layer buffers the whole body in heap. Binary-asset upload paths must satisfy all of the following:

1. Controllers and services read only `MultipartFile.inputStream` (or equivalent). **Forbidden** on binary-asset paths: `bytes`, `resource.contentAsByteArray`, `readBytes()`, `readAllBytes()`, `toByteArray()` of the full payload.
2. Servlet multipart is configured so large parts land on **disk** (low file-size threshold / explicit location under a dedicated temp directory with enough free space), not as in-memory multipart parts.
3. Size enforcement happens while streaming (count bytes, abort and delete partial key when exceeding `tolgee.max-upload-file-size`); do not rely only on a post-buffer check.
4. App and reverse-proxy **request timeouts** and **body size limits** are raised together with the app ceiling. A raised app limit with an unchanged Caddy/nginx body limit is a failed rollout.
5. The constrained-heap gate and production smoke must exercise a real multipart HTTP upload, not only direct `FileStorage` calls.

Document the chosen temp directory and free-space preflight in the deployment checklist.

Extend project export/import from `ByteArray` blobs to bounded streams/temp-backed entries while keeping adapters for screenshots and avatars:

- `service/projectExportImport/blob/BlobHandler.kt:5`
- project ZIP writer/exporter alongside `ScreenshotBlobHandler.kt:11`
- `service/projectExportImport/TransferZipReader.kt:28`
- `ProjectExportImportImporter.kt:165-218`

The export ZIP must include source and localized blobs and their entity metadata, but live storage keys are not portable transfer identities. Export a logical blob reference plus size/SHA-256. Import stages every blob to a fresh generated key under the destination project, validates size/SHA-256, then maps the logical reference to that key in the metadata transaction. On failure, roll back metadata and delete staged keys while leaving pre-import referenced blobs intact; after a successful mirror-import commit, delete superseded old keys.

## API contract

Create a project-scoped controller following `ContentDeliveryConfigController.kt:36-106` and multipart/error conventions from `KeyScreenshotController.kt:62`:

- `GET /v2/projects/{projectId}/binary-assets` — paged search/list with progress.
- `POST /v2/projects/{projectId}/binary-assets` — create metadata plus source multipart.
- `GET /v2/projects/{projectId}/binary-assets/{assetId}` — source metadata and visible language statuses.
- `PUT /v2/projects/{projectId}/binary-assets/{assetId}` — rename/edit description.
- `PUT /v2/projects/{projectId}/binary-assets/{assetId}/source` — replace source and increment revision.
- `POST /v2/projects/{projectId}/binary-assets/{assetId}/source/download-ticket` — after normal Bearer/project authorization, return a short-lived URL ticket for the current source blob.
- `GET /v2/binary-assets/download?token=...` — stream the exact ticket-bound source or localized blob after ticket validation and a fresh current-permission/current-blob check; there is no user-supplied blob identifier.
- `PUT /v2/projects/{projectId}/binary-assets/{assetId}/translations/{languageId}` — upsert localized file with required `translatedAgainstSourceRevision` multipart metadata.
- `POST /v2/projects/{projectId}/binary-assets/{assetId}/translations/{languageId}/download-ticket` — return a short-lived URL ticket after language-view authorization.
- `DELETE /v2/projects/{projectId}/binary-assets/{assetId}/translations/{languageId}` — delete localized file.
- `DELETE /v2/projects/{projectId}/binary-assets/{assetId}` — delete asset and all files.

Every ID lookup includes `projectId` in the repository query, following `ContentDeliveryConfigRepository.kt:41-51`. Cross-project or unauthorized content returns the same non-disclosing response used by existing project APIs.

Annotate project-scoped binary-asset endpoints with `@AllowApiAccess` (same pattern as `KeyScreenshotController`), so project API keys and PATs work for automation. Ticket issuance still requires a valid project-authenticated principal; the download ticket URL remains short-lived and is not a long-lived API key substitute.

Reuse the ticket primitives in `security/authentication/JwtService.kt:161-183,248-272` and the screenshot URL pattern in `hateoas/screenshot/ScreenshotModelAssembler.kt:83-98`, with a dedicated ticket type bound to user, project, asset, source-or-language, and the immutable current blob identity. The streaming endpoint rechecks current project/language permission and confirms that the ticket's blob is still the asset's current referenced blob, so revoked access and replaced files fail non-disclosingly. The ticket is short-lived and is the only credential allowed in the URL; never place the long-lived session JWT there.

Downloads set the stored MIME type or `application/octet-stream`, a sanitized RFC 5987 attachment filename, exact `Content-Length`, `X-Content-Type-Options: nosniff`, and private/no-store caching. They never redirect to a public storage URL.

## Permissions

Reuse existing scopes from `model/enums/Scope.kt:12-32` for MVP only:

| Operation | Required access |
| --- | --- |
| List/detail/source download | `keys.view` |
| Create asset/source | `keys.create` |
| Rename/replace source | `keys.edit` |
| Delete asset | `keys.delete` |
| Download localized file | `translations.view` plus `checkViewPermitted(languageId)` |
| Upload/replace/delete localized file | `translations.edit` plus `checkTranslatePermitted(languageId)` |

Use `SecurityService.kt:232-343` and `ComputedPermissionDto.kt:44-68`; do **not** introduce new scopes in MVP. A translator restricted to Slovenian can download the shared source and manage only the Slovenian localized file. Enforce the same rules in API services and hide unavailable actions/language rows in the webapp via `webapp/src/fixtures/permissions.ts:12-51`.

### Scope-reuse debt (explicit)

This mapping is intentional temporary debt so language-restricted translators work without a permission-schema migration:

- Anyone who can manage keys can manage asset **source** metadata/files; UI and docs must not pretend Assets has independent ACLs.
- Project API keys that already include key scopes gain asset source access without a separate grant.
- You cannot invite a member who only manages assets without key rights.

**Follow-up (not MVP):** add dedicated `assets.view` / `assets.edit` (and optional language-scoped translate) scopes, migrate role templates, and stop expanding `keys.*` semantics. Track this as fork debt; do not invent half-migrated scopes mid-implementation.

## Frontend

Add `webapp/src/views/projects/assets/` with:

- `AssetsView` — standard project shell, search, pagination, create action, source filename, current/target progress, outdated count, and updated time.
- `AssetView` — source card plus non-source language rows showing status, filename, size, uploader/time, and permitted actions.
- Small upload/edit/confirmation components only where reuse is not possible.

Wire the separate section through:

- routes/params in `webapp/src/constants/links.tsx:292-426`
- list/detail routes in `webapp/src/views/projects/ProjectRouter.tsx:39-82`
- a menu item after Translations in `webapp/src/views/projects/projectMenu/ProjectMenu.tsx:57-66`, visible with `keys.view`

Reuse `BaseProjectView.tsx:17`, `PaginatedHateoasList.tsx:16`, and the list pattern in `developer/contentDelivery/CdList.tsx:20`. Use `ProjectLanguagesProvider.tsx:13`, the unrestricted input pattern in `import/component/ImportFileInput.tsx:118`, and typed queries/mutations from `useQueryApi.ts:30`. MVP uploads expose loading, success, and error states only; byte-progress is out of scope because the existing fetch path does not provide it. Do **not** use `downloadResponseAsFile.ts:1`, whose `response.blob()` buffers the file. First request a short-lived download ticket through the normal Bearer-authenticated API, then navigate a normal anchor to that ticket URL so the response streams outside JavaScript memory.

UI copy requirements:

- Empty state and/or help text must state that **assets are project-global and not branch-scoped**.
- Create/rename surfaces unique-name validation errors from the API.
- Do not imply public CDN or in-app preview delivery in MVP copy.

Do not extend the type-restricted `FileDropzone.tsx:49` just for wildcard support unless reuse is smaller than a local file input. Add literal `data-cy` attributes and regenerate their typings through `webapp/dataCy.core.mjs:47`.

## Activity, OpenAPI, and project lifecycle

- Add create/update/delete/source-replace/translation-upsert/translation-delete activity types beside existing entries in `activity/data/ActivityType.kt:23` and annotate entities/requests.
- Register frontend activity labels/entities in `webapp/src/component/activity/configuration.tsx:122`, `activityEntities.tsx:203`, and `types.tsx:45`.
- Regenerate both API schema files through `webapp/scripts/generate-schemas.js:16`; verify group inclusion in `backend/app/src/test/kotlin/io/tolgee/openapi/OpenApiTest.kt:11`.
- Register both entities in `ProjectExportImportPolicyRegistry.kt:92`, `ProjectScopedCollectorQueries.kt:27`, clear ordering in `ProjectContentClearer.kt:72`, blob export/import, and restore handling. The guard at `ee/backend/tests/src/test/kotlin/io/tolgee/ee/projectExportImport/ProjectExportImportPolicyGuardTest.kt:40` must pass.
- Add project/language fixture builders in `ProjectBuilder.kt:68` and persistence in `TestDataService.kt:370`.

## Implementation sequence

### 1. Prove bounded-memory storage and transfer primitives

- Add streaming methods with compatibility defaults to `FileStorage`; implement and contract-test true streaming in Local/S3/Azure, with InMemory limited to legacy small-file compatibility and excluded from binary-asset paths.
- Add atomic local write behavior, closed-stream/error tests, byte-limit enforcement, and SHA-256/count helpers using JDK streams.
- Convert project blob ZIP handling to streams/temp-backed resources without changing existing screenshot/avatar behavior.
- Configure multipart disk spill and verify a controller-level multipart upload path never materializes the full file as `ByteArray`.
- Stop if the local, S3, Azure, in-memory, and existing project export/import tests cannot preserve current behavior, or if the constrained-heap multipart path OOMs.

### 2. Add the asset aggregate and lifecycle

- Add the two entities, repositories, DTOs, service, generated Liquibase changes, unique constraints, and project/language cleanup.
- Implement derived status/progress, generated paths, source revision changes, caller-declared target revision, a repository pessimistic asset-row lock for short mutation swaps, and compensating cleanup.
- Register project export/import classification, collection, clear, blob, and restore paths in the same slice so no project content is unbacked.

### 3. Expose authenticated project APIs

- Add HATEOAS models/assemblers and paginated CRUD/download endpoints.
- Apply the permission matrix and project-filtered lookup to every path.
- Add activity events, secure download headers, validation, OpenAPI group coverage, and fail-closed streaming-capability checks.
- Regenerate TypeScript API schemas only after controller tests pass.

### 4. Build the separate Assets UI

- Add menu/routes, list/create, detail/source, and language rows.
- Reuse typed API hooks, project language provider, generic file input, download helper, and existing permission utilities.
- Add activity renderers, upload loading/error recovery, empty/loading states, and generated Cypress selectors.

### 5. Verify and roll out additively

- Run targeted backend/storage/export/import tests, frontend unit/type/lint checks, and the Assets Cypress flow. In a forked JVM capped at `-Xmx128m`, run a **multipart HTTP upload**, ticket download, and project export/import of a generated 256 MiB file and verify SHA-256 without OOM.
- Build the fork image in the existing `blubblub-image.yml` workflow and publish a new immutable version tag; record and deploy its resolved digest, never a mutable tag.
- Before production: complete the Deployment defaults checklist (upload cap, proxy body limit, timeouts, free disk, multipart temp path), make a cold DB/blob backup, and test export/import on a disposable project.
- Deploy the additive migration, smoke-test a small file and a representative large file over the public hostname, verify language restrictions and checksums, then monitor heap, disk, 4xx/5xx, and cleanup failures. Stop rollout on any permission-matrix, checksum, project-import rollback, proxy 413/timeout, or constrained-heap failure.
- Roll back to the previous image if needed. The old version ignores the additive tables/blobs; retain them for forward recovery rather than dropping them.

## Deployment defaults (localize.blubtools.com)

These are **release gates**, not optional notes. Without them, PSD/video support is false.

| Knob | Recommendation for Blub Blub | Notes |
| --- | --- | --- |
| `TOLGEE_MAX_UPLOAD_FILE_SIZE` / `tolgee.max-upload-file-size` | **524288** (512 MiB) initially; raise to 1048576 (1 GiB) only after disk/proxy review | Property unit is **kilobytes** (upstream default `51200` = 50 MiB) |
| Reverse proxy body size (Caddy / whatever fronts localize) | ≥ app ceiling (e.g. `request_body` max 512 MiB) | Mismatch yields opaque 413s |
| Proxy / load-balancer idle timeouts | ≥ 10 minutes for large uploads on slow links | Tune with observed transfer time |
| Multipart temp / local data disk | Free space ≥ 3× max upload (temp part + new blob + export staging) | Preflight before rollout |
| Local data path | Existing Tolgee FS volume | Confirm volume size after raising cap |
| Antivirus | Not required for trusted internal users | Hard gate before external translators |

Rollout checklist must record the actual values applied, free disk before/after smoke, and a successful multipart upload of a file near the chosen ceiling (or a representative production max).

## Acceptance criteria

1. A project editor creates an asset with PSD, WAV/MP3, MP4, or unknown content and it appears only in **Assets**, never among keys.
2. An unrestricted admin sees every active non-source language exactly once; adding a language yields `MISSING` without inserting an empty row.
3. Replacing the source increments its revision and changes existing `CURRENT` files to `OUTDATED` without deleting or altering their bytes.
4. Uploading/replacing one localized file records the declared source revision. It is `CURRENT` only when that revision still equals the asset's current revision; a source change during upload produces an accurately `OUTDATED` file.
5. A language-restricted translator can download the source and view/download/upload/delete only permitted target languages; direct API attempts for another language fail. Download tickets expire, are bound to one current blob, and fail after access revocation or file replacement.
6. Cross-project IDs and users without project access cannot disclose metadata or bytes.
7. Every accepted file downloads byte-for-byte with the stored byte size and SHA-256; filename and MIME metadata survive.
8. Empty, over-limit, malformed-filename, and invalid-language uploads create neither visible metadata nor a referenced partial blob.
9. Simulated storage/DB failures during create or replacement preserve the previously referenced file. Cleanup failure is logged and counted.
10. Concurrent source/target replacements leave one row per asset/language, one winning referenced blob, and a status derived from the target's declared source revision rather than upload commit order.
11. Target-language deletion removes its localized blob; source-language deletion is blocked; asset/project deletion removes all active owned blobs.
12. Project export -> wipe -> import restores asset metadata, language links, revisions, statuses, and exact source/localized SHA-256 values using fresh destination-project storage keys; failed import preserves the previous project/blob state and removes staged keys.
13. A generated large-file integration test and production smoke test demonstrate bounded-memory transfer via **multipart HTTP upload** plus ticket download; binary endpoints contain no whole-file `readBytes`/`readAllBytes`/`bytes` path.
14. Project API keys with the mapped scopes can create/download assets; keys without the required scopes cannot.
15. Creating two assets with the same name in one project fails validation; names remain unique per project.
16. UI states that assets are project-global (not branch-scoped); assets never appear under Translations/keys.
17. Existing key translation, image, permission, activity, project export/import, and navigation suites remain green.

## Expanded test plan

### Unit

- Status/progress derivation for missing/current/outdated and restricted language sets.
- Filename sanitation, empty/size checks, content-type fallback, generated storage paths, count/SHA-256 while streaming.
- Source revision and declared translation revision transitions, including a source change during an in-flight upload.
- FileStorage streaming adapters, atomic local replacement, stream closure, and partial-write cleanup.

### Integration/controller

- Follow `KeyScreenshotControllerTest.kt:41-216` for multipart/blob/header/delete assertions.
- CRUD, pagination/search, exact bytes/headers, each permission matrix row, cross-project lookup, invalid language, source-language delete conflict, and download-ticket expiry/scope/revocation/replaced-blob cases.
- Local, S3, and Azure streaming contract tests; a generated large stream must not require a same-sized heap array. InMemory and anonymous/default implementations get small-file compatibility tests only.
- DB/storage failure injection for create/replace/delete and concurrent upserts, including a target uploaded against revision N while the source advances to N+1.
- Activity records and OpenAPI schema inclusion.
- Project export policy guard at `ee/backend/tests/src/test/kotlin/io/tolgee/ee/projectExportImport/ProjectExportImportPolicyGuardTest.kt:40`, logical blob references, fresh destination keys, corrupt/missing blob rejection, staged-key cleanup, old-key preservation on rollback, and export/wipe/import checksum round trip using `ProjectExportImportControllerTest.kt:93-228`.

### Frontend

- Unit-test status/progress mapping and filename/size presentation.
- Typecheck generated multipart/ticket calls and permission-gated controls; verify the UI navigates to the ticket URL without building a JavaScript `Blob`.
- Cypress: navigate through the separate menu; upload English source; upload/replace/download/delete one target; observe missing/current/outdated; reload; verify it never appears under keys; repeat with a one-language translator using a dedicated E2E data controller (`e2e/cypress/common/apiCalls/testData/testData.ts:18`).

### Observability/operational

- Counters for upload/download success/failure and post-commit cleanup failures; byte counts and latency without logging filenames or content.
- Structured logs include project/asset/language IDs and failure phase.
- Production smoke compares SHA-256 for small PSD-like data and a representative large generated file while observing JVM heap and disk growth.
- Alert on any cleanup failure, sustained binary endpoint 5xx, and low filesystem capacity for local storage.

## Pre-mortem

1. **Large uploads exhaust heap or disk.** Cause: a hidden `ByteArray` conversion (including `MultipartFile.bytes`), in-memory multipart parts, or a raised app limit without proxy/disk capacity. Mitigation: upload-path contract (InputStream + disk-backed multipart), generated large-file **HTTP** test under `-Xmx128m`, configured cap, proxy body/timeout alignment, disk preflight, and heap/disk monitoring. Stop rollout on proportional heap growth, proxy 413/timeout, or low-space alert.
2. **A translator downloads or replaces another language/project's file.** Cause: asset-ID-only lookup or UI-only permission checks. Mitigation: project-and-ID queries, `SecurityService` language checks on every target endpoint, non-disclosing errors, and a complete direct-API permission matrix.
3. **Metadata and blobs diverge or backups omit content.** Cause: process death between blob and DB operations or incomplete project export registration. Mitigation: immutable new keys, old-file preservation, compensating cleanup plus metrics, policy guard, exact checksum round trip, cold production backup, and additive rollback.

## Risks and mitigations

- **Fork maintenance:** keep new code in a bounded `binaryAsset` domain and additive storage interfaces; avoid changing key/translation semantics.
- **Rare orphaned blobs after abrupt process death:** no user-visible corruption; expose cleanup failures and add prefix reconciliation only if metrics/storage growth justify it.
- **No antivirus:** acceptable only for the trusted internal deployment with attachment-only downloads; add scanning/quarantine before widening uploader trust.
- **No range playback/preview:** download remains correct for every format; add range/presigned delivery only when preview is requested.
- **No runtime CDN delivery in MVP:** product is a CMS workflow; apps must not be told to fetch production assets from Tolgee until a follow-up publish path exists.
- **Scope reuse debt:** key managers inherit asset source control; plan a dedicated `assets.*` scope migration before opening the deployment to untrusted orgs.
- **Global upload cap:** document the selected deployment value and retain endpoint-side validation; do not silently raise it during schema rollout. Align Caddy/proxy limits in the same change.

## ADR

- **Decision:** Build first-class project binary assets with per-language localized rows, existing Tolgee permissions, and streaming extensions to current storage and project transfer.
- **Drivers:** separate workflow, realistic large-file safety, language-scoped access, self-contained project backup, minimal external operations.
- **Alternatives considered:** buffered 50 MiB MVP; external DAM/object workflow.
- **Why chosen:** it is the only option that meets the requested UX and large opaque-file requirement without adding a second product or accepting a known heap ceiling.
- **Consequences:** broader initial implementation; additive tables; maintained streaming/storage/export code; no previews/history in MVP.
- **Follow-ups:** runtime/CDN or bulk-export publish path for apps; dedicated `assets.*` scopes; antivirus before untrusted uploads; orphan reconciliation if metrics show need; range/preview, quotas, history, branches, bulk workflows only when demanded.

## Available agent types and execution staffing

Relevant installed roles are `explore`, `architect`, `executor`, `test-engineer`, `security-scanning__security-auditor`, `code-reviewer`, `verifier`, and `git-master`.

Recommended **Team + Ultragoal** delivery:

- Ultragoal leader owns the durable goal ledger, phase gates, integration decisions, and production stop/go evidence.
- `executor` lane 1 (ultra): streaming storage and project transfer; owns `FileStorage`, Local/S3/Azure implementations, ZIP/blob transfer, and their focused tests.
- `executor` lane 2 (ultra): asset domain/API/security/lifecycle; starts against lane 1's agreed interface and owns migrations, services, controllers, permissions, tickets, deletion, and activity.
- `executor` lane 3 (high or current inherited ultra): webapp Assets section, typed API integration, permission gating, and Cypress selectors; may build the shell early but lands API calls after schema generation.
- `test-engineer` lane 4 (ultra): independent permission matrix, concurrency/failure, constrained-heap, project round-trip, and Cypress coverage.
- `security-scanning__security-auditor` (ultra) reviews upload validation, ticket scoping/revocation, attachment headers, project/language isolation, and malicious filenames before rollout.
- `code-reviewer` then `verifier` (ultra) review the integrated diff and independently reproduce every release gate. `git-master` prepares small commits/PR history after verification.

Suggested launch from an OMX CLI/tmux session:

```text
$ultragoal "Implement docs/plan/prd-binary-asset-translations.md with docs/plan/test-spec-binary-asset-translations.md as the evaluator contract"
$team 4 "Implement the approved binary-asset plan in staged storage, backend, frontend, and test lanes; return checkpoint evidence to Ultragoal"
```

Use `$ralph` only if a single-owner sequential verification/fix loop is explicitly preferred; it is not the default for this multi-lane feature.

## Team verification path

Before Team shuts down, it must return fresh evidence for: generated Liquibase schema, Local/S3/Azure streaming contracts, the `-Xmx128m` 256 MiB test, API permission/ticket matrix, concurrent revision behavior, project export/import rollback and SHA round trip, OpenAPI/typecheck, Assets Cypress flow, lint/format/build, and a clean integrated review. Ultragoal checkpoints those artifacts, then owns immutable image build/digest, production backup, smoke tests, monitoring, and final stop/go. An independent `verifier` must reproduce the release gates before completion.

## Goal-mode follow-up suggestions

- `$ultragoal` is the default implementation follow-up; pair it with `$team` because storage, backend, frontend, and tests have useful staged parallelism.
- `$performance-goal` is appropriate only for a later measurable throughput/latency or memory optimization pass.
- `$autoresearch-goal` is not appropriate: the chosen architecture is already grounded; the remaining work is implementation and verification.

## Consensus changelog

- Added caller-declared target revisions so an in-flight localized upload cannot be mislabeled current.
- Made project import portable and rollback-safe with logical blob references, fresh destination keys, and staged cleanup.
- Added synchronous source-language delete preflight and one pessimistic asset-row lock strategy.
- Made binary paths fail closed unless the provider truly streams; constrained InMemory to legacy small-file tests.
- Replaced JavaScript blob downloads with scoped, short-lived Tolgee tickets and current-permission/current-blob rechecks.
- Removed unimplemented upload byte-progress and added measurable heap, permission, checksum, import-rollback, immutable-image, and rollout stop gates.
- Clarified MVP as in-platform CMS workflow only; runtime/CDN app delivery is a follow-up.
- Specified multipart upload contract (InputStream-only, disk-backed parts, proxy/timeout alignment) so streaming storage cannot be undermined by servlet buffering.
- Named `LanguageHardDeleter` as the target-language blob cleanup path after soft-delete.
- Documented `keys.*`/`translations.*` scope reuse as temporary debt; deferred dedicated `assets.*` scopes.
- Required `@AllowApiAccess` for project API key automation.
- Required unique asset name per project and UI copy that assets are project-global (not branch-scoped).
- Added Blub Blub deployment defaults (512 MiB upload ceiling recommendation) as production release gates.
