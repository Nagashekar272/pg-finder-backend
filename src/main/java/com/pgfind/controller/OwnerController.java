package com.pgfind.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OwnerController — provides APIs for PG owner registration, login, and sessions.
 */
@RestController
@RequestMapping("/api/owner")
public class OwnerController {

    private static final Logger log = LoggerFactory.getLogger(OwnerController.class);

    // In-memory registry of owner accounts: email -> password
    private static final Map<String, String> registeredOwners = new ConcurrentHashMap<>();
    
    // In-memory token store: token -> email
    private static final Map<String, String> activeSessions = new ConcurrentHashMap<>();

    static {
        // Pre-seed a default PG owner account
        registeredOwners.put("owner@pgfind.com", "owner@123");
    }

    /**
     * POST /api/owner/register
     * Register a new PG Owner account
     */
    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, String> body) {
        String email = body.getOrDefault("email", "").trim().toLowerCase();
        String password = body.getOrDefault("password", "").trim();

        log.info("Owner registration attempt for: {}", email);

        if (email.isBlank() || password.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                "status", "error",
                "message", "Email and password are required."
            ));
        }

        if (registeredOwners.containsKey(email)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "status", "error",
                "message", "An account with this email already exists."
            ));
        }

        registeredOwners.put(email, password);
        log.info("Owner registered successfully: {}", email);

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
            "status", "success",
            "message", "Registration successful! You can now sign in."
        ));
    }

    /**
     * POST /api/owner/login
     * Login for PG Owners, issuing a session token
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        String email = body.getOrDefault("email", "").trim().toLowerCase();
        String password = body.getOrDefault("password", "").trim();

        log.info("Owner login attempt for: {}", email);

        String storedPassword = registeredOwners.get(email);
        if (storedPassword == null || !storedPassword.equals(password)) {
            log.warn("Owner login failed for: {}", email);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                "status", "error",
                "message", "Invalid email or password."
            ));
        }

        // Generate session token
        String token = "owner-token-" + UUID.randomUUID().toString().replace("-", "");
        activeSessions.put(token, email);

        log.info("Owner login successful. Issued token for: {}", email);
        return ResponseEntity.ok(Map.of(
            "status", "success",
            "token", token,
            "email", email,
            "message", "Login successful."
        ));
    }

    /**
     * POST /api/owner/logout
     * Log out an active owner session
     */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(@RequestHeader(value = "X-Owner-Token", required = false) String token) {
        if (token != null) {
            activeSessions.remove(token);
        }
        return ResponseEntity.ok(Map.of(
            "status", "success",
            "message", "Logged out successfully."
        ));
    }

    /**
     * Helper to validate owner token and retrieve owner email
     */
    public static String getEmailForToken(String token) {
        if (token == null) return null;
        return activeSessions.get(token);
    }
}
