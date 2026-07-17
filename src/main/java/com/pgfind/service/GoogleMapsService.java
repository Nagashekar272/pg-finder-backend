package com.pgfind.service;

import com.pgfind.model.Pg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

    // OpenStreetMap Overpass API — 100% Free, No Key Required
    private static final String OVERPASS_URL    = "https://overpass-api.de/api/interpreter";
    private static final String NOMINATIM_URL   = "https://nominatim.openstreetmap.org/search";
    private static final int    SEARCH_RADIUS_M = 3000; // 3 km radius search

    @Value("${osm.sync.mock-mode:false}")
    private boolean mockMode;

    public GoogleMapsService() {
        this.restTemplate = new RestTemplate();
    }

    /**
     * Entry point: search PGs from OpenStreetMap or mock data
     */
    public List<Pg> searchAndSyncPgs(String city, String area) {
        log.info("Starting OpenStreetMap sync — City: {}, Area: {} | Mock Mode: {}", city, area, mockMode);
        if (mockMode) {
            return generateMockPgs(city, area);
        }
        return fetchFromOpenStreetMap(city, area);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Dynamic area discovery
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Fetch real suburb/neighbourhood names for a city via Nominatim + Overpass.
     */
    @SuppressWarnings("unchecked")
    public List<String> fetchAreasForCity(String city) {
        log.info("Fetching real areas for city: {}", city);
        try {
            double[] bbox = getCityBoundingBox(city);
            if (bbox == null) return getFallbackAreas(city);

            // bbox = [south, west, north, east]
            String overpassQuery = "[out:json][timeout:30];\n" +
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
            if (resp.getBody() == null) return getFallbackAreas(city);

            List<Map<String, Object>> elements = (List<Map<String, Object>>) resp.getBody().get("elements");
            if (elements == null || elements.isEmpty()) return getFallbackAreas(city);

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

            if (areas.isEmpty()) return getFallbackAreas(city);
            log.info("Fetched {} real areas for {}", areas.size(), city);
            return areas;

        } catch (Exception e) {
            log.warn("fetchAreasForCity failed for {}: {}. Using fallback.", city, e.getMessage());
            return getFallbackAreas(city);
        }
    }

    /** Get city bounding box [south, west, north, east] from Nominatim. */
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

    /** Small static fallback if Nominatim / Overpass unavailable. */
    private List<String> getFallbackAreas(String city) {
        if ("Bangalore".equalsIgnoreCase(city)) {
            return Arrays.asList("BTM Layout", "Electronic City", "HSR Layout",
                    "Indiranagar", "Koramangala", "Marathahalli", "Whitefield");
        }
        return Arrays.asList("Ameerpet", "Banjara Hills", "Gachibowli",
                "Hitech City", "Kondapur", "Madhapur", "Kukatpally");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Nearby PG search (geolocation-based)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Search for PGs near given coordinates using Overpass + reverse geocoding.
     */
    @SuppressWarnings("unchecked")
    public List<Pg> searchNearbyPgs(double lat, double lon) {
        log.info("Searching nearby PGs at lat={}, lon={}", lat, lon);
        String[] loc = reverseGeocode(lat, lon);
        String city = loc[0];
        String area = loc[1];
        log.info("Reverse geocoded → {}, {}", area, city);

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
            if (resp.getBody() == null) return generateMockPgs(city, area);

            List<Map<String, Object>> elements = (List<Map<String, Object>>) resp.getBody().get("elements");
            if (elements == null || elements.isEmpty()) return generateMockPgs(city, area);

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

            return results.isEmpty() ? generateMockPgs(city, area) : results;

        } catch (Exception e) {
            log.error("searchNearbyPgs failed: {}", e.getMessage(), e);
            return generateMockPgs(city, area);
        }
    }

    /** Reverse-geocode lat/lon → [city, area] using Nominatim. */
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
    // STEP 1 — Nominatim geocoding: area name → lat/lon
    // ─────────────────────────────────────────────────────────────────────────
    @SuppressWarnings("unchecked")
    private double[] geocodeArea(String area, String city) {
        try {
            String query = area + ", " + city + ", India";
            String url   = NOMINATIM_URL + "?q=" + query.replace(" ", "+") + "&format=json&limit=1";

            HttpHeaders headers = new HttpHeaders();
            // Nominatim requires a descriptive User-Agent header and Referer to prevent 403s
            headers.set("User-Agent", "PGFindApp-StartupSyncService-v2/2.1 (contact.pgfind.service@gmail.com; developer: nagas)");
            headers.set("Referer", "http://localhost:8080");
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            log.info("Nominatim geocoding: {}", query);
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
        // Fallback coordinates for major areas if geocoding fails
        return getFallbackCoordinates(city, area);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STEP 2 — Overpass API: fetch guest houses / hostels near lat/lon
    // ─────────────────────────────────────────────────────────────────────────
    @SuppressWarnings("unchecked")
    private List<Pg> fetchFromOpenStreetMap(String city, String area) {
        List<Pg> results = new ArrayList<>();
        try {
            double[] coords = geocodeArea(area, city);
            double lat = coords[0];
            double lon = coords[1];

            // Build Overpass QL query — searches multiple accommodation types
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
                return generateMockPgs(city, area);
            }

            List<Map<String, Object>> elements = (List<Map<String, Object>>) response.getBody().get("elements");
            if (elements == null || elements.isEmpty()) {
                log.info("No OSM accommodations found near {}, {} — using mock data as fallback", area, city);
                return generateMockPgs(city, area);
            }

            log.info("Overpass returned {} raw OSM elements", elements.size());

            int addedCount = 0;
            Random rnd = new Random();
            for (Map<String, Object> el : elements) {
                if (addedCount >= 20) { // cap at 20 named ones per sync
                    break;
                }
                Map<String, Object> tags = (Map<String, Object>) el.get("tags");
                if (tags == null) continue;

                String name = (String) tags.get("name");
                if (name == null || name.isBlank()) continue; // skip unnamed places

                // Build OSM unique Place ID
                String osmType = (String) el.get("type");
                Object osmId   = el.get("id");
                String placeId = "osm-" + osmType + "-" + osmId;

                // Build address from OSM addr:* tags
                String address = buildAddress(tags, area, city);

                // Infer PG type from name / tags
                String pgType = inferPgType(name, tags);

                // Generate premium-looking rating: 3.8 to 4.8
                Double rating = 3.8 + (rnd.nextInt(11) / 10.0);
                if (tags.get("stars") != null) {
                    try { rating = Double.parseDouble(tags.get("stars").toString()); } catch (Exception ignored) {}
                }

                // Phone / email from OSM tags
                String phone = getTag(tags, "phone", "contact:phone", "contact:mobile");
                String email = getTag(tags, "email", "contact:email");
                if (phone == null) phone = "+91 9" + (100000000 + rnd.nextInt(899999999));
                if (email == null) email = name.toLowerCase().replaceAll("[^a-z0-9]", "") + "@pgfind.com";

                // Generate premium randomized pricing based on area
                Double basePrice = estimatePrice(city, area);
                Double price = basePrice + (rnd.nextInt(5) * 500) - 1000;

                // Set varied sharing options
                List<String> sharingOptions = new ArrayList<>();
                int sharingType = rnd.nextInt(3);
                if (sharingType == 0) {
                    sharingOptions.addAll(Arrays.asList("Single", "Double"));
                } else if (sharingType == 1) {
                    sharingOptions.addAll(Arrays.asList("Double", "Triple"));
                } else {
                    sharingOptions.addAll(Arrays.asList("Single", "Double", "Triple"));
                }

                // Dynamic, premium amenities distribution
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

            log.info("Mapped {} valid PG listings from OpenStreetMap data", results.size());

        } catch (Exception e) {
            log.error("Overpass API fetch failed: {}", e.getMessage(), e);
            log.info("Falling back to mock data");
            return generateMockPgs(city, area);
        }

        // If OSM returned elements but none had names, use mock fallback
        if (results.isEmpty()) {
            log.info("All OSM elements lacked names — using mock data fallback");
            return generateMockPgs(city, area);
        }
        return results;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static final String[] GIRLS_IMAGES = {
        "https://images.unsplash.com/photo-1560185007-c5ca9d2c014d?auto=format&fit=crop&w=800&q=80",
        "https://images.unsplash.com/photo-1595526114035-0d45ed16cfbf?auto=format&fit=crop&w=800&q=80",
        "https://images.unsplash.com/photo-1505693416388-ac5ce068fe85?auto=format&fit=crop&w=800&q=80"
    };
    private static final String[] BOYS_IMAGES = {
        "https://images.unsplash.com/photo-1616486338812-3dadae4b4ace?auto=format&fit=crop&w=800&q=80",
        "https://images.unsplash.com/photo-1505691938895-1758d7feb511?auto=format&fit=crop&w=800&q=80",
        "https://images.unsplash.com/photo-1522771739844-6a9f6d5f14af?auto=format&fit=crop&w=800&q=80"
    };
    private static final String[] COLIVING_IMAGES = {
        "https://images.unsplash.com/photo-1554995207-c18c203602cb?auto=format&fit=crop&w=800&q=80",
        "https://images.unsplash.com/photo-1598928506311-c55ded91a20c?auto=format&fit=crop&w=800&q=80",
        "https://images.unsplash.com/photo-1522771739844-6a9f6d5f14af?auto=format&fit=crop&w=800&q=80"
    };

    private String getImageByType(String pgType, Random rnd) {
        String[] pool;
        if ("Girls".equals(pgType)) {
            pool = GIRLS_IMAGES;
        } else if ("Boys".equals(pgType)) {
            pool = BOYS_IMAGES;
        } else {
            pool = COLIVING_IMAGES;
        }
        return pool[rnd.nextInt(pool.length)];
    }

    /** Overpass QL query — searches guest_house, hostel, lodging within radius */
    private String buildOverpassQuery(double lat, double lon) {
        String around = "(around:" + SEARCH_RADIUS_M + "," + lat + "," + lon + ")";
        return "[out:json][timeout:30];\n(\n" +
               "  node[\"amenity\"=\"guest_house\"]" + around + ";\n" +
               "  node[\"tourism\"=\"hostel\"]"       + around + ";\n" +
               "  node[\"amenity\"=\"lodging\"]"      + around + ";\n" +
               "  node[\"tourism\"=\"hotel\"][\"name\"~\"PG|Paying Guest|Hostel|Coliving\",i]" + around + ";\n" +
               "  way[\"amenity\"=\"guest_house\"]"   + around + ";\n" +
               "  way[\"tourism\"=\"hostel\"]"        + around + ";\n" +
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

    /** Hardcoded fallback coordinates for all 32 areas if Nominatim is unavailable or rate-limited */
    private double[] getFallbackCoordinates(String city, String area) {
        Map<String, double[]> coords = new HashMap<>();
        
        // Hyderabad areas
        coords.put("madhapur",           new double[]{17.4435, 78.3772});
        coords.put("gachibowli",         new double[]{17.4401, 78.3489});
        coords.put("hitech city",        new double[]{17.4486, 78.3908});
        coords.put("hitech",             new double[]{17.4486, 78.3908});
        coords.put("kondapur",           new double[]{17.4592, 78.3615});
        coords.put("banjara hills",      new double[]{17.4176, 78.4419});
        coords.put("jubilee hills",      new double[]{17.4326, 78.4071});
        coords.put("kukatpally",         new double[]{17.4875, 78.3953});
        coords.put("ameerpet",           new double[]{17.4375, 78.4483});
        coords.put("kphb colony",        new double[]{17.4837, 78.3883});
        coords.put("kphb",               new double[]{17.4837, 78.3883});
        coords.put("miyapur",            new double[]{17.4966, 78.3498});
        coords.put("financial district", new double[]{17.4184, 78.3431});
        coords.put("manikonda",          new double[]{17.4018, 78.3653});
        coords.put("begumpet",           new double[]{17.4447, 78.4664});
        coords.put("secunderabad",       new double[]{17.4399, 78.4983});
        coords.put("mehdipatnam",        new double[]{17.3916, 78.4400});
        coords.put("nallagandla",        new double[]{17.4727, 78.3071});
        
        // Bangalore areas
        coords.put("koramangala",        new double[]{12.9352, 77.6245});
        coords.put("indiranagar",        new double[]{12.9784, 77.6408});
        coords.put("hsr layout",         new double[]{12.9116, 77.6389});
        coords.put("hsr",                new double[]{12.9116, 77.6389});
        coords.put("whitefield",         new double[]{12.9698, 77.7499});
        coords.put("btm layout",         new double[]{12.9166, 77.6101});
        coords.put("btm",                new double[]{12.9166, 77.6101});
        coords.put("electronic city",    new double[]{12.8452, 77.6602});
        coords.put("marathahalli",       new double[]{12.9569, 77.7011});
        coords.put("jayanagar",          new double[]{12.9308, 77.5838});
        coords.put("jp nagar",           new double[]{12.9079, 77.5858});
        coords.put("bellandur",          new double[]{12.9299, 77.6830});
        coords.put("sarjapur road",      new double[]{12.9105, 77.6845});
        coords.put("sarjapur",           new double[]{12.9105, 77.6845});
        coords.put("hebbal",             new double[]{13.0354, 77.5988});
        coords.put("yelahanka",          new double[]{13.1007, 77.5963});
        coords.put("rajajinagar",        new double[]{12.9896, 77.5550});
        coords.put("malleshwaram",       new double[]{13.0031, 77.5696});
        coords.put("domlur",             new double[]{12.9610, 77.6387});

        String key = area.toLowerCase().trim();
        if (coords.containsKey(key)) return coords.get(key);

        // Default city center fallback
        if ("Bangalore".equalsIgnoreCase(city)) return new double[]{12.9716, 77.5946};
        return new double[]{17.3850, 78.4867}; // Hyderabad center
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Mock data generator (used when osm.sync.mock-mode=true)
    // ─────────────────────────────────────────────────────────────────────────
    private List<Pg> generateMockPgs(String city, String area) {
        log.info("Generating mock PG listings for {}, {}", area, city);
        List<Pg> mock = new ArrayList<>();
        Random rnd = new Random();

        String[][] entries = {
            {"Zolo " + area + " Premium Stay",          "Coliving"},
            {"Stanza Living " + area + " House",         "Boys"},
            {"Sree Durga Girls PG " + area,              "Girls"},
        };

        String[] images = {
            "https://images.unsplash.com/photo-1554995207-c18c203602cb?auto=format&fit=crop&w=800&q=80",
            "https://images.unsplash.com/photo-1616486338812-3dadae4b4ace?auto=format&fit=crop&w=800&q=80",
            "https://images.unsplash.com/photo-1560185007-c5ca9d2c014d?auto=format&fit=crop&w=800&q=80",
        };

        for (int i = 0; i < entries.length; i++) {
            String name   = entries[i][0];
            String pgType = entries[i][1];
            String placeId = "osm-mock-" + city.toLowerCase() + "-" + area.toLowerCase() + "-" + i;

            mock.add(new Pg(
                null, name, city, area,
                "Plot " + (rnd.nextInt(200) + 1) + ", Main Road, " + area + ", " + city,
                pgType,
                Arrays.asList("Single", "Double"),
                estimatePrice(city, area) - (i * 1000.0),
                Arrays.asList("Wi-Fi", "Power Backup", "Security", "Washing Machine", "Housekeeping"),
                "+91 9" + (100000000 + rnd.nextInt(899999999)),
                name.toLowerCase().replaceAll("[^a-z0-9]", "") + "@pgfind.com",
                4.0 + (rnd.nextInt(10) / 10.0),
                images[i],
                name + " is a well-maintained stay in " + area + ", " + city +
                " ideal for working professionals. (Mock listing — enable real sync with osm.sync.mock-mode=false)",
                placeId
            ));
        }
        return mock;
    }
}
