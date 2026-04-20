#!/bin/bash
# =============================================================================
# RENTOZA PROJECT RESTRUCTURE SCRIPT
# =============================================================================
#
# ⚠️ WARNING: Only run this script AFTER 24 hours of production stability!
#
# This script restructures the project to follow industry-standard layout:
#   - apps/backend (was: Rentoza)
#   - apps/frontend (was: rentoza-frontend)
#   - apps/chat-service (was: chat-service)
#   - infrastructure/ (deployment scripts)
#
# Pre-requisites:
#   1. 24+ hours since last production deployment
#   2. No active incidents
#   3. All CI/CD pipelines passing
#   4. Team notified
#
# Usage: ./scripts/restructure-project.sh
#
# Created: February 5, 2026
# =============================================================================

set -euo pipefail

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

echo -e "${BLUE}╔═══════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║         RENTOZA PROJECT RESTRUCTURE SCRIPT                    ║${NC}"
echo -e "${BLUE}╚═══════════════════════════════════════════════════════════════╝${NC}"
echo ""

# Safety checks
echo -e "${YELLOW}⚠️  SAFETY CHECKS${NC}"
echo ""

# Check for uncommitted changes
if ! git diff-index --quiet HEAD --; then
    echo -e "${RED}❌ You have uncommitted changes. Please commit or stash them first.${NC}"
    exit 1
fi
echo -e "${GREEN}✓ No uncommitted changes${NC}"

# Check current branch
CURRENT_BRANCH=$(git branch --show-current)
if [ "$CURRENT_BRANCH" != "main" ]; then
    echo -e "${YELLOW}⚠️  Not on main branch (current: $CURRENT_BRANCH)${NC}"
    read -p "Continue anyway? (y/N): " confirm
    if [ "$confirm" != "y" ]; then
        exit 1
    fi
fi
echo -e "${GREEN}✓ On branch: $CURRENT_BRANCH${NC}"

# Confirmation prompt
echo ""
echo -e "${RED}═══════════════════════════════════════════════════════════════${NC}"
echo -e "${RED}This will restructure the entire project. Make sure:${NC}"
echo -e "${RED}  1. It's been 24+ hours since last deployment${NC}"
echo -e "${RED}  2. Production is stable (error rate < 1%)${NC}"
echo -e "${RED}  3. You have time to update CI/CD if needed${NC}"
echo -e "${RED}═══════════════════════════════════════════════════════════════${NC}"
echo ""
read -p "Type 'RESTRUCTURE' to confirm: " confirm
if [ "$confirm" != "RESTRUCTURE" ]; then
    echo "Aborted."
    exit 1
fi

echo ""
echo -e "${BLUE}📁 Creating new structure...${NC}"

# Create apps directory
mkdir -p apps

# Create infrastructure directory
mkdir -p infrastructure/{gcp,firebase,docker,scripts}

# Create database directory (for future use)
mkdir -p database/{migrations,seeds}

echo -e "${GREEN}✓ Created directory structure${NC}"

echo ""
echo -e "${BLUE}📦 Moving applications...${NC}"

# Move backend (Rentoza -> apps/backend)
if [ -d "Rentoza" ]; then
    git mv Rentoza apps/backend
    echo -e "${GREEN}✓ Moved Rentoza → apps/backend${NC}"
fi

# Move frontend (rentoza-frontend -> apps/frontend)
if [ -d "rentoza-frontend" ]; then
    git mv rentoza-frontend apps/frontend
    echo -e "${GREEN}✓ Moved rentoza-frontend → apps/frontend${NC}"
fi

# Move chat-service (chat-service -> apps/chat-service)
if [ -d "chat-service" ]; then
    git mv chat-service apps/chat-service
    echo -e "${GREEN}✓ Moved chat-service → apps/chat-service${NC}"
fi

echo ""
echo -e "${BLUE}🔧 Moving infrastructure files...${NC}"

# Move deployment scripts
[ -f "deploy-backend-secure.sh" ] && git mv deploy-backend-secure.sh infrastructure/gcp/
[ -f "cloudbuild.yaml" ] && git mv cloudbuild.yaml infrastructure/gcp/
[ -f "load-env.sh" ] && git mv load-env.sh infrastructure/scripts/

# Move Firebase config from frontend
[ -f "apps/frontend/firebase.json" ] && cp apps/frontend/firebase.json infrastructure/firebase/

echo -e "${GREEN}✓ Moved infrastructure files${NC}"

echo ""
echo -e "${BLUE}📝 Updating paths in scripts...${NC}"

# Update deploy-backend-secure.sh
if [ -f "infrastructure/gcp/deploy-backend-secure.sh" ]; then
    sed -i '' 's|cd "\$(dirname "\$0")/Rentoza"|cd "$(dirname "$0")/../../apps/backend"|g' infrastructure/gcp/deploy-backend-secure.sh
    echo -e "${GREEN}✓ Updated deploy-backend-secure.sh paths${NC}"
fi

# Update cloudbuild.yaml
if [ -f "infrastructure/gcp/cloudbuild.yaml" ]; then
    sed -i '' 's|Rentoza/|apps/backend/|g' infrastructure/gcp/cloudbuild.yaml
    sed -i '' 's|chat-service/|apps/chat-service/|g' infrastructure/gcp/cloudbuild.yaml
    # Fix min-instances from 0 to 1 (cold start fix)
    sed -i '' "s|--min-instances'$|--min-instances|g" infrastructure/gcp/cloudbuild.yaml
    sed -i '' "s|'0'$|'1'|" infrastructure/gcp/cloudbuild.yaml
    echo -e "${GREEN}✓ Updated cloudbuild.yaml paths and min-instances${NC}"
fi

# Update GitHub Actions Lighthouse CI
if [ -f ".github/workflows/lighthouse-ci.yml" ]; then
    sed -i '' 's|rentoza-frontend|apps/frontend|g' .github/workflows/lighthouse-ci.yml
    echo -e "${GREEN}✓ Updated lighthouse-ci.yml paths${NC}"
fi

# Update load-env.sh for new location
if [ -f "infrastructure/scripts/load-env.sh" ]; then
    sed -i '' 's|/Users/kljaja01/Developer/Rentoza/.env.local|$(dirname "$0")/../../.env.local|g' infrastructure/scripts/load-env.sh
    echo -e "${GREEN}✓ Updated load-env.sh env file path${NC}"
fi

echo ""
echo -e "${BLUE}📄 Creating root README...${NC}"

cat > README.md << 'EOF'
# Rentoza

**P2P Car Rental Platform for the Serbian Market**

[![Backend](https://img.shields.io/badge/backend-Spring%20Boot%203.5-green)](apps/backend)
[![Frontend](https://img.shields.io/badge/frontend-Angular%2020-red)](apps/frontend)
[![Chat](https://img.shields.io/badge/chat-WebSocket-blue)](apps/chat-service)

## 🏗️ Project Structure

```
rentoza/
├── apps/                    # Deployable applications
│   ├── backend/             # Spring Boot API (Java 21)
│   ├── frontend/            # Angular 20 PWA
│   └── chat-service/        # WebSocket chat service
├── infrastructure/          # DevOps & deployment
│   ├── gcp/                 # Google Cloud Run configs
│   ├── firebase/            # Firebase Hosting
│   └── scripts/             # Utility scripts
├── database/                # Database files
│   ├── migrations/          # SQL migrations
│   └── seeds/               # Test data
├── docs/                    # Documentation
│   ├── architecture/        # System design
│   ├── deployment/          # Deploy guides
│   ├── features/            # Feature docs
│   └── runbooks/            # On-call guides
└── archive/                 # Historical docs
```

## 🚀 Quick Start

### Backend
```bash
cd apps/backend
./mvnw spring-boot:run
```

### Frontend
```bash
cd apps/frontend
npm install
npm start
```

### Chat Service
```bash
cd apps/chat-service
./mvnw spring-boot:run
```

## 📦 Deployment

```bash
# Deploy backend to Cloud Run
./infrastructure/gcp/deploy-backend-secure.sh

# Deploy frontend to Firebase
cd apps/frontend && firebase deploy --only hosting
```

## 📚 Documentation

- [Architecture Overview](docs/architecture/)
- [Deployment Guide](docs/deployment/)
- [On-Call Runbook](docs/runbooks/)

## 🔗 Live Platform

- **Production:** https://rentoza.rs
- **API:** https://api.rentoza.rs

---

Built with ❤️ for the Serbian car rental market
EOF

echo -e "${GREEN}✓ Created README.md${NC}"

echo ""
echo -e "${BLUE}📊 Final Structure:${NC}"
echo ""
find . -maxdepth 2 -type d ! -path './.git*' ! -path './node_modules*' ! -path './target*' ! -path './.idea*' | sort | head -30

echo ""
echo -e "${GREEN}╔═══════════════════════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║                    RESTRUCTURE COMPLETE!                       ║${NC}"
echo -e "${GREEN}╚═══════════════════════════════════════════════════════════════╝${NC}"
echo ""
echo -e "${YELLOW}Next steps:${NC}"
echo "  1. Review changes:       git status"
echo "  2. Test backend build:   cd apps/backend && ./mvnw clean package -DskipTests"
echo "  3. Test frontend build:  cd apps/frontend && npm run build"
echo "  4. Test chat build:      cd apps/chat-service && ./mvnw clean package -DskipTests"
echo "  5. Commit changes:       git add -A && git commit -m 'refactor: restructure project layout'"
echo "  6. Push to remote:       git push origin main"
echo ""
echo -e "${BLUE}📋 Files automatically updated by this script:${NC}"
echo "  • infrastructure/gcp/cloudbuild.yaml (paths + min-instances=1)"
echo "  • infrastructure/gcp/deploy-backend-secure.sh (backend path)"
echo "  • .github/workflows/lighthouse-ci.yml (frontend paths)"
echo "  • infrastructure/scripts/load-env.sh (env file path)"
echo ""
echo -e "${GREEN}✅ Production is UNAFFECTED - deployed images remain unchanged${NC}"
echo -e "${GREEN}✅ Next deployment will use new paths automatically${NC}"
echo ""
