# Alumex gallery server

Distributes the section catalogue to installed scanners, and keeps a record of which device holds
which version. Java 21, Spring Boot, SQLite.

It does **not** hold the signing key. Releases are signed where they are produced, and this server
only stores and serves the signed bytes — it verifies them on the way in so a bad export is caught
here rather than by every phone in the warehouse. If this machine is breached, the worst that can be
served is an old catalogue or none at all, never a forged one.

## Why SQLite

Fifteen devices syncing a few times a day is not a workload that needs a database process. SQLite is
one file next to the jar: no service, no user, no password, and a backup is a copy. It costs nothing
on a small server, where PostgreSQL would want a couple of hundred megabytes of RAM to sit idle.

WAL mode and a busy timeout are set in the connection URL, so readers never block the writer and a
phone waits rather than fails if a write is in flight.

Data access is plain SQL through `JdbcClient` rather than an ORM — three tables and a dozen
statements. That also keeps the door open: moving to PostgreSQL later would mean new DDL and a
driver, not unpicking what a mapper decided to do.

## Running it

No database to create. Copy the settings file, fill in an admin token, run:

```
copy .env.example .env
gradlew bootRun
```

`.env` needs two things: `ADMIN_TOKEN` (any long random string, required to publish) and
`GALLERY_PUBLIC_KEY` (already filled in from `keys/gallery_public_key.b64`). The schema is created
on first start and the database appears at `data/gallery.db`.

## Publishing a catalogue

The export lives in the app repository (`tools/export_gallery.py`), because it reads the trained
embedding run. Sign it here, then publish:

```bash
python tools\gallery_keys.py sign --manifest releases\v3\gallery.json
python tools\gallery_keys.py publish --dir releases\v3 --server http://localhost:8080 --token %ADMIN_TOKEN%
```

`sign` refuses to sign a manifest whose `vectors_sha256` does not match the file beside it, and the
server refuses anything it cannot verify with its public key. Versions must increase.

`releases/v2/` is already exported and signed, ready to publish as a test of the update path — the
app ships with v1, so syncing should move it to v2.

## The keys

```bash
python tools\gallery_keys.py keygen --out keys
```

Run once, ever. `keys/` is git-ignored; **the private key is not recoverable and replacing it
invalidates every installed app**, because the public half is compiled into `Signing.kt`. Back it up
offline.

## API

For the app:

| | |
|---|---|
| `POST /api/v1/sync` | Registers the device, records what it holds, returns the newest release |
| `GET /api/v1/gallery/{version}/manifest` | `gallery.json`, byte for byte as signed |
| `GET /api/v1/gallery/{version}/signature` | the ECDSA P-256 signature over those bytes |
| `GET /api/v1/gallery/{version}/vectors` | `gallery.f32` |
| `GET /api/v1/gallery/latest` | metadata only |

The manifest is served as stored and never re-serialised: re-encoding the same JSON would change
the bytes the signature covers, and every device would rightly refuse it.

For an administrator, with `X-Admin-Token`:

| | |
|---|---|
| `POST /api/v1/admin/gallery` | multipart `manifest`, `signature`, `vectors`, optional `notes` |
| `GET /api/v1/admin/devices` | every device, newest contact first |
| `GET /api/v1/admin/releases` | what is published |

## What it stores

* `gallery_release` — the signed releases, manifest and vectors as bytes. 614 KB per release at 600
  sections, comfortably inside SQLite's 1 GB per-value limit, and keeping them here means the whole
  server state is one file to back up.
* `client_device` — one row per install: the id the app generates, what phone it is, what version it
  holds, when it was last seen and last updated. There is no login and no user identity.
* `sync_event` — who asked for what, and when. This is the record of which devices are running the
  app, which is what any later licensing conversation would need.

## Not built yet

Licensing — expiry, grace periods, seat limits, device revocation — was discussed but deliberately
left out of this stage. The schema has room for it (`client_device.label`, the event log), and the
signing infrastructure it would need is already here.
