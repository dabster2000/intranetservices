package dk.trustworks.intranet.recruitmentservice.resources;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Contract sanity for {@code RecruitmentInvitationSettingsResource}: the
 * path the settings screen calls, the class-level {@code recruitment:read}
 * baseline, and the {@code recruitment:write} override on the one mutation.
 * This endpoint configures text that every interviewed candidate reads, so
 * a missing write scope would be a real hole rather than a style slip.
 */
class RecruitmentInvitationSettingsResourceContractTest {

    private static final Class<?> RESOURCE = RecruitmentInvitationSettingsResource.class;

    @Test
    void classLevel_pathAndReadScope() {
        Path path = RESOURCE.getAnnotation(Path.class);
        assertNotNull(path);
        assertEquals("/recruitment/invitation-settings", path.value());

        RolesAllowed roles = RESOURCE.getAnnotation(RolesAllowed.class);
        assertNotNull(roles);
        assertEquals(Set.of("recruitment:read"), Set.of(roles.value()));
    }

    @Test
    void read_isGetUnderTheClassReadScope() {
        Method settings = method("settings");
        assertNotNull(settings.getAnnotation(GET.class), "settings must be @GET");
        assertNull(settings.getAnnotation(RolesAllowed.class),
                "a read endpoint must inherit the class scope, not override it");
    }

    @Test
    void update_isPutAndRequiresRecruitmentWrite() {
        Method update = method("update");
        assertNotNull(update.getAnnotation(PUT.class), "update must be @PUT");
        RolesAllowed roles = update.getAnnotation(RolesAllowed.class);
        assertNotNull(roles, "update must override the class scope");
        assertTrue(Arrays.asList(roles.value()).contains("recruitment:write"));
    }

    private static Method method(String name) {
        for (Method m : RESOURCE.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        fail("RecruitmentInvitationSettingsResource must declare method " + name);
        return null;
    }
}
