---
name: tolgee-deploy
description: Deploy, verify, resume, or roll back the Blub Blub Tolgee fork at localize.blubtools.com through its existing GitHub image workflows and DigitalOcean Droplet. Use for production releases and deployment status; not for provisioning infrastructure or managing Tolgee content.
---

# Tolgee Deploy

Operate the existing production release path from the repository root.

## Read first

Before acting, read these authoritative sources:

1. [AGENTS.md](../../../AGENTS.md): `Production`, `Release procedure`, `Rollback`, and `Before releasing`.
2. `../company/docs/tolgee.md`, when the sibling company repository is available: `Source and image`, `Release procedure`, `Ops`, and `Backups`.

Trust live state over version values copied into documentation. Reconcile stale documentation after a successful release.

## Boundaries and access

- Update the existing `tolgee` Droplet in `fra1`; do not create App Platform apps, Droplets, databases, DNS records, or other infrastructure.
- A deploy request authorizes routine image-release mutations. Starting a new image may run Liquibase automatically; schema migration requires explicit approval, a fresh verified recovery point, and a reviewed rollback plan. It does not authorize infrastructure changes, secret rotation, or deletion of rollback assets.
- Follow the repository Git rules: do not commit or push a version bump without explicit user authorization.
- Require GitHub Actions/artifact access and individual, authorized SSH access. The documented key path is `~/.ssh/digitalocean_rsa`; never copy or share a private key to grant access.
- Never print, copy, or commit `.env` contents, tokens, credentials, or private keys. A normal release does not need a Tolgee PAT.
- The skill makes the procedure available to collaborators; repository access does not grant GitHub or Droplet permissions. Report the exact missing permission when a preflight fails.

## Preflight

1. Resolve the actual platform repository before acting: use the current root when it contains `backend/` and `webapp/`, or the nested `public/` repository when working from a wrapper checkout. Confirm its remote is `blubblub/tolgee-platform`.
2. Inspect `git status`, the complete diff, and the intended release commit. Stop on accidental or unrelated changes.
3. Identify the source SHA of the live image and compare it with the intended release. If the range changes Liquibase changelogs, persistence mappings, or migration-sensitive entities, stop unless migration approval, a fresh verified recovery point, and backward-compatible image rollback or a tested database-restore procedure are all recorded. Never describe image-only rollback as sufficient after an unreviewed migration.
4. Ensure the intended commit is pushed to `origin/main`. If committing or pushing has not been authorized, stop and request it.
5. Run the change-relevant checks from `AGENTS.md`. Automatic CI is manual-only, so do not treat an absent CI run as validation. Never skip or fake a required check.
6. Verify access without exposing secrets:
   - `gh auth status` and read/dispatch access to `blubblub/tolgee-platform`.
   - Non-interactive SSH access to `root@104.248.39.87` using the operator's authorized key.
7. Check that no build/export release is already in progress, production is healthy, and local and remote disks have room for the roughly 440 MiB archive.
8. Record the current Compose file, running app image ID and tag, and health state. Do not begin from an unexplained unhealthy state.

## Release

1. Read both independent image pins:
   - `.github/workflows/blubblub-image.yml` → `env.VERSION`
   - `.github/workflows/export-blubblub-image.yml` → `env.IMAGE`
2. Verify they name the same valid `v<upstream>-blubblub.<n>` tag. Increment only the fork suffix unless the user explicitly requested an upstream upgrade, and require that the target tag does not already exist in GHCR. Patch both pins together, then commit and push only when authorized. A rejected push requires re-reading `main` and choosing a new suffix; never reuse or overwrite a release tag.
3. Record the expected `origin/main` SHA. Dispatch the build:

   ```bash
   gh workflow run blubblub-image.yml -R blubblub/tolgee-platform --ref main
   ```

   Select the newly created run by workflow, dispatch time, and expected SHA; never assume the newest run belongs to this release. Wait with `gh run watch <run-id> --exit-status` and require success. Record the published Linux/AMD64 manifest digest and config image ID from GHCR, and require `origin/main` to remain at the expected SHA.
4. Only after the build succeeds, dispatch and wait for the export:

   ```bash
   gh workflow run export-blubblub-image.yml -R blubblub/tolgee-platform --ref main
   ```

   Require the export run's `headSha` to equal the expected SHA. A concurrent or early export fails with `manifest unknown`; re-run it only after confirming the image exists. Re-resolve the registry manifest digest and config image ID before and after export and stop if either differs from the values recorded after the build.
5. Download the matching run's one-day artifact `tolgee-platform-linux-amd64` into a unique temporary directory. `gh run download` buffers the zip before a file appears; lack of partial output is expected. Read the Docker archive manifest/config, record its image ID, and require it to equal the GHCR config image ID.
6. Compute the local SHA-256 of `tolgee-platform-linux-amd64.tar.gz`. Copy it to `/opt/tolgee/backups/tolgee-platform-<version>-linux-amd64.tar.gz`, compute the remote SHA-256, and require an exact match.
7. On the Droplet:
   - Acquire native `flock` on `/opt/tolgee/.deploy.lock` and keep the lock held by the same remote shell through load, repoint, health verification, rollback if needed, and cleanup. If another operator holds it, stop without mutation.
   - Work only in `/opt/tolgee`.
   - `docker load` the verified archive. Never `docker pull`; GHCR is private and the host has no registry credentials.
   - Resolve and record the loaded image's full `sha256:` ID from its exact tag, and require it to equal the image ID recorded from the downloaded archive.
   - Snapshot `docker-compose.yml` as `docker-compose.rollback-<previous-version>-<UTC timestamp>.yml` and record that exact filename.
   - Change only `services.app.image` to the loaded image ID. Leave Caddy, volumes, environment, and embedded Postgres untouched.
   - Validate with `docker compose config -q`, then run `docker compose up -d --no-deps app`.
8. Poll for at most five minutes, reporting progress at least once per minute. Require all of these:
   - The app container uses the newly loaded image ID.
   - Docker health is `healthy`.
   - `https://localize.blubtools.com/actuator/health` returns `{"status":"UP"}`.
   - `/` and `/api/public/configuration` return HTTP 200.
9. If health does not recover, collect bounded app logs, restore the exact snapshot created in step 7, run `docker compose up -d --no-deps app`, verify the old release, and report the failed release as not deployed.
10. After success, delete the local and uploaded release archives. Keep the rollback Compose snapshot and all loaded Docker images; never prune them.
11. Update `../company/docs/tolgee.md` with the deployed tag, full image ID, source commit, verification date, and any procedure correction. Never add secrets. Respect that repository's own commit/push rules.

## Handoff

Report the deployed version and source SHA, build/export run links, loaded image ID, checksum match, health results, rollback snapshot, archive cleanup, and documentation status. Do not claim completion while any required check or documentation update is unresolved.
