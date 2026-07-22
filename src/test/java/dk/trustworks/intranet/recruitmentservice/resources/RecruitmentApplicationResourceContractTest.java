package dk.trustworks.intranet.recruitmentservice.resources;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Contract sanity for {@code RecruitmentApplicationResource} (plan §P4):
 * every write endpoint requires {@code recruitment:write}, the class
 * baseline is {@code recruitment:read}, and the paths match the spec §6.2
 * sketch (plus the P4 GET-list addition, findings §P4).
 */
class RecruitmentApplicationResourceContractTest {

    private static final Class<?> RESOURCE = RecruitmentApplicationResource.class;

    @Test
    void classLevel_pathAndReadScope() {
        Path path = RESOURCE.getAnnotation(Path.class);
        assertNotNull(path);
        assertEquals("/recruitment", path.value());

        RolesAllowed roles = RESOURCE.getAnnotation(RolesAllowed.class);
        assertNotNull(roles);
        assertEquals(Set.of("recruitment:read"), Set.of(roles.value()));
    }

    @Test
    void everyWriteEndpoint_requiresRecruitmentWrite() {
        List<String> writeMethods = List.of("create", "changeStage", "reject",
                "withdraw", "returnToPool", "assignTeam", "setExpectedStartDate");
        for (String name : writeMethods) {
            Method m = method(name);
            RolesAllowed roles = m.getAnnotation(RolesAllowed.class);
            assertNotNull(roles, name + " must override the class scope");
            assertTrue(Arrays.asList(roles.value()).contains("recruitment:write"),
                    name + " must require recruitment:write");
        }
    }

    @Test
    void endpointPaths_matchSpecSketch() {
        Map<String, String> expectedPaths = Map.of(
                "listForCandidate", "/candidates/{uuid}/applications",
                "create", "/candidates/{uuid}/applications",
                "changeStage", "/applications/{uuid}/stage",
                "reject", "/applications/{uuid}/reject",
                "withdraw", "/applications/{uuid}/withdraw",
                "returnToPool", "/applications/{uuid}/return-to-pool",
                "assignTeam", "/applications/{uuid}/assign-team",
                "setExpectedStartDate", "/applications/{uuid}/expected-start-date");
        expectedPaths.forEach((methodName, expectedPath) ->
                assertEquals(expectedPath, method(methodName).getAnnotation(Path.class).value(),
                        methodName + " path must match the spec §6.2 sketch"));
    }

    @Test
    void endpointVerbs_matchSpecSketch() {
        assertHasAnnotation("listForCandidate", GET.class);
        assertHasAnnotation("create", POST.class);
        assertHasAnnotation("changeStage", POST.class);
        assertHasAnnotation("reject", POST.class);
        assertHasAnnotation("withdraw", POST.class);
        assertHasAnnotation("returnToPool", POST.class);
        assertHasAnnotation("assignTeam", POST.class);
        assertHasAnnotation("setExpectedStartDate", PUT.class);
    }

    private static void assertHasAnnotation(String methodName, Class<? extends Annotation> annotation) {
        assertNotNull(method(methodName).getAnnotation(annotation),
                methodName + " must be @" + annotation.getSimpleName());
    }

    private static Method method(String name) {
        for (Method m : RESOURCE.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        fail("No method named " + name + " on " + RESOURCE.getSimpleName());
        return null;
    }
}
