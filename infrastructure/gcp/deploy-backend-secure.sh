#!/bin/bash
# =============================================================================
# RENTOZA SECURE PLATFORM DEPLOYMENT SCRIPT
# =============================================================================
#
# Deploys chat-service and backend with one release tag by default.
# Secrets are read from Google Secret Manager only (no plaintext in env files).
#
# Usage:
#   ./deploy-backend-secure.sh [environment] [profile] [flags]
#
# Positional:
#   environment: prod (default) | staging
#   profile:     default (default) | smoke
#
# Flags:
#   --allow-prod-mock   Allow PAYMENT_PROVIDER=MOCK in prod (explicit override)
#   --backend-only      Deploy backend only (skip chat)
#   --chat-only         Deploy chat only (skip backend)
#   --image-tag <tag>   Override generated image tag for both services
#   --skip-build        Skip image builds and deploy prebuilt image tag
#   -h, --help          Show usage
#
# Examples:
#   ./deploy-backend-secure.sh staging
#   ./deploy-backend-secure.sh staging smoke
#   ./deploy-backend-secure.sh staging --chat-only
#   ./deploy-backend-secure.sh prod --allow-prod-mock
# =============================================================================

set -euo pipefail

# Core configuration
PROJECT_ID="${PROJECT_ID:-rentoza-485118}"
REGION="${REGION:-europe-west1}"
BACKEND_SERVICE_NAME="${BACKEND_SERVICE_NAME:-rentoza-backend}"
CHAT_SERVICE_NAME="${CHAT_SERVICE_NAME:-rentoza-chat}"

# Runtime options
ENVIRONMENT="prod"
DEPLOY_PROFILE="default"
ALLOW_PROD_MOCK_PAYMENT="false"
DEPLOY_BACKEND="true"
DEPLOY_CHAT="true"
IMAGE_TAG="${IMAGE_TAG:-}"
SKIP_BUILD="false"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# Staging and prod both use production Spring profile unless explicitly overridden.
SPRING_PROFILE="${SPRING_PROFILE:-prod}"

usage() {
    cat <<'USAGE'
Usage:
  ./deploy-backend-secure.sh [environment] [profile] [flags]

Positional:
  environment: prod (default) | staging
  profile:     default (default) | smoke

Flags:
  --allow-prod-mock
  --backend-only
  --chat-only
  --image-tag <tag>
  --skip-build
  -h, --help
USAGE
}

load_env_file() {
    local file_path="$1"
    if [[ -f "$file_path" ]]; then
        echo "📄 Loading config: $(basename "$file_path")"
        set -a
        # shellcheck disable=SC1090
        source "$file_path"
        set +a
    fi
}

require_var() {
    local key="$1"
    if [[ -z "${!key:-}" ]]; then
        echo "❌ Missing required variable: $key"
        exit 1
    fi
}

resolve_secret_name() {
    local __result_var="$1"
    shift

    local candidate
    for candidate in "$@"; do
        # Prefer access check so roles/secretmanager.secretAccessor is sufficient.
        if gcloud secrets versions access latest --secret "$candidate" --project "$PROJECT_ID" >/dev/null 2>&1; then
            printf -v "$__result_var" '%s' "$candidate"
            return 0
        fi
    done

    echo "❌ Could not resolve required secret. Tried: $*"
    exit 1
}

build_image() {
    local service_name="$1"
    local image_ref="$2"
    local dockerfile="${service_name}/Dockerfile"
    local build_config="$SCRIPT_DIR/cloudbuild-docker-build.yaml"

    if [[ ! -f "$REPO_ROOT/apps/$dockerfile" ]]; then
        echo "❌ Dockerfile not found: $REPO_ROOT/apps/$dockerfile"
        exit 1
    fi

    echo "📦 Building image: $image_ref ($dockerfile)"
    gcloud builds submit "$REPO_ROOT/apps" \
        --config "$build_config" \
        --timeout=20m \
        --substitutions "_IMAGE=$image_ref,_DOCKERFILE=$dockerfile,_CONTEXT=."
}

deploy_chat() {
    echo ""
    echo "💬 Deploying chat-service..."

    local backend_url_for_chat="${CHAT_BACKEND_API_BASE_URL:-}"
    if [[ -z "$backend_url_for_chat" ]]; then
        backend_url_for_chat="$(gcloud run services describe "$BACKEND_SERVICE_NAME" \
            --region "$REGION" \
            --platform managed \
            --format 'value(status.url)' 2>/dev/null || true)"
    fi

    if [[ -z "$backend_url_for_chat" ]]; then
        echo "❌ Could not determine BACKEND_API_BASE_URL for chat deployment."
        echo "   Export CHAT_BACKEND_API_BASE_URL or deploy backend first."
        exit 1
    fi

    local -a chat_deploy_cmd=(
        gcloud run deploy "$CHAT_SERVICE_NAME"
        --image "$CHAT_IMAGE"
        --region "$REGION"
        --platform managed
        --allow-unauthenticated
        --port 8081
        --memory "$CHAT_MEMORY"
        --cpu "$CHAT_CPU"
        --min-instances "$CHAT_MIN_INSTANCES"
        --max-instances "$CHAT_MAX_INSTANCES"
        --concurrency "$CHAT_CONCURRENCY"
        --remove-env-vars "$CHAT_SECRET_ENV_NAMES"
        --update-env-vars "^#^SPRING_PROFILES_ACTIVE=$SPRING_PROFILE#SERVER_PORT=8081#BACKEND_API_BASE_URL=$backend_url_for_chat#CORS_ALLOWED_ORIGINS=$CHAT_CORS_ALLOWED_ORIGINS#WEBSOCKET_ALLOWED_ORIGINS=$CHAT_WEBSOCKET_ALLOWED_ORIGINS#CHAT_STORAGE_PROVIDER=$CHAT_STORAGE_PROVIDER#CHAT_STORAGE_BUCKET=$CHAT_STORAGE_BUCKET#REDIS_ENABLED=$CHAT_REDIS_ENABLED#RABBITMQ_ENABLED=$CHAT_RABBITMQ_ENABLED#MANAGEMENT_HEALTH_REDIS_ENABLED=false"
        --update-secrets "$CHAT_SECRET_BINDINGS"
    )

    local chat_deploy_output
    if ! chat_deploy_output="$("${chat_deploy_cmd[@]}" 2>&1)"; then
        echo "$chat_deploy_output"
        return 1
    fi
    echo "$chat_deploy_output"

    CHAT_SERVICE_URL="$(gcloud run services describe "$CHAT_SERVICE_NAME" \
        --region "$REGION" \
        --platform managed \
        --format 'value(status.url)')"

    CHAT_REVISION="$(gcloud run services describe "$CHAT_SERVICE_NAME" \
        --region "$REGION" \
        --platform managed \
        --format 'value(status.latestReadyRevisionName)')"

    echo "✅ Chat deployed: $CHAT_SERVICE_URL ($CHAT_REVISION)"
}

deploy_backend() {
    echo ""
    echo "🛠️ Deploying backend..."

    if [[ -z "${CHAT_SERVICE_URL:-}" ]]; then
        CHAT_SERVICE_URL="$(gcloud run services describe "$CHAT_SERVICE_NAME" \
            --region "$REGION" \
            --platform managed \
            --format 'value(status.url)' 2>/dev/null || true)"
    fi

    if [[ -z "$CHAT_SERVICE_URL" ]]; then
        echo "❌ Could not determine CHAT_SERVICE_URL."
        echo "   Deploy chat first or export CHAT_SERVICE_URL before backend deploy."
        exit 1
    fi

    local -a backend_deploy_cmd=(
        gcloud run deploy "$BACKEND_SERVICE_NAME"
        --image "$BACKEND_IMAGE"
        --region "$REGION"
        --platform managed
        --allow-unauthenticated
        --memory "$BACKEND_MEMORY"
        --cpu "$BACKEND_CPU"
        --min-instances "$BACKEND_MIN_INSTANCES"
        --max-instances "$BACKEND_MAX_INSTANCES"
        --timeout "$BACKEND_TIMEOUT"
        --remove-env-vars "$BACKEND_SECRET_ENV_NAMES"
        --update-env-vars "^#^SPRING_PROFILES_ACTIVE=$SPRING_PROFILE#CHAT_SERVICE_URL=$CHAT_SERVICE_URL#CORS_ORIGINS=$BACKEND_CORS_ORIGINS#ALLOWED_ORIGINS=$BACKEND_ALLOWED_ORIGINS#COOKIE_SECURE=$BACKEND_COOKIE_SECURE#COOKIE_DOMAIN=$BACKEND_COOKIE_DOMAIN#STORAGE_MODE=$BACKEND_STORAGE_MODE#PAYMENT_PROVIDER=$PAYMENT_PROVIDER#APP_PAYMENT_ENFORCE_REAL_PROVIDER=$APP_PAYMENT_ENFORCE_REAL_PROVIDER#APP_FRONTEND_URL=$APP_FRONTEND_URL#CONSENT_POLICY_VERSION=$CONSENT_POLICY_VERSION#CONSENT_POLICY_HASH=$CONSENT_POLICY_HASH#APP_RATE_LIMIT_ENABLED=true#APP_ID_VERIFICATION_PROVIDER=$APP_ID_VERIFICATION_PROVIDER#APP_RENTER_VERIFICATION_FACE_MATCH_THRESHOLD=$APP_RENTER_VERIFICATION_FACE_MATCH_THRESHOLD#APP_RENTER_VERIFICATION_SELFIE_REQUIRED=$APP_RENTER_VERIFICATION_SELFIE_REQUIRED#CHECKIN_SCHEDULER_ENABLED=$CHECKIN_SCHEDULER_ENABLED#CHECKIN_WINDOW_HOURS_BEFORE_TRIP=$CHECKIN_WINDOW_HOURS_BEFORE_TRIP#CHECKIN_MAX_EARLY_HOURS=$CHECKIN_MAX_EARLY_HOURS#CHECKIN_TIMING_VALIDATION_ENABLED=$CHECKIN_TIMING_VALIDATION_ENABLED#CHECKIN_NO_SHOW_MINUTES_AFTER_TRIP_START=$CHECKIN_NO_SHOW_MINUTES_AFTER_TRIP_START#CHECKIN_SCHEDULER_WINDOW_CRON=$CHECKIN_SCHEDULER_WINDOW_CRON#CHECKIN_SCHEDULER_NOSHOW_CRON=$CHECKIN_SCHEDULER_NOSHOW_CRON#CHECKIN_SCHEDULER_NOSHOW_DIAGNOSTICS_ENABLED=$CHECKIN_SCHEDULER_NOSHOW_DIAGNOSTICS_ENABLED#CHECKOUT_SCHEDULER_ENABLED=$CHECKOUT_SCHEDULER_ENABLED#CHECKOUT_WINDOW_HOURS_BEFORE_TRIP=$CHECKOUT_WINDOW_HOURS_BEFORE_TRIP#CHECKOUT_LATE_GRACE_MINUTES=$CHECKOUT_LATE_GRACE_MINUTES#NOTIFICATIONS_EMAIL_ENABLED=$NOTIFICATIONS_EMAIL_ENABLED#MAIL_FROM=$MAIL_FROM#OAUTH2_REDIRECT_ALLOWED_URIS=$OAUTH2_REDIRECT_ALLOWED_URIS#APP_COMPLIANCE_RENTAL_AGREEMENT_CHECKIN_ENFORCED=$APP_COMPLIANCE_RENTAL_AGREEMENT_CHECKIN_ENFORCED"
        --update-secrets "$BACKEND_SECRET_BINDINGS"
    )

    local backend_deploy_output
    if ! backend_deploy_output="$("${backend_deploy_cmd[@]}" 2>&1)"; then
        echo "$backend_deploy_output"
        return 1
    fi
    echo "$backend_deploy_output"

    BACKEND_SERVICE_URL="$(gcloud run services describe "$BACKEND_SERVICE_NAME" \
        --region "$REGION" \
        --platform managed \
        --format 'value(status.url)')"

    BACKEND_REVISION="$(gcloud run services describe "$BACKEND_SERVICE_NAME" \
        --region "$REGION" \
        --platform managed \
        --format 'value(status.latestReadyRevisionName)')"

    echo "✅ Backend deployed: $BACKEND_SERVICE_URL ($BACKEND_REVISION)"
}

# -----------------------------------------------------------------------------
# Argument parsing
# -----------------------------------------------------------------------------
while [[ $# -gt 0 ]]; do
    case "$1" in
        prod|staging)
            ENVIRONMENT="$1"
            shift
            ;;
        default|smoke)
            DEPLOY_PROFILE="$1"
            shift
            ;;
        --allow-prod-mock)
            ALLOW_PROD_MOCK_PAYMENT="true"
            shift
            ;;
        --backend-only)
            DEPLOY_CHAT="false"
            shift
            ;;
        --chat-only)
            DEPLOY_BACKEND="false"
            shift
            ;;
        --image-tag)
            if [[ $# -lt 2 ]]; then
                echo "❌ Missing value for --image-tag"
                exit 1
            fi
            IMAGE_TAG="$2"
            shift 2
            ;;
        --skip-build)
            SKIP_BUILD="true"
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "❌ Invalid argument: $1"
            usage
            exit 1
            ;;
    esac
done

if [[ "$DEPLOY_BACKEND" == "false" && "$DEPLOY_CHAT" == "false" ]]; then
    echo "❌ Nothing to deploy. Remove one of --backend-only/--chat-only."
    exit 1
fi

if [[ "$SKIP_BUILD" == "true" && -z "$IMAGE_TAG" ]]; then
    echo "❌ --skip-build requires --image-tag <existing-tag>"
    exit 1
fi

# -----------------------------------------------------------------------------
# Load environment profile files
# -----------------------------------------------------------------------------
load_env_file "$SCRIPT_DIR/${ENVIRONMENT}.env"
if [[ "$DEPLOY_PROFILE" != "default" ]]; then
    load_env_file "$SCRIPT_DIR/${ENVIRONMENT}-${DEPLOY_PROFILE}.env"
fi

# -----------------------------------------------------------------------------
# Defaults (env file values can override)
# -----------------------------------------------------------------------------
CHECKIN_WINDOW_HOURS_BEFORE_TRIP="${CHECKIN_WINDOW_HOURS_BEFORE_TRIP:-2}"
CHECKIN_MAX_EARLY_HOURS="${CHECKIN_MAX_EARLY_HOURS:-2}"
CHECKIN_TIMING_VALIDATION_ENABLED="${CHECKIN_TIMING_VALIDATION_ENABLED:-true}"
CHECKIN_SCHEDULER_ENABLED="${CHECKIN_SCHEDULER_ENABLED:-true}"
CHECKIN_NO_SHOW_MINUTES_AFTER_TRIP_START="${CHECKIN_NO_SHOW_MINUTES_AFTER_TRIP_START:-120}"
CHECKIN_SCHEDULER_WINDOW_CRON="${CHECKIN_SCHEDULER_WINDOW_CRON:-0 0/15 * * * *}"
CHECKIN_SCHEDULER_NOSHOW_CRON="${CHECKIN_SCHEDULER_NOSHOW_CRON:-0 0/10 * * * *}"
CHECKIN_SCHEDULER_NOSHOW_DIAGNOSTICS_ENABLED="${CHECKIN_SCHEDULER_NOSHOW_DIAGNOSTICS_ENABLED:-false}"
CHECKOUT_WINDOW_HOURS_BEFORE_TRIP="${CHECKOUT_WINDOW_HOURS_BEFORE_TRIP:-1}"
CHECKOUT_LATE_GRACE_MINUTES="${CHECKOUT_LATE_GRACE_MINUTES:-60}"
CHECKOUT_SCHEDULER_ENABLED="${CHECKOUT_SCHEDULER_ENABLED:-true}"
NOTIFICATIONS_EMAIL_ENABLED="${NOTIFICATIONS_EMAIL_ENABLED:-true}"
MAIL_FROM="${MAIL_FROM:-rentozzza@gmail.com}"
OAUTH2_REDIRECT_ALLOWED_URIS="${OAUTH2_REDIRECT_ALLOWED_URIS:-https://rentoza.rs/auth/supabase/google/callback,https://www.rentoza.rs/auth/supabase/google/callback}"
PAYMENT_PROVIDER="${PAYMENT_PROVIDER:-MOCK}"
APP_PAYMENT_ENFORCE_REAL_PROVIDER="${APP_PAYMENT_ENFORCE_REAL_PROVIDER:-true}"
APP_FRONTEND_URL="${APP_FRONTEND_URL:-https://rentoza.rs}"

APP_ID_VERIFICATION_PROVIDER="${APP_ID_VERIFICATION_PROVIDER:-MOCK}"
APP_RENTER_VERIFICATION_FACE_MATCH_THRESHOLD="${APP_RENTER_VERIFICATION_FACE_MATCH_THRESHOLD:-0.95}"
APP_RENTER_VERIFICATION_SELFIE_REQUIRED="${APP_RENTER_VERIFICATION_SELFIE_REQUIRED:-true}"
APP_COMPLIANCE_RENTAL_AGREEMENT_CHECKIN_ENFORCED="${APP_COMPLIANCE_RENTAL_AGREEMENT_CHECKIN_ENFORCED:-true}"
CONSENT_POLICY_VERSION="${CONSENT_POLICY_VERSION:-2025-01-01-v1}"
CONSENT_POLICY_HASH="${CONSENT_POLICY_HASH:-}"

# Backend Cloud Run sizing
BACKEND_MEMORY="${BACKEND_MEMORY:-1Gi}"
BACKEND_CPU="${BACKEND_CPU:-1}"
BACKEND_MIN_INSTANCES="${BACKEND_MIN_INSTANCES:-1}"
BACKEND_MAX_INSTANCES="${BACKEND_MAX_INSTANCES:-5}"
BACKEND_TIMEOUT="${BACKEND_TIMEOUT:-300}"
BACKEND_CORS_ORIGINS="${BACKEND_CORS_ORIGINS:-https://rentoza.rs,https://www.rentoza.rs}"
BACKEND_ALLOWED_ORIGINS="${BACKEND_ALLOWED_ORIGINS:-https://rentoza.rs,https://www.rentoza.rs}"
BACKEND_COOKIE_SECURE="${BACKEND_COOKIE_SECURE:-true}"
BACKEND_COOKIE_DOMAIN="${BACKEND_COOKIE_DOMAIN:-rentoza.rs}"
BACKEND_STORAGE_MODE="${BACKEND_STORAGE_MODE:-supabase}"

# Chat Cloud Run sizing + runtime
CHAT_MEMORY="${CHAT_MEMORY:-1Gi}"
CHAT_CPU="${CHAT_CPU:-1}"
CHAT_MIN_INSTANCES="${CHAT_MIN_INSTANCES:-1}"
CHAT_MAX_INSTANCES="${CHAT_MAX_INSTANCES:-5}"
CHAT_CONCURRENCY="${CHAT_CONCURRENCY:-100}"
CHAT_CORS_ALLOWED_ORIGINS="${CHAT_CORS_ALLOWED_ORIGINS:-https://rentoza.rs,https://www.rentoza.rs}"
CHAT_WEBSOCKET_ALLOWED_ORIGINS="${CHAT_WEBSOCKET_ALLOWED_ORIGINS:-https://rentoza.rs,https://www.rentoza.rs}"
CHAT_STORAGE_PROVIDER="${CHAT_STORAGE_PROVIDER:-supabase}"
CHAT_STORAGE_BUCKET="${CHAT_STORAGE_BUCKET:-chat-attachments}"
CHAT_REDIS_ENABLED="${CHAT_REDIS_ENABLED:-false}"
CHAT_RABBITMQ_ENABLED="${CHAT_RABBITMQ_ENABLED:-false}"
CHAT_BACKEND_API_BASE_URL="${CHAT_BACKEND_API_BASE_URL:-}"
CHAT_SERVICE_URL="${CHAT_SERVICE_URL:-}"

require_var "PAYMENT_PROVIDER"
require_var "CHECKIN_NO_SHOW_MINUTES_AFTER_TRIP_START"
require_var "CHECKIN_SCHEDULER_NOSHOW_CRON"
require_var "CHECKIN_SCHEDULER_NOSHOW_DIAGNOSTICS_ENABLED"

if [[ "$DEPLOY_BACKEND" == "true" && "$SPRING_PROFILE" == "prod" ]]; then
    require_var "CONSENT_POLICY_VERSION"
    require_var "CONSENT_POLICY_HASH"
    if [[ ! "$CONSENT_POLICY_HASH" =~ ^[a-f0-9]{64}$ ]]; then
        echo "❌ CONSENT_POLICY_HASH must be a 64-character lowercase SHA-256 hex digest."
        echo "   Current value: '$CONSENT_POLICY_HASH'"
        exit 1
    fi
fi

if [[ "$DEPLOY_BACKEND" == "true" && "$ENVIRONMENT" == "prod" && "$PAYMENT_PROVIDER" == "MOCK" && "$ALLOW_PROD_MOCK_PAYMENT" != "true" ]]; then
    echo "❌ Blocking prod deploy: PAYMENT_PROVIDER=MOCK"
    echo "   Use a real gateway provider or rerun with --allow-prod-mock for explicit override."
    exit 1
fi

# Generate shared immutable image tag if none provided.
if [[ -z "$IMAGE_TAG" ]]; then
    if git -C "$REPO_ROOT" rev-parse --short HEAD >/dev/null 2>&1; then
        GIT_SHA="$(git -C "$REPO_ROOT" rev-parse --short HEAD)"
    else
        GIT_SHA="nogit"
    fi
    IMAGE_TAG="${ENVIRONMENT}-${GIT_SHA}-$(date +%Y%m%d%H%M%S)"
fi

BACKEND_IMAGE="europe-west1-docker.pkg.dev/$PROJECT_ID/rentoza/backend:$IMAGE_TAG"
CHAT_IMAGE="europe-west1-docker.pkg.dev/$PROJECT_ID/rentoza/chat-service:$IMAGE_TAG"

# -----------------------------------------------------------------------------
# Validate gcloud auth + project
# -----------------------------------------------------------------------------
if ! gcloud auth print-identity-token &>/dev/null; then
    echo "❌ Error: Not authenticated with gcloud. Run: gcloud auth login"
    exit 1
fi

gcloud config set project "$PROJECT_ID" >/dev/null

# -----------------------------------------------------------------------------
# Resolve secret IDs (supports both new + legacy names where needed)
# -----------------------------------------------------------------------------
resolve_secret_name SECRET_DB_URL                         "rentoza-db-url"
resolve_secret_name SECRET_DB_USERNAME                    "rentoza-db-username"
resolve_secret_name SECRET_DB_PASSWORD                    "rentoza-db-password"
resolve_secret_name SECRET_SUPABASE_URL                   "rentoza-supabase-url"
resolve_secret_name SECRET_SUPABASE_ANON_KEY             "rentoza-supabase-anon-key"
resolve_secret_name SECRET_SUPABASE_SERVICE_ROLE_KEY      "rentoza-supabase-service-role-key" "rentoza-supabase-service-role"
resolve_secret_name SECRET_SUPABASE_JWT_SECRET            "rentoza-supabase-jwt-secret"
resolve_secret_name SECRET_JWT_SECRET                     "rentoza-jwt-secret"
resolve_secret_name SECRET_INTERNAL_SERVICE_JWT_SECRET    "rentoza-internal-service-jwt-secret" "rentoza-internal-jwt-secret"

if [[ "$DEPLOY_BACKEND" == "true" ]]; then
    resolve_secret_name SECRET_GOOGLE_CLIENT_ID           "rentoza-google-client-id"
    resolve_secret_name SECRET_GOOGLE_CLIENT_SECRET       "rentoza-google-client-secret" "rentoza-google-oauth-secret"
    resolve_secret_name SECRET_PII_ENCRYPTION_KEY         "rentoza-pii-encryption-key"
    resolve_secret_name SECRET_MAIL_USERNAME              "rentoza-mail-username"
    resolve_secret_name SECRET_MAIL_PASSWORD              "rentoza-mail-password"
fi

CHAT_SECRET_BINDINGS="DB_URL=${SECRET_DB_URL}:latest,DB_USERNAME=${SECRET_DB_USERNAME}:latest,DB_PASSWORD=${SECRET_DB_PASSWORD}:latest,SUPABASE_URL=${SECRET_SUPABASE_URL}:latest,SUPABASE_ANON_KEY=${SECRET_SUPABASE_ANON_KEY}:latest,SUPABASE_SERVICE_ROLE_KEY=${SECRET_SUPABASE_SERVICE_ROLE_KEY}:latest,SUPABASE_JWT_SECRET=${SECRET_SUPABASE_JWT_SECRET}:latest,JWT_SECRET=${SECRET_JWT_SECRET}:latest,INTERNAL_SERVICE_JWT_SECRET=${SECRET_INTERNAL_SERVICE_JWT_SECRET}:latest"
CHAT_SECRET_ENV_NAMES="DB_URL,DB_USERNAME,DB_PASSWORD,SUPABASE_URL,SUPABASE_ANON_KEY,SUPABASE_SERVICE_ROLE_KEY,SUPABASE_JWT_SECRET,JWT_SECRET,INTERNAL_SERVICE_JWT_SECRET"

if [[ "$DEPLOY_BACKEND" == "true" ]]; then
    BACKEND_SECRET_BINDINGS="DB_URL=${SECRET_DB_URL}:latest,DB_USERNAME=${SECRET_DB_USERNAME}:latest,DB_PASSWORD=${SECRET_DB_PASSWORD}:latest,SUPABASE_URL=${SECRET_SUPABASE_URL}:latest,SUPABASE_ANON_KEY=${SECRET_SUPABASE_ANON_KEY}:latest,SUPABASE_SERVICE_ROLE_KEY=${SECRET_SUPABASE_SERVICE_ROLE_KEY}:latest,SUPABASE_JWT_SECRET=${SECRET_SUPABASE_JWT_SECRET}:latest,JWT_SECRET=${SECRET_JWT_SECRET}:latest,INTERNAL_SERVICE_JWT_SECRET=${SECRET_INTERNAL_SERVICE_JWT_SECRET}:latest,GOOGLE_CLIENT_ID=${SECRET_GOOGLE_CLIENT_ID}:latest,GOOGLE_CLIENT_SECRET=${SECRET_GOOGLE_CLIENT_SECRET}:latest,PII_ENCRYPTION_KEY=${SECRET_PII_ENCRYPTION_KEY}:latest,MAIL_USERNAME=${SECRET_MAIL_USERNAME}:latest,MAIL_PASSWORD=${SECRET_MAIL_PASSWORD}:latest"
    BACKEND_SECRET_ENV_NAMES="DB_URL,DB_USERNAME,DB_PASSWORD,SUPABASE_URL,SUPABASE_ANON_KEY,SUPABASE_SERVICE_ROLE_KEY,SUPABASE_JWT_SECRET,JWT_SECRET,INTERNAL_SERVICE_JWT_SECRET,GOOGLE_CLIENT_ID,GOOGLE_CLIENT_SECRET,PII_ENCRYPTION_KEY,MAIL_USERNAME,MAIL_PASSWORD"
fi

# -----------------------------------------------------------------------------
# Summary
# -----------------------------------------------------------------------------
echo "🚀 Deploying Rentoza Platform"
echo "=============================================="
echo "   Environment:        $ENVIRONMENT/$DEPLOY_PROFILE"
echo "   Spring profile:     $SPRING_PROFILE"
echo "   Image tag:          $IMAGE_TAG"
echo "   Deploy chat:        $DEPLOY_CHAT"
echo "   Deploy backend:     $DEPLOY_BACKEND"
echo "   Skip build:         $SKIP_BUILD"
echo "   PAYMENT_PROVIDER:   $PAYMENT_PROVIDER"
if [[ -n "$CONSENT_POLICY_HASH" ]]; then
    echo "   CONSENT_POLICY:     $CONSENT_POLICY_VERSION (${CONSENT_POLICY_HASH:0:8}...)"
else
    echo "   CONSENT_POLICY:     $CONSENT_POLICY_VERSION (hash not set)"
fi

# -----------------------------------------------------------------------------
# Deploy order: chat first, backend second
# -----------------------------------------------------------------------------
if [[ "$DEPLOY_CHAT" == "true" ]]; then
    if [[ "$SKIP_BUILD" != "true" ]]; then
        build_image "chat-service" "$CHAT_IMAGE"
    else
        echo "⏭️ Skipping chat build. Using image: $CHAT_IMAGE"
    fi
    deploy_chat
fi

if [[ "$DEPLOY_BACKEND" == "true" ]]; then
    if [[ "$SKIP_BUILD" != "true" ]]; then
        build_image "backend" "$BACKEND_IMAGE"
    else
        echo "⏭️ Skipping backend build. Using image: $BACKEND_IMAGE"
    fi
    deploy_backend
fi

echo ""
echo "✅ Deployment complete!"
if [[ "$DEPLOY_CHAT" == "true" ]]; then
    echo "💬 Chat:    $CHAT_SERVICE_URL"
    echo "           revision: $CHAT_REVISION"
fi
if [[ "$DEPLOY_BACKEND" == "true" ]]; then
    echo "🛠️ Backend: $BACKEND_SERVICE_URL"
    echo "           revision: $BACKEND_REVISION"
fi
