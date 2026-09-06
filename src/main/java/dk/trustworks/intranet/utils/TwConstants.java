package dk.trustworks.intranet.utils;

import java.util.Set;

public class TwConstants {

    public static final String CRM_INTERNAL_WORK = "fdfbb1a1-bbae-48a1-955d-e681153d6731";

    // Internal client for non-client portfolio tracking
    public static final String INTERNAL_CLIENT_TRUSTWORKS = "40c93307-1dfa-405a-8211-37cbda75318b";

    /**
     * The second internal client, used by the finance / CXO services and documented in V209
     * ("Internal client exclusion"). Two internal-client uuids exist in code; the junior
     * surfaces treat both as internal (JK Team 2.0, spec §4.4.0).
     */
    public static final String INTERNAL_CLIENT_FINANCE = "d58bb00b-4474-4250-84eb-d8f77548ddac";

    /**
     * The client-hours discriminator for the junior surfaces (WP6, WP7, WP8): a work_full row
     * is <em>client work</em> when its clientuuid is not one of these. Never derive "client
     * work" from the rate — a declared 0 kr line is client work with zero revenue.
     */
    public static final Set<String> INTERNAL_CLIENT_UUIDS = Set.of(
        INTERNAL_CLIENT_TRUSTWORKS,
        INTERNAL_CLIENT_FINANCE
    );

    // Set of internal clients to exclude from CXO client portfolio metrics
    public static final Set<String> EXCLUDED_CLIENT_IDS = Set.of(
        INTERNAL_CLIENT_TRUSTWORKS
    );

}
