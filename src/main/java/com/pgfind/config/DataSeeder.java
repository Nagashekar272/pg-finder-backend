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
        this.firebaseService    = firebaseService;
        this.googleMapsService  = googleMapsService;
    }

    @Override
    public void run(String... args) throws Exception {
        log.info("Startup database seeding is disabled. Sourcing listings dynamically via APIs.");
    }
}
