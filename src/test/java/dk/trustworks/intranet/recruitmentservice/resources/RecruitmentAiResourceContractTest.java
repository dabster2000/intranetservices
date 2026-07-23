package dk.trustworks.intranet.recruitmentservice.resources;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * P9 contract lock for {@code RecruitmentAiResource} (contract §6.1): one
 * read endpoint, {@code GET /recruitment/ai/flags}, under the class-level
 * {@code recruitment:read} scope with no method-level override.
 */
class RecruitmentAiResourceContractTest {

    private static final Class<?> RESOURCE = RecruitmentAiResource.class;

    @Test
    void classLevel_pathAndReadScope() {
        Path path = RESOURCE.getAnnotation(Path.class);
        assertNotNull(path);
        assertEquals("/recruitment/ai", path.value());

        RolesAllowed roles = RESOURCE.getAnnotation(RolesAllowed.class);
        assertNotNull(roles);
        assertEquals(Set.of("recruitment:read"), Set.of(roles.value()));
    }

    @Test
    void flagsEndpoint_isGetOnFlags_underTheClassReadScope() {
        Method flags = method("flags");
        assertNotNull(flags.getAnnotation(GET.class), "flags must be @GET");
        assertEquals("/flags", flags.getAnnotation(Path.class).value());
        assertNull(flags.getAnnotation(RolesAllowed.class),
                "flags must inherit the class-level recruitment:read scope");
    }

    private static Method method(String name) {
        for (Method m : RESOURCE.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        fail("RecruitmentAiResource must declare method " + name);
        return null;
    }
}
