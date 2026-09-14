package dk.trustworks.intranet.aggregates.crm.signal.resources;

import dk.trustworks.intranet.aggregates.crm.signal.dto.AccountSignalRequest;
import dk.trustworks.intranet.aggregates.crm.signal.dto.SignalExtractionRequest;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Locks the routing and the scope of the "Heard something?" capture endpoints.
 *
 * <p>Plain JUnit, no {@code @QuarkusTest}: this must run in the DB-free fast tier that
 * gates every deploy, because both facts it protects fail silently. A resource rooted at
 * {@code /} or nested under a prefix another class owns answers 404 "Unable to find
 * matching target resource method" — RESTEasy Reactive picks the resource CLASS by its
 * class-level {@code @Path} before it looks at any method — and {@code ./mvnw compile}
 * does not catch a colliding path at all.
 *
 * <p>The composed paths are the BFF contract
 * ({@code trustworks-intranet-v2/src/app/api/account-signals/**}) and must not move.
 */
class AccountSignalResourceRoutingTest {

    private static final Class<?> SIGNALS = AccountSignalResource.class;

    /** {@code /account-signals} is a prefix no other resource class owns. */
    @Test
    void isRootedAtAccountSignals() {
        assertEquals("/account-signals", classPath(SIGNALS));
    }

    /**
     * The trap itself: rooted at {@code /}, this class would be invisible under every
     * prefix another class owns — {@code ClientResource} owns {@code /clients}.
     */
    @Test
    void isNotRootedAtSlash() {
        assertNotEquals("/", classPath(SIGNALS));
    }

    @Test
    void createIsPostOnTheClassRoot() {
        Method create = method(SIGNALS, "create", AccountSignalRequest.class);
        assertVerb(create, POST.class);
        assertNull(create.getAnnotation(Path.class),
                "create must sit on the class root — POST /account-signals is the BFF contract");
    }

    /**
     * Extraction is POST, not GET: the line is free text about named third parties and has
     * no business in a URL, a query string or an access log.
     */
    @Test
    void extractIsPostOnASubPath() {
        Method extract = method(SIGNALS, "extract", SignalExtractionRequest.class);
        assertVerb(extract, POST.class);
        assertEquals("/account-signals/extract", fullPath(extract));
    }

    /**
     * {@code signals:write} is granted to role USER — every employee. Reusing
     * {@code crm:write} would 403 everyone outside SALES/ADMIN/PARTNER and break the whole
     * premise that all ~100 consultants can contribute.
     */
    @Test
    void bothEndpointsRequireSignalsWrite() {
        assertEquals(Set.of("signals:write"), classScopes(SIGNALS));
        assertNull(method(SIGNALS, "create", AccountSignalRequest.class).getAnnotation(RolesAllowed.class),
                "create inherits the class-level signals:write");
        assertNull(method(SIGNALS, "extract", SignalExtractionRequest.class).getAnnotation(RolesAllowed.class),
                "extract inherits the class-level signals:write");
    }

    /**
     * The request record must not carry an author, a source or a status. Adding one would
     * let a caller forge who heard something, or pre-decide a signal, by putting it in the
     * body — the mass-assignment rule, enforced here rather than trusted.
     */
    @Test
    void requestCannotForgeAttributionOrStatus() {
        Set<String> components = Set.of(AccountSignalRequest.class.getRecordComponents())
                .stream()
                .map(java.lang.reflect.RecordComponent::getName)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("clientUuid", "clientUuids", "text", "personName", "personRole",
                        "relationText", "signalType", "colleagueUuids", "newCompanies"),
                components,
                "AccountSignalRequest must expose only what a caller may decide — "
                        + "author, source, status and timestamps are derived server-side. "
                        + "clientUuids and colleagueUuids joined it in V593: a line names "
                        + "the accounts it is about and the colleagues who were there, and "
                        + "neither is attribution the caller could forge — the author is "
                        + "still X-Requested-By and nothing else. newCompanies joined it "
                        + "on 2026-09-14: a NAME and a SECTOR for a company Intra has "
                        + "never heard of, created as a PROSPECT in the same transaction "
                        + "as the signal. It is not attribution either — the company it "
                        + "creates carries no owner and no author, and the signal's author "
                        + "is still the header.");
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
