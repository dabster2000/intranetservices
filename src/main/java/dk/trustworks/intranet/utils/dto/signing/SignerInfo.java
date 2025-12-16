package dk.trustworks.intranet.utils.dto.signing;

/**
 * Information about a signer for a signing case.
 *
 * @param group Signing order/group (1, 2, 3...). Signers in the same group sign in parallel,
 *              different groups sign sequentially (group 1 first, then group 2, etc.)
 * @param name Full name of the signer
 * @param email Email address for signing invitation
 * @param role Signer role (e.g., "signer", "approver", "witness")
 * @param signing True if this recipient must sign, false if they just receive a copy (CC)
 * @param needsCpr True if the signer must verify their identity with CPR via MitID Substantial
 */
public record SignerInfo(
    int group,
    String name,
    String email,
    String role,
    boolean signing,
    boolean needsCpr
) {
    /**
     * Creates a default signer with role "signer" who must sign, without CPR requirement.
     */
    public static SignerInfo signer(int group, String name, String email) {
        return new SignerInfo(group, name, email, "signer", true, false);
    }

    /**
     * Creates a signer with CPR validation requirement.
     */
    public static SignerInfo signerWithCpr(int group, String name, String email) {
        return new SignerInfo(group, name, email, "signer", true, true);
    }

    /**
     * Creates a copy recipient (CC) who receives a copy but doesn't sign.
     */
    public static SignerInfo copyRecipient(int group, String name, String email) {
        return new SignerInfo(group, name, email, "copy", false, false);
    }
}
