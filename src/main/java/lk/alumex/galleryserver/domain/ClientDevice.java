package lk.alumex.galleryserver.domain;

import java.time.Instant;

/** An install of the app. There is no account and no user identity behind this. */
public record ClientDevice(
        String deviceId,
        String label,
        Instant firstSeenAt,
        Instant lastSeenAt,
        Instant lastSyncAt,
        Integer galleryVersion,
        String appVersion,
        String androidRelease,
        String deviceModel,
        int syncCount,
        String lastIp) {
}
