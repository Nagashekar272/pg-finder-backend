package com.pgfind.controller;

import com.pgfind.model.Pg;
import com.pgfind.service.FirebaseService;
import com.pgfind.service.GoogleMapsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * AdminController — provides admin-only REST APIs.
 *
 * Authentication is token-based:
 *   1. POST /api/admin/login  →  returns a session token (valid 8 hours)
 *   2. All other /api/admin/* endpoints require header:  X-Admin-Token: <token>
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    // ── In-memory token store: token → expiry epoch millis ──────────────────
    private static final Map<String, Long> activeSessions = new ConcurrentHashMap<>();
    private static final long SESSION_DURATION_MS = 8 * 60 * 60 * 1000L; // 8 hours

    @Value("${admin.email}")
    private String adminEmail;

    @Value("${admin.password}")
    private String adminPassword;

    @Value("${admin.token.secret}")
    private String tokenSecret;

    private final FirebaseService firebaseService;
    private final GoogleMapsService googleMapsService;

    public AdminController(FirebaseService firebaseService, GoogleMapsService googleMapsService) {
        this.firebaseService   = firebaseService;
        this.googleMapsService = googleMapsService;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Validates the X-Admin-Token header. Returns true if the token is active. */
    private boolean isValidToken(String token) {
        if (token == null || token.isBlank()) return false;
        Long expiry = activeSessions.get(token);
        if (expiry == null) return false;
        if (System.currentTimeMillis() > expiry) {
            activeSessions.remove(token);
            return false;
        }
        return true;
    }

    private ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(401).body(Map.of(
            "status", "error",
            "message", "Unauthorized. Please log in as admin first."
        ));
    }

    // ── POST /api/admin/login ─────────────────────────────────────────────────
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        String email    = body.getOrDefault("email", "").trim();
        String password = body.getOrDefault("password", "").trim();

        log.info("Admin login attempt for email: {}", email);

        if (!adminEmail.equalsIgnoreCase(email) || !adminPassword.equals(password)) {
            log.warn("Admin login failed for: {}", email);
            return ResponseEntity.status(401).body(Map.of(
                "status",  "error",
                "message", "Invalid email or password."
            ));
        }

        // Generate a secure session token
        String token = UUID.randomUUID().toString().replace("-", "") + "-" + tokenSecret.hashCode();
        activeSessions.put(token, System.currentTimeMillis() + SESSION_DURATION_MS);

        log.info("Admin login successful. Token issued.");
        return ResponseEntity.ok(Map.of(
            "status",  "success",
            "token",   token,
            "email",   email,
            "message", "Login successful. Token valid for 8 hours."
        ));
    }

    // ── POST /api/admin/logout ────────────────────────────────────────────────
    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        if (token != null) activeSessions.remove(token);
        return ResponseEntity.ok(Map.of("status", "success", "message", "Logged out successfully."));
    }

    // ── GET /api/admin/dashboard ──────────────────────────────────────────────
    @GetMapping("/dashboard")
    public ResponseEntity<Map<String, Object>> dashboard(
            @RequestHeader(value = "X-Admin-Token", required = false) String token) {

        if (!isValidToken(token)) return unauthorized();

        List<Pg> allPgs = firebaseService.getAllPgs();

        // Stats
        long totalPgs     = allPgs.size();
        long totalReal    = allPgs.stream().filter(p -> p.getPlaceId() != null
                && !p.getPlaceId().startsWith("seed-initial-")
                && !p.getPlaceId().startsWith("osm-mock-")).count();
        long totalMock    = totalPgs - totalReal;

        // By city
        Map<String, Long> byCity = allPgs.stream()
                .filter(p -> p.getCity() != null)
                .collect(Collectors.groupingBy(Pg::getCity, Collectors.counting()));

        // By type
        Map<String, Long> byType = allPgs.stream()
                .filter(p -> p.getPgType() != null)
                .collect(Collectors.groupingBy(Pg::getPgType, Collectors.counting()));

        // By area (top 10)
        Map<String, Long> byArea = allPgs.stream()
                .filter(p -> p.getArea() != null)
                .collect(Collectors.groupingBy(p -> p.getCity() + " — " + p.getArea(), Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(10)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (e1, e2) -> e1, LinkedHashMap::new));

        // Area coverage: how many of 32 areas have real PGs
        long areasWithReal = allPgs.stream()
                .filter(p -> p.getPlaceId() != null
                        && !p.getPlaceId().startsWith("seed-initial-")
                        && !p.getPlaceId().startsWith("osm-mock-"))
                .map(p -> (p.getCity() + "|" + p.getArea()).toLowerCase())
                .distinct().count();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalPgs",       totalPgs);
        stats.put("realPgs",        totalReal);
        stats.put("mockPgs",        totalMock);
        stats.put("areasWithReal",  areasWithReal);
        stats.put("totalAreas",     32);
        stats.put("byCity",         byCity);
        stats.put("byType",         byType);
        stats.put("topAreasByCount", byArea);

        log.info("Admin dashboard requested. Total PGs: {}", totalPgs);
        return ResponseEntity.ok(Map.of(
            "status", "success",
            "stats",  stats,
            "pgs",    allPgs
        ));
    }

    // ── DELETE /api/admin/pgs/{id} ────────────────────────────────────────────
    @DeleteMapping("/pgs/{id}")
    public ResponseEntity<Map<String, Object>> deletePg(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable String id) {

        if (!isValidToken(token)) return unauthorized();

        try {
            firebaseService.deletePg(id);
            log.info("Admin deleted PG id: {}", id);
            return ResponseEntity.ok(Map.of("status", "success", "message", "PG deleted successfully.", "id", id));
        } catch (Exception e) {
            log.error("Failed to delete PG {}: {}", id, e.getMessage());
            return ResponseEntity.status(500).body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    // ── POST /api/admin/pgs/sync ──────────────────────────────────────────────
    @PostMapping("/pgs/sync")
    public ResponseEntity<Map<String, Object>> syncArea(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestParam String city,
            @RequestParam String area) {

        if (!isValidToken(token)) return unauthorized();

        log.info("Admin triggered sync for {}, {}", area, city);
        try {
            List<Pg> synced = googleMapsService.searchAndSyncPgs(city, area);
            int added = firebaseService.syncGoogleMapsPgs(synced);
            return ResponseEntity.ok(Map.of(
                "status",  "success",
                "synced",  synced.size(),
                "added",   added,
                "message", "Synced " + synced.size() + " listings, " + added + " newly added for " + area + ", " + city
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    // ── POST /api/admin/pgs/clear-mocks ──────────────────────────────────────
    @PostMapping("/pgs/clear-mocks")
    public ResponseEntity<Map<String, Object>> clearMockPgs(
            @RequestHeader(value = "X-Admin-Token", required = false) String token) {

        if (!isValidToken(token)) return unauthorized();

        List<Pg> allPgs = firebaseService.getAllPgs();
        List<Pg> mocks = allPgs.stream()
                .filter(p -> p.getPlaceId() != null
                        && (p.getPlaceId().startsWith("seed-initial-") || p.getPlaceId().startsWith("osm-mock-")))
                .collect(Collectors.toList());

        int deleted = 0;
        for (Pg pg : mocks) {
            try { firebaseService.deletePg(pg.getId()); deleted++; } catch (Exception ignored) {}
        }

        log.info("Admin cleared {} mock PGs from database", deleted);
        return ResponseEntity.ok(Map.of(
            "status",  "success",
            "deleted", deleted,
            "message", "Cleared " + deleted + " mock/seeded listings from the database."
        ));
    }
}
