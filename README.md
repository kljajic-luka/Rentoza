# Rentoza

P2P car rental platform built for the Serbian market.

[![Backend](https://img.shields.io/badge/backend-Spring%20Boot%203.5-green)](apps/backend)
[![Frontend](https://img.shields.io/badge/frontend-Angular%2020-red)](apps/frontend)
[![Chat](https://img.shields.io/badge/chat-WebSocket-blue)](apps/chat-service)

## For Employers: Quick Overview

Rentoza is a full-stack product focused on real production needs:

- architecture split into frontend, backend API, and a dedicated chat service
- cloud deployment and operational scripts for reliable delivery
- security discipline (no secrets in source control, automated secret scanning)
- business logic for bookings, payments, user verification, and messaging

## System Architecture

### 1. Frontend Application

- Angular 20 PWA in [apps/frontend](apps/frontend)
- focused UX flows for guest, host, and admin roles
- integrated with backend API and chat service

### 2. Backend API

- Spring Boot 3.5 (Java 21) in [apps/backend](apps/backend)
- core business logic: auth, bookings, payments, and compliance
- migrations, tests, and operational controls

### 3. Chat Service

- separate Spring Boot service in [apps/chat-service](apps/chat-service)
- real-time communication for booking-related workflows
- isolated responsibilities for easier scaling and risk containment

### 4. Infrastructure and Deployment

- scripts and configuration in [infrastructure](infrastructure)
- deployment workflows for Cloud Run and Firebase
- strict separation of runtime configuration and secrets

## Product Impact and Value

Rentoza is built as a startup-ready foundation:

- digitized P2P rental flow from search to trip completion
- clear domain/service separation for faster feature development
- growth-ready through production and security practices

## Production Practices

- Secrets are not stored in source code and are handled via env/secret manager patterns
- Automated secret scan on every push and pull request: [secret-scan.yml](.github/workflows/secret-scan.yml)
- Main branch guardrail recommendations: [GITHUB_BRANCH_PROTECTION.md](docs/deployment/GITHUB_BRANCH_PROTECTION.md)
- Documentation and deployment workflows are versioned with the codebase

## Local Start

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

## Deployment Examples

```bash
# Backend deploy
./infrastructure/gcp/deploy-backend-secure.sh

# Frontend deploy
cd apps/frontend && firebase deploy --only hosting
```

## Repository Structure

```text
rentoza/
|-- apps/                  # deployable applications
|   |-- backend/           # Spring Boot API
|   |-- frontend/          # Angular PWA
|   `-- chat-service/      # real-time chat service
|-- docs/                  # architecture, deployment, runbook
|-- infrastructure/        # cloud and ops scripts
`-- scripts/               # local helper scripts
```

## Documentation

- [Architecture](docs/architecture)
- [Deployment](docs/deployment)
- [Runbook](docs/runbooks)

## Live Environment

- Production: https://rentoza.rs
- API: https://api.rentoza.rs

---

Rentoza is an actively developed project with a clear focus on production readiness, code quality, and business impact.
