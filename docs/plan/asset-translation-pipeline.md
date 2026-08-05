# Plan: Asset translation pipeline (versions + tools + reviewed final)

Date: 2026-08-05
Status: proposed, not implemented
Builds on: binary-asset-translations (see `prd-binary-asset-translations.md`)

## 0. Reframe — most of this already exists

The fork already has the whole "EN one asset → one OG file per language" mapping:

- `BinaryAsset` — source file, `sourceLanguage`, `transcriptKey`, `sourceRevision`
  (`backend/data/src/main/kotlin/io/tolgee/model/binaryAsset/BinaryAsset.kt`)
- `BinaryAssetTranslation` — the OG uploaded file per target language, unique on
  `(asset_id, language_id)`, tracks `sourceRevision` for outdatedness
- Upload/download via JWT tickets + streaming, transcription (ElevenLabs,
  synchronous), activity logging, and the `AssetsView` / `AssetView` /
  `AssetTranscript` pages with permission gating

**New work is only:** (1) a versions table under the translation, (2) a "chosen
final" marker, (3) a tool runner that turns (OG or version) → new version,
(4) a page to see and manage all that. The OG stays exactly where it is —
`BinaryAssetTranslation` remains the OG row, untouched.

## 1. Data model

New entity `BinaryAssetTranslationVersion` (`model/binaryAsset/`, table
`binary_asset_translation_version`):

```kotlin
@Entity
@ActivityLoggedEntity
@ActivityEntityDescribingPaths(paths = ["translation", "asset"])
class BinaryAssetTranslationVersion(
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "translation_id", nullable = false)
  var translation: BinaryAssetTranslation,
) : StandardAuditModel() {
  @Column(nullable = false, length = 512) lateinit var storageKey: String
  lateinit var originalFilename: String
  lateinit var contentType: String
  var byteSize: Long = 0
  @Column(nullable = false, length = 64) lateinit var sha256: String
  @ActivityLoggedProp @Column(nullable = false, length = 64) lateinit var tool: String // e.g. "convert", "normalize"
  var toolParams: String? = null // JSON, @Column(columnDefinition = "text")
  @ActivityLoggedProp var chosen: Boolean = false // the reviewed final
  @ManyToOne(fetch = FetchType.LAZY) var createdBy: UserAccount? = null
}
```

`BinaryAssetTranslation` gains:

```kotlin
@OneToMany(mappedBy = "translation", orphanRemoval = true)
var versions: MutableList<BinaryAssetTranslationVersion> = mutableListOf()
```

**"Chosen final" is a flag on the version, not a FK on the translation.** A
`chosenVersionId` FK creates a parent→child FK inside a cascade-delete graph
(translation delete cascades to versions while still pointing at one) — the
flag avoids that whole class of problem. Semantics: **no chosen version = OG
is the final.** Invariant "at most one chosen per translation" is enforced in
service code plus a partial unique index in the migration:

```sql
CREATE UNIQUE INDEX batv_one_chosen_per_translation
  ON binary_asset_translation_version (translation_id) WHERE chosen;
```

Migration: append to `backend/data/src/main/resources/db/changelog/schema.xml`,
`author="blubblub"`, epoch-millis id, all columns with explicit lengths (fork
quirks). **No FK to `key` anywhere** — `KeyService` hard-delete paths stay
untouched.

## 2. API (new `BinaryAssetTranslationVersionController`, same base path style)

| Endpoint | Scope | Purpose |
|---|---|---|
| `GET …/binary-assets/{assetId}/translations/{languageId}/versions` | `TRANSLATIONS_VIEW` + lang view | list versions (OG is implied by the translation model itself) |
| `POST …/translations/{languageId}/versions/run` | `TRANSLATIONS_EDIT` + lang translate | body `{tool, params?, baseVersionId?}`; runs tool **synchronously** on OG (null) or a version, stores blob, returns new version |
| `PUT …/translations/{languageId}/chosen-version` | `TRANSLATIONS_STATE_EDIT` | body `{versionId?}`; null = back to OG. Clears any previously chosen flag |
| `DELETE …/versions/{versionId}` | `TRANSLATIONS_EDIT` | delete row + best-effort blob delete |
| `POST …/versions/{versionId}/download-ticket` | `TRANSLATIONS_VIEW` + lang view | ticket gains optional `versionId`; download controller re-checks storageKey against that version |

Extend `BinaryAssetTranslationModel` with `chosenVersionId` + `versionCount` so
list views can badge translations. Existing translation download-ticket endpoint
keeps serving the OG; a follow-up (decision point 4) can make it serve the
chosen file.

## 3. Tool runner — synchronous, registry, no BatchJob

Transcription already runs synchronously with a 300 s timeout; single-file
audio tools are in the same class. **v1: synchronous `POST …/run`, no BatchJob,
no websockets.** If a future tool is genuinely long-running, promote to
BatchJob then — the version row is already the durable result, so nothing is
thrown away.

```kotlin
interface BinaryAssetTool {
  val name: String
  fun run(input: FileStream, params: Map<String, Any?>): ToolOutput // stream, filename, contentType
}
```

`BinaryAssetToolService` looks up the tool by name (400 on unknown), opens the
input stream (OG or `baseVersionId`), runs, stores via the existing
`storeNewBlob` path, creates the version row. "Pipeline" chaining falls out for
free: any version can be the input to the next run.

Candidate first tools — pick one (decision point 1):

- **`convert` / `normalize` / `trim-silence`** — one `ffmpeg` invocation each,
  but ffmpeg must be added to the Docker image (release-procedure note).
- **`tts`** — ElevenLabs text-to-speech from the translated transcript; reuses
  the existing ElevenLabs client pattern, no image change.

## 4. Frontend — one new page, sub-route of the asset

New route `LINKS.PROJECT_ASSET_TRANSLATION` = `PROJECT_ASSET` +
`translations/{languageId}`, registered in `ProjectRouter.tsx`, rendering a new
`AssetTranslationView.tsx` in `webapp/src/views/projects/assets/`:

- **Header**: asset name, language flag/tag, outdated badge (existing
  `sourceRevision` logic).
- **OG card**: `BinaryAssetPreview` player, download, "run tool on this file".
- **Versions list**: one row per version — inline audio player, tool + params +
  author + timestamp, download, delete, and a "Final" radio. Choosing one hits
  `PUT chosen-version`; choosing "Original" sends null.
- **Run-tool dialog**: pick tool → tool-specific params → pick base file (OG or
  any version) → submit; on success the new version appears in the list.
- Entry point: per-language rows in the existing `AssetView` get a link/badge
  (`versionCount`, chosen indicator) into this page.

Gating mirrors the API: `translations.view` to see, `translations.edit`
(+ `satisfiesLanguageAccess`) to run tools/delete, `translations.state-edit`
for the Final radio. After backend lands: `npm run schema`, fork keys into
`webapp/tolgee.prod.d.ts` (never `en.json`), `npm run generate-data-cy` for new
selectors.

## 5. Lifecycle & deletion checklist

All existing delete paths are per-entity (`repository.delete`/`deleteAll` on
loaded entities), so `orphanRemoval` cascades reach versions; each path just
needs version blob keys added to its cleanup list:

- `deleteTranslation` — collect `translation.versions.map { it.storageKey }`
- `deleteAsset` / `deleteAllByProject` — same, via
  `asset.translations.flatMap { it.versions }`
- `deleteTranslationsForLanguage` — same
- Version delete endpoint — no chosen-FK to clear (flag design); just row + blob

Activity: new `ActivityType` values (`BINARY_ASSET_TRANSLATION_VERSION_RUN`,
`…_CHOOSE`, `…_DELETE`), `@RequestActivity` on the controller methods,
activity-display keys in `tolgee.prod.d.ts` (pattern: existing
`activity_entity_binary_asset*` keys).

## 6. Phasing

1. **Backend core** — entity, migration, repository, service, controller,
   download-ticket extension, deletion wiring, activity, tests in
   `runStandardTests`. Ships with the run endpoint + first tool so the loop is
   provable end-to-end.
2. **Frontend page** — route, view, AssetView entry points, schema regen, keys,
   data-cy.
3. **Pre-release checks** — `docker rm -f tolgee_backend_tests_postgres_main`
   after the migration, targeted backend tests, `tsc:prod`,
   `tolgee extract check`, eslint, vitest; update `company/docs/tolgee.md` if
   the image changes (ffmpeg).

## 7. Decision points

1. **First tool**: `tts` (no image change, reuses ElevenLabs) vs
   `convert`/`normalize` (needs ffmpeg in the image). Mechanism is identical.
2. **Sync execution OK?** Matches transcription; async/BatchJob only when a
   tool outgrows a request.
3. **Page placement**: new sub-route per asset+language (recommended) vs
   cramming versions into `AssetView`.
4. **What downstream consumers see**: should the existing "download
   translation" ticket serve the **chosen** file once one is picked (OG
   otherwise)? That's the natural "final" semantics for exports/apps, but it
   changes current behavior — decide before phase 1.
