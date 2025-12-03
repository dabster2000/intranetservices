package dk.trustworks.intranet.utils;

import java.util.Set;

public class TwConstants {

    public static final String CRM_INTERNAL_WORK = "fdfbb1a1-bbae-48a1-955d-e681153d6731";

    // Internal client for non-client portfolio tracking
    public static final String INTERNAL_CLIENT_TRUSTWORKS = "40c93307-1dfa-405a-8211-37cbda75318b";

    // Set of internal clients to exclude from CXO client portfolio metrics
    public static final Set<String> EXCLUDED_CLIENT_IDS = Set.of(
        INTERNAL_CLIENT_TRUSTWORKS
    );

}
