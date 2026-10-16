# ADR-0002: Service map — 10 deployables + 1 Python

- **Status:** Accepted · **Date:** 2026-09-21 · **Phase:** P00

## Decision

| Service | Port | Owns | DB | Replaces (old repo) |
|---|---|---|---|---|
| gateway | 8080 | Edge: routing, JWT pre-check, rate limit, request-id, header hygiene | — (Redis) | gateway-service |
| identity | 8081 | Accounts, credentials, sessions, refresh tokens, MFA, JWKS, admins, RBAC | identity | auth-service, admin-monolith (auth) |
| ledger | 8082 | Expenses, categories (ltree), merchants (pg_trgm), daily totals, calendar, imports | ledger | expense-service |
| catalog | 8083 | Issuers, card products, versioned reward rules, offers, ingestion pipeline | catalog | catalog-service, recalc-worker (catalog) |
| portfolio | 8084 | User cards, statements, card transactions, EMI tracker, reward points, calc engines | portfolio | card-portfolio-service |
| planning | 8085 | Budgets, spend projections, liabilities, sandbox scenarios, alerts | planning | budget-service |
| reco | 8086 | Spend profile, catalog projection, scoring, wallet optimizer, best-card, SSE | reco | recommendation-service, recalc-worker |
| billing | 8087 | Plans, subscriptions, entitlements, payments adapter, invoices, webhooks | billing | subscription-service |
| commerce | 8088 | Marketplace deals, affiliate clicks/conversions, card applications, ads | commerce | marketplace-service, ads-service |
| audit | 8089 | Hash-chained audit log, activity analytics, notifications, privacy saga | audit | activity-service, document-retention-worker |
| ai-parser (Python) | — | Statement PDF → transactions, async via Kafka | — | ai-parser-service |

## Rationale

The old repo had 17 modules. Each new service is one bounded context with a single owner of its data.
Workers (recalc, retention, outbox relay) run as profile-enabled components inside the service that owns them.
This gives fewer network hops and no distributed transactions.

## Rules carried by every service

- One database per service, and 5 roles (`_owner`, `_app`, `_admin`, `_relay`, `_readonly`) created by infra, never by Flyway.
- No cross-database foreign keys. References are UUIDs.
- Sync REST only for user-facing reads. Everything else goes through Kafka, via outbox and inbox.
- Package layout `in.dexter.<svc>.{api,app,domain,infra,config}`, enforced by ArchUnit from P02.
