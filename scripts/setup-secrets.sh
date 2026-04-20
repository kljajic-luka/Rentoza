#!/usr/bin/env bash
# =============================================================================
# scripts/setup-secrets.sh
#
# One-time provisioning of GCP Secret Manager secrets for all Rentoza services.
# Run this BEFORE the first deployment that uses --update-secrets in cloudbuild.yaml,
# and re-run whenever you need to rotate a secret.
#
# !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
# IMMEDIATE ACTION REQUIRED
# All the values that were previously in cloudrun-env.yaml have been exposed in
# git history and must be rotated before provisioning:
#
#   1. DB_PASSWORD     → Generate a new Supabase DB password
#                        (Supabase dashboard → Settings → Database → Reset password)
#   2. SUPABASE keys   → If project is still in pre-production you can keep the
#                        existing JWTs (they are project-scoped, not user passwords),
#                        but rotate them if this repo is/was public.
#                        (Supabase dashboard → Project Settings → API keys)
#   3. GOOGLE_CLIENT_SECRET → Rotate in Google Cloud Console → APIs & Services →
#                             Credentials
#   4. PII_ENCRYPTION_KEY   → Generate a new 32-char random key:
#                             openssl rand -base64 24
# !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
#
# Usage:
#   export GCLOUD_PROJECT=<your-gcp-project-id>
#
#   # Set each secret value from the environment (never pass on the command line)
#   export RENTOZA_DB_PASSWORD="<rotated-value>"
#   export RENTOZA_SUPABASE_ANON_KEY="<value>"
#   export RENTOZA_SUPABASE_SERVICE_ROLE_KEY="<value>"
#   export RENTOZA_SUPABASE_JWT_SECRET="<value>"
#   export RENTOZA_JWT_SECRET="<value>"
#   export RENTOZA_INTERNAL_SERVICE_JWT_SECRET="<value>"
#   export RENTOZA_GOOGLE_CLIENT_SECRET="<rotated-value>"
#   export RENTOZA_PII_ENCRYPTION_KEY="<rotated-value>"
#   export RENTOZA_MAIL_USERNAME="<value>"
#   export RENTOZA_MAIL_PASSWORD="<value>"
#
#   bash scripts/setup-secrets.sh
#
# Prerequisites:
#   - gcloud CLI authenticated with a principal that has roles/secretmanager.admin
#   - GCLOUD_PROJECT exported or gcloud config set project <id>
# =============================================================================

set -euo pipefail

PROJECT_ID="${GCLOUD_PROJECT:-$(gcloud config get-value project 2>/dev/null)}"
if [[ -z "$PROJECT_ID" ]]; then
  echo "ERROR: Set GCLOUD_PROJECT or configure gcloud: gcloud config set project <id>" >&2
  exit 1
fi
echo "Target project: $PROJECT_ID"
echo ""

# ── Verify required vars are set ─────────────────────────────────────────────
required_vars=(
  RENTOZA_DB_PASSWORD
  RENTOZA_SUPABASE_ANON_KEY
  RENTOZA_SUPABASE_SERVICE_ROLE_KEY
  RENTOZA_SUPABASE_JWT_SECRET
  RENTOZA_JWT_SECRET
  RENTOZA_INTERNAL_SERVICE_JWT_SECRET
  RENTOZA_GOOGLE_CLIENT_SECRET
  RENTOZA_PII_ENCRYPTION_KEY
  RENTOZA_MAIL_USERNAME
  RENTOZA_MAIL_PASSWORD
)

for var in "${required_vars[@]}"; do
  if [[ -z "${!var:-}" ]]; then
    echo "ERROR: $var is not set. Export it before running this script." >&2
    exit 1
  fi
done

# ── Helper ────────────────────────────────────────────────────────────────────
upsert_secret() {
  local name="$1"
  local value="$2"
  if gcloud secrets describe "$name" --project="$PROJECT_ID" &>/dev/null; then
    echo "  [update] Adding new version: $name"
    printf '%s' "$value" | gcloud secrets versions add "$name" \
      --data-file=- --project="$PROJECT_ID"
  else
    echo "  [create] Creating secret: $name"
    printf '%s' "$value" | gcloud secrets create "$name" \
      --data-file=- \
      --replication-policy=automatic \
      --project="$PROJECT_ID"
  fi
}

# ── Shared secrets (both backend and chat service) ────────────────────────────
echo "=== Shared secrets ==="
upsert_secret "rentoza-db-password"                 "$RENTOZA_DB_PASSWORD"
upsert_secret "rentoza-supabase-anon-key"           "$RENTOZA_SUPABASE_ANON_KEY"
upsert_secret "rentoza-supabase-service-role-key"   "$RENTOZA_SUPABASE_SERVICE_ROLE_KEY"
upsert_secret "rentoza-supabase-jwt-secret"         "$RENTOZA_SUPABASE_JWT_SECRET"
upsert_secret "rentoza-jwt-secret"                  "$RENTOZA_JWT_SECRET"
upsert_secret "rentoza-internal-service-jwt-secret" "$RENTOZA_INTERNAL_SERVICE_JWT_SECRET"

# ── Backend-only secrets ──────────────────────────────────────────────────────
echo ""
echo "=== Backend-only secrets ==="
upsert_secret "rentoza-google-client-secret"  "$RENTOZA_GOOGLE_CLIENT_SECRET"
upsert_secret "rentoza-pii-encryption-key"    "$RENTOZA_PII_ENCRYPTION_KEY"
upsert_secret "rentoza-mail-username"         "$RENTOZA_MAIL_USERNAME"
upsert_secret "rentoza-mail-password"         "$RENTOZA_MAIL_PASSWORD"

# ── Grant Cloud Run service accounts access ───────────────────────────────────
echo ""
echo "=== Granting secretAccessor to Cloud Run service accounts ==="

grant_access() {
  local secret="$1"
  local sa="$2"
  gcloud secrets add-iam-policy-binding "$secret" \
    --member="serviceAccount:$sa" \
    --role="roles/secretmanager.secretAccessor" \
    --project="$PROJECT_ID" \
    --quiet
}

# Resolve service accounts from deployed Cloud Run services
BACKEND_SA=$(gcloud run services describe rentoza-backend \
  --region europe-west1 --format='value(spec.template.spec.serviceAccountName)' \
  --project="$PROJECT_ID" 2>/dev/null || echo "")

CHAT_SA=$(gcloud run services describe rentoza-chat \
  --region europe-west1 --format='value(spec.template.spec.serviceAccountName)' \
  --project="$PROJECT_ID" 2>/dev/null || echo "")

shared_secrets=(
  rentoza-db-password
  rentoza-supabase-anon-key
  rentoza-supabase-service-role-key
  rentoza-supabase-jwt-secret
  rentoza-jwt-secret
  rentoza-internal-service-jwt-secret
)

backend_secrets=(
  rentoza-google-client-secret
  rentoza-pii-encryption-key
  rentoza-mail-username
  rentoza-mail-password
)

if [[ -n "$BACKEND_SA" ]]; then
  for s in "${shared_secrets[@]}" "${backend_secrets[@]}"; do
    grant_access "$s" "$BACKEND_SA"
  done
  echo "  Backend SA ($BACKEND_SA): granted on all backend secrets"
else
  echo "  WARNING: rentoza-backend service not found — grant IAM manually after first deploy"
fi

if [[ -n "$CHAT_SA" ]]; then
  for s in "${shared_secrets[@]}"; do
    grant_access "$s" "$CHAT_SA"
  done
  echo "  Chat SA ($CHAT_SA): granted on shared secrets"
else
  echo "  WARNING: rentoza-chat service not found — grant IAM manually after first deploy"
fi

echo ""
echo "Done. Cloud Build service account also needs roles/secretmanager.secretAccessor"
echo "on all secrets above to read them during deploy:"
echo ""
echo "  CB_SA=\$(gcloud projects describe $PROJECT_ID --format='value(projectNumber)')@cloudbuild.gserviceaccount.com"
echo "  for S in \${shared_secrets[@]} \${backend_secrets[@]}; do"
echo "    gcloud secrets add-iam-policy-binding \"\$S\" \\"
echo "      --member=\"serviceAccount:\$CB_SA\" \\"
echo "      --role=roles/secretmanager.secretAccessor --project=$PROJECT_ID"
echo "  done"
