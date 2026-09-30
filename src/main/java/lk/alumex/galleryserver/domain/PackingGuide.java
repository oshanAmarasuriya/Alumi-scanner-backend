package lk.alumex.galleryserver.domain;

import java.time.Instant;

/** A packing guide PDF for one catalogue section, identified by its code. */
public record PackingGuide(String sectionCode, byte[] pdf, String sha256, Instant uploadedAt) {}
