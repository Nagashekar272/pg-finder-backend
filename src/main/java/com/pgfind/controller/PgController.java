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
        
        // Trigger automatic sync if city and area are present and no real PGs exist for this area in Firebase
        if (city != null && !city.isBlank() && area != null && !area.isBlank()) {
            try {
                List<Pg> currentPgs = firebaseService.getAllPgs();
                boolean hasRealPgs = currentPgs.stream()
                        .filter(p -> city.equalsIgnoreCase(p.getCity()) && area.equalsIgnoreCase(p.getArea()))
                        .anyMatch(p -> p.getPlaceId() != null 
                                && !p.getPlaceId().startsWith("seed-initial-") 
                                && !p.getPlaceId().startsWith("osm-mock-"));
                
                if (!hasRealPgs) {
                    log.info("No real PGs found in database for {}, {}. Triggering automatic sync...", area, city);
                    List<Pg> synced = googleMapsService.searchAndSyncPgs(city, area);
                    if (synced != null && !synced.isEmpty()) {
                        firebaseService.syncGoogleMapsPgs(synced);
                    }
                }
            } catch (Exception e) {
                log.error("Automatic sync failed for {}, {}: {}", area, city, e.getMessage());
            }
        }
        
        List<Pg> pgs = firebaseService.getAllPgs();
        
        // Filter dynamically
        List<Pg> filtered = pgs.stream()
                .filter(pg -> city == null || city.isBlank() || city.equalsIgnoreCase(pg.getCity()))
                .filter(pg -> area == null || area.isBlank() || area.equalsIgnoreCase(pg.getArea()))
                .filter(pg -> type == null || type.isBlank() || type.equalsIgnoreCase(pg.getPgType()))
                .filter(pg -> maxPrice == null || (pg.getStartingPrice() != null && pg.getStartingPrice() <= maxPrice))
                .filter(pg -> {
                    if (amenities == null || amenities.isEmpty()) {
                        return true;
                    }
                    if (pg.getAmenities() == null) {
                        return false;
                    }
                    List<String> pgAmenitiesLower = pg.getAmenities().stream()
                            .map(String::toLowerCase)
                            .collect(Collectors.toList());
                    return amenities.stream()
                            .map(String::toLowerCase)
                            .allMatch(pgAmenitiesLower::contains);
                })
                .collect(Collectors.toList());

        // Remove mock PGs if real ones exist for that area
        Map<String, Boolean> areaHasReal = new java.util.HashMap<>();
        for (Pg pg : filtered) {
            String key = (pg.getCity() + "|" + pg.getArea()).toLowerCase();
            boolean isReal = pg.getPlaceId() != null 
                    && !pg.getPlaceId().startsWith("seed-initial-") 
                    && !pg.getPlaceId().startsWith("osm-mock-");
            if (isReal) {
                areaHasReal.put(key, true);
            }
        }
        
        filtered = filtered.stream()
                .filter(pg -> {
                    String key = (pg.getCity() + "|" + pg.getArea()).toLowerCase();
                    boolean isMock = pg.getPlaceId() != null 
                            && (pg.getPlaceId().startsWith("seed-initial-") || pg.getPlaceId().startsWith("osm-mock-"));
                    return !(isMock && areaHasReal.getOrDefault(key, false));
                })
                .collect(Collectors.toList());
        
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
