-- V103: Dodavanje kolone za pracenje SLA alertova za sporove
ALTER TABLE damage_claims ADD COLUMN last_sla_alert_sent_at TIMESTAMP;
