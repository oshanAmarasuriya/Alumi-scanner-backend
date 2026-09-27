package lk.alumex.galleryserver.domain;

import java.time.Instant;

/**
 * One signed catalogue release, held exactly as it was signed.
 *
 * <p>The three files travel together: the manifest, the signature over its bytes, and the vectors
 * whose checksum the manifest states. Verifying the signature therefore covers both files.
 */
public record GalleryRelease(
        int version,
        String modelId,
        int embeddingDim,
        int sectionCount,
        byte[] manifest,
        String manifestSha256,
        byte[] signature,
        byte[] vectors,
        String vectorsSha256,
        Instant createdAt,
        Instant publishedAt,
        String notes) {
}
