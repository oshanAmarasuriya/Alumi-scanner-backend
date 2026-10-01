# CLAUDE.md — Alumex Gallery Server (Backend)

## What this is

Spring Boot (Java 21) REST API that distributes signed product catalogues and packing guide PDFs to the Alumex Profile Scanner Android app. Uses SQLite for storage.

## Run locally

```bash
cp .env.example .env
# Fill in ADMIN_TOKEN and GALLERY_PUBLIC_KEY in .env
./gradlew bootRun
```

Starts on port 8080. Creates `data/gallery.db` automatically.

## Deployment

Deployed on Render: https://alumi-scanner-backend.onrender.com

Render free tier uses ephemeral storage — the SQLite DB resets on every deploy/restart. Uploaded packing guide PDFs and gallery releases are lost. Consider persistent disk or external DB for production.

## API endpoints

### Public (no auth)

- `POST /api/v1/sync` — device check-in
- `GET /api/v1/gallery/latest` — latest release metadata
- `GET /api/v1/gallery/{version}/manifest` — download manifest
- `GET /api/v1/gallery/{version}/signature` — download signature
- `GET /api/v1/gallery/{version}/vectors` — download vectors
- `GET /api/v1/guides/index` — list available packing guides (sectionCode + sha256)
- `GET /api/v1/guides/{code}` — download PDF for a section

### Admin (requires `X-Admin-Token` header)

- `POST /api/v1/admin/gallery` — publish signed release (multipart: manifest, signature, vectors)
- `POST /api/v1/admin/guides` — upload packing guide PDF (multipart: `code` string + `pdf` file)
- `GET /api/v1/admin/devices` — list registered devices
- `GET /api/v1/admin/releases` — list published releases

### Upload a packing guide

```bash
curl -X POST 'https://alumi-scanner-backend.onrender.com/api/v1/admin/guides' \
  -H 'X-Admin-Token: <token>' \
  -F 'code=AL-963' \
  -F 'pdf=@/path/to/AL-963.pdf'
```

**Not JSON** — must be multipart form data (`-F`, not `-d`).

## Project structure

- `api/AdminController.java` — admin endpoints (publish, upload guides, list devices)
- `api/SyncController.java` — public sync endpoints (device check-in, guide download)
- `domain/GalleryDao.java` — data access (raw SQL via JdbcClient, no ORM)
- `domain/PackingGuide.java` — packing guide entity (sectionCode, pdf blob, sha256)
- `domain/GalleryRelease.java` — signed catalogue release entity
- `service/GalleryService.java` — business logic (signature verification, guide upload)
- `resources/schema.sql` — SQLite schema (idempotent, runs on every start)
- `resources/application.yml` — configuration
- `tools/gallery_keys.py` — key generation, signing, and publishing utility

## Key design decisions

- SQLite instead of PostgreSQL — 15 devices, few syncs/day, backup is a file copy
- Server holds only the public key — cannot forge releases, only verify
- PDFs stored as BLOBs in SQLite (no filesystem storage)
- ECDSA P-256 signatures (not Ed25519) for Android 8+ compatibility
- Constant-time admin token comparison to prevent timing attacks

## Companion app

Android app repo: `Alum-scanner-app`. See its CLAUDE.md for build instructions.
