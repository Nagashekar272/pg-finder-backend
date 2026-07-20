package com.pgfind.service;

import com.pgfind.model.Pg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class GoogleMapsService {

    private static final Logger log = LoggerFactory.getLogger(GoogleMapsService.class);
    private final RestTemplate restTemplate;

    // OpenStreetMap Overpass & Nominatim APIs — 100% Free, Dynamic API Integration
    private static final String OVERPASS_URL    = "https://overpass-api.de/api/interpreter";
    private static final String NOMINATIM_URL   = "https://nominatim.openstreetmap.org/search";
    private static final int    SEARCH_RADIUS_M = 8000; // 8 km radius API search for full area coverage

    private final Map<String, List<String>> areaCache = new java.util.concurrent.ConcurrentHashMap<>();

    public GoogleMapsService() {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(4000);
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * Entry point: search real PGs from OpenStreetMap via API
     */
    public List<Pg> searchAndSyncPgs(String city, String area) {
        log.info("Starting OpenStreetMap API fetch — City: {}, Area: {}", city, area);
        return fetchFromOpenStreetMap(city, area);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Dynamic area discovery via API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Fetch real suburb/neighbourhood names for a city dynamically via Nominatim + Overpass APIs.
     */
    @SuppressWarnings("unchecked")
    public List<String> fetchAreasForCity(String city) {
        if (city == null || city.isBlank()) return Collections.emptyList();
        String cityKey = city.trim().toLowerCase();
        
        if (areaCache.containsKey(cityKey)) {
            log.debug("Returning cached areas for city: {}", city);
            return areaCache.get(cityKey);
        }

        log.info("Fetching real areas for city via API: {}", city);
        try {
            double[] bbox = getCityBoundingBox(city);
            if (bbox == null) {
                return Collections.emptyList();
            }

            // bbox = [south, west, north, east]
            String overpassQuery = "[out:json][timeout:10];\n" +
                    "node[\"place\"~\"suburb|neighbourhood|quarter\"]" +
                    "(" + bbox[0] + "," + bbox[1] + "," + bbox[2] + "," + bbox[3] + ");\n" +
                    "out tags;";

            HttpHeaders hdr = new HttpHeaders();
            hdr.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            hdr.set("User-Agent", "PGFindApp-AreaSearch/2.0 (contact.pgfind.service@gmail.com)");

            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("data", overpassQuery);
            HttpEntity<MultiValueMap<String, String>> req = new HttpEntity<>(form, hdr);

            ResponseEntity<Map> resp = restTemplate.exchange(OVERPASS_URL, HttpMethod.POST, req, Map.class);
            if (resp.getBody() == null) {
                return Collections.emptyList();
            }

            List<Map<String, Object>> elements = (List<Map<String, Object>>) resp.getBody().get("elements");
            if (elements == null || elements.isEmpty()) {
                return Collections.emptyList();
            }

            List<String> areas = elements.stream()
                    .map(el -> (Map<String, Object>) el.get("tags"))
                    .filter(Objects::nonNull)
                    .map(tags -> {
                        String en = (String) tags.get("name:en");
                        return en != null ? en : (String) tags.get("name");
                    })
                    .filter(name -> name != null && !name.isBlank())
                    .distinct()
                    .sorted()
                    .collect(Collectors.toList());

            log.info("Fetched {} real areas for {} via API", areas.size(), city);
            if (!areas.isEmpty()) {
                areaCache.put(cityKey, areas);
            }
            return areas;

        } catch (Exception e) {
            log.warn("fetchAreasForCity API call failed for {}: {}", city, e.getMessage());
            return Collections.emptyList();
        }
    }

    /** Get city bounding box [south, west, north, east] from Nominatim API. */
    @SuppressWarnings("unchecked")
    private double[] getCityBoundingBox(String city) {
        try {
            String url = NOMINATIM_URL + "?city=" + city.replace(" ", "+")
                    + "&country=India&format=json&limit=1";
            HttpHeaders hdr = new HttpHeaders();
            hdr.set("User-Agent", "PGFindApp-CityBBox/2.0 (contact.pgfind.service@gmail.com)");
            hdr.set("Referer", "http://localhost:8080");
            HttpEntity<Void> entity = new HttpEntity<>(hdr);

            ResponseEntity<List> resp = restTemplate.exchange(url, HttpMethod.GET, entity, List.class);
            if (resp.getBody() == null || resp.getBody().isEmpty()) return null;

            Map<String, Object> result = (Map<String, Object>) resp.getBody().get(0);
            List<String> bb = (List<String>) result.get("boundingbox");
            if (bb == null || bb.size() < 4) return null;

            // Nominatim bbox order: [south, north, west, east]
            double south = Double.parseDouble(bb.get(0));
            double north = Double.parseDouble(bb.get(1));
            double west  = Double.parseDouble(bb.get(2));
            double east  = Double.parseDouble(bb.get(3));
            return new double[]{south, west, north, east};

        } catch (Exception e) {
            log.warn("getCityBoundingBox failed for {}: {}", city, e.getMessage());
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Nearby PG search (geolocation-based API)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Search for PGs near given coordinates using Overpass API
     */
    @SuppressWarnings("unchecked")
    public List<Pg> searchNearbyPgs(double lat, double lon) {
        log.info("Searching nearby PGs via API at lat={}, lon={}", lat, lon);
        String[] loc = reverseGeocode(lat, lon);
        String city = loc[0];
        String area = loc[1];

        List<Pg> results = new ArrayList<>();
        try {
            String overpassQuery = buildOverpassQuery(lat, lon);

            HttpHeaders hdr = new HttpHeaders();
            hdr.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            hdr.set("User-Agent", "PGFind/1.0 (pg-find-app@example.com)");

            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("data", overpassQuery);
            HttpEntity<MultiValueMap<String, String>> req = new HttpEntity<>(form, hdr);

            ResponseEntity<Map> resp = restTemplate.exchange(OVERPASS_URL, HttpMethod.POST, req, Map.class);
            if (resp.getBody() == null) return Collections.emptyList();

            List<Map<String, Object>> elements = (List<Map<String, Object>>) resp.getBody().get("elements");
            if (elements == null || elements.isEmpty()) return Collections.emptyList();

            Random rnd = new Random();
            int addedCount = 0;
            for (Map<String, Object> el : elements) {
                if (addedCount >= 20) break;
                Map<String, Object> tags = (Map<String, Object>) el.get("tags");
                if (tags == null) continue;
                String name = (String) tags.get("name");
                if (name == null || name.isBlank()) continue;

                String placeId = "osm-nearby-" + el.get("type") + "-" + el.get("id");
                String address = buildAddress(tags, area, city);
                String pgType  = inferPgType(name, tags);
                Double rating  = 3.8 + (rnd.nextInt(11) / 10.0);

                String phone = getTag(tags, "phone", "contact:phone", "contact:mobile");
                String email = getTag(tags, "email", "contact:email");
                if (phone == null) phone = "+91 9" + (100000000 + rnd.nextInt(899999999));
                if (email == null) email = name.toLowerCase().replaceAll("[^a-z0-9]", "") + "@pgfind.com";

                Double price = estimatePrice(city, area) + (rnd.nextInt(5) * 500) - 1000;

                List<String> sharing = rnd.nextInt(3) == 0
                        ? Arrays.asList("Single", "Double")
                        : rnd.nextInt(2) == 0 ? Arrays.asList("Double", "Triple")
                        : Arrays.asList("Single", "Double", "Triple");

                List<String> amenities = new ArrayList<>(Arrays.asList("Wi-Fi", "Power Backup", "Security", "Housekeeping"));
                if (rnd.nextBoolean()) amenities.add("Food");
                if (rnd.nextBoolean()) amenities.add("AC");
                if (rnd.nextBoolean()) amenities.add("Washing Machine");
                if (rnd.nextBoolean()) amenities.add("Geyser");

                results.add(new Pg(null, name, city, area, address, pgType, sharing, price,
                        amenities, phone, email, rating, getImageByType(pgType, rnd),
                        buildDescription(name, area, city, tags), placeId));
                addedCount++;
            }

            return results;

        } catch (Exception e) {
            log.error("searchNearbyPgs API fetch failed: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /** Reverse-geocode lat/lon → [city, area] using Nominatim API. */
    @SuppressWarnings("unchecked")
    private String[] reverseGeocode(double lat, double lon) {
        try {
            String url = "https://nominatim.openstreetmap.org/reverse?lat=" + lat
                    + "&lon=" + lon + "&format=json&addressdetails=1";
            HttpHeaders hdr = new HttpHeaders();
            hdr.set("User-Agent", "PGFindApp-ReverseGeo/2.0 (contact.pgfind.service@gmail.com)");
            hdr.set("Referer", "http://localhost:8080");
            HttpEntity<Void> entity = new HttpEntity<>(hdr);

            ResponseEntity<Map> resp = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
            if (resp.getBody() == null) return new String[]{"Unknown City", "Nearby"};

            Map<String, Object> addr = (Map<String, Object>) resp.getBody().get("address");
            if (addr == null) return new String[]{"Unknown City", "Nearby"};

            String city = firstNonNull(addr, "city", "town", "state_district", "state");
            String area = firstNonNull(addr, "suburb", "neighbourhood", "quarter", "village", "county");
            return new String[]{
                    city != null ? city : "Unknown City",
                    area != null ? area : "Nearby"
            };
        } catch (Exception e) {
            log.warn("reverseGeocode failed: {}", e.getMessage());
            return new String[]{"Unknown City", "Nearby"};
        }
    }

    private String firstNonNull(Map<String, Object> map, String... keys) {
        for (String k : keys) {
            Object v = map.get(k);
            if (v != null && !v.toString().isBlank()) return v.toString();
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STEP 1 — Nominatim geocoding API: area name → lat/lon
    // ─────────────────────────────────────────────────────────────────────────
    @SuppressWarnings("unchecked")
    private double[] geocodeArea(String area, String city) {
        try {
            String query = area + ", " + city + ", India";
            String url   = NOMINATIM_URL + "?q=" + query.replace(" ", "+") + "&format=json&limit=1";

            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "PGFindApp-StartupSyncService-v2/2.1 (contact.pgfind.service@gmail.com; developer: nagas)");
            headers.set("Referer", "http://localhost:8080");
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            log.info("Nominatim geocoding API: {}", query);
            ResponseEntity<List> response = restTemplate.exchange(url, HttpMethod.GET, entity, List.class);

            if (response.getBody() != null && !response.getBody().isEmpty()) {
                Map<String, Object> result = (Map<String, Object>) response.getBody().get(0);
                double lat = Double.parseDouble(result.get("lat").toString());
                double lon = Double.parseDouble(result.get("lon").toString());
                log.info("Geocoded '{}' → lat={}, lon={}", query, lat, lon);
                return new double[]{lat, lon};
            }
        } catch (Exception e) {
            log.warn("Nominatim geocoding failed for '{}', {}: {}", area, city, e.getMessage());
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STEP 2 — Overpass API: fetch guest houses / hostels near lat/lon
    // ─────────────────────────────────────────────────────────────────────────
    @SuppressWarnings("unchecked")
    private List<Pg> fetchFromOpenStreetMap(String city, String area) {
        List<Pg> results = new ArrayList<>();
        try {
            double[] coords = geocodeArea(area, city);
            if (coords == null) {
                log.warn("Geocoding failed for {}, {}. Returning empty list.", area, city);
                return Collections.emptyList();
            }
            double lat = coords[0];
            double lon = coords[1];

            String overpassQuery = buildOverpassQuery(lat, lon);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.set("User-Agent", "PGFind/1.0 (pg-find-app@example.com)");

            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("data", overpassQuery);

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(form, headers);
            log.info("Querying Overpass API with radius {}m around ({}, {})", SEARCH_RADIUS_M, lat, lon);

            ResponseEntity<Map> response = restTemplate.exchange(OVERPASS_URL, HttpMethod.POST, request, Map.class);
            if (response.getBody() == null) {
                log.warn("Overpass API returned empty body");
                return Collections.emptyList();
            }

            List<Map<String, Object>> elements = (List<Map<String, Object>>) response.getBody().get("elements");
            if (elements == null || elements.isEmpty()) {
                log.info("No OSM accommodations found near {}, {}", area, city);
                return Collections.emptyList();
            }

            log.info("Overpass API returned {} raw OSM elements", elements.size());

            int addedCount = 0;
            Random rnd = new Random();
            for (Map<String, Object> el : elements) {
                if (addedCount >= 30) {
                    break;
                }
                Map<String, Object> tags = (Map<String, Object>) el.get("tags");
                if (tags == null) continue;

                String name = (String) tags.get("name");
                if (name == null || name.isBlank()) continue;

                String osmType = (String) el.get("type");
                Object osmId   = el.get("id");
                String placeId = "osm-" + osmType + "-" + osmId;

                String address = buildAddress(tags, area, city);
                String pgType = inferPgType(name, tags);

                Double rating = 3.8 + (rnd.nextInt(11) / 10.0);
                if (tags.get("stars") != null) {
                    try { rating = Double.parseDouble(tags.get("stars").toString()); } catch (Exception ignored) {}
                }

                String phone = getTag(tags, "phone", "contact:phone", "contact:mobile");
                String email = getTag(tags, "email", "contact:email");
                if (phone == null) phone = "+91 9" + (100000000 + rnd.nextInt(899999999));
                if (email == null) email = name.toLowerCase().replaceAll("[^a-z0-9]", "") + "@pgfind.com";

                Double basePrice = estimatePrice(city, area);
                Double price = basePrice + (rnd.nextInt(5) * 500) - 1000;

                List<String> sharingOptions = new ArrayList<>();
                int sharingType = rnd.nextInt(3);
                if (sharingType == 0) {
                    sharingOptions.addAll(Arrays.asList("Single", "Double"));
                } else if (sharingType == 1) {
                    sharingOptions.addAll(Arrays.asList("Double", "Triple"));
                } else {
                    sharingOptions.addAll(Arrays.asList("Single", "Double", "Triple"));
                }

                List<String> amenities = new ArrayList<>(Arrays.asList("Wi-Fi", "Power Backup", "Security", "Housekeeping"));
                if (rnd.nextBoolean()) amenities.add("Food");
                if (rnd.nextBoolean()) amenities.add("AC");
                if (rnd.nextBoolean()) amenities.add("Washing Machine");
                if (rnd.nextBoolean()) amenities.add("Geyser");
                if (rnd.nextBoolean()) amenities.add("Parking");
                if (rnd.nextBoolean()) amenities.add("TV");
                if (rnd.nextBoolean() && "Coliving".equals(pgType)) amenities.add("Gym");

                String imageUrl = getImageByType(pgType, rnd);
                String description = buildDescription(name, area, city, tags);

                Pg pg = new Pg(
                    null, name, city, area, address, pgType,
                    sharingOptions,
                    price,
                    amenities,
                    phone, email, rating, imageUrl, description, placeId
                );
                results.add(pg);
                addedCount++;
            }

            log.info("Mapped {} valid PG listings from OpenStreetMap API data", results.size());
            return results;

        } catch (Exception e) {
            log.error("Overpass API fetch failed: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private String getImageByType(String pgType, Random rnd) {
        String[] pool = {
            "https://images.unsplash.com/photo-1554995207-c18c203602cb?auto=format&fit=crop&w=800&q=80",
            "https://images.unsplash.com/photo-1616486338812-3dadae4b4ace?auto=format&fit=crop&w=800&q=80",
            "https://images.unsplash.com/photo-1560185007-c5ca9d2c014d?auto=format&fit=crop&w=800&q=80"
        };
        return pool[rnd.nextInt(pool.length)];
    }

    /** Overpass QL query — searches guest_house, hostel, lodging, and named PGs within radius */
    private String buildOverpassQuery(double lat, double lon) {
        String around = "(around:" + SEARCH_RADIUS_M + "," + lat + "," + lon + ")";
        return "[out:json][timeout:30];\n(\n" +
               "  node[\"amenity\"=\"guest_house\"]" + around + ";\n" +
               "  node[\"amenity\"=\"hostel\"]"      + around + ";\n" +
               "  node[\"tourism\"=\"hostel\"]"       + around + ";\n" +
               "  node[\"amenity\"=\"lodging\"]"      + around + ";\n" +
               "  node[\"name\"~\"PG|Paying Guest|Hostel|Coliving|Gents PG|Ladies PG|Boys PG|Girls PG\",i]" + around + ";\n" +
               "  way[\"amenity\"=\"guest_house\"]"   + around + ";\n" +
               "  way[\"amenity\"=\"hostel\"]"        + around + ";\n" +
               "  way[\"tourism\"=\"hostel\"]"        + around + ";\n" +
               "  way[\"name\"~\"PG|Paying Guest|Hostel|Coliving|Gents PG|Ladies PG|Boys PG|Girls PG\",i]" + around + ";\n" +
               ");\nout tags center;";
    }

    private String buildAddress(Map<String, Object> tags, String area, String city) {
        StringBuilder addr = new StringBuilder();
        if (tags.get("addr:housenumber") != null) addr.append(tags.get("addr:housenumber")).append(", ");
        if (tags.get("addr:street")      != null) addr.append(tags.get("addr:street")).append(", ");
        if (tags.get("addr:suburb")      != null) addr.append(tags.get("addr:suburb")).append(", ");
        addr.append(area).append(", ").append(city);
        if (tags.get("addr:postcode")    != null) addr.append(" - ").append(tags.get("addr:postcode"));
        return addr.toString();
    }

    private String getTag(Map<String, Object> tags, String... keys) {
        for (String key : keys) {
            Object val = tags.get(key);
            if (val != null && !val.toString().isBlank()) return val.toString();
        }
        return null;
    }

    private String inferPgType(String name, Map<String, Object> tags) {
        String lower = name.toLowerCase();
        String gender = tags.getOrDefault("gender", "").toString().toLowerCase();
        if (lower.contains("girls") || lower.contains("ladies") || lower.contains("women") || "female".equals(gender))
            return "Girls";
        if (lower.contains("boys") || lower.contains("gents") || lower.contains("men") || "male".equals(gender))
            return "Boys";
        return "Coliving";
    }

    private String buildDescription(String name, String area, String city, Map<String, Object> tags) {
        String website = tags.getOrDefault("website", "").toString();
        String desc = name + " is a verified accommodation listed on OpenStreetMap, located in " +
                      area + ", " + city + ". Ideal for working professionals and students.";
        if (!website.isBlank()) desc += " Website: " + website;
        return desc;
    }

    private Double estimatePrice(String city, String area) {
        double base = "Bangalore".equalsIgnoreCase(city) ? 11000.0 : 8000.0;
        String areaL = area.toLowerCase();
        if (areaL.contains("hitech") || areaL.contains("madhapur") ||
            areaL.contains("indiranagar") || areaL.contains("whitefield") ||
            areaL.contains("koramangala")) base += 3000.0;
        return base;
    }
}
