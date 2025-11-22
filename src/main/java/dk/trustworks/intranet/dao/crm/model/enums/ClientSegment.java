package dk.trustworks.intranet.dao.crm.model.enums;

import lombok.Getter;

/**
 * Enum representing the industry segment of a client.
 * Used for categorizing clients by their primary business sector.
 */
@Getter
public enum ClientSegment {
    PUBLIC("Public Sector"),
    HEALTH("Healthcare"),
    FINANCIAL("Financial Services"),
    ENERGY("Energy & Utilities"),
    EDUCATION("Education"),
    OTHER("Other");

    private final String displayName;

    ClientSegment(String displayName) {
        this.displayName = displayName;
    }
}
