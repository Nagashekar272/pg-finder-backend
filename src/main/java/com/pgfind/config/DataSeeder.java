package com.pgfind.config;

import com.pgfind.model.Pg;
import com.pgfind.service.FirebaseService;
import com.pgfind.service.GoogleMapsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.*;

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
                log.info("Firebase database is empty. Seeding initial listings directly to Firebase...");

                List<Pg> initialPgs = Arrays.asList(
                        new Pg(
                                null,
                                "Sri Vigneswara Luxury Boys PG",
                                "Hyderabad",
                                "Kukatpally",
                                "Road No 1, Near KPHB Metro Station, Kukatpally, Hyderabad - 500072",
                                "Boys",
                                Arrays.asList("Single", "Double", "Triple", "Four Sharing"),
                                6500.0,
                                Arrays.asList("Wi-Fi", "Food", "Power Backup", "Geyser", "Parking", "Security",
                                        "Washing Machine", "Housekeeping", "TV"),
                                "+91 9440123456",
                                "srivigneswarapg@gmail.com",
                                4.7,
                                "https://images.unsplash.com/photo-1505691938895-1758d7feb511?auto=format&fit=crop&w=800&q=80",
                                "Sri Vigneswara Luxury Boys PG offers clean, spacious, and highly comfortable living for students and working professionals in Kukatpally / KPHB. Located close to Metro stations and IT hubs.",
                                "seed-sri-vigneswara-kukatpally"),
                        new Pg(
                                null,
                                "Sri Vigneswara Executive Boys PG",
                                "Hyderabad",
                                "Ameerpet",
                                "Flat 202, Opp. Maitrivanam, Ameerpet, Hyderabad - 500038",
                                "Boys",
                                Arrays.asList("Double", "Triple", "Four Sharing"),
                                6000.0,
                                Arrays.asList("Wi-Fi", "Food", "Power Backup", "Geyser", "Security", "Washing Machine",
                                        "Housekeeping"),
                                "+91 9848012345",
                                "srivigneswara.ameerpet@gmail.com",
                                4.5,
                                "https://images.unsplash.com/photo-1616486338812-3dadae4b4ace?auto=format&fit=crop&w=800&q=80",
                                "Sri Vigneswara Executive Boys PG in Ameerpet is ideal for software trainees, job seekers, and students near Metro station.",
                                "seed-sri-vigneswara-ameerpet"),
                        new Pg(
                                null,
                                "Sri Vigneswara Deluxe Boys PG",
                                "Hyderabad",
                                "Madhapur",
                                "Near Cyber Towers, Madhapur, Hyderabad - 500081",
                                "Boys",
                                Arrays.asList("Single", "Double", "Triple"),
                                7500.0,
                                Arrays.asList("Wi-Fi", "AC", "Food", "Power Backup", "Geyser", "Parking", "Security",
                                        "Washing Machine", "Housekeeping", "TV"),
                                "+91 9100098765",
                                "srivigneswara.madhapur@gmail.com",
                                4.6,
                                "https://images.unsplash.com/photo-1522771739844-6a9f6d5f14af?auto=format&fit=crop&w=800&q=80",
                                "Sri Vigneswara Deluxe Boys PG in Madhapur offers premium rooms with AC, attached bathrooms, LED TV, and tasty food for IT professionals.",
                                "seed-sri-vigneswara-madhapur"),
                        new Pg(
                                null,
                                "Stanza Living Hyderabad",
                                "Hyderabad",
                                "Madhapur",
                                "Plot 12, Kavuri Hills, Madhapur, Hyderabad - 500081",
                                "Coliving",
                                Arrays.asList("Single", "Double"),
                                12000.0,
                                Arrays.asList("Wi-Fi", "AC", "Food", "Power Backup", "Gym", "Geyser", "Parking",
                                        "Security", "Washing Machine", "Housekeeping"),
                                "+91 9876543210",
                                "contact@stanzaliving.com",
                                4.8,
                                "https://images.unsplash.com/photo-1598928506311-c55ded91a20c?auto=format&fit=crop&w=800&q=80",
                                "Stanza Living offers a premium coliving experience in Kavuri Hills, Madhapur.",
                                "seed-stanza-living-hyderabad"),
                        new Pg(
                                null,
                                "Zolo Stay Bangalore",
                                "Bangalore",
                                "Koramangala",
                                "45, 4th Block, Near Sony Signal, Koramangala, Bangalore - 560034",
                                "Coliving",
                                Arrays.asList("Single", "Double", "Triple"),
                                14500.0,
                                Arrays.asList("Wi-Fi", "Food", "Power Backup", "Geyser", "Parking", "Security",
                                        "Washing Machine", "Housekeeping", "TV"),
                                "+91 9123456789",
                                "support@zolostays.com",
                                4.6,
                                "https://images.unsplash.com/photo-1522771739844-6a9f6d5f14af?auto=format&fit=crop&w=800&q=80",
                                "Zolo Stay provides fully furnished rooms in Koramangala with all necessary amenities.",
                                "seed-zolo-stay-bangalore"));

                for (Pg pg : initialPgs) {
                    try {
                        firebaseService.addPg(pg);
                        log.info("Seeded PG directly to Firebase: {}", pg.getName());
                    } catch (Exception e) {
                        log.warn("Failed to seed PG {}: {}", pg.getName(), e.getMessage());
                    }
                }
            } else {
                log.info("Firebase database already contains {} PGs. Skipping initial seed.", existing.size());
            }
        } catch (Exception e) {
            log.error("DataSeeder failed to query or seed Firebase: {}", e.getMessage());
        }
    }
}
