package dk.trustworks.intranet.aggregates.crm.sector.resources;

import dk.trustworks.intranet.aggregates.crm.gtm.dto.GtmTeamSectorsRequest;
import dk.trustworks.intranet.aggregates.crm.gtm.resources.GtmTeamResource;
import dk.trustworks.intranet.aggregates.crm.plan.dto.PlanRequests;
import dk.trustworks.intranet.aggregates.crm.sector.dto.SectorLeadRequest;
import dk.trustworks.intranet.aggregates.crm.sector.dto.SectorReviewRequest;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Locks the routing and the scopes of the sector, sector-plan and GTM-team endpoints.
 *
 * <p>Plain JUnit, no {@code @QuarkusTest}: this must run in the DB-free fast tier that gates
 * every deploy, because what it protects fails silently. A resource nested under a prefix
 * another class owns answers 404 "Unable to find matching target resource method" —
 * RESTEasy Reactive picks the resource CLASS by its class-level {@code @Path} first — and
 * {@code ./mvnw compile} does not catch a colliding path.
 *
 * <p>The composed paths are the BFF contract
 * ({@code trustworks-intranet-v2/src/app/api/sectors/**}, {@code /sector-plans/**},
 * {@code /gtm-teams/**}) and must not move.
 */
class SectorResourcesRoutingTest {

    private static final Class<?> SECTORS = SectorResource.class;
    private static final Class<?> PLANS = SectorPlanResource.class;
    private static final Class<?> TEAMS = GtmTeamResource.class;

    @Test
    void eachResourceOwnsItsOwnRoot() {
        assertEquals("/sectors", classPath(SECTORS));
        assertEquals("/sector-plans", classPath(PLANS));
        assertEquals("/gtm-teams", classPath(TEAMS));
        for (Class<?> type : new Class<?>[]{SECTORS, PLANS, TEAMS}) {
            assertNotEquals("/", classPath(type));
            assertNotEquals("/clients", classPath(type), "/clients is ClientResource's and would shadow this class");
            assertNotEquals("/accounts", classPath(type), "/accounts is AccountResource's and would shadow this class");
        }
    }

    @Test
    void sectorEndpoints() {
        Method summaries = method(SECTORS, "summaries");
        assertVerb(summaries, GET.class);
        assertNull(summaries.getAnnotation(Path.class), "the six cards sit on the class root — GET /sectors");

        Method read = method(SECTORS, "read", String.class);
        assertVerb(read, GET.class);
        assertEquals("/sectors/{segment}", fullPath(read));

        Method lead = method(SECTORS, "setLead", String.class, SectorLeadRequest.class);
        assertVerb(lead, PUT.class);
        assertEquals("/sectors/{segment}/lead", fullPath(lead));
    }

    @Test
    void sectorPlanEndpointsMirrorTheAccountPlanMinusPeople() {
        assertEquals("/sector-plans/{segment}", fullPath(method(PLANS, "read", String.class)));
        assertVerb(method(PLANS, "start", String.class, PlanRequests.StartPlanRequest.class), POST.class);
        assertVerb(method(PLANS, "patch", String.class, PlanRequests.PlanPatchRequest.class), PATCH.class);
        assertEquals("/sector-plans/{segment}/sentences/{slot}",
                fullPath(method(PLANS, "putSentence", String.class, String.class, PlanRequests.SentenceRequest.class)));
        assertEquals("/sector-plans/{segment}/objectives",
                fullPath(method(PLANS, "addObjective", String.class, PlanRequests.ObjectiveRequest.class)));
        assertEquals("/sector-plans/{segment}/objectives/{objectiveUuid}",
                fullPath(method(PLANS, "updateObjective", String.class, String.class, PlanRequests.ObjectiveRequest.class)));
        assertVerb(method(PLANS, "closeObjective", String.class, String.class), DELETE.class);
        assertEquals("/sector-plans/{segment}/actions",
                fullPath(method(PLANS, "addAction", String.class, PlanRequests.ActionRequest.class)));
        assertEquals("/sector-plans/{segment}/actions/{actionUuid}",
                fullPath(method(PLANS, "removeAction", String.class, String.class)));
        assertEquals("/sector-plans/{segment}/reviews",
                fullPath(method(PLANS, "closeReview", String.class, SectorReviewRequest.class)));
        for (Method method : PLANS.getDeclaredMethods()) {
            assertNotEquals("addStakeholder", method.getName(), "a sector plan has no people — stakeholders live on accounts");
        }
    }

    @Test
    void gtmTeamEndpoints() {
        Method list = method(TEAMS, "list");
        assertVerb(list, GET.class);
        assertNull(list.getAnnotation(Path.class), "the teams sit on the class root — GET /gtm-teams");
        Method sectors = method(TEAMS, "replaceSectors", String.class, GtmTeamSectorsRequest.class);
        assertVerb(sectors, PUT.class);
        assertEquals("/gtm-teams/{bubbleUuid}/sectors", fullPath(sectors));
    }

    /**
     * Reads are {@code accounts:read}, held by every employee — sectors are as firm-readable as
     * the accounts in them. Every write is {@code accounts:write}, the sales tier. No new
     * permission key was introduced, so none may appear here.
     */
    @Test
    void readsAreAccountsReadAndEveryWriteIsAccountsWrite() {
        for (Class<?> type : new Class<?>[]{SECTORS, PLANS, TEAMS}) {
            assertEquals(Set.of("accounts:read"), classScopes(type), type.getSimpleName());
            for (Method method : type.getDeclaredMethods()) {
                boolean write = method.isAnnotationPresent(POST.class) || method.isAnnotationPresent(PUT.class)
                        || method.isAnnotationPresent(PATCH.class) || method.isAnnotationPresent(DELETE.class);
                RolesAllowed roles = method.getAnnotation(RolesAllowed.class);
                if (write) {
                    assertNotNull(roles, method.getName() + " is a write and must carry accounts:write");
                    assertEquals(Set.of("accounts:write"), Set.of(roles.value()), method.getName());
                } else if (method.isAnnotationPresent(GET.class)) {
                    assertNull(roles, method.getName() + " inherits the class-level accounts:read");
                }
            }
        }
    }

    /** The request records carry only what a caller may decide; the actor is derived server-side. */
    @Test
    void requestsCannotForgeTheActor() {
        assertEquals(Set.of("userUuid", "clear"), componentNames(SectorLeadRequest.class));
        assertEquals(Set.of("segments"), componentNames(GtmTeamSectorsRequest.class));
    }

    private static Set<String> componentNames(Class<?> record) {
        return java.util.Arrays.stream(record.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .collect(java.util.stream.Collectors.toSet());
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
