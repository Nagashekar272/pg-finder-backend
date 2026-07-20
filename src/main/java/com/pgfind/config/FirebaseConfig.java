package com.pgfind.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Initializes the Firebase Admin SDK once at startup.
 * Supports three authentication modes (tried in order):
 *  1. GOOGLE_APPLICATION_CREDENTIALS env var pointing to a service-account JSON file
 *  2. firebase.credentials.path Spring property (set via .env / application.properties)
 *  3. FIREBASE_SERVICE_ACCOUNT_JSON env var containing the raw JSON string (for Render/cloud)
 *  4. Application Default Credentials (Google Cloud / Cloud Run)
 */
@Configuration
public class FirebaseConfig {

    private static final Logger log = LoggerFactory.getLogger(FirebaseConfig.class);

    @Value("${firebase.project.id:pg-find-c23ab}")
    private String projectId;

    /** Path to service account JSON — can be set via .env as firebase.credentials.path */
    @Value("${firebase.credentials.path:}")
    private String credentialsPath;

    @PostConstruct
    public void init() {
        if (!FirebaseApp.getApps().isEmpty()) {
            log.info("Firebase Admin SDK already initialized — skipping.");
            return;
        }

        try {
            GoogleCredentials credentials = resolveCredentials();
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(credentials)
                    .setProjectId(projectId)
                    .build();

            FirebaseApp.initializeApp(options);
            log.info("Firebase Admin SDK initialized successfully for project: {}", projectId);
        } catch (Exception e) {
            log.error("Firebase Admin SDK initialization failed: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to initialize Firebase Admin SDK", e);
        }
    }

    private GoogleCredentials resolveCredentials() throws Exception {
        // Option 1: Explicit file path via GOOGLE_APPLICATION_CREDENTIALS
        String credentialsFile = System.getenv("GOOGLE_APPLICATION_CREDENTIALS");
        if (credentialsFile != null && !credentialsFile.isBlank()) {
            log.info("Loading Firebase credentials from file: {}", credentialsFile);
            try (InputStream is = new FileInputStream(credentialsFile)) {
                return GoogleCredentials.fromStream(is)
                        .createScoped("https://www.googleapis.com/auth/cloud-platform");
            }
        }

        // Option 2: firebase.credentials.path from Spring property / .env file
        if (credentialsPath != null && !credentialsPath.isBlank()) {
            log.info("Loading Firebase credentials from firebase.credentials.path: {}", credentialsPath);
            try (InputStream is = new FileInputStream(credentialsPath)) {
                return GoogleCredentials.fromStream(is)
                        .createScoped("https://www.googleapis.com/auth/cloud-platform");
            }
        }

        // Option 3: Raw service account JSON stored in env var (for Render / cloud deployments)
        String serviceAccountJson = System.getenv("FIREBASE_SERVICE_ACCOUNT_JSON");
        if (serviceAccountJson != null && !serviceAccountJson.isBlank()) {
            log.info("Loading Firebase credentials from FIREBASE_SERVICE_ACCOUNT_JSON env var");
            try (InputStream is = new ByteArrayInputStream(serviceAccountJson.getBytes(StandardCharsets.UTF_8))) {
                return GoogleCredentials.fromStream(is)
                        .createScoped("https://www.googleapis.com/auth/cloud-platform");
            }
        }

        // Option 3: Application Default Credentials (Google Cloud environments)
        log.info("No explicit credentials found — falling back to Application Default Credentials");
        return GoogleCredentials.getApplicationDefault()
                .createScoped("https://www.googleapis.com/auth/cloud-platform");
    }
}
