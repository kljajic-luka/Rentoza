#!/usr/bin/env bash

set -euo pipefail

if ! command -v gcloud >/dev/null 2>&1; then
  echo "Greska: gcloud CLI nije instaliran ili nije u PATH-u." >&2
  exit 1
fi

PROJECT_ID="${GCLOUD_PROJECT:-$(gcloud config get-value project 2>/dev/null || true)}"
ENV_FILE="${1:-.env.local}"

if [[ -z "$PROJECT_ID" ]]; then
  echo "Greska: nije postavljen GCLOUD_PROJECT i gcloud nema aktivan projekat." >&2
  exit 1
fi

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Greska: fajl '$ENV_FILE' ne postoji." >&2
  exit 1
fi

KEYS=(
  "DB_PASSWORD"
  "SUPABASE_ANON_KEY"
  "SUPABASE_SERVICE_ROLE_KEY"
  "SUPABASE_JWT_SECRET"
  "JWT_SECRET"
  "INTERNAL_SERVICE_JWT_SECRET"
  "GOOGLE_CLIENT_SECRET"
  "PII_ENCRYPTION_KEY"
  "MAIL_USERNAME"
  "MAIL_PASSWORD"
  "REDIS_PASSWORD"
  "PAYMENT_WEBHOOK_SECRET"
  "LOCKBOX_ENCRYPTION_KEY"
)

SECRETS=(
  "rentoza-db-password"
  "rentoza-supabase-anon-key"
  "rentoza-supabase-service-role-key"
  "rentoza-supabase-jwt-secret"
  "rentoza-jwt-secret"
  "rentoza-internal-service-jwt-secret"
  "rentoza-google-client-secret"
  "rentoza-pii-encryption-key"
  "rentoza-mail-username"
  "rentoza-mail-password"
  "rentoza-redis-password"
  "PAYMENT_WEBHOOK_SECRET"
  "LOCKBOX_ENCRYPTION_KEY"
)

upsert_env_key() {
  local file="$1"
  local key="$2"
  local value="$3"
  local tmp_out
  tmp_out="$(mktemp)"

  awk -v key="$key" -v value="$value" '
    BEGIN { updated = 0 }
    $0 ~ "^" key "=" {
      print key "=" value
      updated = 1
      next
    }
    { print }
    END {
      if (!updated) {
        print key "=" value
      }
    }
  ' "$file" > "$tmp_out"

  mv "$tmp_out" "$file"
}

backup_file="${ENV_FILE}.bak_sync_$(date +%Y%m%d_%H%M%S)"
cp "$ENV_FILE" "$backup_file"

work_file="$(mktemp)"
cp "$ENV_FILE" "$work_file"

updated_keys=()
missing_secrets=()

for i in "${!KEYS[@]}"; do
  key="${KEYS[$i]}"
  secret_name="${SECRETS[$i]}"

  secret_value="$(gcloud secrets versions access latest \
    --secret="$secret_name" \
    --project="$PROJECT_ID" 2>/dev/null || true)"

  if [[ -z "$secret_value" ]]; then
    missing_secrets+=("$secret_name")
    continue
  fi

  upsert_env_key "$work_file" "$key" "$secret_value"
  updated_keys+=("$key")
done

mv "$work_file" "$ENV_FILE"

echo "OK: sync zavrsen za fajl '$ENV_FILE' (projekat: $PROJECT_ID)."
echo "Backup: $backup_file"

if [[ ${#updated_keys[@]} -gt 0 ]]; then
  echo "Azurirani kljucevi:"
  for key in "${updated_keys[@]}"; do
    echo "- $key"
  done
else
  echo "Nijedan kljuc nije azuriran."
fi

if [[ ${#missing_secrets[@]} -gt 0 ]]; then
  echo "Preskoceni secret-i (ne postoje ili nisu dostupni):"
  for secret_name in "${missing_secrets[@]}"; do
    echo "- $secret_name"
  done
fi
