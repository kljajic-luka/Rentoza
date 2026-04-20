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

# Deploy STAGING frontend preview (credentialless mock payments)
cd apps/frontend && npm run deploy:staging
```

## 📚 Documentation

- [Architecture Overview](docs/architecture/)
- [Deployment Guide](docs/deployment/)
- [On-Call Runbook](docs/runbooks/)

## 🔗 Live Platform

- **Production:** https://rentoza.rs
- **API:** https://api.rentoza.rs

---

