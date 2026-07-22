package dk.trustworks.intranet.recruitmentservice.resources;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Contract sanity for {@code RecruitmentPositionResource} (plan §P2 DoD:
 * scope annotations checked in review — locked here instead): every write
 * endpoint requires {@code recruitment:write}, the class baseline is
 * {@code recruitment:read}, and the paths match the spec §6.2 sketch.
 */
class RecruitmentPositionResourceContractTest {

    private static final Class<?> RESOURCE = RecruitmentPositionResource.class;

    @Test
    void classLevel_pathAndReadScope() {
        Path path = RESOURCE.getAnnotation(Path.class);
        assertNotNull(path);
        assertEquals("/recruitment/positions", path.value());

        RolesAllowed roles = RESOURCE.getAnnotation(RolesAllowed.class);
        assertNotNull(roles);
        assertEquals(Set.of("recruitment:read"), Set.of(roles.value()));
    }

    @Test
    void everyWriteEndpoint_requiresRecruitmentWrite() {
        List<String> writeMethods = List.of("create", "update", "close",
                "addCircleMember", "removeCircleMember");
        for (String name : writeMethods) {
            Method m = method(name);
            RolesAllowed roles = m.getAnnotation(RolesAllowed.class);
            assertNotNull(roles, name + " must override the class scope");
            assertTrue(Arrays.asList(roles.value()).contains("recruitment:write"),
                    name + " must require recruitment:write");
        }
    }

    @Test
    void endpointVerbs_matchSpecSketch() {
        assertHasAnnotation("list", GET.class);
        assertHasAnnotation("get", GET.class);
        assertHasAnnotation("create", POST.class);
        assertHasAnnotation("update", PUT.class);
        assertHasAnnotation("close", POST.class);
        assertEquals("/{uuid}/close", method("close").getAnnotation(Path.class).value());
        assertHasAnnotation("circle", GET.class);
        assertHasAnnotation("addCircleMember", POST.class);
        assertEquals("/{uuid}/circle", method("addCircleMember").getAnnotation(Path.class).value());
        assertHasAnnotation("removeCircleMember", DELETE.class);
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
        fail("RecruitmentPositionResource must declare method " + name);
        return null;
    }
}
