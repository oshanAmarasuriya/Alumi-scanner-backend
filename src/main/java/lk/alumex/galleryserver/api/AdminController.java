package lk.alumex.galleryserver.api;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import lk.alumex.galleryserver.api.SyncController.ReleaseSummary;
import lk.alumex.galleryserver.domain.ClientDevice;
import lk.alumex.galleryserver.service.GalleryService;
import lk.alumex.galleryserver.service.PublishRejected;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Publishing, and looking at who has synced.
 *
 * <p>Guarded by a shared token, which is enough while the only administrator is the person who
 * built it. It is not an account system and does not pretend to be one.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final GalleryService gallery;
    private final String adminToken;

    public AdminController(GalleryService gallery, @Value("${gallery.admin-token:}") String adminToken) {
        this.gallery = gallery;
        this.adminToken = adminToken;
    }

    @PostMapping("/gallery")
    public ReleaseSummary publish(@RequestHeader("X-Admin-Token") String token,
                                  @RequestParam("manifest") MultipartFile manifest,
                                  @RequestParam("signature") MultipartFile signature,
                                  @RequestParam("vectors") MultipartFile vectors,
                                  @RequestParam(value = "notes", required = false) String notes) throws Exception {
        authorise(token);
        return ReleaseSummary.of(
                gallery.publish(manifest.getBytes(), signature.getBytes(), vectors.getBytes(), notes));
    }

    @PostMapping("/guides")
    public Map<String, String> uploadGuide(@RequestHeader("X-Admin-Token") String token,
                                           @RequestParam("code") String code,
                                           @RequestParam("pdf") MultipartFile pdf) throws Exception {
        authorise(token);
        if (code == null || code.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "a section code is required");
        }
        byte[] bytes = pdf.getBytes();
        if (bytes.length < 4 || bytes[0] != '%' || bytes[1] != 'P' || bytes[2] != 'D' || bytes[3] != 'F') {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "the file does not look like a PDF");
        }
        gallery.uploadGuide(code, bytes);
        return Map.of("status", "ok", "sectionCode", code, "size", String.valueOf(bytes.length));
    }

    @GetMapping("/devices")
    public List<Map<String, Object>> devices(@RequestHeader("X-Admin-Token") String token) {
        authorise(token);
        return gallery.devices().stream().map(AdminController::describe).toList();
    }

    @GetMapping("/releases")
    public List<ReleaseSummary> releases(@RequestHeader("X-Admin-Token") String token) {
        authorise(token);
        return gallery.releases().stream().map(ReleaseSummary::of).toList();
    }

    private static Map<String, Object> describe(ClientDevice d) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("deviceId", d.deviceId());
        row.put("label", d.label());
        row.put("galleryVersion", d.galleryVersion());
        row.put("appVersion", d.appVersion());
        row.put("deviceModel", d.deviceModel());
        row.put("androidRelease", d.androidRelease());
        row.put("firstSeenAt", d.firstSeenAt());
        row.put("lastSeenAt", d.lastSeenAt());
        row.put("lastSyncAt", d.lastSyncAt());
        row.put("syncCount", d.syncCount());
        row.put("lastIp", d.lastIp());
        return row;
    }

    private void authorise(String token) {
        if (adminToken == null || adminToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "no ADMIN_TOKEN is configured");
        }
        // Constant-time comparison: a token is a secret, and a timing difference leaks it slowly.
        byte[] expected = adminToken.getBytes(StandardCharsets.UTF_8);
        byte[] given = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
        int diff = expected.length ^ given.length;
        for (int i = 0; i < expected.length; i++) {
            diff |= expected[i] ^ (i < given.length ? given[i] : 0);
        }
        if (diff != 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "bad admin token");
        }
    }
}

@RestControllerAdvice
class Errors {
    @ExceptionHandler(PublishRejected.class)
    ResponseEntity<Map<String, String>> rejected(PublishRejected e) {
        return ResponseEntity.unprocessableEntity().body(Map.of("error", e.getMessage()));
    }
}
