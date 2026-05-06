package dk.trustworks.intranet.recruitmentservice.resources;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reflection-only sanity check for the new
 * {@code GET /candidates/{uuid}/dossier/revisions/{revUuid}/signing-status}
 * endpoint. Full integration is exercised in CI.
 */
class RecruitmentResourceSigningStatusTest {

    @Test
    void getSigningStatus_methodExists_andHasExpectedAnnotations() throws Exception {
        Method m = findGetSigningStatusMethod();
        assertNotNull(m, "RecruitmentResource must expose getSigningStatus(...)");

        assertNotNull(m.getAnnotation(GET.class), "must be @GET");

        Path methodPath = m.getAnnotation(Path.class);
        assertNotNull(methodPath, "must have @Path");
        assertEquals("/candidates/{uuid}/dossier/revisions/{revUuid}/signing-status",
                methodPath.value());

        // @RolesAllowed("recruitment:read")
        jakarta.annotation.security.RolesAllowed roles =
                m.getAnnotation(jakarta.annotation.security.RolesAllowed.class);
        assertNotNull(roles, "must have @RolesAllowed");
        boolean hasRecruitmentRead = false;
        for (String r : roles.value()) {
            if ("recruitment:read".equals(r)) hasRecruitmentRead = true;
        }
        assertTrue(hasRecruitmentRead, "must require recruitment:read");
    }

    private static Method findGetSigningStatusMethod() {
        for (Method m :
                dk.trustworks.intranet.recruitmentservice.resources.RecruitmentResource.class
                        .getDeclaredMethods()) {
            if ("getSigningStatus".equals(m.getName())) return m;
        }
        return null;
    }
}
