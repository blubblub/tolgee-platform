# Test specification: Binary asset translations

Status: Critic approved with the PRD
Primary plan: `docs/plan/prd-binary-asset-translations.md`

## Release rule

The feature is releasable only when every required gate below passes with fresh output. Any permission, checksum, constrained-heap, project-import rollback, proxy 413/timeout, or deployment-defaults checklist failure stops deployment.

MVP success means the **in-platform CMS workflow** works under the stated size ceiling. Runtime/CDN publish is out of scope and is not a release gate.

## Test fixtures

- Two projects with overlapping numeric asset/language IDs to exercise project scoping.
- An admin, keys-only editor, unrestricted translator, Slovenian-only translator, viewer, and non-member.
- Source language English plus Slovenian, German, and a language added/deleted during a test.
- Tiny deterministic PSD-signature, WAV/MP3-like, MP4-like, and unknown binary fixtures with recorded SHA-256 values.
- A generated 256 MiB deterministic stream; do not commit it to Git.
- A fault-injecting streaming store and a temp-directory `LocalFileStorage`; do not use ByteArray-backed InMemory storage for binary-path tests.

## Required backend tests

### Domain and repository

- Create one asset/source and prove at most one localized row per `(asset_id, language_id)`.
- Derive `MISSING`, `CURRENT`, and `OUTDATED` without persisting empty language rows.
- Add a language and observe `MISSING`; rename preserves links by ID.
- Reject duplicate `(project_id, name)` on create and rename.
- Reject a target revision greater than the current source revision.
- Upload against revision N while source advances to N+1; persisted status is `OUTDATED`.
- Concurrent target upserts leave one referenced winner under the unique constraint and pessimistic asset lock.
- Every asset lookup includes project ownership; an ID from another project cannot return a row.

### Storage and integrity

- Local/S3/Azure streaming contract: store, open, stream, close, delete, missing file, partial-write failure, and exact SHA-256.
- Verify Local writes a temporary file and promotes atomically; failed writes do not expose the final key.
- Verify the provider capability defaults false and all binary-asset service paths fail closed on a non-streaming provider.
- Reject zero bytes, over-limit content, control-character/path filenames, and unsupported source/target language relationships without a referenced blob.
- On storage failure, DB failure, and replacement failure, the previously referenced blob remains downloadable; new temporary/immutable keys are cleaned best-effort and cleanup failures increment a metric.
- Static or architectural check: binary-asset upload/download/export/import code paths do not call `MultipartFile.bytes`, `readBytes`, `readAllBytes`, or full-payload `toByteArray`.

### API, permissions, and tickets

For list/detail/create/edit/delete/source-ticket/target-ticket/target-upsert/target-delete, test the complete role matrix from the PRD.

- `keys.view/create/edit/delete` governs asset/source operations exactly.
- `translations.view` plus `checkViewPermitted(languageId)` governs target ticket/download.
- `translations.edit` plus `checkTranslatePermitted(languageId)` governs target upload/replace/delete.
- Project API keys / PATs with mapped scopes succeed on the same operations (`@AllowApiAccess`); keys without those scopes fail.
- The Slovenian-only translator downloads the shared source and can act only on Slovenian.
- Cross-project IDs, non-members, and forbidden languages return non-disclosing errors and no metadata/bytes.
- Ticket is short-lived and bound to user, project, asset, source-or-language, and current immutable blob identity.
- Expired, cross-project, cross-language, tampered, revoked-user, and replaced-blob tickets fail.
- Streaming response has exact `Content-Length`, stored/fallback content type, RFC 5987 attachment filename, `X-Content-Type-Options: nosniff`, and private/no-store cache headers.

Follow the existing assertion shape in `backend/app/src/test/kotlin/io/tolgee/api/v2/controllers/v2ScreenshotController/KeyScreenshotControllerTest.kt:41` and authenticated helpers in `backend/testing/src/main/kotlin/io/tolgee/testing/AuthorizedControllerTest.kt:127`.

### Language/project lifecycle

- `LanguageService.deleteLanguage` rejects a source language before soft delete or permission removal.
- Target-language hard-delete via `LanguageHardDeleter` (or equivalent hard-delete pipeline) removes `binary_asset_translation` rows and then blobs after commit; soft-delete alone does not leave permanent orphan requirements beyond the documented crash window.
- Injected blob-delete failure after commit leaves DB consistent and emits cleanup telemetry.
- Asset and project deletion remove all current referenced blobs.
- Existing language and project deletion tests remain green.

### Project export/import

- Policy guard classifies both entities and supplies project collector, clear order, associations, and blob handling:
  `ee/backend/tests/src/test/kotlin/io/tolgee/ee/projectExportImport/ProjectExportImportPolicyGuardTest.kt:40`.
- Export contains logical blob references, metadata, source and targets, but no reusable live storage key.
- Import to a different project generates fresh destination-scoped keys and verifies byte count/SHA-256 before metadata visibility.
- Export -> wipe -> import restores names, languages, revisions, statuses, filenames, MIME, byte counts, and exact checksums.
- Missing/corrupt/oversized blob, DB failure, and cancellation roll back metadata, delete staged keys, and preserve pre-import rows/blobs.
- Successful mirror import removes superseded old keys only after commit.

Use `backend/app/src/test/kotlin/io/tolgee/api/v2/controllers/administration/ProjectExportImportControllerTest.kt:93` and exporter coverage under `ee/backend/tests/src/test/kotlin/io/tolgee/ee/projectExportImport/`.

### Bounded-memory gate

Fork a JVM with `-Xmx128m`; generate a 256 MiB deterministic stream and run it through:

1. **HTTP multipart upload** to the binary-asset create/replace endpoint (not only a direct `FileStorage` call),
2. ticket download,
3. project export,
4. project import.

The process must finish without OOM, the final SHA-256 must match, and no binary endpoint/transfer path may call whole-file `MultipartFile.bytes`, `readBytes`, `readAllBytes`, `toByteArray`, or frontend `response.blob()`.

## Required frontend tests

### Unit/type

- Status/progress mapping for admin and language-restricted views.
- Filename/size/date rendering and permission-gated action derivation.
- Ticket request then browser navigation; assert no JavaScript `Blob` construction.
- Upload loading/success/error states and server validation messages; no byte-progress promise.
- Regenerated OpenAPI types compile for multipart and ticket endpoints.

### Cypress

Create `e2e/cypress/e2e/binaryAssets.cy.ts` with literal generated `data-cy` selectors:

1. Open **Assets** from the project menu and verify assets do not appear in Translations/keys.
2. Create an English source; reload and verify source metadata/checksum-backed download.
3. Observe missing rows, upload Slovenian, replace source, observe Slovenian outdated, then replace Slovenian and observe current.
4. Download and delete Slovenian; verify German remains unaffected.
5. Log in as a Slovenian-only translator and verify German controls/tickets are unavailable while the source remains downloadable.
6. Exercise create/replace failure recovery and persistence across reload.
7. Attempt a duplicate asset name and assert a clear validation error.
8. Assert empty/help copy mentions project-global (not branch-scoped) assets where implemented.

Register dedicated E2E data through `e2e/cypress/common/apiCalls/testData/testData.ts:18` and a backend internal data controller.

## Observability checks

- Upload/download success/failure counters and byte/latency metrics move once per operation.
- Cleanup failure counter and structured log include project/asset/language IDs and failure phase, not filenames or content.
- Ticket validation failures do not log the raw token.
- Low local-disk, cleanup failure, and sustained binary-endpoint 5xx alerts are configured/tested before production enablement.

## Commands and evidence

Run the smallest targeted class tests first, then the repository suites required by `AGENTS.md`. Start `./gradlew server-app:bootRun --args='--spring.profiles.active=dev'` in a separate terminal before `npm run schema`:

```text
./gradlew ktlintFormat
./gradlew :data:test --tests '*BinaryAsset*'
./gradlew server-app:runStandardTests
./gradlew ee-test:test
./gradlew security:test
cd webapp && npm run schema && npm test && npm run tsc && npm run eslint && npm run generate-data-cy
cd e2e && npm run tsc && npm run eslint && npm run cy:run -- --spec cypress/e2e/binaryAssets.cy.ts
```

Also run the dedicated constrained-heap task/script, full project-export policy guard, image build, and immutable digest inspection. Record exact command, exit status, test count, image tag/digest, and artifact links in the implementation handoff.

## Production smoke and rollback proof

1. Create a fresh cold DB/blob backup and verify its checksum off-host.
2. Record the prior immutable image ID and validated rollback Compose file.
3. Apply Deployment defaults from the PRD: app upload ceiling (recommended 512 MiB), reverse-proxy body limit ≥ ceiling, upload timeouts, multipart temp free space ≥ 3× ceiling; record actual values.
4. Deploy a new immutable fork tag by resolved digest after free-disk check.
5. In a disposable project, multipart-upload a tiny PSD-like file and a representative large generated file through the public hostname; compare SHA-256 on ticket download.
6. Verify admin, API-key, and Slovenian-only permission/ticket cases directly against the API.
7. Export, wipe, and import the disposable project; verify fresh storage keys, checksums, and rollback behavior.
8. Confirm heap/disk/5xx/cleanup telemetry, then enable normal use.
9. On any required-gate failure (including proxy 413/timeout), restore the previous image. Do not drop additive tables or delete new blobs; retain them for forward recovery unless a verified cold restore is required.
