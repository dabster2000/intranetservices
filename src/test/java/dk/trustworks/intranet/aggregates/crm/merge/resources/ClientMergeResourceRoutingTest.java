package dk.trustworks.intranet.aggregates.crm.merge.resources;

import dk.trustworks.intranet.aggregates.crm.merge.dto.ClientMergeRequest;
import dk.trustworks.intranet.apigateway.resources.ClientResource;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the routing and the scope of the merge endpoints.
 *
 * <p>Plain JUnit, no {@code @QuarkusTest}: this must run in the DB-free fast tier that
 * gates every deploy, because both facts it protects fail silently. A resource nested
 * under a prefix another class owns answers 404 "Unable to find matching target resource
 * method" — RESTEasy Reactive picks the resource CLASS by its class-level {@code @Path}
 * before it looks at any method, longest prefix first — and {@code ./mvnw compile} does
 * not catch a colliding path at all.
 *
 * <p>The composed paths are the BFF contract
 * ({@code trustworks-intranet-v2/src/app/api/clients/merge/**}) and must not move.
 */
class ClientMergeResourceRoutingTest {

    private static final Class<?> MERGE = ClientMergeResource.class;

    /** {@code /clients/merge} is a longer prefix than {@code ClientResource}'s {@code /clients}, so it wins. */
    @Test
    void isRootedUnderClientsMergeBesideClientResource() {
        assertEquals("/clients/merge", classPath(MERGE));
        assertEquals("/clients", classPath(ClientResource.class),
                "ClientResource moving would silently change which class answers /clients/merge/**");
    }

    @Test
    void previewIsAGetOnWinnerPreviewLoser() {
        Method preview = method("preview", String.class, String.class);
        assertVerb(preview, GET.class);
        assertEquals("/clients/merge/{winnerUuid}/preview/{loserUuid}", fullPath(preview));
    }

    @Test
    void mergeIsAPostOnWinnerFromLoser() {
        Method merge = method("merge", String.class, String.class, ClientMergeRequest.class);
        assertVerb(merge, POST.class);
        assertEquals("/clients/merge/{winnerUuid}/from/{loserUuid}", fullPath(merge));
    }

    /**
     * {@code clients:merge} on the class, inherited by both methods — including the
     * preview, which lists a company's invoices, contracts and e-conomic customer numbers
     * and is therefore more than {@code crm:read} shows. Reusing {@code crm:write} would
     * hand a bookkeeping act to every SALES holder (spec §7).
     */
    @Test
    void bothEndpointsRequireClientsMerge() {
        assertEquals(Set.of("clients:merge"), classScopes(MERGE));
        assertNull(method("preview", String.class, String.class).getAnnotation(RolesAllowed.class),
                "preview inherits the class-level clients:merge");
        assertNull(method("merge", String.class, String.class, ClientMergeRequest.class).getAnnotation(RolesAllowed.class),
                "merge inherits the class-level clients:merge");
    }

    /**
     * The body carries the one decision the rules cannot make and nothing else. A field
     * for "delete the loser", "skip verification" or the actor would be a field a caller
     * could forge — the mass-assignment rule, enforced here rather than trusted.
     */
    @Test
    void theRequestCarriesOnlyTheAccountDecision() {
        Set<String> components = Set.of(ClientMergeRequest.class.getRecordComponents()).stream()
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());
        assertEquals(Set.of("accountFrom"), components);
    }

    // ---- helpers ------------------------------------------------------------------

    private static String classPath(Class<?> type) {
        Path path = type.getAnnotation(Path.class);
        assertNotNull(path, type.getSimpleName() + " has no class-level @Path");
        return path.value();
    }

    private static String fullPath(Method method) {
        Path path = method.getAnnotation(Path.class);
        return classPath(method.getDeclaringClass()) + (path == null ? "" : path.value());
    }

    private static Set<String> classScopes(Class<?> type) {
        RolesAllowed roles = type.getAnnotation(RolesAllowed.class);
        assertNotNull(roles, type.getSimpleName() + " has no class-level @RolesAllowed");
        return Set.of(roles.value());
    }

    private static Method method(String name, Class<?>... params) {
        try {
            return MERGE.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("ClientMergeResource." + name + " moved or changed its signature", e);
        }
    }

    private static void assertVerb(Method method, Class<? extends Annotation> verb) {
        assertTrue(method.isAnnotationPresent(verb), method.getName() + " must be " + verb.getSimpleName());
    }
}
