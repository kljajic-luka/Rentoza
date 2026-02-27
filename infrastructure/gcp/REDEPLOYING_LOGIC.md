# GCP Platform Redeploying Logic

This document describes the canonical redeploy flow in
`/Users/kljaja01/Developer/Rentoza/infrastructure/gcp/deploy-backend-secure.sh`.

The script now deploys **chat-service + backend** in one run by default, with one shared image tag.

## Source Of Truth

Runtime policy is env-file driven:

- `staging.env` = canonical staging values
- `staging-smoke.env` = temporary smoke overrides layered on staging
- `prod.env` = canonical production values

Load order:

1. `<environment>.env`
2. `<environment>-<profile>.env` (only when profile != `default`)
3. already-exported shell env vars (highest precedence)

## Script Usage

From `/Users/kljaja01/Developer/Rentoza/infrastructure/gcp`:

- `./deploy-backend-secure.sh [environment] [profile] [flags]`
- `environment`: `prod` (default) | `staging`
- `profile`: `default` (default) | `smoke`

Flags:

- `--allow-prod-mock`
- `--backend-only`
- `--chat-only`
- `--image-tag <tag>`
- `--skip-build` (deploy prebuilt images; requires `--image-tag`)

Examples:

- Full staging deploy (chat + backend): `./deploy-backend-secure.sh staging`
- Staging smoke deploy: `./deploy-backend-secure.sh staging smoke`
- Chat-only emergency redeploy: `./deploy-backend-secure.sh staging --chat-only`
- Backend-only emergency redeploy: `./deploy-backend-secure.sh staging --backend-only`
- Full prod deploy: `./deploy-backend-secure.sh prod`
- Deploy prebuilt artifacts: `./deploy-backend-secure.sh staging --image-tag <tag> --skip-build`

## Deploy Order

Default order is intentional:

1. Build and deploy chat-service
2. Resolve chat URL from Cloud Run
3. Build and deploy backend with fresh `CHAT_SERVICE_URL`

This prevents backend from pointing at stale chat revisions.

## CI/CD Source Of Truth

`/Users/kljaja01/Developer/Rentoza/infrastructure/gcp/cloudbuild.yaml` now:

1. Builds/pushes both images with `SHORT_SHA`
2. Calls `deploy-backend-secure.sh` with:
   - `--image-tag ${SHORT_SHA}`
   - `--skip-build`

This keeps Cloud Build and manual deploys on the exact same deploy logic.

Recommended trigger model:

- `staging-daily` trigger:
  - branch: `main`
  - automatic: yes
  - substitutions: `_ENVIRONMENT=staging`, `_DEPLOY_PROFILE=default`, `_ALLOW_PROD_MOCK=false`
- `prod-release` trigger:
  - source: release tag (`v*`) or manual run
  - approval: required
  - substitutions: `_ENVIRONMENT=prod`, `_DEPLOY_PROFILE=default`, `_ALLOW_PROD_MOCK=false`

## Secret Strategy

The script uses `--update-secrets` only (no plaintext secrets in env files):

- Shared secrets for both services: DB/Supabase/JWT/Internal JWT
- Backend-only secrets: Google OAuth, PII key, mail creds, payment webhook secret
- Payment secrets (conditional): Monri merchant key, authenticity token (only when `PAYMENT_PROVIDER != MOCK`)

It supports both legacy and current secret IDs for two migrated names:

- `rentoza-supabase-service-role-key` fallback `rentoza-supabase-service-role`
- `rentoza-internal-service-jwt-secret` fallback `rentoza-internal-jwt-secret`

## Production Guard

The script blocks accidental production deploys when:

- `environment=prod`
- `PAYMENT_PROVIDER=MOCK`
- `--allow-prod-mock` is not present

Set `PAYMENT_PROVIDER` in `prod.env` to `MONRI` before go-live.

## Payment Provider Configuration

The backend uses a provider-agnostic payment adapter pattern. The active provider is
selected by `PAYMENT_PROVIDER` env var and wired via `@ConditionalOnProperty` in Spring.

### Staging (MOCK mode — default)

No payment credentials needed. `staging.env` sets:

- `PAYMENT_PROVIDER=MOCK` — credentialless mock adapter
- `SPRING_PROFILE=staging` — loads `application-staging.properties` (no `enforce-real-provider`)
- `MONRI_API_URL=https://ipgtest.monri.com` — inert when MOCK, pre-configured for integration testing

### Production (Monri mode)

Before switching production to real payments:

1. **Create GCP Secret Manager entries** (one-time):
   ```bash
   echo -n "<merchant-key>" | gcloud secrets create MONRI_MERCHANT_KEY --data-file=-
   echo -n "<authenticity-token>" | gcloud secrets create MONRI_AUTHENTICITY_TOKEN --data-file=-
   ```
   Grant `roles/secretmanager.secretAccessor` to the Cloud Run service account.

2. **Update `prod.env`**:
   ```
   PAYMENT_PROVIDER=MONRI
   ```

3. **Deploy**:
   ```bash
   ./deploy-backend-secure.sh prod
   ```
   The script will:
   - Resolve `MONRI_MERCHANT_KEY` and `MONRI_AUTHENTICITY_TOKEN` from Secret Manager
   - Bind them via `--update-secrets` (not plaintext env vars)
   - Pass `MONRI_API_URL` (non-sensitive) via `--update-env-vars`

4. **Verify**: `PaymentProviderStartupValidator` enforces `enforce-real-provider=true` in
   the prod Spring profile — startup will crash if `PAYMENT_PROVIDER=MOCK` leaks into prod.

### Webhook Secret

`PAYMENT_WEBHOOK_SECRET` is already managed in Secret Manager. It is used by the
`/api/webhooks/payment` endpoint to validate HMAC signatures from the payment gateway.

### Switching staging to Monri (integration testing)

To temporarily test real Monri in staging:

1. Create the two secrets in Secret Manager (if not already present)
2. Set `PAYMENT_PROVIDER=MONRI` in `staging.env` (or override via shell export)
3. Deploy: `./deploy-backend-secure.sh staging`
4. Revert `staging.env` back to `PAYMENT_PROVIDER=MOCK` when done

## Daily Staging Runbook

1. Update `staging.env` (and `staging-smoke.env` only if needed).
2. Deploy stack: `./deploy-backend-secure.sh staging`
3. Verify:
   - chat attachment upload/download
   - websocket messaging
   - backend ↔ chat notification flow
4. Roll forward by rerunning the same command with a new tag, or roll back by deploying an earlier known-good image tag (`--image-tag`).

## Production Runbook

1. Confirm `prod.env` has `PAYMENT_PROVIDER=MONRI` (or intended provider).
2. Confirm Secret Manager has current values for `MONRI_MERCHANT_KEY`, `MONRI_AUTHENTICITY_TOKEN`, and `PAYMENT_WEBHOOK_SECRET`.
3. Deploy stack: `./deploy-backend-secure.sh prod`
4. Run production smoke checks (auth, booking flow, payment charge, chat attachments, notifications).

## Rollback Strategy

Use immutable image tags printed by the deploy script. To roll back:

- redeploy with previous tag: `./deploy-backend-secure.sh <env> --image-tag <old-tag>`

If one service is impacted, use targeted rollback:

- chat only: `--chat-only`
- backend only: `--backend-only`
