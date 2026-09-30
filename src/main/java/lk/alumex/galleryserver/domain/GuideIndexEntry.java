package lk.alumex.galleryserver.domain;

/** A section code and its PDF's hash, without the blob. Used for the index endpoint. */
public record GuideIndexEntry(String sectionCode, String sha256) {}
