# Rentoza Documentation

**Last Updated:** February 5, 2026

## 📚 Documentation Structure

### Current Documentation
- [architecture/](architecture/) - System design, API specs, database schema
- [deployment/](deployment/) - Cloud Run setup, secrets, monitoring
- [features/](features/) - Feature documentation (booking, check-in, chat)
- [runbooks/](runbooks/) - On-call guides, incident response
- [adr/](adr/) - Architecture Decision Records

### Historical Documentation
See [archive-index/](archive-index/) for older development notes and audit reports.

## 🔗 Quick Links

| Document | Description |
|----------|-------------|
| [System Architecture](architecture/system-overview.md) | High-level system design |
| [Deployment Guide](deployment/cloud-run-guide.md) | Cloud Run deployment process |
| [Secrets Setup](deployment/secrets-setup.md) | Google Secret Manager configuration |
| [On-Call Runbook](runbooks/on-call-runbook.md) | Incident response procedures |

## 🏗️ Tech Stack

- **Backend:** Spring Boot 3.5.6, Java 21
- **Frontend:** Angular 20, TypeScript
- **Database:** PostgreSQL (Supabase)
- **Cloud:** Google Cloud Run, Firebase Hosting
- **Chat:** Spring Boot WebSocket microservice

## 📞 Quick Contacts

| Role | Contact |
|------|---------|
| On-call | Check PagerDuty |
| Platform Owner | @kljaja01 |

---

*For archived documentation, see [archive/](../archive/)*
