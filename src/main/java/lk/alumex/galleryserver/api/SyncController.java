package lk.alumex.galleryserver.api;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import lk.alumex.galleryserver.domain.GalleryRelease;
import lk.alumex.galleryserver.domain.GuideIndexEntry;
import lk.alumex.galleryserver.service.GalleryService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * What the app talks to.
 *
 * <p>Three files make up a release and each is served as its own bytes: the manifest exactly as it
 * was signed, the signature, and the vectors. The manifest is never re-serialised on the way out —
 * re-encoding the same JSON would change the bytes the signature covers, and every device would
 * rightly refuse it.
 */
@RestController
@RequestMapping("/api/v1")
public class SyncController {

    private final GalleryService gallery;

    public SyncController(GalleryService gallery) {
        this.gallery = gallery;
    }

    public record SyncRequest(String deviceId, String appVersion, String androidRelease,
                              String deviceModel, Integer galleryVersion) {
    }

    public record ReleaseSummary(int version, String modelId, int sectionCount, int embeddingDim,
                                 String manifestSha256, String vectorsSha256, Instant publishedAt) {
        static ReleaseSummary of(GalleryRelease r) {
            return new ReleaseSummary(r.version(), r.modelId(), r.sectionCount(), r.embeddingDim(),
                    r.manifestSha256(), r.vectorsSha256(), r.publishedAt());
        }
    }

    public record SyncResponse(Instant serverTime, boolean updateAvailable, ReleaseSummary latest) {
    }

    @PostMapping("/sync")
    public SyncResponse sync(@RequestBody SyncRequest body, HttpServletRequest request) {
        if (body.deviceId() == null || body.deviceId().isBlank() || body.deviceId().length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "a deviceId is required");
        }
        GalleryRelease latest = gallery.checkIn(body.deviceId(), body.appVersion(), body.androidRelease(),
                body.deviceModel(), body.galleryVersion(), clientIp(request),
                request.getHeader(HttpHeaders.USER_AGENT)).orElse(null);

        boolean updateAvailable = latest != null
                && (body.galleryVersion() == null || latest.version() > body.galleryVersion());
        return new SyncResponse(Instant.now(), updateAvailable,
                latest == null ? null : ReleaseSummary.of(latest));
    }

    @GetMapping("/gallery/latest")
    public ReleaseSummary latest() {
        return gallery.latest().map(ReleaseSummary::of)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "nothing published yet"));
    }

    @GetMapping(value = "/gallery/{version}/manifest", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> manifest(@PathVariable int version) {
        return bytes(require(version).manifest(), MediaType.APPLICATION_JSON);
    }

    @GetMapping("/gallery/{version}/signature")
    public ResponseEntity<byte[]> signature(@PathVariable int version) {
        return bytes(require(version).signature(), MediaType.APPLICATION_OCTET_STREAM);
    }

    @GetMapping("/gallery/{version}/vectors")
    public ResponseEntity<byte[]> vectors(@PathVariable int version,
                                          @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
                                          HttpServletRequest request) {
        GalleryRelease release = require(version);
        // Downloading the vectors is the act that changes what a device holds, so that is what the
        // audit trail records rather than the metadata calls around it.
        if (deviceId != null && !deviceId.isBlank()) {
            gallery.recordDownload(deviceId, version, clientIp(request), request.getHeader(HttpHeaders.USER_AGENT));
        }
        return bytes(release.vectors(), MediaType.APPLICATION_OCTET_STREAM);
    }

    // ---------------------------------------------------------------- packing guides

    @GetMapping("/guides/index")
    public List<GuideIndexEntry> guideIndex() {
        return gallery.guideIndex();
    }

    @GetMapping("/guides/{code}")
    public ResponseEntity<byte[]> guidePdf(@PathVariable String code) {
        return gallery.guidePdf(code)
                .map(pdf -> bytes(pdf, MediaType.APPLICATION_PDF))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no packing guide for " + code));
    }

    private GalleryRelease require(int version) {
        return gallery.release(version)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no release " + version));
    }

    private static ResponseEntity<byte[]> bytes(byte[] body, MediaType type) {
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .body(body);
    }

    static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
