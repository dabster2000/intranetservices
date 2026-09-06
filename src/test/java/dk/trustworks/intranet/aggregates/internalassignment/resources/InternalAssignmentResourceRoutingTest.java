package dk.trustworks.intranet.aggregates.internalassignment.resources;

import dk.trustworks.intranet.aggregates.internalassignment.dto.InternalAssignmentRequest;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Locks the routing split the profile Availability tab depends on.
 *
 * <p>RESTEasy Reactive selects the resource class by its class-level {@code @Path} before it
 * matches any method. With twenty-odd classes rooted at {@code /users}, a class rooted at
 * {@code /} that declared {@code /users/{useruuid}/internal-assignments} on a method was never
 * consulted for {@code GET /users/...}; the router answered 404 "Unable to find matching target
 * resource method" and the tab showed "Could not load assignments". The assignee family
 * therefore lives in a {@code /users}-rooted class and the row family under
 * {@code /internal-assignments}. The composed paths below are the BFF contract
 * ({@code trustworks-intranet-v2/src/app/api/profile/internal-assignments/route.ts} and
 * {@code .../api/internal-assignments/**}) and must not move.
 */
class InternalAssignmentResourceRoutingTest {

    private static final Class<?> USERS = UserInternalAssignmentResource.class;
    private static final Class<?> ROWS = InternalAssignmentResource.class;

    @Test
    void assigneeFamily_isRootedAtUsers_soTheRouterReachesIt() {
        assertEquals("/users", classPath(USERS));
        assertEquals("/users/{useruuid}/internal-assignments",
                fullPath(method(USERS, "list", String.class, String.class, String.class)));
        assertEquals("/users/{useruuid}/internal-assignments",
                fullPath(method(USERS, "create", String.class, InternalAssignmentRequest.class)));
    }

    @Test
    void rowFamily_isRootedAtInternalAssignments() {
        assertEquals("/internal-assignments", classPath(ROWS));
        assertEquals("/internal-assignments/{uuid}",
                fullPath(method(ROWS, "update", String.class, InternalAssignmentRequest.class)));
        assertEquals("/internal-assignments/{uuid}/approve", fullPath(method(ROWS, "approve", String.class)));
        assertEquals("/internal-assignments/{uuid}/reject", fullPath(method(ROWS, "reject", String.class, String.class)));
        assertEquals("/internal-assignments/{uuid}", fullPath(method(ROWS, "delete", String.class)));
        assertEquals("/internal-assignments/pending", fullPath(method(ROWS, "pending")));
    }

    /** The defect itself: a resource rooted at {@code /} is unreachable under every prefix another class owns. */
    @Test
    void neitherClass_isRootedAtSlash() {
        assertNotEquals("/", classPath(USERS));
        assertNotEquals("/", classPath(ROWS));
    }

    @Test
    void verbs_unchangedByTheSplit() {
        assertVerb(method(USERS, "list", String.class, String.class, String.class), GET.class);
        assertVerb(method(USERS, "create", String.class, InternalAssignmentRequest.class), POST.class);
        assertVerb(method(ROWS, "update", String.class, InternalAssignmentRequest.class), PUT.class);
        assertVerb(method(ROWS, "approve", String.class), POST.class);
        assertVerb(method(ROWS, "reject", String.class, String.class), POST.class);
        assertVerb(method(ROWS, "delete", String.class), DELETE.class);
        assertVerb(method(ROWS, "pending"), GET.class);
    }

    @Test
    void scopes_unchangedByTheSplit() {
        assertEquals(Set.of("users:read"), classScopes(USERS));
        assertEquals(Set.of("users:read"), classScopes(ROWS));

        assertNull(method(USERS, "list", String.class, String.class, String.class).getAnnotation(RolesAllowed.class),
                "list inherits the class-level users:read; ScopeGuard does the row-level check");
        assertScope(method(USERS, "create", String.class, InternalAssignmentRequest.class), "users:write");
        assertScope(method(ROWS, "update", String.class, InternalAssignmentRequest.class), "users:write");
        assertScope(method(ROWS, "delete", String.class), "users:write");
        assertScope(method(ROWS, "approve", String.class), "teams:write");
        assertScope(method(ROWS, "reject", String.class, String.class), "teams:write");
        assertScope(method(ROWS, "pending"), "teams:read");
    }

    /** The BFF sends {@code ?fromdate=&todate=}; the parameter names and the cap are the contract. */
    @Test
    void listQueryParameters_areFromdateAndTodate() {
        Parameter[] params = method(USERS, "list", String.class, String.class, String.class).getParameters();
        assertEquals("useruuid", params[0].getAnnotation(PathParam.class).value());
        assertEquals("fromdate", params[1].getAnnotation(QueryParam.class).value());
        assertEquals("todate", params[2].getAnnotation(QueryParam.class).value());
        assertEquals(800, UserInternalAssignmentResource.MAX_RANGE_DAYS);
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

    private static void assertScope(Method m, String scope) {
        RolesAllowed roles = m.getAnnotation(RolesAllowed.class);
        assertNotNull(roles, m.getName() + " must override the class scope");
        assertEquals(Set.of(scope), Set.of(roles.value()), m.getName() + " must require " + scope);
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
