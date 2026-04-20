# Rentoza Secrets Management Guide

## Google Cloud Secret Manager Setup

### Required Secrets

Create these secrets in Google Secret Manager before deploying:

```bash
# Database credentials
gcloud secrets create rentoza-db-url --replication-policy="automatic"
gcloud secrets create rentoza-db-username --replication-policy="automatic"
gcloud secrets create rentoza-db-password --replication-policy="automatic"

# Supabase credentials
gcloud secrets create rentoza-supabase-url --replication-policy="automatic"
gcloud secrets create rentoza-supabase-anon-key --replication-policy="automatic"
gcloud secrets create rentoza-supabase-service-role --replication-policy="automatic"
gcloud secrets create rentoza-supabase-jwt-secret --replication-policy="automatic"

# JWT secrets
gcloud secrets create rentoza-jwt-secret --replication-policy="automatic"
gcloud secrets create rentoza-internal-jwt-secret --replication-policy="automatic"

# Google OAuth
gcloud secrets create rentoza-google-client-id --replication-policy="automatic"
gcloud secrets create rentoza-google-client-secret --replication-policy="automatic"

# PII Encryption
gcloud secrets create rentoza-pii-encryption-key --replication-policy="automatic"
```

### Adding Secret Values

```bash
# Example: Add database password
echo -n "your-secure-password" | gcloud secrets versions add rentoza-db-password --data-file=-

# Example: Add JWT secret (generate strong random key)
openssl rand -base64 64 | gcloud secrets versions add rentoza-jwt-secret --data-file=-
```

### Grant Cloud Run Access

```bash
# Get Cloud Run service account
SERVICE_ACCOUNT=$(gcloud iam service-accounts list --filter="displayName:Compute Engine default" --format="value(email)")

# Grant access to all secrets
for SECRET in rentoza-db-url rentoza-db-username rentoza-db-password \
              rentoza-supabase-url rentoza-supabase-anon-key rentoza-supabase-service-role \
              rentoza-supabase-jwt-secret rentoza-jwt-secret rentoza-internal-jwt-secret \
              rentoza-google-client-id rentoza-google-client-secret rentoza-pii-encryption-key; do
    gcloud secrets add-iam-policy-binding $SECRET \
        --member="serviceAccount:$SERVICE_ACCOUNT" \
        --role="roles/secretmanager.secretAccessor"
done
```

## Secret Rotation Schedule

| Secret | Rotation Frequency | Notes |
|--------|-------------------|-------|
| DB Password | Quarterly | Coordinate with Supabase |
| JWT Secret | Quarterly | Will invalidate all sessions |
| Internal JWT Secret | Quarterly | Coordinate backend + chat-service |
| Google Client Secret | Annually | Or if compromised |
| PII Encryption Key | Never (unless compromised) | Would require re-encrypting all PII data |

## Emergency: Secret Compromise Response

1. **Immediately** rotate compromised secret in Secret Manager
2. Redeploy affected services
3. If JWT secret compromised: all user sessions invalidated (acceptable)
4. If DB password compromised: rotate in Supabase first, then Secret Manager
5. If PII key compromised: security incident - engage data protection officer

## Local Development

For local development, create a `.env.local` file (gitignored):

```bash
# .env.local - NEVER COMMIT THIS FILE
DB_URL=jdbc:postgresql://localhost:5432/rentoza_dev
DB_USERNAME=postgres
DB_PASSWORD=local_dev_password
JWT_SECRET=local_dev_jwt_secret_at_least_64_chars_long_for_security_requirements
# ... etc
```

Load with: `source .env.local && ./mvnw spring-boot:run`
