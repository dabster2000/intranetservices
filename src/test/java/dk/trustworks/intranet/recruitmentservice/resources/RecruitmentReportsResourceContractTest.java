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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the P20 reports surface's security contract (plan §P20, spec §6.2):
 * the read is {@code recruitment:read}, the rebuild — the one sanctioned
 * replay-from-history — is {@code recruitment:admin}, and the paths never
 * drift from what the BFF calls.
 */
class RecruitmentReportsResourceContractTest {

    @Test
    void classPath_isTheReportsRoot() {
        Path path = RecruitmentReportsResource.class.getAnnotation(Path.class);
        assertNotNull(path);
        assertEquals("/recruitment/reports", path.value());
    }

    @Test
    void read_requiresTheReadScope() throws NoSuchMethodException {
        Method reports = RecruitmentReportsResource.class.getMethod("reports", String.class, String.class);
        assertTrue(reports.isAnnotationPresent(GET.class));
        assertEquals(Set.of("recruitment:read"), Set.of(reports.getAnnotation(RolesAllowed.class).value()));
    }

    @Test
    void rebuild_requiresTheAdminScope() throws NoSuchMethodException {
        Method rebuild = RecruitmentReportsResource.class.getMethod("rebuild");
        assertTrue(rebuild.isAnnotationPresent(POST.class));
        assertEquals("/rebuild", rebuild.getAnnotation(Path.class).value());
        assertEquals(Set.of("recruitment:admin"), Set.of(rebuild.getAnnotation(RolesAllowed.class).value()));
    }
}
