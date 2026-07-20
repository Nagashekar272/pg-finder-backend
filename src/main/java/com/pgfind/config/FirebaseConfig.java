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
 * Credential resolution order:
 *  1. GOOGLE_APPLICATION_CREDENTIALS env var (file path)
 *  2. firebase.credentials.path Spring property (file path from .env)
 *  3. FIREBASE_SERVICE_ACCOUNT_JSON env var (raw JSON string — set this on Render)
 *  4. Hardcoded service account JSON (last resort / production fallback)
 */
@Configuration
public class FirebaseConfig {

    private static final Logger log = LoggerFactory.getLogger(FirebaseConfig.class);

    // Hardcoded fallback service account JSON for production environments
    // where env vars are not set (e.g. Render free tier)
    private static final String FALLBACK_SERVICE_ACCOUNT_JSON =
        "{\n" +
        "  \"type\": \"service_account\",\n" +
        "  \"project_id\": \"pg-find-c23ab\",\n" +
        "  \"private_key_id\": \"1b23ac94f2b84622c4dc30f099347c52382f0707\",\n" +
        "  \"private_key\": \"-----BEGIN PRIVATE KEY-----\\nMIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQC2nEjUeUvgIo+K\\nNPtBlBboSrtmbmPjgXYgfyzGQgavvgX8pUkkd7W/xA2hN+xdLnqAypQWByAE2qt0\\nKoWeHxrByo3qXi+2A/7XGMebyrH0lYWjRQUp/ogQMPr0Kw1lNJFWVwhEjvU6k8yb\\n2ktf3n25UVL6Egpt9Ys7dVri4VwjAUyYKcipEodrPAdCcYUQT8I+R1cNoIqCco8F\\njtOuFY/lEIfn8hIBZKf5T2qBN57XHQK/aCFlG1CEZX0MhoyqnaZXm3wf23XB4UfH\\nhjF1TOjltflcDzK/ST8sPxUDyKbvCiA5R+gic48gRjxdOJ3zn14mjSyK2OLGIepJ\\nwJoM+cA7AgMBAAECggEAKpb2sX98v9Cvs6c5tojIcvMDFBfI+kW2fEvM8IxzrMdf\\n9BXEolm9LPWXzDMT0IPHlIQq15xfzmIFvPkx4rgUcIBCaxf+frAd3qLr4xz6NjYt\\nTfAKDUpNB6G01f+4hxMkloOD7SF+dmQEQ5E9JSXqIq+h76sHfa/YAk0Tnni7GXqO\\nJ5MWFWCtX2zgXtYoK8glWexR3w9gsSB2s7l6uCoKNLCT8VWQ89/8qvaLbBFZ2JlZ\\nlYRNWje9cPaAeL6svqlYUky2EWgSZFW9vW9ON3WXjNm8Rzm3KY5mpj7Vzy1xHLpG\\n2dgaMVM7eQrMI5Pvt29ImUyGNmg/dQRfj7C2UUeLGQKBgQDhJV2w9ngJUfOCwI3v\\n8sNkyB1mr6GdytYqAlHROLfj4ixq/Mz+5QvyQJ2Z1nSJwJFZOBgVpDspInttNicD\\ni5/g7Dv09yRRiHJR6V5Tzo4uo6iK6OiGO6LhlBlyv0ylg8CDG07bURxn4GsYsPzi\\nBW0+NT7lNW94+CMtnKtWBMj5ZQKBgQDPoq1QjQYjlqQm2tkQeABYiVAUHmXUzwoV\\nQN6boQQyDbsecbF9I0O/mpgVQiexFXyz6jKJjx1Kl7pfK6/0Fnb/KEL3OI6dF1CL\\nQtAo+RmwL05hOBznLfST0KGjcI/l2Srndh7mpA/xOylzuVzkHtmb0jOsbawsxNYl\\nVglsqfMJHwKBgQDRulYJd76fp/h3r1lI0NKVOhixRB//9igEH/8JL5WVMWYBD5cC\\nRmkXdHubB3utqnV7L9a3qjH2AooJVO2IzvLSuhyr9+CcFsevZ/2Xgg1OguOI6qdY\\nC4uSKLx/+JW0hQO63aBairc0SIhbCtu2zClUTpNVPq6leDiEz7GHQpPYRQKBgQCm\\ns7wkhOTd488tJt3JfB8C9lOlo3YscoFs6OUQ3CirKx0FZ1CR4KM/DRZ3UuLdKRwy\\nsqAakx14SvMl/8RH21V0rFV/eRf/Bb3z4RORIdW+/2wVX+DMtre7iXCM8Q2HA2GP\\n7eUHVcc67T3tnG/48s/Ra+Vy1aa7Vtl7pmTwqk3nDwKBgHTz4nGvAZmpJHfyQZgt\\nEyG3oAhguiACZlsfebNDQu19Kwxfps+MLp+adAGba3KMmDAwgVNJzMQKagApsfLS\\nKibms4EP1Ft8idJi+eGoxo4D8SlqS6R9Pa1CF/CsX5+hjslB/7U17s0JRChTFAcO\\nvfVzLRQqtvSXiVJbX7/nv30/\\n-----END PRIVATE KEY-----\\n\",\n" +
        "  \"client_email\": \"firebase-adminsdk-fbsvc@pg-find-c23ab.iam.gserviceaccount.com\",\n" +
        "  \"client_id\": \"101467971076756053967\",\n" +
        "  \"auth_uri\": \"https://accounts.google.com/o/oauth2/auth\",\n" +
        "  \"token_uri\": \"https://oauth2.googleapis.com/token\",\n" +
        "  \"auth_provider_x509_cert_url\": \"https://www.googleapis.com/oauth2/v1/certs\",\n" +
        "  \"client_x509_cert_url\": \"https://www.googleapis.com/robot/v1/metadata/x509/firebase-adminsdk-fbsvc%40pg-find-c23ab.iam.gserviceaccount.com\",\n" +
        "  \"universe_domain\": \"googleapis.com\"\n" +
        "}";

    @Value("${firebase.project.id:pg-find-c23ab}")
    private String projectId;

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
        // Option 1: OS env var GOOGLE_APPLICATION_CREDENTIALS pointing to a file
        String credentialsFile = System.getenv("GOOGLE_APPLICATION_CREDENTIALS");
        if (credentialsFile != null && !credentialsFile.isBlank()) {
            log.info("Loading Firebase credentials from GOOGLE_APPLICATION_CREDENTIALS file: {}", credentialsFile);
            try (InputStream is = new FileInputStream(credentialsFile)) {
                return GoogleCredentials.fromStream(is)
                        .createScoped("https://www.googleapis.com/auth/cloud-platform");
            }
        }

        // Option 2: firebase.credentials.path Spring property (from local .env)
        if (credentialsPath != null && !credentialsPath.isBlank()) {
            log.info("Loading Firebase credentials from firebase.credentials.path: {}", credentialsPath);
            try (InputStream is = new FileInputStream(credentialsPath)) {
                return GoogleCredentials.fromStream(is)
                        .createScoped("https://www.googleapis.com/auth/cloud-platform");
            }
        }

        // Option 3: Raw JSON in FIREBASE_SERVICE_ACCOUNT_JSON env var (recommended for Render)
        String serviceAccountJson = System.getenv("FIREBASE_SERVICE_ACCOUNT_JSON");
        if (serviceAccountJson != null && !serviceAccountJson.isBlank()) {
            log.info("Loading Firebase credentials from FIREBASE_SERVICE_ACCOUNT_JSON env var");
            try (InputStream is = new ByteArrayInputStream(serviceAccountJson.getBytes(StandardCharsets.UTF_8))) {
                return GoogleCredentials.fromStream(is)
                        .createScoped("https://www.googleapis.com/auth/cloud-platform");
            }
        }

        // Option 4: Hardcoded fallback (production safety net)
        log.warn("No credential env vars found. Using hardcoded service account (production fallback).");
        try (InputStream is = new ByteArrayInputStream(FALLBACK_SERVICE_ACCOUNT_JSON.getBytes(StandardCharsets.UTF_8))) {
            return GoogleCredentials.fromStream(is)
                    .createScoped("https://www.googleapis.com/auth/cloud-platform");
        }
    }
}
