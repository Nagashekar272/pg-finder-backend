package com.pgfind.service;

import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.*;
import com.google.firebase.cloud.FirestoreClient;
import com.pgfind.model.Pg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Service for CRUD operations on the Cloud Firestore "pgs" collection.
 * All PG data is stored in and retrieved from Firestore — no in-memory mock data.
 */
@Service
public class FirebaseService {

    private static final Logger log = LoggerFactory.getLogger(FirebaseService.class);
    private static final String COLLECTION_PGS = "pgs";

    // Short-lived in-memory cache to reduce Firestore read costs
    private List<Pg> cachedPgs = null;
    private long lastCacheTime = 0;
    private static final long CACHE_TTL_MS = 30_000; // 30 seconds

    private Firestore getFirestore() {
        return FirestoreClient.getFirestore();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cache management
    // ─────────────────────────────────────────────────────────────────────────

    public synchronized void invalidateCache() {
        cachedPgs = null;
        lastCacheTime = 0;
        log.debug("Firestore cache invalidated");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // READ
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Fetch all PGs from Cloud Firestore (with 30s TTL cache).
     */
    public synchronized List<Pg> getAllPgs() {
        long now = System.currentTimeMillis();
        if (cachedPgs != null && (now - lastCacheTime) < CACHE_TTL_MS) {
            log.debug("Returning cached PG list ({} items)", cachedPgs.size());
            return new ArrayList<>(cachedPgs);
        }

        try {
            Firestore db = getFirestore();
            ApiFuture<QuerySnapshot> future = db.collection(COLLECTION_PGS).get();
            List<QueryDocumentSnapshot> documents = future.get().getDocuments();

            List<Pg> pgsList = new ArrayList<>();
            for (QueryDocumentSnapshot doc : documents) {
                Pg pg = documentToPg(doc);
                if (pg != null) pgsList.add(pg);
            }

            log.info("Fetched {} PGs from Firestore", pgsList.size());
            cachedPgs = pgsList;
            lastCacheTime = now;
            return new ArrayList<>(cachedPgs);

        } catch (Exception e) {
            log.error("Failed to fetch PGs from Firestore: {}", e.getMessage(), e);
            return cachedPgs != null ? new ArrayList<>(cachedPgs) : Collections.emptyList();
        }
    }

    /**
     * Fetch a single PG by its Firestore document ID.
     */
    public Pg getPgById(String id) {
        if (id == null || id.isBlank()) return null;
        try {
            Firestore db = getFirestore();
            DocumentSnapshot doc = db.collection(COLLECTION_PGS).document(id).get().get();
            if (doc.exists()) {
                return documentToPg(doc);
            }
            log.warn("PG with id {} not found in Firestore", id);
            return null;
        } catch (Exception e) {
            log.error("Failed to fetch PG {} from Firestore: {}", id, e.getMessage(), e);
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WRITE
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Add a new PG to Cloud Firestore. Firestore auto-generates the document ID.
     */
    public Pg addPg(Pg pg) {
        invalidateCache();
        try {
            Firestore db = getFirestore();
            Map<String, Object> data = pgToMap(pg);

            // Auto-generate document ID
            DocumentReference docRef = db.collection(COLLECTION_PGS).document();
            String generatedId = docRef.getId();
            data.put("id", generatedId);

            docRef.set(data).get(); // blocking write

            pg.setId(generatedId);
            log.info("Added PG '{}' to Firestore with ID: {}", pg.getName(), generatedId);
            return pg;

        } catch (Exception e) {
            log.error("Failed to add PG '{}' to Firestore: {}", pg.getName(), e.getMessage(), e);
            throw new RuntimeException("Failed to save PG to Firestore: " + e.getMessage());
        }
    }

    /**
     * Delete a PG document from Cloud Firestore by its document ID.
     */
    public void deletePg(String id) {
        invalidateCache();
        try {
            Firestore db = getFirestore();
            db.collection(COLLECTION_PGS).document(id).delete().get(); // blocking delete
            log.info("Deleted PG {} from Firestore", id);
        } catch (Exception e) {
            log.error("Failed to delete PG {} from Firestore: {}", id, e.getMessage(), e);
            throw new RuntimeException("Delete failed: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lookups / Metadata
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Supported cities.
     */
    public List<String> getCities() {
        return Arrays.asList("Hyderabad", "Bangalore");
    }

    /**
     * Area stub — real area fetching delegated to GoogleMapsService in PgController.
     */
    public List<String> getAreas(String city) {
        return Collections.emptyList();
    }

    /**
     * Check if a PG with the given placeId already exists in Firestore.
     */
    public boolean existsByPlaceId(String placeId) {
        if (placeId == null || placeId.isBlank()) return false;
        return getAllPgs().stream().anyMatch(pg -> placeId.equals(pg.getPlaceId()));
    }

    /**
     * Sync a list of PGs from OpenStreetMap into Firestore, skipping duplicates by placeId.
     *
     * @return number of newly added PGs
     */
    public int syncGoogleMapsPgs(List<Pg> googlePgs) {
        if (googlePgs == null || googlePgs.isEmpty()) return 0;

        // Load existing placeIds once to avoid per-record round trips
        Set<String> existingPlaceIds = getAllPgs().stream()
                .map(Pg::getPlaceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        int added = 0;
        for (Pg pg : googlePgs) {
            if (pg.getPlaceId() != null && existingPlaceIds.contains(pg.getPlaceId())) {
                log.debug("Skipping duplicate: {} (placeId: {})", pg.getName(), pg.getPlaceId());
                continue;
            }
            try {
                addPg(pg);
                if (pg.getPlaceId() != null) existingPlaceIds.add(pg.getPlaceId());
                added++;
            } catch (Exception e) {
                log.warn("Failed to sync PG '{}' to Firestore: {}", pg.getName(), e.getMessage());
            }
        }
        log.info("Synced {} new PGs to Firestore", added);
        return added;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers: Pg ↔ Firestore document
    // ─────────────────────────────────────────────────────────────────────────

    private Pg documentToPg(DocumentSnapshot doc) {
        try {
            Pg pg = new Pg();
            pg.setId(doc.getId());
            pg.setName(doc.getString("name"));
            pg.setCity(doc.getString("city"));
            pg.setArea(doc.getString("area"));
            pg.setAddress(doc.getString("address"));
            pg.setPgType(doc.getString("pgType"));
            pg.setContactNumber(doc.getString("contactNumber"));
            pg.setContactEmail(doc.getString("contactEmail"));
            pg.setImageUrl(doc.getString("imageUrl"));
            pg.setDescription(doc.getString("description"));
            pg.setPlaceId(doc.getString("placeId"));

            Double price = doc.getDouble("startingPrice");
            pg.setStartingPrice(price);

            Double rating = doc.getDouble("rating");
            pg.setRating(rating);

            @SuppressWarnings("unchecked")
            List<String> sharing = (List<String>) doc.get("sharingOptions");
            pg.setSharingOptions(sharing != null ? sharing : new ArrayList<>());

            @SuppressWarnings("unchecked")
            List<String> amenities = (List<String>) doc.get("amenities");
            pg.setAmenities(amenities != null ? amenities : new ArrayList<>());

            // New Google Places API fields
            pg.setLatitude(doc.getDouble("latitude"));
            pg.setLongitude(doc.getDouble("longitude"));
            Long totalRatingsVal = doc.getLong("totalRatings");
            pg.setTotalRatings(totalRatingsVal != null ? totalRatingsVal.intValue() : null);
            pg.setWebsite(doc.getString("website"));
            pg.setBusinessStatus(doc.getString("businessStatus"));
            pg.setOpeningStatus(doc.getString("openingStatus"));
            pg.setOpenNow(doc.getBoolean("openNow"));
            @SuppressWarnings("unchecked")
            List<String> photos = (List<String>) doc.get("photos");
            pg.setPhotos(photos != null ? photos : new ArrayList<>());

            return pg;
        } catch (Exception e) {
            log.warn("Failed to parse Firestore document {}: {}", doc.getId(), e.getMessage());
            return null;
        }
    }

    private Map<String, Object> pgToMap(Pg pg) {
        Map<String, Object> map = new HashMap<>();
        map.put("name",           pg.getName());
        map.put("city",           pg.getCity());
        map.put("area",           pg.getArea());
        map.put("address",        pg.getAddress());
        map.put("pgType",         pg.getPgType());
        map.put("sharingOptions", pg.getSharingOptions() != null ? pg.getSharingOptions() : new ArrayList<>());
        map.put("startingPrice",  pg.getStartingPrice());
        map.put("amenities",      pg.getAmenities() != null ? pg.getAmenities() : new ArrayList<>());
        map.put("contactNumber",  pg.getContactNumber());
        map.put("contactEmail",   pg.getContactEmail());
        map.put("rating",         pg.getRating());
        map.put("imageUrl",       pg.getImageUrl());
        map.put("description",    pg.getDescription());
        map.put("placeId",        pg.getPlaceId());

        // New Google Places API fields
        map.put("latitude",       pg.getLatitude());
        map.put("longitude",      pg.getLongitude());
        map.put("totalRatings",   pg.getTotalRatings());
        map.put("photos",         pg.getPhotos() != null ? pg.getPhotos() : new ArrayList<>());
        map.put("website",        pg.getWebsite());
        map.put("businessStatus", pg.getBusinessStatus());
        map.put("openingStatus",  pg.getOpeningStatus());
        map.put("openNow",        pg.getOpenNow());
        return map;
    }
}
