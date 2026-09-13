package dk.trustworks.intranet.aggregates.crm.trustlink.resources;

import dk.trustworks.intranet.aggregates.crm.trustlink.dto.TrustLinkAliasesRequest;
import dk.trustworks.intranet.aggregates.crm.trustlink.dto.TrustLinkSyncStateDTO;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Locks the routing and the scopes of the TrustLink alias endpoints.
 *
 * <p>Plain JUnit, no {@code @QuarkusTest}: this must run in the DB-free fast tier that
 * gates every deploy, because what it protects fails silently. A resource nested under a
 * prefix another class owns answers 404 "Unable to find matching target resource method" —
 * RESTEasy Reactive picks the resource CLASS by its class-level {@code @Path} first — and
 * {@code ./mvnw compile} does not catch a colliding path.
 *
 * <p>The composed paths are the BFF contract
 * ({@code trustworks-intranet-v2/src/app/api/trustlink/**}) and must not move.
 */
class TrustLinkResourceRoutingTest {

    private static final Class<?> TRUSTLINK = TrustLinkResource.class;

    /**
     * {@code /trustlink} is a root no other resource class owns — verified against every
     * class-level {@code @Path} in the codebase when this was written. The two roots it could
     * plausibly have been folded into are named here because folding it in is exactly the
     * tempting mistake: the aliases belong to a client, so {@code /clients/...} looks
     * right and would 404.
     */
    @Test
    void itOwnsItsOwnRoot() {
        assertEquals("/trustlink", classPath(TRUSTLINK));
        assertNotEquals("/", classPath(TRUSTLINK));
        assertNotEquals("/clients", classPath(TRUSTLINK), "/clients is ClientResource's and would shadow this class");
        assertNotEquals("/accounts", classPath(TRUSTLINK), "/accounts is AccountResource's and would shadow this class");
    }

    @Test
    void theAliasEndpointsHangOffTheClient() {
        Method read = method(TRUSTLINK, "aliases", String.class);
        assertVerb(read, GET.class);
        assertEquals("/trustlink/clients/{clientUuid}/aliases", fullPath(read));

        Method replace = method(TRUSTLINK, "replaceAliases", String.class, TrustLinkAliasesRequest.class);
        assertVerb(replace, PUT.class);
        assertEquals("/trustlink/clients/{clientUuid}/aliases", fullPath(replace));
    }

    @Test
    void theTypeaheadAndTheManualSyncAreWhereTheBffExpectsThem() {
        Method search = method(TRUSTLINK, "searchCompanies", String.class, int.class);
        assertVerb(search, GET.class);
        assertEquals("/trustlink/companies/search", fullPath(search));

        Method sync = method(TRUSTLINK, "syncNow");
        assertVerb(sync, POST.class);
        assertEquals("/trustlink/sync", fullPath(sync));
    }

    /**
     * The answer to "did last night work?".
     *
     * <p>{@code /trustlink/sync-state} is a sibling of the manual trigger, not a path under it:
     * {@code /trustlink/sync} is a POST that starts a run, and a GET on the same path would be
     * read by every caching layer between here and a browser as the same resource. The two are
     * asserted to differ for that reason.
     *
     * <p>It carries no method-level scope on purpose — see
     * {@link #readingTheMappingInheritsTheClassScope()}. It reads our own bookkeeping row,
     * calls nothing upstream, and names no external person, so anyone who may see the "who
     * knows them" card may see whether the job that fills it is alive.
     */
    @Test
    void theSyncStateIsReadableAndInheritsTheOpenReadScope() {
        Method state = method(TRUSTLINK, "syncState");
        assertVerb(state, GET.class);
        assertEquals("/trustlink/sync-state", fullPath(state));
        assertNotEquals(fullPath(method(TRUSTLINK, "syncNow")), fullPath(state),
                "a GET and a POST that start and read a run must not share a path");
        assertNull(state.getAnnotation(RolesAllowed.class),
                "the sync state is a read and inherits the class-level accounts:read");
    }

    /**
     * The shape the BFF and any operator depend on. {@code enabled} is the load-bearing field:
     * without it a reader cannot tell "switched off in this environment" from "the job ran and
     * achieved nothing", because both are nulls and zeros. {@code unmatchedTrustworkerNames}
     * is the other one — the count alone has no remedy attached, since the fix for an unmatched
     * colleague is a {@code trustlink_trustworker_map} row keyed BY the name.
     */
    @Test
    void theSyncStateResponseCarriesTheFlagAndTheNames() {
        assertEquals(Set.of("enabled", "lastRunAt", "lastSuccessAt", "companiesQueried",
                        "connectionsUpserted", "edgesUpserted", "unmatchedTrustworkers",
                        "unmatchedTrustworkerNames", "failureCode"),
                componentNames(TrustLinkSyncStateDTO.class));
    }

    /**
     * Reads are {@code accounts:read}, held by every employee — the mapping is account
     * context, as firm-readable as the account page that shows it. Every write is
     * {@code accounts:write}, the sales tier. No new permission key was introduced for
     * TrustLink, so none may appear here.
     */
    @Test
    void readsAreAccountsReadAndEveryWriteIsAccountsWrite() {
        assertEquals(Set.of("accounts:read"), classScopes(TRUSTLINK));
        for (Method method : TRUSTLINK.getDeclaredMethods()) {
            boolean write = method.isAnnotationPresent(POST.class) || method.isAnnotationPresent(PUT.class)
                    || method.isAnnotationPresent(PATCH.class) || method.isAnnotationPresent(DELETE.class);
            RolesAllowed roles = method.getAnnotation(RolesAllowed.class);
            if (write) {
                assertNotNull(roles, method.getName() + " is a write and must carry accounts:write");
                assertEquals(Set.of("accounts:write"), Set.of(roles.value()), method.getName());
            }
        }
    }

    /**
     * The company typeahead is the only endpoint here that reaches a third party live on a
     * user's keystroke, and it is deliberately NOT on the open read scope: a reader who may
     * not edit the mapping has no reason to be able to walk TrustLink's company list
     * through our credentials.
     */
    @Test
    void theTypeaheadIsGatedOnWriteEvenThoughItIsAGet() {
        RolesAllowed roles = method(TRUSTLINK, "searchCompanies", String.class, int.class)
                .getAnnotation(RolesAllowed.class);
        assertNotNull(roles, "the typeahead must not fall through to the class-level accounts:read");
        assertEquals(Set.of("accounts:write"), Set.of(roles.value()));
    }

    /** Reading the mapping inherits the class-level scope; nothing narrows or widens it. */
    @Test
    void readingTheMappingInheritsTheClassScope() {
        assertNull(method(TRUSTLINK, "aliases", String.class).getAnnotation(RolesAllowed.class));
    }

    /**
     * The request record carries only what a caller may decide. The actor is derived from
     * {@code X-Requested-By} server-side, so no body can claim to be somebody else, and the
     * client uuid stays in the path where the BFF's own ownership check can see it.
     */
    @Test
    void theRequestCannotForgeTheActorOrTheClient() {
        assertEquals(Set.of("companyNames"), componentNames(TrustLinkAliasesRequest.class));
    }

    private static Set<String> componentNames(Class<?> record) {
        return Arrays.stream(record.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .collect(Collectors.toSet());
    }

    private static String classPath(Class<?> type) {
        Path path = type.getAnnotation(Path.class);
        assertNotNull(path, type.getSimpleName() + " must carry a class-level @Path");
        return path.value();
    }

    private static String fullPath(Method m) {
        Path path = m.getAnnotation(Path.class);
        assertNotNull(path, m.getName() + " must carry a method-level @Path");
        return classPath(m.getDeclaringClass()) + path.value();
    }

    private static Set<String> classScopes(Class<?> type) {
        RolesAllowed roles = type.getAnnotation(RolesAllowed.class);
        assertNotNull(roles, type.getSimpleName() + " must be scope-gated at the class level");
        return Set.of(roles.value());
    }

    private static void assertVerb(Method m, Class<? extends Annotation> verb) {
        assertNotNull(m.getAnnotation(verb), m.getName() + " must be @" + verb.getSimpleName());
    }

    private static Method method(Class<?> type, String name, Class<?>... params) {
        try {
            return type.getDeclaredMethod(name, params);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(type.getSimpleName() + "." + name + " is missing; the BFF contract references it", e);
        }
    }
}
