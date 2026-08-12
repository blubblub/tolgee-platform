---
name: tolgee-mcp
description: Drive the Blub Blub Tolgee instance (localize.blubtools.com) through its MCP server — manage translation keys/translations and the full binary-asset pipeline (upload, AI transcription, TTS, voice-changer, versions, review, download). Use when an agent needs to operate Tolgee programmatically — translating strings, processing voice-over audio or other localization assets, running the asset pipeline, or scripting against the Tolgee API.
---

# Tolgee via MCP

Production instance: `https://localize.blubtools.com`. Everything the web UI
does with translations and binary assets is available as MCP tools at
`POST /mcp/developer` (streamable HTTP transport).

Auth: `X-API-Key` header on **every** request.

- **PAT** (`tgpat_…`) — full power of its user. Tools take an explicit
  `projectId` argument.
- **Project API key** (`tgpak_…`) — limited to its scopes and pinned to one
  project; `projectId` auto-resolves and must be omitted.

## Which interface — MCP, REST, or the CLI

- **MCP** — everything here. All 42 tools, including the complete binary-asset
  surface (every asset REST endpoint has a tool).
- **REST** — needed only for **very large uploads**. MCP arguments are JSON, so
  uploads are base64 with a 200 MiB cap; above that use the REST multipart
  endpoints, which stream and accept 512 MiB on `localize.blubtools.com`.
  Downloads never need REST: `get_asset_download_url` returns a ticket URL that
  works without authentication, so a plain HTTP GET fetches the bytes. REST is
  also the only way to reach non-asset endpoints that have no tools (export,
  import, screenshots).
- **`@tolgee/cli`** (`tolgee pull/push/sync/extract/tag`) — **not for assets.**
  It syncs translation strings between source code and a project and has no
  asset, transcript, TTS, or version commands. Reach for it when wiring string
  extraction into a frontend build; ignore it for the asset pipeline.

## Getting a key

Ask the user for a Personal Access Token. To mint one: Tolgee UI → avatar →
**Personal Access Tokens**. (A shared PAT also lives in the `company` repo's
`scripts/env.sh` as `TOLGEE_PAK` — prefer per-person tokens. The ops runbook
`company/docs/tolgee.md` documents the instance.)

Known project IDs: `speech-blubs` = 2. `list_projects` (PAT only) shows the rest.

## Connecting

Generic streamable-HTTP MCP client config:

```json
{
  "mcpServers": {
    "tolgee": {
      "type": "http",
      "url": "https://localize.blubtools.com/mcp/developer",
      "headers": { "X-API-Key": "tgpat_…" }
    }
  }
}
```

Claude Code one-liner:

```bash
claude mcp add --transport http tolgee \
  https://localize.blubtools.com/mcp/developer -H "X-API-Key: tgpat_…"
```

### No MCP client? Use REST

The MCP transport is stateful JSON-RPC over SSE (`initialize` → session header →
call), which is miserable to drive by hand. Don't. Every tool is a thin wrapper
over the REST controller of the same name, with the same `X-API-Key` and the
same permission scopes, so scripts and one-off checks should just call REST:

```bash
curl -sS -H "X-API-Key: $KEY" \
  https://localize.blubtools.com/v2/projects/2/binary-assets
```

Tool ↔ endpoint mapping is 1:1 (`list_assets` → `GET …/binary-assets`,
`create_asset` → `POST …/binary-assets`, `run_asset_tool` → `POST
…/binary-assets/{assetId}/translations/{languageId}/versions/run`, …). Use MCP
when an agent is driving; use REST when a human or a shell script is.

## Tool map

- **Keys**: `list_keys`, `search_keys`, `create_keys`, `get_key`,
  `update_key`, `delete_keys`
- **Translations**: `get_translations`, `set_translation`
- **Project**: `list_projects`, `create_project`,
  `get_project_language_statistics`, `list_languages`, `create_language`,
  `list_tags`, `tag_keys`, `list_namespaces`
- **Branches** (EE): `list_branches`, `create_branch`, `delete_branch`
- **Machine translation**: `machine_translate` (async batch job → poll
  `get_batch_job_status`), `store_big_meta`
- **Assets**: `list_assets`, `get_asset`, `create_asset`, `update_asset`,
  `replace_asset_source`, `delete_asset`
- **Transcripts**: `set_asset_transcript`, `generate_asset_transcript`,
  `delete_asset_transcript`
- **Localized files & pipeline**: `upload_asset_translation`,
  `set_asset_translation_reviewed`, `delete_asset_translation`,
  `list_asset_versions`, `upload_asset_version`, `run_asset_tool`,
  `set_asset_chosen_version`, `delete_asset_version`, `get_asset_download_url`
- **Voices**: `list_asset_voices`, `set_asset_voice`

## The asset pipeline (most common workflow)

Voice-over localization, end to end:

1. `create_asset` — `name`, `fileName`, `fileContentBase64` (≤ 200 MiB decoded),
   optional `contentType`. Omit **both** `fileName` and `fileContentBase64` for a
   translation-only asset — one with no original file, localized purely by its
   per-language files. Its source download URL then 404s
   `binary_asset_source_not_found`, `voice-changer` on the source lane is refused
   (nothing to re-voice), and `tts` still works because it synthesizes from the
   transcript. A source can be attached later.
2. `generate_asset_transcript` — speech-to-text (ElevenLabs Scribe) on the
   source file; writes a `transcript.<assetName>` key. **Synchronous — can take
   minutes.** With `languageId` it transcribes that language's localized file
   instead.
3. Translate the transcript key into target languages with `set_translation`
   (or `machine_translate` for a batch).
4. `run_asset_tool` per target language:
   - `{"tool": "tts"}` — synthesizes the language's transcript translation.
   - `{"tool": "voice-changer"}` — re-voices existing audio
     (`params.removeBackgroundNoise` supported).
   - `params`: `voiceId`, `modelId` optional. Voice resolution:
     `params.voiceId` → language default → project default → error
     (`list_asset_voices` / `set_asset_voice` manage defaults).
   - `baseVersionId` optional — run on a previous version instead of the OG.
5. `list_asset_versions` → `set_asset_chosen_version` (the "Final") →
   `set_asset_translation_reviewed`.
6. `get_asset_download_url` — returns a short-lived public ticket URL; fetch
   the bytes with plain `curl`. Omit `languageId` for the source file; add
   `versionId` for a specific version.

Notes:

- A transcript is a normal translation key — edit its text with
  `set_translation`, never via asset tools. `set_asset_transcript` with `keyId`
  links an existing key (e.g. the on-screen string the voiceover reads).
- `upload_asset_translation` requires `translatedAgainstSourceRevision` equal
  to the asset's current `sourceRevision` (see `get_asset`).
- Any change to "what final is" (new upload, different chosen version,
  replaced source) clears that language's `reviewed` flag — re-confirm after.

## Limits & gotchas

- **MCP uploads cap at 200 MiB decoded**, and hold the whole file in memory.
  Larger files (up to 512 MiB) use the REST multipart endpoints, which stream:

  ```bash
  curl -X POST -H "X-API-Key: $KEY" \
    -F "name=my-asset" -F "file=@intro.wav" \
    https://localize.blubtools.com/v2/projects/2/binary-assets
  ```

  Same pattern for `PUT …/binary-assets/{assetId}/source`,
  `PUT …/translations/{languageId}`, `POST …/versions`.
- AI calls are synchronous; give MCP client requests a generous timeout
  (≥ 5 min) for long audio.
- Transcription needs audio/video assets; images/documents are rejected.
- `delete_*` tools are destructive (versions and files are unrecoverable) —
  always confirm with the user first.
- MCP responses carry the same JSON models as the REST API
  (`GET /v2/projects/{id}/binary-assets…`), so fields line up one-to-one.

## Where this lives in the code

Working on the platform itself rather than just driving it:

- **Tools**: `backend/app/src/main/kotlin/io/tolgee/mcp/tools/` — one file per
  area (`BinaryAssetMcpTools`, `BinaryAssetTranslationMcpTools`, `KeyMcpTools`,
  …). Each tool declares its auth with
  `buildSpec(SomeController::method, "tool_name")`, which reads the controller
  method's `@RequiresProjectPermissions` / `@AllowApiAccess` annotations. That
  is why an MCP tool can never be more permissive than its REST endpoint — add
  the endpoint first, then the tool.
- **Endpoints**: `backend/api/src/main/kotlin/io/tolgee/api/v2/controllers/binaryAsset/`.
- **Server config**: `backend/app/src/main/kotlin/io/tolgee/mcp/McpConfig.kt`
  (`/mcp/developer`). `tools/list` works unauthenticated; `tools/call` does not.
- **Upload limits**: `MCP_MAX_UPLOAD_BYTES` in `mcp/tools/McpUploads.kt` (in
  memory, 200 MiB) and `maxUploadFileSize` in `TolgeeProperties.kt` (streamed,
  200 MiB default, overridden to 512 MiB on `localize.blubtools.com` via
  `TOLGEE_MAX_UPLOAD_FILE_SIZE`). MCP decodes base64 in memory, so raising its
  cap needs matching JVM heap.
- **Tests**: `backend/app/src/test/kotlin/io/tolgee/mcp/`, driven by
  `AbstractMcpTest`.
