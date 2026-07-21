package com.pgfind.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.pgfind.model.Pg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
public class GooglePlacesService {

    private static final Logger log = LoggerFactory.getLogger(GooglePlacesService.class);

    private static final String TEXT_SEARCH_URL = "https://places.googleapis.com/v1/places:searchText";
    private static final String NEARBY_SEARCH_URL = "https://places.googleapis.com/v1/places:searchNearby";
    
    private final RestTemplate restTemplate;

    @Value("${google.places.api.key:}")
    private String apiKey;

    public GooglePlacesService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * Search for PGs in a specific area of a city.
     * Performs multiple targeted search queries using 11 different keywords in parallel,
     * automatically paginates over all results, deduplicates by Place ID,
     * and returns the list sorted by rating (descending) and distance from area center (ascending).
     */
    public List<Pg> searchPgsByArea(String city, String area) {
        if (apiKey == null || apiKey.isBlank() || "YOUR_API_KEY_HERE".equals(apiKey)) {
            log.warn("Google Places API Key is not configured. Returning empty list.");
            return Collections.emptyList();
        }

        log.info("Starting enhanced Google Places API search for City: {}, Area: {}", city, area);

        // 11 distinct keywords requested
        List<String> keywords = Arrays.asList(
                "PG",
                "Paying Guest",
                "Men's PG",
                "Women's PG",
                "Boys Hostel",
                "Girls Hostel",
                "Hostel",
                "Co Living",
                "Student Hostel",
                "Working Men's Hostel",
                "Working Women's Hostel"
        );

        List<String> queries = keywords.stream()
                .map(kw -> kw + " in " + area + ", " + city)
                .toList();

        List<CompletableFuture<List<Pg>>> futures = queries.stream()
                .map(query -> CompletableFuture.supplyAsync(() -> performTextSearch(query, city, area)))
                .toList();

        // Wait for all searches to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        Map<String, Pg> deduplicatedPgs = new LinkedHashMap<>();
        for (CompletableFuture<List<Pg>> future : futures) {
            try {
                for (Pg pg : future.get()) {
                    if (pg.getPlaceId() != null) {
                        deduplicatedPgs.putIfAbsent(pg.getPlaceId(), pg);
                    }
                }
            } catch (Exception e) {
                log.error("Error executing parallel PG search query: {}", e.getMessage(), e);
            }
        }

        // Resolve area center coordinates for sorting
        LatLng center = resolveAreaCenter(city, area);
        if (center == null) {
            center = calculateCentroid(deduplicatedPgs.values());
        }

        // Sort: Rating descending (primary), distance from center ascending (secondary)
        List<Pg> sortedResults = sortPgs(deduplicatedPgs.values(), center, true);
        log.info("Completed dynamic Google Places API search. Found {} unique PG listings in {}, {}", sortedResults.size(), area, city);
        return sortedResults;
    }

    /**
     * Search for PGs nearby the given coordinates (lat, lon).
     * Performs searches from multiple grid coordinates covering the locality to maximize coverage,
     * deduplicates results, and returns them sorted by distance (ascending) and rating (descending).
     */
    public List<Pg> searchNearbyPgs(double lat, double lon) {
        if (apiKey == null || apiKey.isBlank() || "YOUR_API_KEY_HERE".equals(apiKey)) {
            log.warn("Google Places API Key is not configured. Returning empty list.");
            return Collections.emptyList();
        }

        log.info("Starting multi-coordinate Google Places Nearby Search at lat={}, lon={}", lat, lon);

        // Generate grid of 5 points (Center + North, South, East, West offsets) to cover the locality
        List<LatLng> gridPoints = new ArrayList<>();
        
        LatLng centerPoint = new LatLng();
        centerPoint.setLatitude(lat);
        centerPoint.setLongitude(lon);
        gridPoints.add(centerPoint);

        double offsetMeters = 2000.0;
        double deltaLat = offsetMeters / 111000.0;
        double deltaLon = offsetMeters / (111000.0 * Math.cos(Math.toRadians(lat)));

        LatLng north = new LatLng();
        north.setLatitude(lat + deltaLat);
        north.setLongitude(lon);
        gridPoints.add(north);

        LatLng south = new LatLng();
        south.setLatitude(lat - deltaLat);
        south.setLongitude(lon);
        gridPoints.add(south);

        LatLng east = new LatLng();
        east.setLatitude(lat);
        east.setLongitude(lon + deltaLon);
        gridPoints.add(east);

        LatLng west = new LatLng();
        west.setLatitude(lat);
        west.setLongitude(lon - deltaLon);
        gridPoints.add(west);

        List<CompletableFuture<List<Pg>>> futures = gridPoints.stream()
                .map(pt -> CompletableFuture.supplyAsync(() -> performNearbySearchForPoint(pt)))
                .toList();

        // Wait for all searches to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        Map<String, Pg> deduplicatedPgs = new LinkedHashMap<>();
        for (CompletableFuture<List<Pg>> future : futures) {
            try {
                for (Pg pg : future.get()) {
                    if (pg.getPlaceId() != null) {
                        deduplicatedPgs.putIfAbsent(pg.getPlaceId(), pg);
                    }
                }
            } catch (Exception e) {
                log.error("Error executing parallel Nearby Search query: {}", e.getMessage(), e);
            }
        }

        // Sort: Distance from center ascending (primary), rating descending (secondary)
        List<Pg> sortedResults = sortPgs(deduplicatedPgs.values(), centerPoint, false);
        log.info("Completed multi-coordinate Nearby Search. Found {} unique PG listings.", sortedResults.size());
        return sortedResults;
    }

    /**
     * Executes Nearby Search for a specific coordinates point.
     */
    private List<Pg> performNearbySearchForPoint(LatLng pt) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Goog-Api-Key", apiKey);
            headers.set("X-Goog-FieldMask", "places.id,places.displayName,places.formattedAddress,places.location,places.rating,places.userRatingCount,places.photos,places.nationalPhoneNumber,places.websiteUri,places.businessStatus,places.regularOpeningHours,places.types");

            Map<String, Object> circle = new HashMap<>();
            circle.put("radius", 2500.0); // 2.5 km radius per point to ensure dense overlap

            Map<String, Object> center = new HashMap<>();
            center.put("latitude", pt.getLatitude());
            center.put("longitude", pt.getLongitude());
            circle.put("center", center);

            Map<String, Object> locationRestriction = new HashMap<>();
            locationRestriction.put("circle", circle);

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("includedTypes", Arrays.asList("lodging"));
            requestBody.put("maxResultCount", 20); // API limit per Nearby Search request
            requestBody.put("locationRestriction", locationRestriction);

            HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<SearchResponse> response = restTemplate.exchange(
                    NEARBY_SEARCH_URL,
                    HttpMethod.POST,
                    requestEntity,
                    SearchResponse.class
            );

            if (response.getBody() == null || response.getBody().getPlaces() == null) {
                return Collections.emptyList();
            }

            return response.getBody().getPlaces().stream()
                    .filter(this::isLodgingAccommodation)
                    .map(place -> mapToPg(place, "Unknown City", "Nearby"))
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Nearby Search for point [lat={}, lon={}] failed: {}", pt.getLatitude(), pt.getLongitude(), e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Executes a Google Places Text Search query, handles pagination using pageToken, and maps the results.
     * Continues requesting pages until no nextPageToken is available.
     */
    private List<Pg> performTextSearch(String query, String city, String area) {
        List<Pg> allPgs = new ArrayList<>();
        String pageToken = null;

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Goog-Api-Key", apiKey);
            headers.set("X-Goog-FieldMask", "places.id,places.displayName,places.formattedAddress,places.location,places.rating,places.userRatingCount,places.photos,places.nationalPhoneNumber,places.websiteUri,places.businessStatus,places.regularOpeningHours,places.types,nextPageToken");

            do {
                Map<String, Object> requestBody = new HashMap<>();
                requestBody.put("textQuery", query);
                if (pageToken != null) {
                    requestBody.put("pageToken", pageToken);
                    // Introduce a tiny delay (200ms) for rate-limiting compliance
                    Thread.sleep(200);
                }

                HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);
                ResponseEntity<SearchResponse> response = restTemplate.exchange(
                        TEXT_SEARCH_URL,
                        HttpMethod.POST,
                        requestEntity,
                        SearchResponse.class
                );

                if (response.getBody() == null) {
                    break;
                }

                List<GooglePlace> places = response.getBody().getPlaces();
                if (places != null) {
                    places.stream()
                            .filter(this::isLodgingAccommodation)
                            .map(place -> mapToPg(place, city, area))
                            .forEach(allPgs::add);
                }

                pageToken = response.getBody().getNextPageToken();

            } while (pageToken != null && !pageToken.isBlank());

        } catch (Exception e) {
            log.error("Text search failed for query [{}]: {}", query, e.getMessage());
        }

        return allPgs;
    }

    /**
     * Resolves the coordinate point for a specific area/locality to serve as the sorting reference center.
     */
    private LatLng resolveAreaCenter(String city, String area) {
        String query = area + ", " + city;
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Goog-Api-Key", apiKey);
            headers.set("X-Goog-FieldMask", "places.location");

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("textQuery", query);

            HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<SearchResponse> response = restTemplate.exchange(
                    TEXT_SEARCH_URL,
                    HttpMethod.POST,
                    requestEntity,
                    SearchResponse.class
            );

            if (response.getBody() != null && response.getBody().getPlaces() != null && !response.getBody().getPlaces().isEmpty()) {
                return response.getBody().getPlaces().get(0).getLocation();
            }
        } catch (Exception e) {
            log.warn("Failed to geocode area center for [{}]: {}", query, e.getMessage());
        }
        return null;
    }

    /**
     * Calculates the centroid coordinate from a collection of PGs.
     */
    private LatLng calculateCentroid(Collection<Pg> pgs) {
        double sumLat = 0;
        double sumLon = 0;
        int count = 0;
        for (Pg pg : pgs) {
            if (pg.getLatitude() != null && pg.getLongitude() != null) {
                sumLat += pg.getLatitude();
                sumLon += pg.getLongitude();
                count++;
            }
        }
        if (count > 0) {
            LatLng centroid = new LatLng();
            centroid.setLatitude(sumLat / count);
            centroid.setLongitude(sumLon / count);
            return centroid;
        }
        return null;
    }

    /**
     * Filters out non-accommodation places from search results.
     */
    private boolean isLodgingAccommodation(GooglePlace place) {
        if (place == null) return false;

        // Check if explicit Google Places type contains lodging
        if (place.getTypes() != null && place.getTypes().contains("lodging")) {
            return true;
        }

        // Case-insensitive name keywords check
        if (place.getDisplayName() != null && place.getDisplayName().getText() != null) {
            String name = place.getDisplayName().getText().toLowerCase();
            return name.contains("pg") ||
                    name.contains("paying guest") ||
                    name.contains("hostel") ||
                    name.contains("coliving") ||
                    name.contains("co-living") ||
                    name.contains("stay") ||
                    name.contains("accommodation") ||
                    name.contains("home") ||
                    name.contains("residency") ||
                    name.contains("guest house") ||
                    name.contains("villa");
        }

        return false;
    }

    /**
     * Maps a GooglePlace DTO to our application's Pg model.
     */
    private Pg mapToPg(GooglePlace place, String city, String area) {
        Pg pg = new Pg();
        
        pg.setPlaceId(place.getId());
        
        String name = place.getDisplayName() != null ? place.getDisplayName().getText() : "Paying Guest Accommodation";
        pg.setName(name);
        
        pg.setAddress(place.getFormattedAddress() != null ? place.getFormattedAddress() : "Address not available");
        pg.setCity(city);
        pg.setArea(area);

        // Geolocation coordinates
        if (place.getLocation() != null) {
            pg.setLatitude(place.getLocation().getLatitude());
            pg.setLongitude(place.getLocation().getLongitude());
        }

        // Ratings
        pg.setRating(place.getRating() != null ? place.getRating() : 4.0);
        pg.setTotalRatings(place.getUserRatingCount() != null ? place.getUserRatingCount() : 0);

        // Photos: Map first photo to imageUrl and keep full list in photos field
        List<String> photoUrls = new ArrayList<>();
        if (place.getPhotos() != null && !place.getPhotos().isEmpty()) {
            for (GooglePhoto photo : place.getPhotos()) {
                if (photo.getName() != null) {
                    String photoUrl = String.format(
                            "https://places.googleapis.com/v1/%s/media?maxHeightPx=800&key=%s",
                            photo.getName(),
                            apiKey
                    );
                    photoUrls.add(photoUrl);
                }
            }
        }
        pg.setPhotos(photoUrls);
        if (!photoUrls.isEmpty()) {
            pg.setImageUrl(photoUrls.get(0));
        } else {
            // Unsplash lodging image fallback
            pg.setImageUrl("https://images.unsplash.com/photo-1598928506311-c55ded91a20c?auto=format&fit=crop&w=800&q=80");
        }

        // Contact info
        pg.setContactNumber(place.getNationalPhoneNumber() != null ? place.getNationalPhoneNumber() : "+91 9900990099");
        pg.setWebsite(place.getWebsiteUri());
        
        // Generate a clean mock email from name
        String cleanName = name.toLowerCase().replaceAll("[^a-z0-9]", "");
        pg.setContactEmail(cleanName.isEmpty() ? "owner@pgfind.com" : cleanName + "@pgfind.com");

        // Business Status
        pg.setBusinessStatus(place.getBusinessStatus());

        // Opening Status
        Boolean openNow = null;
        if (place.getRegularOpeningHours() != null) {
            openNow = place.getRegularOpeningHours().getOpenNow();
        }
        pg.setOpenNow(openNow);
        if (openNow != null) {
            pg.setOpeningStatus(openNow ? "Open Now" : "Closed");
        } else {
            pg.setOpeningStatus("Unknown");
        }

        // Infer PG type (Boys / Girls / Coliving)
        pg.setPgType(inferPgType(name, place.getTypes()));

        // Estimate starting price based on area/city
        Double basePrice = estimatePrice(city, area);
        pg.setStartingPrice(basePrice);

        // Default sharing options and amenities
        pg.setSharingOptions(Arrays.asList("Single", "Double", "Triple"));
        pg.setAmenities(Arrays.asList("Wi-Fi", "Power Backup", "Security", "Housekeeping", "Water Purifier"));

        // Description
        pg.setDescription(name + " is a premium accommodation located in " + area + ", " + city + 
                ". It features clean rooms, great facilities, and is close to local transit. Business Status: " + 
                (place.getBusinessStatus() != null ? place.getBusinessStatus() : "Operational"));

        return pg;
    }

    private String inferPgType(String name, List<String> types) {
        String lower = name.toLowerCase();
        if (lower.contains("girls") || lower.contains("ladies") || lower.contains("women") || lower.contains("female")) {
            return "Girls";
        }
        if (lower.contains("boys") || lower.contains("gents") || lower.contains("men") || lower.contains("male")) {
            return "Boys";
        }
        return "Coliving";
    }

    private Double estimatePrice(String city, String area) {
        double base = "Bangalore".equalsIgnoreCase(city) ? 10000.0 : 7500.0;
        String areaL = area != null ? area.toLowerCase() : "";
        if (areaL.contains("hitech") || areaL.contains("madhapur") ||
                areaL.contains("indiranagar") || areaL.contains("whitefield") ||
                areaL.contains("koramangala") || areaL.contains("gachibowli") ||
                areaL.contains("financial")) {
            base += 3500.0;
        }
        return base;
    }

    /**
     * Calculates distance using the Haversine formula.
     */
    private double calculateDistance(double lat1, double lon1, Double lat2, Double lon2) {
        if (lat2 == null || lon2 == null) {
            return Double.MAX_VALUE;
        }
        double earthRadius = 6371000; // meters
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                   Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                   Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return earthRadius * c;
    }

    /**
     * Sorts the combined PG list based on rating and distance.
     * @param primaryRatingFirst If true, sorts by rating desc (primary) and distance asc (secondary).
     *                           If false, sorts by distance asc (primary) and rating desc (secondary).
     */
    private List<Pg> sortPgs(Collection<Pg> pgs, LatLng center, boolean primaryRatingFirst) {
        if (pgs == null || pgs.isEmpty()) {
            return Collections.emptyList();
        }

        final double cLat = center != null && center.getLatitude() != null ? center.getLatitude() : 0.0;
        final double cLon = center != null && center.getLongitude() != null ? center.getLongitude() : 0.0;
        final boolean hasCenter = center != null && center.getLatitude() != null && center.getLongitude() != null;

        return pgs.stream()
                .sorted((pg1, pg2) -> {
                    double r1 = pg1.getRating() != null ? pg1.getRating() : 0.0;
                    double r2 = pg2.getRating() != null ? pg2.getRating() : 0.0;
                    int ratingCompare = Double.compare(r2, r1); // reverse order (highest first)

                    double d1 = hasCenter ? calculateDistance(cLat, cLon, pg1.getLatitude(), pg1.getLongitude()) : Double.MAX_VALUE;
                    double d2 = hasCenter ? calculateDistance(cLat, cLon, pg2.getLatitude(), pg2.getLongitude()) : Double.MAX_VALUE;
                    int distanceCompare = Double.compare(d1, d2); // normal order (closest first)

                    if (primaryRatingFirst) {
                        if (ratingCompare != 0) return ratingCompare;
                        return distanceCompare;
                    } else {
                        if (distanceCompare != 0) return distanceCompare;
                        return ratingCompare;
                    }
                })
                .collect(Collectors.toList());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DTO Inner Classes
    // ─────────────────────────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SearchResponse {
        private List<GooglePlace> places;
        private String nextPageToken;

        public List<GooglePlace> getPlaces() {
            return places;
        }

        public void setPlaces(List<GooglePlace> places) {
            this.places = places;
        }

        public String getNextPageToken() {
            return nextPageToken;
        }

        public void setNextPageToken(String nextPageToken) {
            this.nextPageToken = nextPageToken;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GooglePlace {
        private String id;
        private LocalizedText displayName;
        private String formattedAddress;
        private LatLng location;
        private Double rating;
        private Integer userRatingCount;
        private List<GooglePhoto> photos;
        private String nationalPhoneNumber;
        private String websiteUri;
        private String businessStatus;
        private OpeningHours regularOpeningHours;
        private List<String> types;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public LocalizedText getDisplayName() {
            return displayName;
        }

        public void setDisplayName(LocalizedText displayName) {
            this.displayName = displayName;
        }

        public String getFormattedAddress() {
            return formattedAddress;
        }

        public void setFormattedAddress(String formattedAddress) {
            this.formattedAddress = formattedAddress;
        }

        public LatLng getLocation() {
            return location;
        }

        public void setLocation(LatLng location) {
            this.location = location;
        }

        public Double getRating() {
            return rating;
        }

        public void setRating(Double rating) {
            this.rating = rating;
        }

        public Integer getUserRatingCount() {
            return userRatingCount;
        }

        public void setUserRatingCount(Integer userRatingCount) {
            this.userRatingCount = userRatingCount;
        }

        public List<GooglePhoto> getPhotos() {
            return photos;
        }

        public void setPhotos(List<GooglePhoto> photos) {
            this.photos = photos;
        }

        public String getNationalPhoneNumber() {
            return nationalPhoneNumber;
        }

        public void setNationalPhoneNumber(String nationalPhoneNumber) {
            this.nationalPhoneNumber = nationalPhoneNumber;
        }

        public String getWebsiteUri() {
            return websiteUri;
        }

        public void setWebsiteUri(String websiteUri) {
            this.websiteUri = websiteUri;
        }

        public String getBusinessStatus() {
            return businessStatus;
        }

        public void setBusinessStatus(String businessStatus) {
            this.businessStatus = businessStatus;
        }

        public OpeningHours getRegularOpeningHours() {
            return regularOpeningHours;
        }

        public void setRegularOpeningHours(OpeningHours regularOpeningHours) {
            this.regularOpeningHours = regularOpeningHours;
        }

        public List<String> getTypes() {
            return types;
        }

        public void setTypes(List<String> types) {
            this.types = types;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LocalizedText {
        private String text;

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LatLng {
        private Double latitude;
        private Double longitude;

        public Double getLatitude() {
            return latitude;
        }

        public void setLatitude(Double latitude) {
            this.latitude = latitude;
        }

        public Double getLongitude() {
            return longitude;
        }

        public void setLongitude(Double longitude) {
            this.longitude = longitude;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GooglePhoto {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OpeningHours {
        private Boolean openNow;

        public Boolean getOpenNow() {
            return openNow;
        }

        public void setOpenNow(Boolean openNow) {
            this.openNow = openNow;
        }
    }
}
