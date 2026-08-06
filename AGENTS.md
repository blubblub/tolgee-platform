# AGENTS.md

This file provides Tolgee-specific guidance for AI coding agents working on the Tolgee localization platform.

## Repository Structure

This project uses a multi-repository setup managed by a wrapper repository:

```
tolgee-platform/                    # platform-dev-start (wrapper repo)
├── .git/                           # git@github.com:tolgee/platform-dev-start.git
├── start.sh                        # Development startup script
├── public/                         # tolgee-platform (main repo)
│   ├── .git/                       # git@github.com:tolgee/tolgee-platform.git
│   ├── backend/                    # Kotlin/Spring Boot backend
│   ├── webapp/                     # React frontend
│   └── e2e/                        # Cypress E2E tests
└── billing/                        # billing (private repo)
    └── .git/                       # git@github.com:tolgee/billing.git
```

**Important**: When working with git commands, ensure you're in the correct repository:
- For platform code changes: `cd public/` then run git commands
- For billing changes: `cd billing/` then run git commands
- The wrapper repo (`platform-dev-start`) ignores `public/` and `billing/` in its `.gitignore`

## Backend Development

### Database Migrations
After modifying JPA entities, always run:
```bash
./gradlew diffChangeLog
```
This generates Liquibase changelog entries. If you get "docker command not found", add `--no-daemon` flag.

### Backend Testing
Tests are split into multiple categories that run in parallel in CI:
```bash
./gradlew server-app:runContextRecreatingTests && \
./gradlew server-app:runStandardTests && \
./gradlew server-app:runWebsocketTests && \
./gradlew server-app:runWithoutEeTests && \
./gradlew ee-test:test && \
./gradlew data:test && \
./gradlew security:test
```

Don't use the bare test task (it doesn't work) – always run a specific test suite even when running a single test, e.g:
```bash
# Don't do this
./gradlew test --tests "io.tolgee.unit.formats.android.out.AndroidSdkFileExporterTest"

# Do this
./gradlew :data:test --tests "io.tolgee.unit.formats.android.out.AndroidSdkFileExporterTest"
```

### Running Tests with Visible Output
To see test output in real-time (like in an IDE), use `--console=plain` with grep to filter relevant logs:
```bash
# Run a specific test with visible INFO logs
./gradlew :server-app:test --tests "io.tolgee.batch.SomeTest" --console=plain --info 2>&1 | grep -E "(INFO.*SomeTest|ERROR|WARN)" | head -100

# See all test output without filtering
./gradlew :server-app:test --tests "io.tolgee.batch.SomeTest" --console=plain --info 2>&1 | tail -200
```

Use `logger.info()` in tests for diagnostic output that will be visible with these commands.

**TestData Pattern**: Use TestData classes for test setup:
```kotlin
class YourControllerTest {
  @Autowired
  lateinit var testDataService: TestDataService

  lateinit var testData: YourTestData

  @BeforeEach
  fun setup() {
    testData = YourTestData()
    testDataService.saveTestData(testData.root)
    userAccount = testData.user
  }

  @AfterEach
  fun cleanup() {
    testDataService.cleanTestData(testData.root)
  }
}
```

**JSON Response Testing**: Use `.andAssertThatJson` for API responses:
```kotlin
performProjectAuthGet("items").andAssertThatJson {
  node("_embedded.items") {
    node("[0].id").isEqualTo(1)
    node("[0].name").isEqualTo("Item name")
  }
  node("page.totalElements").isNumber.isEqualTo(BigDecimal(2))
}
```

### Code Formatting
Always run before commits:
```bash
./gradlew ktlintFormat
```

## Frontend Development

### Path Aliases
Tolgee uses custom TypeScript path aliases instead of relative imports:
- `tg.component/*` → `component/*`
- `tg.service/*` → `service/*`
- `tg.hooks/*` → `hooks/*`
- `tg.views/*` → `views/*`
- `tg.globalContext/*` → `globalContext/*`

Example: `import { useUser } from 'tg.hooks/useUser'`

### API Schema Regeneration
After backend API changes, regenerate TypeScript types. **Backend must be running first**:
```bash
# 1. Start backend (in separate terminal)
./gradlew server-app:bootRun --args='--spring.profiles.active=dev'

# 2. Regenerate schemas
cd webapp
npm run schema        # For main API
npm run billing-schema # For billing API (if applicable)
```

### API Communication
Use typed React Query hooks from `useQueryApi.ts` (not raw React Query):
```typescript
// Query example
const { data, isLoading } = useApiQuery({
  url: '/v2/projects/{projectId}/languages',
  method: 'get',
  path: { projectId: project.id },
});

// Mutation example
const mutation = useApiMutation({
  url: '/v2/projects/{projectId}/languages',
  method: 'post',
  invalidatePrefix: '/v2/projects',
});

const handleSubmit = (data) => {
  mutation.mutate({
    path: { projectId: project.id },
    content: data,
  });
};
```

### Business Event Tracking
Use Tolgee-specific hooks for analytics:
```typescript
import { useReportEvent } from 'tg.hooks/useReportEvent';

const reportEvent = useReportEvent();
reportEvent('event_name', { key: 'value' });

// For component mount events:
import { useReportOnce } from 'tg.hooks/useReportEvent';
useReportOnce('page_viewed', { pageName: 'settings' });
```

## Testing

### E2E Test Data Setup
Creating E2E test data requires **3 components**:

1. **TestData Class** (`backend/data/src/main/kotlin/io/tolgee/development/testDataBuilder/data/YourFeatureTestData.kt`):
```kotlin
class YourTestData : BaseTestData() {
  val specificEntity: Entity

  init {
    root.apply {
      specificEntity = addEntity {
        name = "Test Entity"
      }.self
    }
  }
}
```

2. **E2E Data Controller** (`backend/development/src/main/kotlin/io/tolgee/controllers/internal/e2eData/YourFeatureE2eDataController.kt`):
```kotlin
@RestController
@RequestMapping("/api/internal/e2e-data/your-feature")
class YourFeatureE2eDataController : AbstractE2eDataController() {
  // Implement data generation endpoints
}
```

3. **Frontend Test Data Object** (`e2e/cypress/common/apiCalls/testData/testData.ts`):
```typescript
export const yourFeatureTestData = generateTestDataObject('your-feature');
```

Usage in tests:
```typescript
beforeEach(() => {
  yourFeatureTestData.clean();
  yourFeatureTestData.generateStandard().then((r) => {
    const testData = r.body;
    // Use testData in your tests
  });
});
```

**Note**: Use `generateStandard()`, not `generate()` (outdated pattern).

### data-cy Attributes (CRITICAL)
**STRICTLY ENFORCED**: Always use `data-cy` attributes for selectors, never text content.

- All data-cy values are typed in `e2e/cypress/support/dataCyType.d.ts` (auto-generated, don't modify)
- Use typed helpers: `gcy('...')` or `cy.gcy('...')`
- Add data-cy to all components accessed from tests
- Make data-cy attributes specific and descriptive

Example:
```tsx
// Component
<Alert severity="error" data-cy="signup-error-seats-spending-limit">
  <T keyName="spending_limit_dialog_title" />
</Alert>

// Test (GOOD)
gcy('signup-error-seats-spending-limit').should('be.visible');

// Test (BAD - don't use text content)
cy.contains('exceeded').should('be.visible');
```

### Error Codes
Backend error codes use `Message.kt` enum, converted to **lowercase** when sent to frontend:
```typescript
cy.intercept('POST', '/v2/projects/*/keys*', {
  statusCode: 400,
  body: {
    code: 'plan_key_limit_exceeded',  // lowercase
    params: [1000, 1001],
  },
}).as('createKey');
```

## Git Workflow

### Branch Naming
Format: `firstname-lastname/feature-description`

Generate name from git config:
```bash
git config get user.name | awk '{print $1, $2}' | \
  iconv -f UTF-8 -t ASCII//TRANSLIT | \
  tr -cd '[:alpha:]' | tr '[:upper:]' '[:lower:]'
```

### Commit Message Prefixes
- `feat:` - Breaking changes or new features
- `fix:` - Non-breaking bug fixes
- `chore:` - Non-behavior changes (docs, tests, formatting)

Example: `feat: add CSV export feature`

## Critical Quirks

### Translation Keys
**NEVER** update translation files with new keys manually. Translation keys are automatically added to files after your changes are merged to the main branch. Freely use nonexistent keys in code - they'll be handled outside the codebase.

---

# Blub Blub fork

Everything above is upstream Tolgee's. This section is specific to the
`blubblub/tolgee-platform` fork and its production deployment. Where the two
disagree, this section wins **for this fork only** — the differences are called
out explicitly under Fork quirks.

## Production

| | |
|---|---|
| URL | <https://localize.blubtools.com> |
| Host | DigitalOcean droplet `tolgee` (`104.248.39.87`), region `fra1` |
| SSH | `ssh -i ~/.ssh/digitalocean_rsa root@104.248.39.87` |
| Stack | `docker compose` in `/opt/tolgee` — `app` (fork image, embedded Postgres) + `caddy` (TLS) |
| Secrets | `/opt/tolgee/.env` on the droplet, mirrored in the `company` repo's gitignored `.env` |
| Ops runbook | `blubblub/company` → `docs/tolgee.md` — keep it current with every release |

Uploaded files (binary assets, screenshots, avatars) live in DigitalOcean
Spaces, not on the droplet volume. Postgres is still the image's embedded one on
a local volume.

## Release procedure

GHCR packages are **private** and the droplet has no registry credentials, so a
release cannot be a `docker pull`. It is build → export → copy → load → repoint,
and the two ~440 MB transfers dominate the ~25 minute wall time.

```bash
# 1. Bump BOTH workflows — they pin the version independently
#    .github/workflows/blubblub-image.yml        VERSION:
#    .github/workflows/export-blubblub-image.yml IMAGE:
# 2. Commit and push main, then:
gh workflow run blubblub-image.yml -R blubblub/tolgee-platform --ref main        # ~6 min
# wait for the image build to COMPLETE first — the export pulls the new tag and
# fails with manifest unknown otherwise; just re-run it
gh workflow run export-blubblub-image.yml -R blubblub/tolgee-platform --ref main # ~1 min

# 3. Download the artifact (1-day retention; gh buffers the whole zip, so
#    nothing appears on disk until it finishes — this is not a hang)
gh run download <exportRunId> -R blubblub/tolgee-platform -n tolgee-platform-linux-amd64

# 4. Ship it, verifying the checksum on both ends
scp -i ~/.ssh/digitalocean_rsa tolgee-platform-linux-amd64.tar.gz \
  root@104.248.39.87:/opt/tolgee/backups/tolgee-platform-<VER>-linux-amd64.tar.gz

# 5. On the droplet
cd /opt/tolgee
docker load -i backups/tolgee-platform-<VER>-linux-amd64.tar.gz
cp docker-compose.yml docker-compose.rollback-<prevVer>-$(date -u +%Y%m%dT%H%M%SZ).yml
# put the loaded image ID in docker-compose.yml, then:
docker compose up -d --no-deps app
docker compose ps          # wait for (healthy) — takes ~90s

# 6. Delete the archive afterwards. Every image stays loaded in Docker, so the
#    rollback snapshots keep working; the archives are only for rebuilding a
#    lost host and they accumulate at 440 MB per release.
```

`docker-compose.yml` pins the **image ID**, not the tag, because the droplet
cannot resolve the private registry.

### Rollback

Each release snapshots the compose file first, and every previous image is still
loaded:

```bash
cd /opt/tolgee
cp "$(ls -t docker-compose.rollback-*.yml | head -1)" docker-compose.yml
docker compose up -d --no-deps app
```

### Before releasing

Green CI is not enough — the E2E shards take hours, so run these locally:

```bash
./gradlew :server-app:runStandardTests --tests "io.tolgee.api.v2.controllers.<YourTest>"
cd webapp
npx tsc --project tsconfig.prod.json     # strict key union; `npm run tsc` is the permissive one
npx tolgee extract check
npx eslint --ext .ts --ext .tsx --max-warnings 0 --resolve-plugins-relative-to . .
npx vitest run
npm run generate-data-cy                 # after adding any data-cy
```

Frontend-only changes still need a full image build — the webapp ships inside
the backend image.

## Fork quirks

**Fork-only translation keys.** *This contradicts "Critical Quirks → Translation
Keys" above.* That guidance assumes keys reach upstream Tolgee's project 1, which
fork keys never do. `webapp/src/i18n/en.json` is generated by `tolgee pull` and
must not be hand-edited — a fork key added there is wiped on the next pull.
Declare fork keys in `webapp/tolgee.prod.d.ts` instead; they resolve from their
`defaultValue` at runtime. `tolgee.dev.d.ts` is permissive, `tolgee.prod.d.ts` is
strict, so only `tsc:prod` catches a missing declaration.

**`keyName` must be a string literal.** `tolgee extract check` fails on a
variable, so `<T keyName={someVar} />` breaks the build. Keep the literal in the
JSX even when mapping over a config array.

**`ktlintFormat` does not exist here** — *contradicting "Code Formatting" above.*
Only `ktlintCheck` does, and neither it nor `diffChangeLog` runs on a JDK 17
machine: both die with `UnsupportedClassVersionError … class file version 65.0`
because CI builds on JDK 21. Install a JDK 21 toolchain to run them locally;
otherwise CI is the only check for Kotlin formatting and entity/schema drift.

**Reading a CI job log while the run is still going.** `gh api …/jobs/{id}/logs`
returns empty and `gh run view --log` refuses until the *whole* run finishes.
This works for any job that has itself completed:

```bash
curl -sL -H "Authorization: Bearer $(gh auth token)" \
  "https://api.github.com/repos/blubblub/tolgee-platform/actions/jobs/<jobId>/logs"
```

Without it there is no way to see which lint rules actually failed, and guessing
from rule names wastes hours.

**The test Postgres skips migrations on a reused container.** See
`PostgresDockerRunner.shouldRunMigrations`. After adding a Liquibase changeset,
every test hits `column … does not exist` — including tests you did not touch,
which makes it look like you broke something. Fix:

```bash
docker rm -f tolgee_backend_tests_postgres_main
```

**Migrations are one monolithic file.** Append to
`backend/data/src/main/resources/db/changelog/schema.xml` with
`author="blubblub"` and an epoch-millis id. There is no separate fork changelog.

**There are no DB-level cascades on `key`.** A new FK to `key` blocks deletion
until every hard-delete path in `KeyService` clears it — `hardDelete`,
`hardDeleteMultiple`, and `deleteAllByProject`. `BinaryAsset.transcriptKey` is
the worked example.

**Give every entity an explicit `@Column(length = …)`** when the schema declares
one. Without it Hibernate assumes 255 and `diffChangeLog` generates a migration
that *shrinks* the column — this kept Migration Check red until fixed.

## Speech-to-text and voice tools

Transcription of binary assets calls ElevenLabs Scribe directly over multipart —
deliberately not through Tolgee's LLM stack, which is EE-only and speaks
chat-completions, so audio would have to be base64-inlined. The same API key
drives the asset-translation pipeline tools (`tts`, `voice-changer`), which are
`BinaryAssetTool` implementations resolved by name in `BinaryAssetToolService`.
Configure on the droplet:

```bash
TOLGEE_TRANSCRIPTION_API_KEY=...          # unset = feature hidden, endpoint refuses
TOLGEE_TRANSCRIPTION_MODEL=scribe_v2
TOLGEE_TRANSCRIPTION_API_URL=https://api.elevenlabs.io   # or api.eu.residency.elevenlabs.io
TOLGEE_TRANSCRIPTION_TTS_MODEL=eleven_multilingual_v2            # default for the tts tool
TOLGEE_TRANSCRIPTION_VOICE_CHANGER_MODEL=eleven_multilingual_sts_v2  # default for voice-changer
```

Both model ids are per-run overridable from the pipeline dialog; the properties
only set the default. The pipeline UI lives at
`/projects/{id}/assets/{assetId}/translations/{languageId}` — reached from the
asset page, there is no separate asset-translations list page.

**Default voices** live in `binary_asset_voice`, shaped like `MtServiceConfig`:
a row with no language is the project default, a row with one overrides it.
Resolution is `params.voiceId` → language → project → `BINARY_ASSET_TTS_VOICE_ID_REQUIRED`,
done once in `BinaryAssetTranslationVersionService.runTool` and handed to the
tools via `BinaryAssetToolContext.defaultVoiceId`. Managed under **Languages →
Voices** (`GET`/`PUT /v2/projects/{id}/binary-asset-voices`; read needs
`translations.view`, write `languages.edit`).

`BinaryAssetVoiceService` deliberately injects no `ProjectService`/`LanguageService`
— project deletion calls into it and that would close a bean cycle, so it sets
FKs through `EntityManager.getReference` and the controller checks that the
language belongs to the project. Its FKs to `project` and `language` mean
cleanup must stay wired into `ProjectHardDeletingService`, `ProjectContentClearer`,
and `LanguageHardDeleter`.
