# Rentoza

Platforma za P2P rentiranje automobila za trziste Srbije.

[![Backend](https://img.shields.io/badge/backend-Spring%20Boot%203.5-green)](apps/backend)
[![Frontend](https://img.shields.io/badge/frontend-Angular%2020-red)](apps/frontend)
[![Chat](https://img.shields.io/badge/chat-WebSocket-blue)](apps/chat-service)

## Za poslodavce: kratak pregled

Rentoza je full-stack proizvod sa fokusom na realne produkcione zahteve:

- arhitektura podeljena na frontend, backend API i poseban chat servis
- cloud deployment i operativne skripte za stabilan rad
- bezbednosna disciplina (tajne van repozitorijuma, CI secret scan)
- poslovna logika za rezervacije, placanja, verifikaciju korisnika i messaging

## Arhitektura sistema

### 1. Frontend aplikacija

- Angular 20 PWA u [apps/frontend](apps/frontend)
- fokus na UX tokove za gosta, hosta i admin uloge
- integracija sa backend API i chat servisom

### 2. Backend API

- Spring Boot 3.5 (Java 21) u [apps/backend](apps/backend)
- centralna poslovna logika: auth, rezervacije, placanja, compliance
- migracije, testovi i operativne kontrole

### 3. Chat servis

- zaseban Spring Boot servis u [apps/chat-service](apps/chat-service)
- real-time komunikacija i podrska za booking tokove
- odvojena odgovornost radi skaliranja i izolacije rizika

### 4. Infrastruktura i deploy

- skripte i konfiguracija u [infrastructure](infrastructure)
- ciljano za Cloud Run/Firebase tokove
- separacija runtime konfiguracije i tajni

## Uticaj i vrednost proizvoda

Rentoza je gradjena kao startup-ready osnova:

- digitalizovan P2P rental flow od pretrage do zavrsetka voznje
- jasna separacija domena i servisa za brzi razvoj novih funkcija
- priprema za rast kroz operativne i bezbednosne prakse

## Produkcione prakse

- Tajne se ne drze u kodu, vec kroz env/secret manager pristup
- Automatski secret scan na svaki push i PR: [secret-scan.yml](.github/workflows/secret-scan.yml)
- Preporuke za zastitu glavne grane: [GITHUB_BRANCH_PROTECTION.md](docs/deployment/GITHUB_BRANCH_PROTECTION.md)
- Dokumentacija i deploy tokovi su verzionisani uz kod

## Lokalni start

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

### Chat servis

```bash
cd apps/chat-service
./mvnw spring-boot:run
```

## Deploy primeri

```bash
# Backend deploy
./infrastructure/gcp/deploy-backend-secure.sh

# Frontend deploy
cd apps/frontend && firebase deploy --only hosting
```

## Struktura repozitorijuma

```text
rentoza/
├── apps/                  # aplikacije koje se deploy-uju
│   ├── backend/           # Spring Boot API
│   ├── frontend/          # Angular PWA
│   └── chat-service/      # real-time chat servis
├── docs/                  # arhitektura, deployment, runbook
├── infrastructure/        # cloud i ops skripte
└── scripts/               # pomocne lokalne skripte
```

## Dokumentacija

- [Arhitektura](docs/architecture)
- [Deployment](docs/deployment)
- [Runbook](docs/runbooks)

## Live okruzenje

- Produkcija: https://rentoza.rs
- API: https://api.rentoza.rs

---

Rentoza je aktivan projekat sa jasnim fokusom na produkcionu spremnost, kvalitet koda i poslovni uticaj.
