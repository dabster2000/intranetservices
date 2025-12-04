package dk.trustworks.intranet.utils;

import dk.trustworks.intranet.utils.client.AddoClient;
import dk.trustworks.intranet.utils.dto.addo.AddoLoginRequest;
import dk.trustworks.intranet.utils.dto.addo.AddoLoginResponse;
import dk.trustworks.intranet.utils.dto.addo.InitiateSigningRequest;
import dk.trustworks.intranet.utils.dto.addo.InitiateSigningResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * Service for managing ADDO Sign digital signature workflows.
 * Handles authentication, token caching, and signing request initiation.
 *
 * <p>Key features:
 * <ul>
 *   <li>Token caching (4 min) to minimize login calls</li>
 *   <li>SHA-512 password hashing for authentication</li>
 *   <li>Microsoft date format for API compatibility</li>
 *   <li>Hardcoded sequential signers for employment contracts</li>
 * </ul>
 */
@JBossLog
@ApplicationScoped
public class AddoSigningService {

    // Token cache (ADDO tokens expire at 5 min, cache for 4 min for safety)
    private static final long TOKEN_CACHE_DURATION_MS = 4 * 60 * 1000; // 4 minutes
    private volatile String cachedToken;
    private volatile long tokenExpiryTime;

    @Inject
    @RestClient
    AddoClient addoClient;

    @ConfigProperty(name = "addo.email")
    String addoEmail;

    @ConfigProperty(name = "addo.password")
    String addoPassword;

    @ConfigProperty(name = "addo.template-id")
    Integer templateId;

    /**
     * Initiates digital signing workflow for an employment contract PDF.
     * Uses hardcoded signers: Hans Lassen (seq 1), Hans Godfather (seq 2).
     *
     * @param pdfBytes Binary PDF content
     * @param documentName PDF filename
     * @return Signing token for tracking workflow
     * @throws AddoSigningException if signing initiation fails
     */
    public String initiateEmploymentContractSigning(byte[] pdfBytes, String documentName) {
        log.infof("Initiating ADDO signing for document: %s (%d bytes)", documentName, pdfBytes.length);

        try {
            // Get valid token (cached or fresh login)
            String token = getValidToken();

            // Build signing request
            InitiateSigningRequest request = buildSigningRequest(token, pdfBytes, documentName);

            // Call ADDO API
            InitiateSigningResponse response = addoClient.initiateSigning(request);

            // Check for errors
            if (response.errorCode() != null && response.errorCode() != 0) {
                throw new AddoSigningException(
                    String.format("ADDO API error %d: %s", response.errorCode(), response.errorMessage())
                );
            }

            if (response.signingToken() == null || response.signingToken().isEmpty()) {
                throw new AddoSigningException("ADDO API returned no signing token");
            }

            log.infof("Successfully initiated ADDO signing. Token: %s", response.signingToken());
            return response.signingToken();

        } catch (AddoSigningException e) {
            throw e;
        } catch (Exception e) {
            log.errorf(e, "Unexpected error initiating ADDO signing for: %s", documentName);
            throw new AddoSigningException("Failed to initiate signing: " + e.getMessage(), e);
        }
    }

    /**
     * Gets a valid authentication token, using cache or logging in if needed.
     *
     * @return Valid ADDO authentication token
     * @throws AddoSigningException if login fails
     */
    private String getValidToken() {
        long now = System.currentTimeMillis();

        // Return cached token if still valid
        if (cachedToken != null && now < tokenExpiryTime) {
            log.debug("Using cached ADDO token");
            return cachedToken;
        }

        // Token expired or missing - login again
        log.info("ADDO token expired or missing, logging in");
        return login();
    }

    /**
     * Authenticates with ADDO API and caches the token.
     *
     * @return Fresh authentication token
     * @throws AddoSigningException if login fails
     */
    private String login() {
        try {
            // Hash password with SHA-512 and Base64 encode
            String hashedPassword = hashPasswordSha512(addoPassword);

            // Debug logging (mask sensitive data)
            log.infof("ADDO login attempt - email: %s, password length: %d, hash length: %d",
                addoEmail,
                addoPassword != null ? addoPassword.length() : 0,
                hashedPassword != null ? hashedPassword.length() : 0);
            log.debugf("ADDO password hash (first 20 chars): %s...",
                hashedPassword != null && hashedPassword.length() > 20
                    ? hashedPassword.substring(0, 20) : hashedPassword);

            // Call login API
            AddoLoginRequest request = new AddoLoginRequest(addoEmail, hashedPassword);
            AddoLoginResponse response = addoClient.login(request);

            // Check for errors
            if (response.errorCode() != null && response.errorCode() != 0) {
                throw new AddoSigningException(
                    String.format("ADDO login failed (error %d): %s",
                        response.errorCode(), response.errorMessage())
                );
            }

            if (response.token() == null || response.token().isEmpty()) {
                throw new AddoSigningException("ADDO login returned no token");
            }

            // Cache token
            cachedToken = response.token();
            tokenExpiryTime = System.currentTimeMillis() + TOKEN_CACHE_DURATION_MS;

            log.info("Successfully logged in to ADDO API");
            return cachedToken;

        } catch (AddoSigningException e) {
            throw e;
        } catch (Exception e) {
            log.errorf(e, "Unexpected error during ADDO login");
            throw new AddoSigningException("Login failed: " + e.getMessage(), e);
        }
    }

    /**
     * Hashes password using SHA-512 and returns Base64 encoded result.
     *
     * @param password Plain text password
     * @return Base64 encoded SHA-512 hash
     * @throws AddoSigningException if hashing fails
     */
    private String hashPasswordSha512(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new AddoSigningException("SHA-512 algorithm not available", e);
        }
    }

    /**
     * Builds signing request with hardcoded signers for employment contracts.
     * Signers: 1) Hans Lassen, 2) Hans Godfather
     *
     * @param token Authentication token
     * @param pdfBytes PDF content
     * @param documentName PDF filename
     * @return Complete signing request
     */
    private InitiateSigningRequest buildSigningRequest(String token, byte[] pdfBytes, String documentName) {
        // Base64 encode document
        String encodedDocument = Base64.getEncoder().encodeToString(pdfBytes);

        // Create document data
        InitiateSigningRequest.DocumentData document =
            new InitiateSigningRequest.DocumentData(encodedDocument, documentName);

        // Hardcoded signers
        List<InitiateSigningRequest.RecipientData> recipients = List.of(
            new InitiateSigningRequest.RecipientData("Hans Lassen", "hans.lassen@trustworks.dk", 1),
            new InitiateSigningRequest.RecipientData("Hans Godfather", "hans@godfather.dk", 2)
        );

        // Create signing data
        InitiateSigningRequest.SigningData signingData =
            new InitiateSigningRequest.SigningData(List.of(document), recipients);

        // Current timestamp in Microsoft format (CRITICAL: no timezone suffix)
        String startDate = toMicrosoftDateFormat(Instant.now());

        return new InitiateSigningRequest(
            token,
            "Employment Contract - " + documentName,
            startDate,
            templateId,
            signingData
        );
    }

    /**
     * Converts Instant to Microsoft JSON date format: /Date(milliseconds)/
     * CRITICAL: Must be UTC with NO timezone suffix (+0200, etc.) or API returns BadRequest.
     *
     * @param instant Timestamp to convert
     * @return Microsoft format date string
     */
    private String toMicrosoftDateFormat(Instant instant) {
        return String.format("/Date(%d)/", instant.toEpochMilli());
    }

    /**
     * Custom exception for ADDO signing failures.
     */
    public static class AddoSigningException extends RuntimeException {
        public AddoSigningException(String message) {
            super(message);
        }

        public AddoSigningException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
