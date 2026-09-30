package lk.alumex.galleryserver.domain;

import java.time.Instant;

public record PackingGuide(String sectionCode, byte[] pdf, String sha256, Instant uploadedAt) {}
