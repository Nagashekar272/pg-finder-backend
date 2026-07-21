package com.pgfind.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/otp")
public class OtpController {

    private static final Logger log = LoggerFactory.getLogger(OtpController.class);

    // In-memory store: target (email or mobile) -> OtpData
    private static final Map<String, OtpData> otpStore = new ConcurrentHashMap<>();
    private final Random random = new Random();

    @PostMapping("/send")
    public ResponseEntity<Map<String, Object>> sendOtp(@RequestBody Map<String, String> request) {
        String type = request.get("type"); // "email" or "mobile"
        String target = request.get("target"); // the email or phone number

        if (target == null || target.isBlank() || type == null || type.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Both 'type' and 'target' are required"));
        }

        target = target.trim();
        
        // Generate a 6-digit OTP code
        String otpCode = String.format("%06d", random.nextInt(1000000));
        long expiry = System.currentTimeMillis() + (5 * 60 * 1000); // 5 minutes validity
        otpStore.put(target.toLowerCase(), new OtpData(otpCode, expiry));

        log.info("[OTP VERIFICATION] Generated OTP [{}] for {} target: [{}]", otpCode, type, target);
        System.out.println("==================================================");
        System.out.printf("   [OTP VERIFICATION] OTP FOR %s (%s): %s\n", type.toUpperCase(), target, otpCode);
        System.out.println("==================================================");

        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "OTP sent successfully to " + target,
                "debugOtp", otpCode // returned for easy local testing
        ));
    }

    @PostMapping("/verify")
    public ResponseEntity<Map<String, Object>> verifyOtp(@RequestBody Map<String, String> request) {
        String target = request.get("target");
        String code = request.get("otp");

        if (target == null || target.isBlank() || code == null || code.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Both 'target' and 'otp' are required"));
        }

        target = target.trim().toLowerCase();
        code = code.trim();

        OtpData data = otpStore.get(target);
        if (data == null) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "No OTP found or code expired. Please request a new one."));
        }

        if (System.currentTimeMillis() > data.expiry) {
            otpStore.remove(target);
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "OTP code has expired."));
        }

        if (!data.code.equals(code)) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Invalid OTP code. Please check and try again."));
        }

        // Successfully verified, remove from store
        otpStore.remove(target);
        log.info("[OTP VERIFICATION] Successfully verified target: [{}]", target);

        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Verification successful!"
        ));
    }

    private static class OtpData {
        String code;
        long expiry;

        OtpData(String code, long expiry) {
            this.code = code;
            this.expiry = expiry;
        }
    }
}
