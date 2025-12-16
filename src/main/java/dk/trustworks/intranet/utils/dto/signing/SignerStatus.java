package dk.trustworks.intranet.utils.dto.signing;

import java.time.LocalDateTime;

/**
 * Status information for a single signer in a signing case.
 *
 * @param group Signing order/group number
 * @param name Signer's full name
 * @param email Signer's email address
 * @param role Signer's role (e.g., "signer", "approver")
 * @param status Current signing status ("pending", "signed", "rejected", "expired")
 * @param signedAt Timestamp when the signer signed (null if not yet signed)
 * @param cprIsMatch CPR validation result: true if CPR matched identity, false if mismatch, null if not required/verified
 */
public record SignerStatus(
    int group,
    String name,
    String email,
    String role,
    String status,
    LocalDateTime signedAt,
    Boolean cprIsMatch
) {
    /**
     * Creates a pending signer status.
     */
    public static SignerStatus pending(int group, String name, String email, String role) {
        return new SignerStatus(group, name, email, role, "pending", null, null);
    }

    /**
     * Checks if this signer has completed signing.
     */
    public boolean isSigned() {
        return "signed".equalsIgnoreCase(status);
    }

    /**
     * Checks if CPR validation was successful.
     * @return true if CPR was validated and matched, false otherwise (including null/not required)
     */
    public boolean isCprValidated() {
        return Boolean.TRUE.equals(cprIsMatch);
    }
}
