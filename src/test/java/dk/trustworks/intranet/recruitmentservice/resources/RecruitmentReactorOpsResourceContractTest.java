package dk.trustworks.intranet.recruitmentservice.resources;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * V490 contract lock for the dead-letter ops surface.
 * <p>
 * Every endpoint here replays or closes a real side effect (a Slack post, a
 * mail), so the {@code recruitment:admin} scope is the load-bearing part of
 * this class — {@code recruitment:write} would put replay in reach of every
 * recruiter. The class-level annotation covers all three; no method may
 * widen it.
 */
class RecruitmentReactorOpsResourceContractTest {

    @Test
    void resource_isRootedAtReactors_underTheAdminScope() {
        Path path = RecruitmentReactorOpsResource.class.getAnnotation(Path.class);
        assertNotNull(path);
        assertEquals("/recruitment/reactors", path.value());

        RolesAllowed roles = RecruitmentReactorOpsResource.class.getAnnotation(RolesAllowed.class);
        assertNotNull(roles, "the ops surface must carry a class-level scope");
        assertEquals(Set.of("recruitment:admin"), Set.of(roles.value()));
    }

    @Test
    void listing_isGetOnDeadLetters() {
        Method deadLetters = method("deadLetters");
        assertNotNull(deadLetters.getAnnotation(GET.class));
        assertEquals("/dead-letters", deadLetters.getAnnotation(Path.class).value());
    }

    @Test
    void replay_isPostAndKeyedByReactorAndSeq() {
        Method replay = method("replay");
        assertNotNull(replay.getAnnotation(POST.class));
        assertEquals("/dead-letters/{reactor}/{seq}/replay", replay.getAnnotation(Path.class).value());
    }

    @Test
    void abandon_isPostAndKeyedByReactorAndSeq() {
        Method abandon = method("abandon");
        assertNotNull(abandon.getAnnotation(POST.class));
        assertEquals("/dead-letters/{reactor}/{seq}/abandon", abandon.getAnnotation(Path.class).value());
    }

    /**
     * A method-level {@code @RolesAllowed} would silently REPLACE the
     * class-level admin scope for that method rather than intersect with it.
     * None of these three may carry one.
     */
    @Test
    void noEndpointWidensTheClassScope() {
        for (String name : new String[]{"deadLetters", "replay", "abandon"}) {
            assertNull(method(name).getAnnotation(RolesAllowed.class),
                    name + " must inherit the class-level recruitment:admin scope, not override it");
        }
    }

    private static Method method(String name) {
        for (Method m : RecruitmentReactorOpsResource.class.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        return fail("no method " + name + " on RecruitmentReactorOpsResource");
    }
}
