package dk.trustworks.intranet.utils.dto.signing;

/**
 * Information about a signer for a signing case.
 *
 * @param group Signing order/group (1, 2, 3...). Signers in the same group sign in parallel,
 *              different groups sign sequentially (group 1 first, then group 2, etc.)
 * @param name Full name of the signer
 * @param email Email address for signing invitation
 * @param role Signer role (e.g., "signer", "approver", "witness")
 */
public record SignerInfo(
    int group,
    String name,
    String email,
    String role
) {
    /**
     * Creates a default signer with role "signer".
     */
    public static SignerInfo signer(int group, String name, String email) {
        return new SignerInfo(group, name, email, "signer");
    }
}
