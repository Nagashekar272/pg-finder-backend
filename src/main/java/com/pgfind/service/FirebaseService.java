package com.pgfind.service;

import com.pgfind.model.Pg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class FirebaseService {

    private static final Logger log = LoggerFactory.getLogger(FirebaseService.class);
    
    private final RestTemplate restTemplate;
    
    @Value("${firebase.database.url:https://pg-find-c23ab-default-rtdb.firebaseio.com}")
    private String databaseUrl;

    public FirebaseService() {
        this.restTemplate = new RestTemplate();
    }

    private String getPgsUrl() {
        return databaseUrl + "/pgs.json";
    }

    private String getPgUrl(String id) {
        return databaseUrl + "/pgs/" + id + ".json";
    }

    private List<Pg> cachedPgs = null;
    private long lastCacheTime = 0;
    private static final long CACHE_TTL_MS = 30000; // 30 seconds cache

    /**
     * Invalidate the in-memory PG cache
     */
    public synchronized void invalidateCache() {
        cachedPgs = null;
        lastCacheTime = 0;
    }

    /**
     * Fetch all PGs directly from Firebase Realtime Database / Firestore
     */
    public synchronized List<Pg> getAllPgs() {
        long now = System.currentTimeMillis();
        if (cachedPgs != null && (now - lastCacheTime) < CACHE_TTL_MS) {
            log.debug("Returning cached PGs list ({} items)", cachedPgs.size());
            return new ArrayList<>(cachedPgs);
        }

        try {
            String url = getPgsUrl();
            log.debug("Fetching all PGs from Firebase Database: {}", url);
            
            // Firebase returns a Map of { firebaseId: PGDetails }
            ParameterizedTypeReference<Map<String, Pg>> responseType = 
                    new ParameterizedTypeReference<Map<String, Pg>>() {};
            
            ResponseEntity<Map<String, Pg>> response = restTemplate.exchange(
                    url, HttpMethod.GET, null, responseType);
            
            Map<String, Pg> pgsMap = response.getBody();
            List<Pg> pgsList = new ArrayList<>();
            if (pgsMap != null && !pgsMap.isEmpty()) {
                for (Map.Entry<String, Pg> entry : pgsMap.entrySet()) {
                    Pg pg = entry.getValue();
                    if (pg != null) {
                        pg.setId(entry.getKey()); // Set the ID as the Firebase database key
                        pgsList.add(pg);
                    }
                }
            } else {
                log.info("No PGs found in Firebase database.");
            }

            cachedPgs = pgsList;
            lastCacheTime = now;
            return new ArrayList<>(cachedPgs);
        } catch (Exception e) {
            log.error("Firebase query failed: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Fetch a single PG by its ID from Firebase Database
     */
    public Pg getPgById(String id) {
        if (id == null || id.isBlank()) return null;
        try {
            String url = getPgUrl(id);
            log.debug("Fetching PG by id from Firebase: {}", url);
            
            Pg pg = restTemplate.getForObject(url, Pg.class);
            if (pg != null) {
                pg.setId(id);
            }
            return pg;
        } catch (Exception e) {
            log.warn("Firebase fetch by ID failed for {}: {}", id, e.getMessage());
            return null;
        }
    }

    /**
     * Add a new PG directly to Firebase Database
     */
    @SuppressWarnings("unchecked")
    public Pg addPg(Pg pg) {
        invalidateCache();
        try {
            String url = getPgsUrl();
            log.debug("Adding new PG to Firebase: {}", url);
            
            // Firebase REST POST returns { "name": "generated_id" }
            Map<String, String> response = restTemplate.postForObject(url, pg, Map.class);
            if (response != null && response.containsKey("name")) {
                String generatedId = response.get("name");
                pg.setId(generatedId);
                
                // Write back the generated ID inside the PG record in Firebase
                String updateUrl = databaseUrl + "/pgs/" + generatedId + "/id.json";
                restTemplate.put(updateUrl, generatedId);
                
                log.info("Successfully added PG to Firebase with ID: {}", generatedId);
                return pg;
            }
            throw new RuntimeException("Failed to get generated key from Firebase response");
        } catch (Exception e) {
            log.error("Firebase save failed for PG {}: {}", pg.getName(), e.getMessage(), e);
            throw new RuntimeException("Failed to save PG to Firebase: " + e.getMessage());
        }
    }

    /**
     * Delete a PG by its ID directly from Firebase Database
     */
    public void deletePg(String id) {
        invalidateCache();
        try {
            String url = getPgUrl(id);
            restTemplate.delete(url);
            log.info("Deleted PG {} from Firebase", id);
        } catch (Exception e) {
            log.error("Failed to delete PG {} from Firebase: {}", id, e.getMessage());
            throw new RuntimeException("Delete failed: " + e.getMessage());
        }
    }

    /**
     * Get supported cities — fixed list.
     */
    public List<String> getCities() {
        return Arrays.asList("Hyderabad", "Bangalore");
    }

    /**
     * Get areas stub — actual area fetching is delegated to GoogleMapsService in PgController.
     */
    public List<String> getAreas(String city) {
        return Collections.emptyList();
    }

    /**
     * Check whether a PG with the given Google Maps Place ID already exists in Firebase
     */
    public boolean existsByPlaceId(String placeId) {
        if (placeId == null || placeId.isBlank()) return false;
        return getAllPgs().stream()
                .anyMatch(pg -> placeId.equals(pg.getPlaceId()));
    }

    /**
     * Sync a list of PGs from Google Maps / OpenStreetMap directly into Firebase — skips duplicates by placeId
     * @return count of newly added PGs
     */
    public int syncGoogleMapsPgs(List<Pg> googlePgs) {
        if (googlePgs == null || googlePgs.isEmpty()) return 0;
        
        // Fetch existing place IDs ONCE to avoid N+1 network requests
        Set<String> existingPlaceIds = getAllPgs().stream()
                .map(Pg::getPlaceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        int added = 0;
        for (Pg pg : googlePgs) {
            if (pg.getPlaceId() != null && existingPlaceIds.contains(pg.getPlaceId())) {
                log.debug("Skipping duplicate place: {} (placeId: {})", pg.getName(), pg.getPlaceId());
                continue;
            }
            try {
                addPg(pg);
                if (pg.getPlaceId() != null) {
                    existingPlaceIds.add(pg.getPlaceId());
                }
                log.info("Synced new PG to Firebase: {}", pg.getName());
                added++;
            } catch (Exception e) {
                log.warn("Failed to sync PG {} to Firebase: {}", pg.getName(), e.getMessage());
            }
        }
        return added;
    }
}
