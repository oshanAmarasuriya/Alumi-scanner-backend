package lk.alumex.galleryserver.domain;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * All the SQL, in one place and written out.
 *
 * <p>Three tables and a dozen statements do not need an object-relational mapper. Explicit SQL also
 * keeps the door open: moving to PostgreSQL later means new DDL and a driver, not unpicking what a
 * mapper decided to do.
 *
 * <p>Instants are stored as ISO-8601 text. SQLite has no date type, and text in that format sorts
 * and compares correctly while staying readable to anyone who opens the file with a SQL browser.
 */
@Repository
public class GalleryDao {

    private final JdbcClient db;

    public GalleryDao(JdbcClient db) {
        this.db = db;
    }

    // ---------------------------------------------------------------- releases

    public Optional<GalleryRelease> findLatestRelease() {
        return db.sql("select * from gallery_release order by version desc limit 1")
                .query(GalleryDao::toRelease)
                .optional();
    }

    public Optional<GalleryRelease> findRelease(int version) {
        return db.sql("select * from gallery_release where version = ?")
                .param(version)
                .query(GalleryDao::toRelease)
                .optional();
    }

    public boolean releaseExists(int version) {
        Integer found = db.sql("select 1 from gallery_release where version = ?")
                .param(version)
                .query(Integer.class)
                .optional()
                .orElse(null);
        return found != null;
    }

    public void insertRelease(GalleryRelease release) {
        db.sql("""
                insert into gallery_release
                    (version, model_id, embedding_dim, section_count, manifest, manifest_sha256,
                     signature, vectors, vectors_sha256, created_at, published_at, notes)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .params(release.version(), release.modelId(), release.embeddingDim(), release.sectionCount(),
                        release.manifest(), release.manifestSha256(), release.signature(), release.vectors(),
                        release.vectorsSha256(), release.createdAt().toString(),
                        release.publishedAt().toString(), release.notes())
                .update();
    }

    public List<GalleryRelease> listReleases() {
        return db.sql("select * from gallery_release order by version desc")
                .query(GalleryDao::toRelease)
                .list();
    }

    // ---------------------------------------------------------------- devices

    public Optional<ClientDevice> findDevice(String deviceId) {
        return db.sql("select * from client_device where device_id = ?")
                .param(deviceId)
                .query(GalleryDao::toDevice)
                .optional();
    }

    public List<ClientDevice> listDevices() {
        return db.sql("select * from client_device order by last_seen_at desc")
                .query(GalleryDao::toDevice)
                .list();
    }

    /**
     * Records a device being seen, creating its row the first time.
     *
     * <p>An unknown device is registered rather than refused: there is no login yet, and a device
     * that cannot check in is a device nobody can see.
     */
    public void recordSeen(String deviceId, String appVersion, String androidRelease, String deviceModel,
                           Integer galleryVersion, String ip, Instant at) {
        String now = at.toString();
        int updated = db.sql("""
                update client_device
                   set last_seen_at = ?, app_version = ?, android_release = ?, device_model = ?,
                       last_ip = ?, gallery_version = coalesce(?, gallery_version)
                 where device_id = ?
                """)
                .params(now, appVersion, androidRelease, deviceModel, ip, galleryVersion, deviceId)
                .update();
        if (updated == 0) {
            db.sql("""
                    insert into client_device
                        (device_id, first_seen_at, last_seen_at, app_version, android_release,
                         device_model, gallery_version, sync_count, last_ip)
                    values (?, ?, ?, ?, ?, ?, ?, 0, ?)
                    """)
                    .params(deviceId, now, now, appVersion, androidRelease, deviceModel, galleryVersion, ip)
                    .update();
        }
    }

    /** Records that a device actually took a release, which is the event that matters. */
    public void recordDownload(String deviceId, int version, String ip, Instant at) {
        String now = at.toString();
        int updated = db.sql("""
                update client_device
                   set last_seen_at = ?, last_sync_at = ?, gallery_version = ?, sync_count = sync_count + 1,
                       last_ip = coalesce(?, last_ip)
                 where device_id = ?
                """)
                .params(now, now, version, ip, deviceId)
                .update();
        if (updated == 0) {
            // A device that downloads without checking in first still belongs in the list.
            db.sql("""
                    insert into client_device
                        (device_id, first_seen_at, last_seen_at, last_sync_at, gallery_version, sync_count, last_ip)
                    values (?, ?, ?, ?, ?, 1, ?)
                    """)
                    .params(deviceId, now, now, now, version, ip)
                    .update();
        }
    }

    // ---------------------------------------------------------------- audit

    public void recordEvent(String deviceId, String action, Integer from, Integer to,
                            String ip, String userAgent, Instant at) {
        db.sql("""
                insert into sync_event (device_id, at, action, from_version, to_version, ip, user_agent)
                values (?, ?, ?, ?, ?, ?, ?)
                """)
                .params(deviceId, at.toString(), action, from, to, ip, userAgent)
                .update();
    }

    // ---------------------------------------------------------------- mapping

    private static GalleryRelease toRelease(ResultSet rs, int row) throws SQLException {
        return new GalleryRelease(
                rs.getInt("version"),
                rs.getString("model_id"),
                rs.getInt("embedding_dim"),
                rs.getInt("section_count"),
                rs.getBytes("manifest"),
                rs.getString("manifest_sha256"),
                rs.getBytes("signature"),
                rs.getBytes("vectors"),
                rs.getString("vectors_sha256"),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("published_at")),
                rs.getString("notes"));
    }

    private static ClientDevice toDevice(ResultSet rs, int row) throws SQLException {
        return new ClientDevice(
                rs.getString("device_id"),
                rs.getString("label"),
                instant(rs, "first_seen_at"),
                instant(rs, "last_seen_at"),
                instant(rs, "last_sync_at"),
                (Integer) rs.getObject("gallery_version"),
                rs.getString("app_version"),
                rs.getString("android_release"),
                rs.getString("device_model"),
                rs.getInt("sync_count"),
                rs.getString("last_ip"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        return value == null ? null : Instant.parse(value);
    }
}
