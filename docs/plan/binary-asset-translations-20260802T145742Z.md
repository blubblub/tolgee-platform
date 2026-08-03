# Context: Binary asset translations

## Task statement

Plan a first-class, project-scoped Tolgee feature where an editor uploads one source binary asset (including opaque formats such as PSD, audio, or video) and translators upload one localized binary file for each project language. Assets must live in a separate **Assets** section, not among translation keys.

## Desired outcome

- A consensus implementation plan for the `blubblub/tolgee-platform` fork.
- A deliberately small MVP with explicit lifecycle, permission, storage, backup, UI, test, and rollout contracts.
- No source implementation during this planning workflow.

## Product defaults

- An asset belongs directly to a project and has a stable name, optional description, stored source language, and one current source file.
- Each active non-source project language has zero or one localized file.
- Derived statuses are `MISSING`, `CURRENT`, and `OUTDATED`; replacing the source increments a source revision and leaves existing localized files downloadable but outdated.
- Arbitrary non-empty files are stored opaquely. Filenames are metadata only; storage keys are generated.
- MVP has upload/download/delete and metadata only. Preview, transcoding, extraction, machine translation, review states, history, branches, comments, tasks, bulk ZIP, and public URLs are out of scope.
- Existing Tolgee key/translation scopes and language restrictions should be reused unless review finds a concrete blocker.

## Repository evidence

- The current file abstraction buffers entire objects as `ByteArray`: `backend/data/src/main/kotlin/io/tolgee/component/fileStorage/FileStorage.kt:5`. Local and S3 implementations do the same: `LocalFileStorage.kt:16`, `S3FileStorage.kt:21`.
- Multipart requests default to about 50 MiB: `backend/data/src/main/kotlin/io/tolgee/configuration/tolgee/TolgeeProperties.kt:90` and `backend/app/src/main/kotlin/io/tolgee/configuration/WebConfiguration.kt:82`.
- Existing image delivery is unsuitable: it is byte-buffered and its security is screenshot-specific: `backend/api/src/main/kotlin/io/tolgee/controllers/ImageStorageController.kt:78`.
- Closest domain patterns are a direct project child in `ContentDeliveryConfig.kt:31` and a unique per-language value in `model/translation/Translation.kt:44`.
- Project ownership-safe repositories use project-and-id queries: `ContentDeliveryConfigRepository.kt:41`.
- Language-aware authorization is implemented in `SecurityService.kt:232` and `ComputedPermissionDto.kt:44`.
- Deletion is manually coordinated in `LanguageHardDeleter.kt:27` and `ProjectHardDeletingService.kt:66`.
- Every new project-owned entity must be registered with export/import policy, collection, clear, and restore flows: `ProjectExportImportPolicyRegistry.kt:92`, `ProjectScopedCollectorQueries.kt:27`, `ProjectContentClearer.kt:72`, and `ProjectExportImportImporter.kt:89`.
- Project export/import currently materializes ZIP entries in memory: `TransferZipReader.kt:28`; large binary inclusion therefore also needs streaming transfer work.
- Project navigation and routing live at `webapp/src/views/projects/projectMenu/ProjectMenu.tsx:40`, `webapp/src/constants/links.tsx:292`, and `webapp/src/views/projects/ProjectRouter.tsx:39`.
- Reusable UI patterns: `BaseProjectView.tsx:17`, `PaginatedHateoasList.tsx:16`, `developer/contentDelivery/CdList.tsx:20`, `ProjectLanguagesProvider.tsx:13`, unrestricted file input in `import/component/ImportFileInput.tsx:118`, and authenticated download helper in `fixtures/downloadResponseAsFile.ts:1`.
- OpenAPI client generation is in `webapp/scripts/generate-schemas.js:16`; Cypress selectors are generated via `webapp/dataCy.core.mjs:47`.

## Constraints and trust boundaries

- Realistic PSD/video support must not buffer the whole file in JVM heap. Streaming storage/upload/download and streaming project transfer are prerequisites for claiming large-media support.
- Downloads must be authenticated and project/language authorized, use attachment disposition and `nosniff`, and never derive storage paths from user filenames.
- Store filename, reported MIME, byte count, SHA-256, uploader, and timestamps. Reject empty, oversized, and unsafe filenames.
- Blob storage is non-transactional. Upload a new immutable key before swapping DB metadata, preserve the old referenced blob on failure, and clean replaced blobs after commit. A crash-only orphan window may remain in MVP and must be observable/documented.
- Source access must remain available to assigned translators; target upload/delete/download must enforce existing per-language restrictions at the API, not only in the UI.
- Deleting a target language removes its localized file. Deleting a language used as an asset source is blocked until assets are moved or deleted.
- Project export/import must round-trip exact bytes before production rollout; normal key import/export remains unchanged.
- Assets are project-global in MVP and do not participate in Tolgee branches.

## Open product knobs (non-blocking)

- Maximum binary size is configurable. The plan must not hard-code a business limit; verification should cover a representative large streamed file without heap-proportional growth.
- Antivirus scanning is excluded for the current trusted internal deployment, but is a release gate before uploads are opened to untrusted/public users.

## Likely implementation touchpoints

- Backend model/repository/service/controller/HATEOAS under `backend/data` and `backend/api`.
- Streaming additions to `FileStorage` plus local, S3, Azure, and in-memory implementations.
- Scope reuse and language checks in existing security services; project activity registry additions.
- Liquibase schema generation and project/language hard-delete wiring.
- Project export/import policy, streaming ZIP, blob handler, collector, clearer, importer, and guard tests.
- New `webapp/src/views/projects/assets/` area plus routes/menu, typed API regeneration, activity registry, and Cypress data/tests.

