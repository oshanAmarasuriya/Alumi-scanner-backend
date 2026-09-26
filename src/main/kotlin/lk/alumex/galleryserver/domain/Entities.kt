package lk.alumex.galleryserver.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** One signed catalogue release, stored exactly as it was signed. */
@Entity
@Table(name = "gallery_release")
class GalleryRelease(
    @Id
    var version: Int = 0,

    @Column(name = "model_id", nullable = false)
    var modelId: String = "",

    @Column(name = "embedding_dim", nullable = false)
    var embeddingDim: Int = 0,

    @Column(name = "section_count", nullable = false)
    var sectionCount: Int = 0,

    @Column(nullable = false)
    var manifest: ByteArray = ByteArray(0),

    @Column(name = "manifest_sha256", nullable = false)
    var manifestSha256: String = "",

    @Column(nullable = false)
    var signature: ByteArray = ByteArray(0),

    @Column(nullable = false)
    var vectors: ByteArray = ByteArray(0),

    @Column(name = "vectors_sha256", nullable = false)
    var vectorsSha256: String = "",

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.EPOCH,

    @Column(name = "published_at", nullable = false)
    var publishedAt: Instant = Instant.now(),

    var notes: String? = null,
)

/** An install of the app. Identifies a device, never a person: there is no account and no login. */
@Entity
@Table(name = "client_device")
class ClientDevice(
    @Id
    @Column(name = "device_id")
    var deviceId: String = "",

    var label: String? = null,

    @Column(name = "first_seen_at", nullable = false)
    var firstSeenAt: Instant = Instant.now(),

    @Column(name = "last_seen_at", nullable = false)
    var lastSeenAt: Instant = Instant.now(),

    @Column(name = "last_sync_at")
    var lastSyncAt: Instant? = null,

    @Column(name = "gallery_version")
    var galleryVersion: Int? = null,

    @Column(name = "app_version")
    var appVersion: String? = null,

    @Column(name = "android_release")
    var androidRelease: String? = null,

    @Column(name = "device_model")
    var deviceModel: String? = null,

    @Column(name = "sync_count", nullable = false)
    var syncCount: Int = 0,

    @Column(name = "last_ip")
    var lastIp: String? = null,
)

/** Audit trail: which device asked for what, and when. */
@Entity
@Table(name = "sync_event")
class SyncEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,

    @Column(name = "device_id", nullable = false)
    var deviceId: String = "",

    @Column(nullable = false)
    var at: Instant = Instant.now(),

    @Column(nullable = false)
    var action: String = "",

    @Column(name = "from_version")
    var fromVersion: Int? = null,

    @Column(name = "to_version")
    var toVersion: Int? = null,

    var ip: String? = null,

    @Column(name = "user_agent")
    var userAgent: String? = null,
)
