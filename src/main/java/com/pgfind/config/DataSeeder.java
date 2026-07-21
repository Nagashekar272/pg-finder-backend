package com.pgfind.config;

import com.pgfind.model.Pg;
import com.pgfind.service.FirebaseService;
import com.pgfind.service.GoogleMapsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);
    private final FirebaseService firebaseService;
    private final GoogleMapsService googleMapsService;

    public DataSeeder(FirebaseService firebaseService, GoogleMapsService googleMapsService) {
        this.firebaseService = firebaseService;
        this.googleMapsService = googleMapsService;
    }

    @Override
    public void run(String... args) throws Exception {
        try {
            List<Pg> existing = firebaseService.getAllPgs();
            if (existing == null || existing.isEmpty()) {
                log.info("Firebase database is empty. Performing initial dynamic sync from Google Places API for Kukatpally & Koramangala...");

                // Dynamic startup sync for Hyderabad
                try {
                    List<Pg> syncedHyd = googleMapsService.searchAndSyncPgs("Hyderabad", "Kukatpally");
                    if (syncedHyd != null) {
                        for (Pg pg : syncedHyd) {
                            firebaseService.addPg(pg);
                        }
                        log.info("Successfully synced {} real Hyderabad Kukatpally PGs on startup.", syncedHyd.size());
                    }
                } catch (Exception e) {
                    log.warn("Failed to sync Hyderabad Kukatpally PGs on startup: {}", e.getMessage());
                }

                // Dynamic startup sync for Bangalore
                try {
                    List<Pg> syncedBlr = googleMapsService.searchAndSyncPgs("Bangalore", "Koramangala");
                    if (syncedBlr != null) {
                        for (Pg pg : syncedBlr) {
                            firebaseService.addPg(pg);
                        }
                        log.info("Successfully synced {} real Bangalore Koramangala PGs on startup.", syncedBlr.size());
                    }
                } catch (Exception e) {
                    log.warn("Failed to sync Bangalore Koramangala PGs on startup: {}", e.getMessage());
                }
            } else {
                log.info("Firebase database already contains {} PGs. Skipping initial dynamic sync.", existing.size());
            }
        } catch (Exception e) {
            log.error("DataSeeder failed to check database status: {}", e.getMessage());
        }
    }
}
