# GCP Backend Redeploying Logic

This document explains how backend redeploys are controlled in `infrastructure/gcp/deploy-backend-secure.sh`.

## Source of truth

Deploy runtime policy is env-file driven:

- `staging.env` = canonical staging values
- `staging-smoke.env` = temporary smoke overrides layered on staging
- `prod.env` = canonical production values

The deploy script loads files in this order:

1. `<environment>.env`
2. `<environment>-<profile>.env` (only when profile is not `default`)
3. already-exported shell env vars (highest precedence)

## Script usage

From `infrastructure/gcp`:

- `./deploy-backend-secure.sh [environment] [profile] [--allow-prod-mock]`
- `environment`: `prod` (default) | `staging`
- `profile`: `default` (default) | `smoke`

Examples:

- Staging normal deploy: `./deploy-backend-secure.sh staging`
- Staging smoke deploy: `./deploy-backend-secure.sh staging smoke`
- Prod deploy: `./deploy-backend-secure.sh prod`
- Explicit prod mock override: `./deploy-backend-secure.sh prod --allow-prod-mock`

## Production payment guard

The script blocks accidental production deploys when:

- `environment=prod`
- `PAYMENT_PROVIDER=MOCK`
- `--allow-prod-mock` is **not** passed

Failure message:

- `Blocking prod deploy: PAYMENT_PROVIDER=MOCK`

This is intentional. For real production deploys, set `PAYMENT_PROVIDER` in `prod.env` to the real provider.

## Standard redeploy workflow

1. Update values in the correct env file (`staging.env` or `prod.env`).
2. (Optional) Add temporary smoke values in `staging-smoke.env`.
3. Validate script + env parsing:
   - `bash -n deploy-backend-secure.sh`
   - `set -a && source staging.env && set +a`
   - `set -a && source staging-smoke.env && set +a`
   - `set -a && source prod.env && set +a`
4. Deploy:
   - Staging default: `./deploy-backend-secure.sh staging`
   - Staging smoke: `./deploy-backend-secure.sh staging smoke`
   - Prod: `./deploy-backend-secure.sh prod`
5. Verify Cloud Run revision and env values in GCP console/logs.

## Smoke flow and rollback

Recommended smoke cycle:

1. Deploy `staging smoke`.
2. Execute smoke validation (DB-driven no-show scenarios + logs).
3. Remove/stop smoke overrides by redeploying staging default:
   - `./deploy-backend-secure.sh staging`

This ensures staging returns to canonical policy values.

## Notes

- `SPRING_PROFILE` defaults to `prod` unless exported.
- Script uses `--update-env-vars` and `--update-secrets` so unrelated settings stay intact.
- Secret values are sourced from Secret Manager at deploy time; do not place secrets in env files.
