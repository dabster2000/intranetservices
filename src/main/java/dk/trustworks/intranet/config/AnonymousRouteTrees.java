package dk.trustworks.intranet.config;

import java.util.List;
import java.util.Locale;

/**
 * Route trees that serve users with no (or non-Auth.js) session — the anonymous flows
 * the authorization plan must never over-gate: candidate apply, GDPR consent links,
 * guest kiosk, onboarding upload, and the mobile expenses PWA (Face-ID token
 * sessions). Owner decision 2026-08-06 (Phase 7): binding a required_permission to a
 * page_registry row inside one of these trees is a <strong>hard block</strong>, not a
 * warning — gating one of them breaks the flow outright for external users, and
 * un-blocking is deliberately a code change (symmetric with the protected permission
 * set in ProtectedPermissions).
 *
 * <p>The frontend's RouteAccessGuard is only mounted in the (protected) layout and
 * bypasses /expenses/mobile by prefix, so today such a binding would be inert or
 * confusing rather than immediately fatal — the rail exists so a registry edit can
 * never become load-bearing against an anonymous flow, whatever the guard does later.
 *
 * <p>Mirrored in the BFF (`src/lib/auth/anonymousRouteTrees.ts`) and disabled in the
 * admin console picker; this class is the enforcement point.
 */
public final class AnonymousRouteTrees {

    /** Prefixes of react_route values that belong to anonymous flows. */
    public static final List<String> PREFIXES = List.of(
            "/apply",
            "/consent",
            "/guest",
            "/onboarding",
            "/login",
            "/expenses/mobile"
    );

    private AnonymousRouteTrees() {
    }

    /** True when the given react_route lies inside an anonymous flow's tree. */
    public static boolean isAnonymousTree(String reactRoute) {
        if (reactRoute == null || reactRoute.isBlank()) {
            return false;
        }
        String route = reactRoute.trim().toLowerCase(Locale.ROOT);
        return PREFIXES.stream().anyMatch(prefix ->
                route.equals(prefix) || route.startsWith(prefix + "/"));
    }
}
