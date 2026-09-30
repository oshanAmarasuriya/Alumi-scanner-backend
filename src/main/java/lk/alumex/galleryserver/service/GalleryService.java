package lk.alumex.galleryserver.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import lk.alumex.galleryserver.domain.ClientDevice;
import lk.alumex.galleryserver.domain.GalleryDao;
import lk.alumex.galleryserver.domain.GalleryRelease;
import lk.alumex.galleryserver.domain.GuideIndexEntry;
import lk.alumex.galleryserver.domain.PackingGuide;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accepting, storing and serving signed catalogue releases.
 *
 * <p>The server holds only the <b>public</b> key. It can check that a release was signed by whoever
 * holds the private one, and refuses anything it cannot verify — so a mistake in the export is
 * caught here rather than by every phone in the warehouse. What it cannot do is produce a release,
 * which is the point: if this machine is breached, the worst an attacker can serve is an old
 * catalogue or none at all.
 */
@Service
public class GalleryService {

    private static final Logger log = LoggerFactory.getLogger(GalleryService.class);

    private final GalleryDao dao;
    private final ObjectMapper mapper;
    private final String publicKeyBase64;

    public GalleryService(GalleryDao dao, ObjectMapper mapper,
                          @Value("${gallery.public-key:}") String publicKeyBase64) {
        this.dao = dao;
        this.mapper = mapper;
        this.publicKeyBase64 = publicKeyBase64;
    }

    public Optional<GalleryRelease> latest() {
        return dao.findLatestRelease();
    }

    public Optional<GalleryRelease> release(int version) {
        return dao.findRelease(version);
    }

    public List<ClientDevice> devices() {
        return dao.listDevices();
    }

    public List<GalleryRelease> releases() {
        return dao.listReleases();
    }

    /** Records a device asking what is available, and returns the newest release if there is one. */
    @Transactional
    public Optional<GalleryRelease> checkIn(String deviceId, String appVersion, String androidRelease,
                                            String deviceModel, Integer knownVersion, String ip,
                                            String userAgent) {
        Instant now = Instant.now();
        if (dao.findDevice(deviceId).isEmpty()) {
            log.info("first check-in from device {}", deviceId);
        }
        // What the device says it holds is a report, never a permission: a version this server has
        // never published is not recorded against it.
        Integer claimed = knownVersion != null && dao.releaseExists(knownVersion) ? knownVersion : null;
        dao.recordSeen(deviceId, appVersion, androidRelease, deviceModel, claimed, ip, now);

        Optional<GalleryRelease> latest = latest();
        dao.recordEvent(deviceId, "check_in", knownVersion,
                latest.map(GalleryRelease::version).orElse(null), ip, userAgent, now);
        return latest;
    }

    /** Records that a device actually took a release. */
    @Transactional
    public void recordDownload(String deviceId, int version, String ip, String userAgent) {
        Instant now = Instant.now();
        dao.recordDownload(deviceId, version, ip, now);
        dao.recordEvent(deviceId, "download", null, version, ip, userAgent, now);
    }

    /**
     * Publishes a release, after checking everything that can be checked here.
     *
     * <p>Order matters. The signature is verified over the manifest bytes exactly as received, and
     * the vectors' checksum is compared against the value <i>inside</i> that manifest. Since the
     * signature covers the manifest, and the manifest states the vectors' hash, one signature
     * protects both files.
     */
    @Transactional
    public GalleryRelease publish(byte[] manifest, byte[] signature, byte[] vectors, String notes) {
        verifySignature(manifest, signature);

        JsonNode json;
        try {
            json = mapper.readTree(manifest);
        } catch (Exception e) {
            throw new PublishRejected("the manifest is not valid JSON: " + e.getMessage());
        }

        int version = json.path("version").asInt(-1);
        if (version < 1) {
            throw new PublishRejected("the manifest has no usable version");
        }
        String modelId = json.path("model_id").asText("");
        if (modelId.isBlank()) {
            throw new PublishRejected("the manifest names no model_id");
        }
        int count = json.path("count").asInt(-1);
        int dim = json.path("embedding_dim").asInt(-1);
        if (count < 1 || dim < 1) {
            throw new PublishRejected("the manifest has no usable count or embedding_dim");
        }

        int expectedBytes = count * dim * 4;
        if (vectors.length != expectedBytes) {
            throw new PublishRejected("expected %d bytes of vectors for %d x %d, got %d"
                    .formatted(expectedBytes, count, dim, vectors.length));
        }
        String vectorsHash = sha256(vectors);
        String declared = json.path("vectors_sha256").asText("");
        if (!declared.isEmpty() && !declared.equals(vectorsHash)) {
            throw new PublishRejected("the vectors do not match the checksum in the manifest");
        }

        if (dao.releaseExists(version)) {
            throw new PublishRejected("version %d is already published; export a new version instead"
                    .formatted(version));
        }
        Optional<GalleryRelease> latest = latest();
        if (latest.isPresent() && version <= latest.get().version()) {
            throw new PublishRejected("version %d is not newer than the published %d"
                    .formatted(version, latest.get().version()));
        }
        latest.filter(previous -> !previous.modelId().equals(modelId)).ifPresent(previous ->
                // Not fatal, but it means every device needs a new app before this is usable.
                log.warn("release {} changes the model from {} to {}", version, previous.modelId(), modelId));

        Instant created = parseInstant(json.path("created").asText(""));
        GalleryRelease release = new GalleryRelease(version, modelId, dim, count, manifest, sha256(manifest),
                signature, vectors, vectorsHash, created, Instant.now(), notes);
        dao.insertRelease(release);
        log.info("published gallery v{}: {} sections, model {}", version, count, modelId);
        return release;
    }

    // ---------------------------------------------------------------- packing guides

    /** Stores (or replaces) a packing guide PDF for a section. */
    @Transactional
    public void uploadGuide(String sectionCode, byte[] pdfBytes) {
        String hash = sha256(pdfBytes);
        dao.upsertGuide(sectionCode, pdfBytes, hash, Instant.now());
        log.info("uploaded packing guide for {} ({} bytes, sha256 {})", sectionCode, pdfBytes.length, hash);
    }

    /** Returns the index of all packing guides: section codes and their hashes, no blobs. */
    public List<GuideIndexEntry> guideIndex() {
        return dao.listGuideIndex();
    }

    /** Returns the raw PDF bytes for one section, if a guide exists. */
    public Optional<byte[]> guidePdf(String sectionCode) {
        return dao.findGuide(sectionCode).map(PackingGuide::pdf);
    }

    private void verifySignature(byte[] manifest, byte[] signature) {
        if (publicKeyBase64.isBlank()) {
            throw new PublishRejected("no GALLERY_PUBLIC_KEY is configured, so nothing can be verified");
        }
        PublicKey key;
        try {
            key = KeyFactory.getInstance("EC")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64.trim())));
        } catch (Exception e) {
            throw new PublishRejected("GALLERY_PUBLIC_KEY is not a usable P-256 public key: " + e.getMessage());
        }
        boolean ok;
        try {
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(key);
            verifier.update(manifest);
            ok = verifier.verify(signature);
        } catch (Exception e) {
            throw new PublishRejected("the signature could not be checked: " + e.getMessage());
        }
        if (!ok) {
            throw new PublishRejected("the signature does not match the manifest and this server's public key");
        }
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return Instant.now();
        }
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            return Instant.now();
        }
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
