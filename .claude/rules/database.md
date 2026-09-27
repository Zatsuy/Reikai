---
paths:
  - "**/*.sq"
  - "**/*.sqm"
  - "data/**"
  - "**/backup/**"
---

# Persistence

- **The fork never adds to upstream's database schema.** Upstream's migrations are numbered in one
  sequence (`data/src/main/sqldelight/tachiyomi/migrations/*.sqm`); a fork migration would take a
  number upstream's next one needs. Fork data lives in the fork's own database (a fork module's
  SQLDelight database or plain SQLite), and Yomitan's data in its own IndexedDB.
- Likewise never add a preference migration gated on upstream's `versionCode`, and never add a
  field to upstream's backup proto; the fork backs up its own data separately.
- When a merge brings upstream schema changes: `scripts/fork/gw :data:verifySqlDelightMigration`.
- Upstream's rules if a fork seam must touch their schema anyway (ask the owner first): never edit
  an existing `.sqm`, add the next number, verify against the stored schema databases, no seeding
  in migrations, index changes in their own migration.
