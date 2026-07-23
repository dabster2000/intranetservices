package dk.trustworks.intranet.recruitmentservice.resources;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Contract sanity for {@code RecruitmentInterviewResource} (plan §P11):
 * class rooting at {@code /recruitment} (the §P9 shadowing lesson — new
 * resources must match the module's class-path rooting), scheduling
 * mutations behind {@code recruitment:write}, interviewer surfaces behind
 * {@code recruitment:interview}, paths per the spec §6.2 sketch.
 */
class RecruitmentInterviewResourceContractTest {

    private static final Class<?> RESOURCE = RecruitmentInterviewResource.class;

    @Test
    void classLevel_moduleRooting_andReadScope() {
        Path path = RESOURCE.getAnnotation(Path.class);
        assertNotNull(path);
        assertEquals("/recruitment", path.value(),
                "resources must root at /recruitment or they shadow siblings (findings §P9.5)");

        RolesAllowed roles = RESOURCE.getAnnotation(RolesAllowed.class);
        assertNotNull(roles);
        assertEquals(Set.of("recruitment:read"), Set.of(roles.value()));
    }

    @Test
    void schedulingMutations_requireRecruitmentWrite() {
        for (String name : new String[]{"create", "reschedule", "cancel"}) {
            RolesAllowed roles = method(name).getAnnotation(RolesAllowed.class);
            assertNotNull(roles, name + " must override the class scope");
            assertTrue(Arrays.asList(roles.value()).contains("recruitment:write"),
                    name + " must require recruitment:write");
        }
    }

    @Test
    void interviewerSurfaces_requireTheInterviewScope() {
        for (String name : new String[]{"submitScorecard", "mine"}) {
            RolesAllowed roles = method(name).getAnnotation(RolesAllowed.class);
            assertNotNull(roles, name + " must override the class scope");
            assertTrue(Arrays.asList(roles.value()).contains("recruitment:interview"),
                    name + " must require recruitment:interview (spec §7.1)");
        }
    }

    @Test
    void endpointPaths_matchSpecSketch() {
        Map<String, String> expectedPaths = Map.of(
                "create", "/applications/{uuid}/interviews",
                "reschedule", "/interviews/{uuid}/schedule",
                "cancel", "/interviews/{uuid}/cancel",
                "submitScorecard", "/interviews/{uuid}/scorecards",
                "scorecards", "/interviews/{uuid}/scorecards",
                "debrief", "/applications/{uuid}/debrief",
                "listForCandidate", "/candidates/{uuid}/interviews",
                "mine", "/interviews/mine");
        expectedPaths.forEach((name, expected) -> {
            Path path = method(name).getAnnotation(Path.class);
            assertNotNull(path, name + " must declare a path");
            assertEquals(expected, path.value(), name);
        });
    }

    private static Method method(String name) {
        return Arrays.stream(RESOURCE.getDeclaredMethods())
                .filter(m -> m.getName().equals(name))
                .findFirst()
                .orElseGet(() -> fail("no such endpoint method: " + name));
    }
}
