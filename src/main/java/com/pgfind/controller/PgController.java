package com.pgfind.controller;

import com.pgfind.model.Pg;
import com.pgfind.service.FirebaseService;
import com.pgfind.service.GoogleMapsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/pgs")
public class PgController {

    private static final Logger log = LoggerFactory.getLogger(PgController.class);
    private final FirebaseService firebaseService;
    private final GoogleMapsService googleMapsService;

    public PgController(FirebaseService firebaseService, GoogleMapsService googleMapsService) {
        this.firebaseService = firebaseService;
        this.googleMapsService = googleMapsService;
    }

    /**
     * Get PGs with optional filtering
     */
    @GetMapping
    public ResponseEntity<List<Pg>> getPgs(
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String area,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Double maxPrice,
            @RequestParam(required = false) List<String> amenities) {
        
        log.info("Received request to fetch PGs. Filters - City: {}, Area: {}, Type: {}, MaxPrice: {}, Amenities: {}", 
                city, area, type, maxPrice, amenities);
        
        final String cleanCity = (city != null) ? city.trim() : "";
        final String cleanArea = (area != null) ? area.trim() : "";
        final String cleanType = (type != null) ? type.trim() : "";

        // Trigger automatic sync if city is provided, or if Firebase is completely empty
        List<Pg> currentPgs = firebaseService.getAllPgs();
        if (currentPgs.isEmpty()) {
            log.info("Firebase contains 0 PGs. Performing initial API sync for Hyderabad & Bangalore...");
            try {
                List<Pg> syncedHyd = googleMapsService.searchAndSyncPgs("Hyderabad", "Kukatpally");
                firebaseService.syncGoogleMapsPgs(syncedHyd);
                List<Pg> syncedBlr = googleMapsService.searchAndSyncPgs("Bangalore", "Koramangala");
                firebaseService.syncGoogleMapsPgs(syncedBlr);
            } catch (Exception e) {
                log.error("Initial empty database sync failed: {}", e.getMessage());
            }
        } else if (!cleanCity.isBlank()) {
            String targetArea = !cleanArea.isBlank() ? cleanArea : ("Hyderabad".equalsIgnoreCase(cleanCity) ? "Kukatpally" : "Koramangala");
            boolean hasSyncedPgs = currentPgs.stream()
                    .anyMatch(p -> p.getCity() != null && cleanCity.equalsIgnoreCase(p.getCity()));
            if (!hasSyncedPgs) {
                log.info("Fetching real PGs via API for city {}, area {} and persisting to Firebase database...", cleanCity, targetArea);
                try {
                    List<Pg> synced = googleMapsService.searchAndSyncPgs(cleanCity, targetArea);
                    if (synced != null && !synced.isEmpty()) {
                        int addedCount = firebaseService.syncGoogleMapsPgs(synced);
                        log.info("Persisted {} new PGs to Firebase database for {}", addedCount, cleanCity);
                    }
                } catch (Exception e) {
                    log.error("Automatic sync failed for city {}: {}", cleanCity, e.getMessage());
                }
            }
        }
        
        List<Pg> pgs = firebaseService.getAllPgs();
        
        // Filter dynamically (preserving BOTH premium and normal PGs)
        List<Pg> filtered = pgs.stream()
                .filter(pg -> cleanCity.isBlank() || (pg.getCity() != null && pg.getCity().equalsIgnoreCase(cleanCity)))
                .filter(pg -> {
                    if (cleanArea.isBlank()) return true;
                    if (pg.getArea() == null) return false;
                    String pgAreaL = pg.getArea().toLowerCase();
                    String reqAreaL = cleanArea.toLowerCase();
                    return pgAreaL.contains(reqAreaL) || reqAreaL.contains(pgAreaL);
                })
                .filter(pg -> cleanType.isBlank() || (pg.getPgType() != null && pg.getPgType().equalsIgnoreCase(cleanType)))
                .filter(pg -> maxPrice == null || (pg.getStartingPrice() != null && pg.getStartingPrice() <= maxPrice))
                .filter(pg -> {
                    if (amenities == null || amenities.isEmpty()) {
                        return true;
                    }
                    if (pg.getAmenities() == null) {
                        return false;
                    }
                    List<String> pgAmenitiesLower = pg.getAmenities().stream()
                            .map(String::trim)
                            .map(String::toLowerCase)
                            .collect(Collectors.toList());
                    return amenities.stream()
                            .map(String::trim)
                            .map(String::toLowerCase)
                            .allMatch(pgAmenitiesLower::contains);
                })
                .sorted(java.util.Comparator.comparing(Pg::getRating, java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                .collect(Collectors.toList());

        log.info("Returning {} PGs matching filter criteria (includes premium & standard listings)", filtered.size());
        return ResponseEntity.ok(filtered);
    }

    /**
     * Get PG by ID
     */
    @GetMapping("/{id}")
    public ResponseEntity<Pg> getPgById(@PathVariable String id) {
        log.info("Fetching PG details for ID: {}", id);
        Pg pg = firebaseService.getPgById(id);
        if (pg == null) {
            log.warn("PG not found with ID: {}", id);
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(pg);
    }

    /**
     * Add a new PG listing
     */
    @PostMapping
    public ResponseEntity<Pg> addPg(@RequestBody Pg pg) {
        log.info("Request received to add new PG: {}", pg.getName());
        
        // Basic Validation
        if (pg.getName() == null || pg.getName().isBlank() ||
            pg.getCity() == null || pg.getCity().isBlank() ||
            pg.getArea() == null || pg.getArea().isBlank() ||
            pg.getStartingPrice() == null) {
            log.warn("Validation failed for PG creation: {}", pg);
            return ResponseEntity.badRequest().build();
        }
        
        // Default rating for new PGs
        if (pg.getRating() == null) {
            pg.setRating(4.0);
        }
        
        Pg savedPg = firebaseService.addPg(pg);
        return ResponseEntity.status(HttpStatus.CREATED).body(savedPg);
    }

    /**
     * Delete a PG listing
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> deletePg(@PathVariable String id) {
        log.info("Request received to delete PG with ID: {}", id);
        try {
            firebaseService.deletePg(id);
            return ResponseEntity.ok(Map.of("status", "success", "message", "PG deleted successfully"));
        } catch (Exception e) {
            log.error("Failed to delete PG {}: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to delete PG: " + e.getMessage()));
        }
    }


    /**
     * Get unique cities listed in PGs
     */
    @GetMapping("/cities")
    public ResponseEntity<List<String>> getCities() {
        log.info("Fetching unique cities list");
        return ResponseEntity.ok(firebaseService.getCities());
    }

    /**
     * Get real areas for a given city from Nominatim + Overpass (dynamic, no hardcoded list)
     */
    @GetMapping("/areas")
    public ResponseEntity<List<String>> getAreas(@RequestParam(required = false) String city) {
        log.info("Fetching dynamic areas list for city: {}", city);
        if (city == null || city.isBlank()) {
            return ResponseEntity.ok(firebaseService.getCities());
        }
        return ResponseEntity.ok(googleMapsService.fetchAreasForCity(city));
    }

    /**
     * Sync PGs from Google Maps for a given city and area
     */
    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> syncFromGoogleMaps(
            @RequestParam String city,
            @RequestParam String area) {
        
        log.info("Received Google Maps sync request - City: {}, Area: {}", city, area);
        
        if (city == null || city.isBlank() || area == null || area.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Both city and area parameters are required"));
        }

        try {
            // Step 1: Search and retrieve PG listings from Google Maps
            List<Pg> googlePgs = googleMapsService.searchAndSyncPgs(city, area);
            
            // Step 2: Save new ones, skip duplicates
            int syncedCount = firebaseService.syncGoogleMapsPgs(googlePgs);
            
            log.info("Google Maps sync complete. Found: {}, Added: {}", googlePgs.size(), syncedCount);
            
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "city", city,
                    "area", area,
                    "found", googlePgs.size(),
                    "syncedCount", syncedCount,
                    "skipped", googlePgs.size() - syncedCount,
                    "message", syncedCount == 0
                            ? "All listings from this area are already up to date."
                            : syncedCount + " new PG(s) from Google Maps added to " + area + ", " + city + "!"
            ));
        } catch (Exception e) {
            log.error("Error during Google Maps sync", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Google Maps sync failed: " + e.getMessage()));
        }
    }

    /**
     * Search for PGs near user's current location (lat/lon from browser geolocation)
     */
    @GetMapping("/nearby")
    public ResponseEntity<List<Pg>> getNearbyPgs(
            @RequestParam double lat,
            @RequestParam double lon) {

        log.info("Nearby PG search: lat={}, lon={}", lat, lon);
        try {
            List<Pg> nearby = googleMapsService.searchNearbyPgs(lat, lon);
            // Persist new ones to Firebase (skip duplicates by placeId)
            firebaseService.syncGoogleMapsPgs(nearby);
            return ResponseEntity.ok(nearby);
        } catch (Exception e) {
            log.error("Nearby PG search failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
