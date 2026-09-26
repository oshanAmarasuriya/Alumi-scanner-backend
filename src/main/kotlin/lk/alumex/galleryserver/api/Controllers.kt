package lk.alumex.galleryserver.api

import jakarta.servlet.http.HttpServletRequest
import java.time.Instant
import lk.alumex.galleryserver.domain.GalleryRelease
import lk.alumex.galleryserver.service.GalleryService
import lk.alumex.galleryserver.service.PublishRejected
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException

data class SyncRequest(
    val deviceId: String,
    val appVersion: String? = null,
    val androidRelease: String? = null,
    val deviceModel: String? = null,
    val galleryVersion: Int? = null,
)

data class ReleaseSummary(
    val version: Int,
    val modelId: String,
    val sectionCount: Int,
    val embeddingDim: Int,
    val manifestSha256: String,
    val vectorsSha256: String,
    val publishedAt: Instant,
)

data class SyncResponse(
    val serverTime: Instant,
    val updateAvailable: Boolean,
    val latest: ReleaseSummary?,
)

private fun GalleryRelease.summary() = ReleaseSummary(
    version = version,
    modelId = modelId,
    sectionCount = sectionCount,
    embeddingDim = embeddingDim,
    manifestSha256 = manifestSha256,
    vectorsSha256 = vectorsSha256,
    publishedAt = publishedAt,
)

private fun HttpServletRequest.clientIp(): String? =
    getHeader("X-Forwarded-For")?.substringBefore(',')?.trim() ?: remoteAddr

/**
 * What the app talks to.
 *
 * Three files make up a release and each is served as its own bytes: the manifest exactly as it was
 * signed, the signature, and the vectors. The manifest is never re-serialised on the way out —
 * re-encoding the same JSON would change the bytes the signature covers and every device would
 * rightly refuse it.
 */
@RestController
@RequestMapping("/api/v1")
class SyncController(private val gallery: GalleryService) {

    @PostMapping("/sync")
    fun sync(@RequestBody body: SyncRequest, request: HttpServletRequest): SyncResponse {
        if (body.deviceId.isBlank() || body.deviceId.length > 128) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "a deviceId is required")
        }
        val latest = gallery.checkIn(
            deviceId = body.deviceId,
            appVersion = body.appVersion,
            androidRelease = body.androidRelease,
            deviceModel = body.deviceModel,
            knownVersion = body.galleryVersion,
            ip = request.clientIp(),
            userAgent = request.getHeader(HttpHeaders.USER_AGENT),
        )
        return SyncResponse(
            serverTime = Instant.now(),
            updateAvailable = latest != null && (body.galleryVersion == null || latest.version > body.galleryVersion),
            latest = latest?.summary(),
        )
    }

    @GetMapping("/gallery/latest")
    fun latest(): ReleaseSummary =
        gallery.latest()?.summary() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "nothing published yet")

    @GetMapping("/gallery/{version}/manifest", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun manifest(@PathVariable version: Int): ResponseEntity<ByteArray> =
        bytes(require(version).manifest, MediaType.APPLICATION_JSON)

    @GetMapping("/gallery/{version}/signature")
    fun signature(@PathVariable version: Int): ResponseEntity<ByteArray> =
        bytes(require(version).signature, MediaType.APPLICATION_OCTET_STREAM)

    @GetMapping("/gallery/{version}/vectors")
    fun vectors(
        @PathVariable version: Int,
        @RequestHeader(value = "X-Device-Id", required = false) deviceId: String?,
        request: HttpServletRequest,
    ): ResponseEntity<ByteArray> {
        val release = require(version)
        // Downloading the vectors is the act that changes what a device holds, so that is what the
        // audit trail records rather than the metadata calls around it.
        if (!deviceId.isNullOrBlank()) {
            gallery.recordDownload(deviceId, version, request.clientIp(), request.getHeader(HttpHeaders.USER_AGENT))
        }
        return bytes(release.vectors, MediaType.APPLICATION_OCTET_STREAM)
    }

    private fun require(version: Int): GalleryRelease =
        gallery.release(version) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "no release $version")

    private fun bytes(body: ByteArray, type: MediaType) = ResponseEntity.ok()
        .contentType(type)
        .header(HttpHeaders.CACHE_CONTROL, "no-cache")
        .body(body)
}

/**
 * Publishing and looking at who has synced. Guarded by a shared token, which is enough while the
 * only administrator is the person who built it; it is not an account system and does not pretend
 * to be one.
 */
@RestController
@RequestMapping("/api/v1/admin")
class AdminController(
    private val gallery: GalleryService,
    @Value("\${gallery.admin-token:}") private val adminToken: String,
) {

    @PostMapping("/gallery")
    fun publish(
        @RequestHeader("X-Admin-Token") token: String,
        @RequestParam("manifest") manifest: MultipartFile,
        @RequestParam("signature") signature: MultipartFile,
        @RequestParam("vectors") vectors: MultipartFile,
        @RequestParam(value = "notes", required = false) notes: String?,
    ): ReleaseSummary {
        authorise(token)
        return gallery.publish(manifest.bytes, signature.bytes, vectors.bytes, notes).summary()
    }

    @GetMapping("/devices")
    fun devices(@RequestHeader("X-Admin-Token") token: String): List<Map<String, Any?>> {
        authorise(token)
        return gallery.devices().map {
            mapOf(
                "deviceId" to it.deviceId,
                "label" to it.label,
                "galleryVersion" to it.galleryVersion,
                "appVersion" to it.appVersion,
                "deviceModel" to it.deviceModel,
                "androidRelease" to it.androidRelease,
                "firstSeenAt" to it.firstSeenAt,
                "lastSeenAt" to it.lastSeenAt,
                "lastSyncAt" to it.lastSyncAt,
                "syncCount" to it.syncCount,
                "lastIp" to it.lastIp,
            )
        }
    }

    @GetMapping("/releases")
    fun releases(@RequestHeader("X-Admin-Token") token: String): List<ReleaseSummary> {
        authorise(token)
        return listOfNotNull(gallery.latest()?.summary())
    }

    private fun authorise(token: String) {
        if (adminToken.isBlank()) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "no ADMIN_TOKEN is configured")
        }
        // Constant-time comparison: a token is a secret, and a timing difference leaks it slowly.
        val a = adminToken.toByteArray()
        val b = token.toByteArray()
        var diff = a.size xor b.size
        for (i in a.indices) diff = diff or (a[i].toInt() xor b.getOrElse(i) { 0 }.toInt())
        if (diff != 0) throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "bad admin token")
    }
}

@RestControllerAdvice
class Errors {
    @ExceptionHandler(PublishRejected::class)
    fun rejected(e: PublishRejected): ResponseEntity<Map<String, String>> =
        ResponseEntity.unprocessableEntity().body(mapOf("error" to (e.message ?: "rejected")))
}
