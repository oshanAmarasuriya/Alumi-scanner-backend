package lk.alumex.galleryserver.service

import com.fasterxml.jackson.databind.ObjectMapper
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
import lk.alumex.galleryserver.domain.ClientDevice
import lk.alumex.galleryserver.domain.ClientDeviceRepository
import lk.alumex.galleryserver.domain.GalleryRelease
import lk.alumex.galleryserver.domain.GalleryReleaseRepository
import lk.alumex.galleryserver.domain.SyncEvent
import lk.alumex.galleryserver.domain.SyncEventRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

class PublishRejected(message: String) : RuntimeException(message)

/**
 * Accepting, storing and serving signed catalogue releases.
 *
 * The server holds only the **public** key. It can check that a release was signed by whoever holds
 * the private one, and refuses anything it cannot verify — so a mistake in the export is caught at
 * publish time rather than by every phone in the warehouse. What it cannot do is produce a release,
 * which is the point: if this machine is breached, the worst an attacker can serve is an old
 * catalogue or none at all.
 */
@Service
class GalleryService(
    private val releases: GalleryReleaseRepository,
    private val devices: ClientDeviceRepository,
    private val events: SyncEventRepository,
    private val mapper: ObjectMapper,
    @Value("\${gallery.public-key:}") private val publicKeyBase64: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun latest(): GalleryRelease? = releases.findLatest()

    fun release(version: Int): GalleryRelease? = releases.findById(version).orElse(null)

    /**
     * Records a device asking what is available, and returns what it should do next.
     *
     * Unknown devices are registered rather than refused: there is no login yet, and a device that
     * cannot check in is a device nobody can see.
     */
    @Transactional
    fun checkIn(
        deviceId: String,
        appVersion: String?,
        androidRelease: String?,
        deviceModel: String?,
        knownVersion: Int?,
        ip: String?,
        userAgent: String?,
    ): GalleryRelease? {
        val now = Instant.now()
        val device = devices.findById(deviceId).orElseGet {
            log.info("first check-in from device {}", deviceId)
            ClientDevice(deviceId = deviceId, firstSeenAt = now)
        }
        device.lastSeenAt = now
        device.appVersion = appVersion
        device.androidRelease = androidRelease
        device.deviceModel = deviceModel
        device.lastIp = ip
        // What the device says it holds. Trusted only as a report, never as a permission.
        if (knownVersion != null && releases.existsById(knownVersion)) device.galleryVersion = knownVersion
        devices.save(device)

        val latest = latest()
        events.save(
            SyncEvent(
                deviceId = deviceId, at = now, action = "check_in",
                fromVersion = knownVersion, toVersion = latest?.version, ip = ip, userAgent = userAgent,
            )
        )
        return latest
    }

    /** Records that a device actually took a release. */
    @Transactional
    fun recordDownload(deviceId: String, version: Int, ip: String?, userAgent: String?) {
        val now = Instant.now()
        devices.findById(deviceId).ifPresent { device ->
            device.lastSeenAt = now
            device.lastSyncAt = now
            device.galleryVersion = version
            device.syncCount += 1
            devices.save(device)
        }
        events.save(
            SyncEvent(deviceId = deviceId, at = now, action = "download", toVersion = version,
                ip = ip, userAgent = userAgent)
        )
    }

    /**
     * Publishes a release, after checking everything that can be checked here.
     *
     * Order matters: the signature is verified over the manifest bytes exactly as received, and the
     * vector checksum is compared against the value *inside* that manifest. Since the signature
     * covers the manifest, and the manifest states the vectors' hash, one signature protects both
     * files.
     */
    @Transactional
    fun publish(manifest: ByteArray, signature: ByteArray, vectors: ByteArray, notes: String?): GalleryRelease {
        verifySignature(manifest, signature)

        val json = try {
            mapper.readTree(manifest)
        } catch (e: Exception) {
            throw PublishRejected("the manifest is not valid JSON: ${e.message}")
        }

        val version = json.path("version").asInt(-1)
        if (version < 1) throw PublishRejected("the manifest has no usable version")
        val modelId = json.path("model_id").asText("")
        if (modelId.isBlank()) throw PublishRejected("the manifest names no model_id")
        val count = json.path("count").asInt(-1)
        val dim = json.path("embedding_dim").asInt(-1)
        if (count < 1 || dim < 1) throw PublishRejected("the manifest has no usable count or embedding_dim")

        val expectedBytes = count * dim * 4
        if (vectors.size != expectedBytes) {
            throw PublishRejected("expected $expectedBytes bytes of vectors for $count x $dim, got ${vectors.size}")
        }
        val vectorsHash = sha256(vectors)
        val declared = json.path("vectors_sha256").asText("")
        if (declared.isNotEmpty() && declared != vectorsHash) {
            throw PublishRejected("the vectors do not match the checksum in the manifest")
        }

        releases.findById(version).ifPresent {
            throw PublishRejected("version $version is already published; export a new version instead")
        }
        val latest = latest()
        if (latest != null && version <= latest.version) {
            throw PublishRejected("version $version is not newer than the published $latest.version")
        }
        if (latest != null && latest.modelId != modelId) {
            // Not fatal, but it means every device must ship a new app before this is usable.
            log.warn("release {} changes the model from {} to {}", version, latest.modelId, modelId)
        }

        val created = json.path("created").asText("").takeIf { it.isNotEmpty() }
            ?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.now()

        val saved = releases.save(
            GalleryRelease(
                version = version,
                modelId = modelId,
                embeddingDim = dim,
                sectionCount = count,
                manifest = manifest,
                manifestSha256 = sha256(manifest),
                signature = signature,
                vectors = vectors,
                vectorsSha256 = vectorsHash,
                createdAt = created,
                publishedAt = Instant.now(),
                notes = notes,
            )
        )
        log.info("published gallery v{}: {} sections, model {}", version, count, modelId)
        return saved
    }

    private fun verifySignature(manifest: ByteArray, signature: ByteArray) {
        if (publicKeyBase64.isBlank()) {
            throw PublishRejected("no GALLERY_PUBLIC_KEY is configured, so nothing can be verified")
        }
        val key = try {
            KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64.trim())))
        } catch (e: Exception) {
            throw PublishRejected("GALLERY_PUBLIC_KEY is not a usable P-256 public key: ${e.message}")
        }
        val ok = Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(manifest)
            verify(signature)
        }
        if (!ok) throw PublishRejected("the signature does not match the manifest and this server's public key")
    }

    fun devices(): List<ClientDevice> = devices.findAll().sortedByDescending { it.lastSeenAt }

    companion object {
        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
