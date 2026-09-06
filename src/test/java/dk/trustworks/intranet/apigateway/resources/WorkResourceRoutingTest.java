package dk.trustworks.intranet.apigateway.resources;

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
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks who owns {@code GET /tasks/{uuid}/work} and the routing invariant that keeps
 * {@link WorkResource} reachable.
 *
 * <p>RESTEasy Reactive selects the resource class by its class-level {@code @Path} before it
 * matches any method, and it does not backtrack. {@code WorkResource} is rooted at {@code /},
 * so any method path it declares under a prefix another class owns is silently unreachable.
 * From the initial commit until 2024-10-01 it declared {@code /tasks/{uuid}/work}; every such
 * request went to {@link TaskResource} (rooted at {@code /tasks}), whose handler is a superset
 * of the dead one (optional {@code useruuids} + {@code registered} filter, same
 * {@code findByTask} fallback) and is the handler the API docs describe
 * ({@code docs/finalized/timeregistration/api/client-project-task-resource.md}). The dead method
 * was commented out in 2024 and deleted on 2026-09-06; these tests keep it from coming back and
 * keep the live handler's contract in place. Same defect shape as
 * {@code InternalAssignmentResourceRoutingTest}.
 */
class WorkResourceRoutingTest {

    private static final List<Class<? extends Annotation>> VERBS =
            List.of(GET.class, POST.class, PUT.class, DELETE.class);

    private static final String TASK_WORK_HANDLER = "findWorkByTaskFilterByUseruuidAndRegistered";

    @Test
    void taskWorkRoute_isOwnedByTaskResource() {
        assertEquals("/tasks", classPath(TaskResource.class));
        Method handler = method(TaskResource.class, TASK_WORK_HANDLER, String.class, String.class, String.class);
        assertEquals("/tasks/{uuid}/work", fullPath(handler));
        assertNotNull(handler.getAnnotation(GET.class), TASK_WORK_HANDLER + " must be @GET");

        Parameter[] params = handler.getParameters();
        assertEquals("uuid", params[0].getAnnotation(PathParam.class).value());
        assertEquals("useruuids", params[1].getAnnotation(QueryParam.class).value());
        assertEquals("registered", params[2].getAnnotation(QueryParam.class).value());
    }

    @Test
    void taskWorkRoute_requiresContractsRead() {
        assertEquals(Set.of("contracts:read"), classScopes(TaskResource.class));
        assertNull(method(TaskResource.class, TASK_WORK_HANDLER, String.class, String.class, String.class)
                        .getAnnotation(RolesAllowed.class),
                TASK_WORK_HANDLER + " inherits the class-level contracts:read");
    }

    /** The defect itself: a class rooted at {@code /} may only declare paths no other class owns. */
    @Test
    void workResource_declaresOnlyWorkPaths() {
        assertEquals("/", classPath(WorkResource.class));
        List<Method> endpoints = Arrays.stream(WorkResource.class.getDeclaredMethods())
                .filter(WorkResourceRoutingTest::isEndpoint)
                .toList();
        assertFalse(endpoints.isEmpty(), "WorkResource must still declare its /work endpoints");
        for (Method m : endpoints) {
            Path path = m.getAnnotation(Path.class);
            assertNotNull(path, m.getName()
                    + " must carry a method-level @Path; a bare verb on a /-rooted class would serve the root");
            String value = path.value();
            assertTrue(value.equals("/work") || value.startsWith("/work/"), m.getName() + " declares " + value
                    + " — a /-rooted class is never consulted under a prefix another class owns (/tasks, /users, ...)");
        }
    }

    @Test
    void workResource_noLongerDeclaresTheTaskWorkRoute() {
        assertTrue(Arrays.stream(WorkResource.class.getDeclaredMethods())
                        .noneMatch(m -> m.getName().equals("getWorkByTask")),
                "getWorkByTask was unreachable (TaskResource owns /tasks/{uuid}/work) and was deleted");
        assertEquals(Set.of("timeregistration:read"), classScopes(WorkResource.class));
    }

    private static boolean isEndpoint(Method m) {
        return VERBS.stream().anyMatch(m::isAnnotationPresent);
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

    private static Method method(Class<?> type, String name, Class<?>... params) {
        try {
            return type.getDeclaredMethod(name, params);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(type.getSimpleName() + "." + name + " is missing; the API contract references it", e);
        }
    }
}
