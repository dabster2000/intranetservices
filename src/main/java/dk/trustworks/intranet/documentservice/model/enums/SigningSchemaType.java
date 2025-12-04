package dk.trustworks.intranet.documentservice.model.enums;

/**
 * Enum representing available MitID signing schemas for NextSign integration.
 * Each schema type represents a different authentication level for document signing.
 */
public enum SigningSchemaType {
    MITID_SUBSTANTIAL("urn:grn:authn:dk:mitid:substantial", "MitID Substantial (CPR validated)"),
    MITID_LOW("urn:grn:authn:dk:mitid:low", "MitID Low (no CPR)"),
    MITID_BUSINESS("urn:grn:authn:dk:mitid:business", "MitID Business"),
    DRAW_CPR("draw-cpr", "NextSign Touch signature with CPR"),
    DRAW("draw", "NextSign Touch Signature");

    private final String urn;
    private final String displayName;

    SigningSchemaType(String urn, String displayName) {
        this.urn = urn;
        this.displayName = displayName;
    }

    /**
     * Get the URN identifier used by NextSign API.
     *
     * @return The URN string (e.g., "urn:grn:authn:dk:mitid:substantial")
     */
    public String getUrn() {
        return urn;
    }

    /**
     * Get the human-readable display name.
     *
     * @return The display name (e.g., "MitID Substantial (CPR validated)")
     */
    public String getDisplayName() {
        return displayName;
    }
}
