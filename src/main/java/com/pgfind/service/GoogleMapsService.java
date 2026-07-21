package com.pgfind.service;

import com.pgfind.model.Pg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class GoogleMapsService {

    private static final Logger log = LoggerFactory.getLogger(GoogleMapsService.class);
    private final GooglePlacesService googlePlacesService;

    public GoogleMapsService(GooglePlacesService googlePlacesService) {
        this.googlePlacesService = googlePlacesService;
    }

    /**
     * Entry point: search real PGs from Google Places API (New) dynamically.
     */
    public List<Pg> searchAndSyncPgs(String city, String area) {
        log.info("Starting Google Places API search — City: {}, Area: {}", city, area);
        return googlePlacesService.searchPgsByArea(city, area);
    }

    /**
     * Search for PGs near given coordinates using Google Places Nearby Search
     */
    public List<Pg> searchNearbyPgs(double lat, double lon) {
        log.info("Searching nearby PGs via Google Places Nearby Search at lat={}, lon={}", lat, lon);
        return googlePlacesService.searchNearbyPgs(lat, lon);
    }

    /**
     * Fetch the list of popular PG areas for the selected city.
     */
    public List<String> fetchAreasForCity(String city) {
        if (city == null || city.isBlank()) {
            return Collections.emptyList();
        }

        String cleanCity = city.trim().toLowerCase();
        log.info("Fetching popular areas for city: {}", city);

        if (cleanCity.contains("hyderabad")) {
            return Arrays.asList(
                    "Madhapur",
                    "Hitech City",
                    "Gachibowli",
                    "Kondapur",
                    "Kukatpally",
                    "KPHB",
                    "Miyapur",
                    "Ameerpet",
                    "Financial District"
            );
        } else if (cleanCity.contains("bangalore") || cleanCity.contains("bengaluru")) {
            return Arrays.asList(
                    "Whitefield",
                    "Electronic City",
                    "Marathahalli",
                    "Bellandur",
                    "HSR Layout",
                    "Koramangala",
                    "BTM Layout"
            );
        }

        // Return empty list if city is not supported
        return Collections.emptyList();
    }
}
