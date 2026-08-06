package dk.trustworks.intranet.security;

/**
 * Test-only wiring for {@link ScopeGuard}: its collaborator fields are
 * package-private for CDI, so resource tests outside this package construct
 * their guard here instead of widening production visibility.
 */
public final class TestScopeGuards {

    private TestScopeGuards() {
    }

    public static ScopeGuard wired(AuthorizationService authorizationService,
                                   RequestHeaderHolder requestHeaderHolder) {
        ScopeGuard guard = new ScopeGuard();
        guard.authorizationService = authorizationService;
        guard.requestHeaderHolder = requestHeaderHolder;
        return guard;
    }
}
