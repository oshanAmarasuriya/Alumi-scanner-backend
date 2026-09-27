-- The catalogue releases the app downloads, and the devices that download them.
--
-- Written to run on every start: `if not exists` everywhere, so it creates the schema once and is a
-- no-op afterwards. Three tables maintained by one person do not need a migration tool yet; when
-- the schema starts changing under a deployed server, that is the moment to add one.
--
-- A release is stored exactly as it was signed. The manifest is kept as bytes rather than parsed
-- JSON because the signature covers those bytes: re-serialising it, even identically in meaning,
-- would change them and every device would rightly refuse the result.

create table if not exists gallery_release (
    version         integer primary key,
    model_id        text    not null,
    embedding_dim   integer not null,
    section_count   integer not null,

    manifest        blob    not null,   -- gallery.json, byte for byte as signed
    manifest_sha256 text    not null,
    signature       blob    not null,   -- ECDSA P-256 over the manifest bytes, DER
    vectors         blob    not null,   -- gallery.f32, count x dim float32 little-endian
    vectors_sha256  text    not null,   -- also stated inside the manifest, so signed too

    created_at      text    not null,   -- ISO-8601 UTC; sorts correctly as text
    published_at    text    not null,
    notes           text
);

-- One row per install. Identifies a device, never a person: there is no account and no login.
create table if not exists client_device (
    device_id       text primary key,
    label           text,               -- set by an administrator: "warehouse 2, phone 3"
    first_seen_at   text not null,
    last_seen_at    text not null,
    last_sync_at    text,               -- last time it actually took a new release
    gallery_version integer references gallery_release (version),
    app_version     text,
    android_release text,
    device_model    text,
    sync_count      integer not null default 0,
    last_ip         text
);

-- Which device asked for what, and when. The record of who is running the app.
create table if not exists sync_event (
    id           integer primary key autoincrement,
    device_id    text not null references client_device (device_id),
    at           text not null,
    action       text not null,         -- check_in | download
    from_version integer,
    to_version   integer,
    ip           text,
    user_agent   text
);

create index if not exists sync_event_device_at_idx on sync_event (device_id, at desc);
create index if not exists sync_event_at_idx on sync_event (at desc);
