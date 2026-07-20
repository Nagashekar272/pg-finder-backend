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
import java.util.Collections;


@Service
public class FirebaseService {

    private static final Logger log = LoggerFactory.getLogger(FirebaseService.class);
    
    private final RestTemplate restTemplate;
    private final List<Pg> fallbackDb = new ArrayList<>();
    private boolean useFallback = false;
    
    @Value("${firebase.database.url}")
    private String databaseUrl;

    public FirebaseService() {
        this.restTemplate = new RestTemplate();
        initializeFallbackDb();
    }

    private void initializeFallbackDb() {
        log.info("Initializing local in-memory fallback database with default listings...");
        fallbackDb.add(new Pg(
            "fb-1",
            "Stanza Living Hyderabad",
            "Hyderabad",
            "Madhapur",
            "Plot 12, Kavuri Hills, Madhapur, Hyderabad - 500081",
            "Coliving",
            Arrays.asList("Single", "Double"),
            12000.0,
            Arrays.asList("Wi-Fi", "AC", "Food", "Power Backup", "Gym", "Geyser", "Parking", "Security", "Washing Machine", "Housekeeping"),
            "+91 9876543210",
            "contact@stanzaliving.com",
            4.8,
            "https://images.unsplash.com/photo-1598928506311-c55ded91a20c?auto=format&fit=crop&w=800&q=80",
            "Stanza Living offers a premium, tech-enabled coliving experience in Kavuri Hills, Madhapur. Located near major IT hubs, it features double and single sharing options with modern decor, home-like multi-cuisine meals, state-of-the-art gym, high-speed Wi-Fi, and 24/7 security."
        ));
        fallbackDb.add(new Pg(
            "fb-2",
            "Zolo Stay Bangalore",
            "Bangalore",
            "Koramangala",
            "45, 4th Block, Near Sony Signal, Koramangala, Bangalore - 560034",
            "Coliving",
            Arrays.asList("Single", "Double", "Triple"),
            14500.0,
            Arrays.asList("Wi-Fi", "Food", "Power Backup", "Geyser", "Parking", "Security", "Washing Machine", "Housekeeping", "TV"),
            "+91 9123456789",
            "support@zolostays.com",
            4.6,
            "https://images.unsplash.com/photo-1522771739844-6a9f6d5f14af?auto=format&fit=crop&w=800&q=80",
            "Zolo Stay provides fully furnished rooms in Koramangala with all necessary amenities. Situated in the heart of Bangalore's startup district, it is surrounded by popular restaurants, cafes, and offices. The rent includes daily housekeeping, Wi-Fi, DTH, and security."
        ));
        fallbackDb.add(new Pg(
            "fb-3",
            "Sri Sai Girls PG",
            "Hyderabad",
            "Gachibowli",
            "Lane Opp. DLF Cyber City, Gachibowli, Hyderabad - 500032",
            "Girls",
            Arrays.asList("Double", "Triple", "Quadruple"),
            8500.0,
            Arrays.asList("Wi-Fi", "Food", "Geyser", "Parking", "Security", "Washing Machine", "Housekeeping"),
            "+91 8877665544",
            "srisaipg@gmail.com",
            4.2,
            "https://images.unsplash.com/photo-1595526114035-0d45ed16cfbf?auto=format&fit=crop&w=800&q=80",
            "A secure and homely PG accommodation exclusively for women. Located directly opposite the DLF Cyber City gate, it offers extremely convenient access for IT professionals. Includes 3-times North & South Indian meals, high security, and housekeeping services."
        ));
        fallbackDb.add(new Pg(
            "fb-4",
            "Sree Balaji Boys Hostel",
            "Hyderabad",
            "Hitech City",
            "Image Gardens Lane, Behind Cyber Towers, Hitech City, Hyderabad - 500081",
            "Boys",
            Arrays.asList("Double", "Triple"),
            7500.0,
            Arrays.asList("Wi-Fi", "Food", "Power Backup", "Parking", "Security", "Washing Machine", "Housekeeping"),
            "+91 7766554433",
            "balajiboys@yahoo.com",
            4.0,
            "https://images.unsplash.com/photo-1505691938895-1758d7feb511?auto=format&fit=crop&w=800&q=80",
            "Affordable and clean accommodation for men right next to Cyber Towers. Sree Balaji Boys Hostel is an ideal choice for job seekers and junior software engineers looking for budget-friendly stays. Includes Wi-Fi, hot water, and three meals daily."
        ));
        fallbackDb.add(new Pg(
            "fb-5",
            "Nestaway Indiranagar",
            "Bangalore",
            "Indiranagar",
            "234, 100 Feet Road, Near Metro Station, Indiranagar, Bangalore - 560038",
            "Coliving",
            Arrays.asList("Single", "Double"),
            16000.0,
            Arrays.asList("Wi-Fi", "AC", "Power Backup", "Gym", "Geyser", "Parking", "Security", "Washing Machine", "Housekeeping", "TV"),
            "+91 9988776655",
            "bookings@nestaway.com",
            4.7,
            "https://images.unsplash.com/photo-1554995207-c18c203602cb?auto=format&fit=crop&w=800&q=80",
            "Premium living spaces in Bangalore's upscale Indiranagar locality. This property offers high-end facilities, modular kitchens, luxury bed linen, power backup, high-speed fiber internet, and is situated within walking distance of Indiranagar metro station."
        ));
        fallbackDb.add(new Pg(
            "fb-6",
            "Elite Luxury PG for Men",
            "Bangalore",
            "HSR Layout",
            "89, Sector 2, 19th Main, HSR Layout, Bangalore - 560102",
            "Boys",
            Arrays.asList("Single", "Double"),
            11000.0,
            Arrays.asList("Wi-Fi", "AC", "Food", "Power Backup", "Geyser", "Parking", "Security", "Washing Machine", "Housekeeping", "TV"),
            "+91 9543210987",
            "eliteluxurypg@outlook.com",
            4.4,
            "https://images.unsplash.com/photo-1616486338812-3dadae4b4ace?auto=format&fit=crop&w=800&q=80",
            "Elite Luxury PG provides state-of-the-art facilities for working professionals in HSR Layout. We offer single and double occupancy rooms with attached washrooms, smart TVs in every room, gaming area, nutritious food prepared by professional chefs, and daily room cleaning."
        ));
        fallbackDb.add(new Pg(
            "fb-7",
            "Aura Living Girls PG",
            "Hyderabad",
            "Kondapur",
            "44, Raghavendra Colony, Kondapur, Hyderabad - 500084",
            "Girls",
            Arrays.asList("Single", "Double", "Triple"),
            9500.0,
            Arrays.asList("Wi-Fi", "Food", "Power Backup", "Geyser", "Security", "Washing Machine", "Housekeeping", "TV"),
            "+91 9012345678",
            "auralivingkondapur@gmail.com",
            4.3,
            "https://images.unsplash.com/photo-1560185007-c5ca9d2c014d?auto=format&fit=crop&w=800&q=80",
            "Located in the serene Raghavendra Colony in Kondapur, Aura Living provides a safe and peaceful environment for girls. It is highly secure, features card access controls, modern shared lounge, dynamic menu containing premium dishes, and prompt maintenance services."
        ));
        fallbackDb.add(new Pg(
            "fb-8",
            "Whitefield Coliving Spaces",
            "Bangalore",
            "Whitefield",
            "Plot 56, ITPL Main Road, Opp. Prestige Shantiniketan, Whitefield, Bangalore - 560066",
            "Coliving",
            Arrays.asList("Single", "Double"),
            13000.0,
            Arrays.asList("Wi-Fi", "AC", "Power Backup", "Gym", "Geyser", "Parking", "Security", "Washing Machine", "Housekeeping", "TV"),
            "+91 8098765432",
            "info@whitefieldcoliving.in",
            4.5,
            "https://images.unsplash.com/photo-1505693416388-ac5ce068fe85?auto=format&fit=crop&w=800&q=80",
            "Directly opposite Prestige Shantiniketan, this coliving space is custom built for Whitefield IT professionals. Walk to work while enjoying high-end community spaces, biometric security, dedicated workstation areas, high-speed internet, fitness center, and regular social events."
        ));
        fallbackDb.add(new Pg(
            "fb-9",
            "Sri Vigneswara Luxury Boys PG",
            "Hyderabad",
            "Kukatpally",
            "Road No 1, Near KPHB Metro Station, Kukatpally, Hyderabad - 500072",
            "Boys",
            Arrays.asList("Single", "Double", "Triple", "Four Sharing"),
            6500.0,
            Arrays.asList("Wi-Fi", "Food", "Power Backup", "Geyser", "Parking", "Security", "Washing Machine", "Housekeeping", "TV"),
            "+91 9440123456",
            "srivigneswarapg@gmail.com",
            4.7,
            "https://images.unsplash.com/photo-1505691938895-1758d7feb511?auto=format&fit=crop&w=800&q=80",
            "Sri Vigneswara Luxury Boys PG offers clean, spacious, and highly comfortable living for students and working professionals in Kukatpally / KPHB. Located close to Metro stations and IT hubs, providing 3 daily delicious meals, high-speed Wi-Fi, 24/7 hot water, power backup, and regular maintenance."
        ));
        fallbackDb.add(new Pg(
            "fb-10",
            "Sri Vigneswara Executive Boys PG",
            "Hyderabad",
            "Ameerpet",
            "Flat 202, Opp. Maitrivanam, Ameerpet, Hyderabad - 500038",
            "Boys",
            Arrays.asList("Double", "Triple", "Four Sharing"),
            6000.0,
            Arrays.asList("Wi-Fi", "Food", "Power Backup", "Geyser", "Security", "Washing Machine", "Housekeeping"),
            "+91 9848012345",
            "srivigneswara.ameerpet@gmail.com",
            4.5,
            "https://images.unsplash.com/photo-1616486338812-3dadae4b4ace?auto=format&fit=crop&w=800&q=80",
            "Sri Vigneswara Executive Boys PG in Ameerpet is ideal for software trainees, job seekers, and students. Conveniently located near major coaching centers and Metro station, offering nutritious food, high-speed internet, and 24/7 security."
        ));
        fallbackDb.add(new Pg(
            "fb-11",
            "Sri Vigneswara Deluxe Boys PG",
            "Hyderabad",
            "Madhapur",
            "Near Cyber Towers, Madhapur, Hyderabad - 500081",
            "Boys",
            Arrays.asList("Single", "Double", "Triple"),
            7500.0,
            Arrays.asList("Wi-Fi", "AC", "Food", "Power Backup", "Geyser", "Parking", "Security", "Washing Machine", "Housekeeping", "TV"),
            "+91 9100098765",
            "srivigneswara.madhapur@gmail.com",
            4.6,
            "https://images.unsplash.com/photo-1522771739844-6a9f6d5f14af?auto=format&fit=crop&w=800&q=80",
            "Sri Vigneswara Deluxe Boys PG in Madhapur offers premium luxury rooms with AC, attached bathrooms, LED TV, high-speed Wi-Fi, and tasty South & North Indian food for IT professionals working in Hitech City and Mindspace."
        ));
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
     * Fetch all PGs from Firebase (with short in-memory TTL caching)
     */
    public synchronized List<Pg> getAllPgs() {
        long now = System.currentTimeMillis();
        if (cachedPgs != null && (now - lastCacheTime) < CACHE_TTL_MS) {
            log.debug("Returning cached PGs list ({} items)", cachedPgs.size());
            return new ArrayList<>(cachedPgs);
        }

        if (useFallback) {
            log.debug("Using local fallback database to fetch all listings");
            cachedPgs = new ArrayList<>(fallbackDb);
            lastCacheTime = now;
            return new ArrayList<>(cachedPgs);
        }

        try {
            String url = getPgsUrl();
            log.debug("Fetching all PGs from Firebase: {}", url);
            
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

            // Always merge fallbackDb so seeded PGs (like Sri Vigneswara Boys PG) are guaranteed to be present
            for (Pg fbPg : fallbackDb) {
                boolean exists = pgsList.stream().anyMatch(p -> 
                    (p.getId() != null && p.getId().equalsIgnoreCase(fbPg.getId())) || 
                    (p.getName() != null && p.getName().equalsIgnoreCase(fbPg.getName()))
                );
                if (!exists) {
                    pgsList.add(fbPg);
                }
            }

            cachedPgs = pgsList;
            lastCacheTime = now;
            return new ArrayList<>(cachedPgs);
        } catch (Exception e) {
            log.warn("Firebase query failed. Switching to in-memory fallback database. Error: {}", e.getMessage());
            useFallback = true;
            cachedPgs = new ArrayList<>(fallbackDb);
            lastCacheTime = now;
            return new ArrayList<>(cachedPgs);
        }
    }

    /**
     * Fetch a single PG by its ID
     */
    public Pg getPgById(String id) {
        if (useFallback || id.startsWith("fb-")) {
            log.debug("Using local fallback database to fetch listing ID: {}", id);
            return fallbackDb.stream()
                    .filter(pg -> id.equals(pg.getId()))
                    .findFirst()
                    .orElse(null);
        }

        try {
            String url = getPgUrl(id);
            log.debug("Fetching PG by id from Firebase: {}", url);
            
            Pg pg = restTemplate.getForObject(url, Pg.class);
            if (pg != null) {
                pg.setId(id);
            }
            return pg;
        } catch (Exception e) {
            log.warn("Firebase fetch by ID failed. Searching fallback database. Error: {}", e.getMessage());
            return fallbackDb.stream()
                    .filter(pg -> id.equals(pg.getId()))
                    .findFirst()
                    .orElse(null);
        }
    }

    /**
     * Add a new PG to Firebase
     */
    public Pg addPg(Pg pg) {
        invalidateCache();
        if (useFallback) {
            log.debug("Adding new PG to local fallback database");
            String generatedId = "fb-" + UUID.randomUUID().toString();
            pg.setId(generatedId);
            fallbackDb.add(pg);
            log.info("Successfully added PG to local database: {}", generatedId);
            return pg;
        }

        try {
            String url = getPgsUrl();
            log.debug("Adding new PG to Firebase: {}", url);
            
            // Firebase REST POST returns { "name": "generated_id" }
            Map<String, String> response = restTemplate.postForObject(url, pg, Map.class);
            if (response != null && response.containsKey("name")) {
                String generatedId = response.get("name");
                pg.setId(generatedId);
                
                // Write back the generated ID inside the PG record
                String updateUrl = databaseUrl + "/pgs/" + generatedId + "/id.json";
                restTemplate.put(updateUrl, generatedId);
                
                log.info("Successfully added PG to Firebase with ID: {}", generatedId);
                
                // Also cache in local database to stay synchronized
                fallbackDb.add(pg);
                return pg;
            }
            throw new RuntimeException("Failed to get generated key from Firebase response");
        } catch (Exception e) {
            log.warn("Firebase save failed. Adding listing to local database. Error: {}", e.getMessage());
            String generatedId = "fb-" + UUID.randomUUID().toString();
            pg.setId(generatedId);
            fallbackDb.add(pg);
            log.info("Successfully added PG to local database: {}", generatedId);
            return pg;
        }
    }

    /**
     * Delete a PG by its ID from Firebase (and local fallback cache)
     */
    public void deletePg(String id) {
        invalidateCache();
        // Always remove from local cache
        fallbackDb.removeIf(p -> id.equals(p.getId()));

        if (useFallback) {
            log.info("Deleted PG {} from local fallback database", id);
            return;
        }
        try {
            String url = getPgUrl(id);
            restTemplate.delete(url);
            log.info("Deleted PG {} from Firebase", id);
        } catch (Exception e) {
            log.warn("Failed to delete PG {} from Firebase: {}", id, e.getMessage());
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
     * Returns empty list here; controller handles the real call.
     */
    public List<String> getAreas(String city) {
        return Collections.emptyList();
    }

    /**
     * Check whether a PG with the given Google Maps Place ID already exists
     */
    public boolean existsByPlaceId(String placeId) {
        if (placeId == null || placeId.isBlank()) return false;
        return getAllPgs().stream()
                .anyMatch(pg -> placeId.equals(pg.getPlaceId()));
    }

    /**
     * Sync a list of PGs from Google Maps — skips duplicates by placeId efficiently
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
                log.debug("Skipping duplicate Google Maps place: {} (placeId: {})", pg.getName(), pg.getPlaceId());
                continue;
            }
            addPg(pg);
            if (pg.getPlaceId() != null) {
                existingPlaceIds.add(pg.getPlaceId());
            }
            log.info("Synced new PG from Google Maps: {}", pg.getName());
            added++;
        }
        return added;
    }
}

