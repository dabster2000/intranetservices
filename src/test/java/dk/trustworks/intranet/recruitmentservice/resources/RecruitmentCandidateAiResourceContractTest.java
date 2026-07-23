package dk.trustworks.intranet.recruitmentservice.resources;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * P9 contract lock for {@code RecruitmentCandidateAiResource} (contract
 * §6.2): the read baseline is {@code recruitment:read}; the two commands
 * (regenerate, resolve) override with {@code recruitment:write}; verbs and
 * wire paths match the contract sketch. The class is rooted at
 * {@code /recruitment} (methods carry {@code /candidates/...}) so the
 * shared {@code /recruitment/candidates/*} URI space keeps resolving to
 * the sibling resources — a {@code /recruitment/candidates} class path
 * would shadow them (JAX-RS longest-literal class matching).
 */
class RecruitmentCandidateAiResourceContractTest {

    private static final Class<?> RESOURCE = RecruitmentCandidateAiResource.class;

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
    void stateEndpoint_isGet_underTheClassReadScope() {
        Method state = method("state");
        assertNotNull(state.getAnnotation(GET.class), "state must be @GET");
        assertEquals("/candidates/{uuid}/ai/state", state.getAnnotation(Path.class).value());
        assertNull(state.getAnnotation(RolesAllowed.class),
                "state must inherit the class-level recruitment:read scope");
    }

    @Test
    void everyCommand_requiresRecruitmentWrite() {
        for (String name : List.of("regenerate", "resolve")) {
            Method m = method(name);
            assertNotNull(m.getAnnotation(POST.class), name + " must be @POST");
            RolesAllowed roles = m.getAnnotation(RolesAllowed.class);
            assertNotNull(roles, name + " must override the class scope");
            assertTrue(Arrays.asList(roles.value()).contains("recruitment:write"),
                    name + " must require recruitment:write");
        }
        assertEquals("/candidates/{uuid}/ai/regenerate", method("regenerate").getAnnotation(Path.class).value());
        assertEquals("/candidates/{uuid}/ai/suggestions/resolve", method("resolve").getAnnotation(Path.class).value());
    }

    private static Method method(String name) {
        for (Method m : RESOURCE.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        fail("RecruitmentCandidateAiResource must declare method " + name);
        return null;
    }
}
