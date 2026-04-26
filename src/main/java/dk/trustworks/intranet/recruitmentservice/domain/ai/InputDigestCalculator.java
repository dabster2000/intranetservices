package dk.trustworks.intranet.recruitmentservice.domain.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.enterprise.context.ApplicationScoped;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

@ApplicationScoped
public class InputDigestCalculator {

    private final ObjectMapper canonicalMapper = new ObjectMapper()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    public String digest(Map<String, Object> inputs) {
        try {
            String canonical = canonicalMapper.writeValueAsString(inputs);
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] h = sha.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : h) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to canonicalise inputs for digest", e);
        }
    }
}
