package dk.trustworks.intranet.recruitmentservice.resources;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reflection-only sanity check for the new
 * {@code POST /candidates/{uuid}/dossier/branch-from-revision/{revUuid}}
 * endpoint. Full happy-path / error-path coverage is exercised by
 * {@code DossierService.branchFromRevision} unit tests and integration
 * smoke in CI.
 */
class RecruitmentResourceBranchEndpointTest {

    @Test
    void branchFromRevision_methodExists_andHasExpectedAnnotations() {
        Method m = findBranchFromRevisionMethod();
        assertNotNull(m, "RecruitmentResource must expose branchFromRevision(...)");

        assertNotNull(m.getAnnotation(POST.class), "must be @POST");

        Path methodPath = m.getAnnotation(Path.class);
        assertNotNull(methodPath);
        assertEquals("/candidates/{uuid}/dossier/branch-from-revision/{revUuid}",
                methodPath.value());

        RolesAllowed roles = m.getAnnotation(RolesAllowed.class);
        assertNotNull(roles);
        boolean hasWrite = false;
        for (String r : roles.value()) {
            if ("recruitment:write".equals(r)) hasWrite = true;
        }
        assertTrue(hasWrite, "must require recruitment:write");
    }

    private static Method findBranchFromRevisionMethod() {
        for (Method m :
                dk.trustworks.intranet.recruitmentservice.resources.RecruitmentResource.class
                        .getDeclaredMethods()) {
            if ("branchFromRevision".equals(m.getName())) return m;
        }
        return null;
    }
}
