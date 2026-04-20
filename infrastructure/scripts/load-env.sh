#!/bin/bash
# Script to properly load .env.local variables

while IFS='=' read -r key value; do
    # Skip comments and empty lines
    [[ $key =~ ^#.*$ ]] && continue
    [[ -z "$key" ]] && continue
    
    # Remove leading/trailing whitespace
    key=$(echo "$key" | xargs)
    value=$(echo "$value" | xargs)
    
    # Export the variable
    export "$key=$value"
done < /Users/kljaja01/Developer/Rentoza/.env.local

# Verify some key variables loaded
echo "✓ Environment loaded"
echo "  DB_PASSWORD: ${DB_PASSWORD}"
echo "  JWT_SECRET: ${JWT_SECRET:0:30}..."
echo "  PII_ENCRYPTION_KEY: ${PII_ENCRYPTION_KEY:0:20}..."
echo "  PAYMENT_PROVIDER: ${PAYMENT_PROVIDER}"
