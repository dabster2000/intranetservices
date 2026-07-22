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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the P3 ATS candidate endpoints' paths and scope annotations
 * (spec §6.2 sketch + §7.1). Read endpoints inherit the class-level
 * {@code recruitment:read}; mutations override with
 * {@code recruitment:write}. The SALARY_EXPECTATION comp gate is runtime
 * logic — covered by {@link CandidateNoteCompScopeApiTest}.
 */
class RecruitmentCandidateAtsContractTest {

    @Test
    void dedupeCheck_isPost_atExpectedPath_withReadScope() {
        Method m = requireMethod("dedupeCheck");
        assertNotNull(m.getAnnotation(POST.class), "dedupe-check must be POST (identifiers stay out of URLs)");
        assertEquals("/candidates/dedupe-check", m.getAnnotation(Path.class).value());
        assertNull(m.getAnnotation(RolesAllowed.class),
                "dedupe-check inherits the class-level recruitment:read");
    }

    @Test
    void poolUnpool_areWriteScoped() {
        Method pool = requireMethod("poolCandidate");
        assertNotNull(pool.getAnnotation(POST.class));
        assertEquals("/candidates/{uuid}/pool", pool.getAnnotation(Path.class).value());
        assertRequiresWrite(pool);

        Method unpool = requireMethod("unpoolCandidate");
        assertNotNull(unpool.getAnnotation(POST.class));
        assertEquals("/candidates/{uuid}/unpool", unpool.getAnnotation(Path.class).value());
        assertRequiresWrite(unpool);
    }

    @Test
    void tags_isPut_writeScoped() {
        Method m = requireMethod("updateTags");
        assertNotNull(m.getAnnotation(PUT.class));
        assertEquals("/candidates/{uuid}/tags", m.getAnnotation(Path.class).value());
        assertRequiresWrite(m);
    }

    @Test
    void notes_isPost_writeScoped() {
        Method m = requireMethod("addNote");
        assertNotNull(m.getAnnotation(POST.class));
        assertEquals("/candidates/{uuid}/notes", m.getAnnotation(Path.class).value());
        assertRequiresWrite(m);
    }

    @Test
    void specializationCatalog_isGet_readScoped() {
        Method m = requireMethod("specializationCatalog");
        assertNotNull(m.getAnnotation(GET.class));
        assertEquals("/candidates/specializations", m.getAnnotation(Path.class).value());
        assertNull(m.getAnnotation(RolesAllowed.class),
                "catalog read inherits the class-level recruitment:read");
    }

    @Test
    void classLevel_baselineIsRecruitmentRead() {
        Annotation[] annotations = RecruitmentResource.class.getAnnotations();
        RolesAllowed roles = RecruitmentResource.class.getAnnotation(RolesAllowed.class);
        assertNotNull(roles, "class-level @RolesAllowed must exist; found: " + Arrays.toString(annotations));
        assertTrue(Arrays.asList(roles.value()).contains("recruitment:read"));
    }

    private static void assertRequiresWrite(Method m) {
        RolesAllowed roles = m.getAnnotation(RolesAllowed.class);
        assertNotNull(roles, m.getName() + " must override with @RolesAllowed");
        assertTrue(Arrays.asList(roles.value()).contains("recruitment:write"),
                m.getName() + " must require recruitment:write");
    }

    private static Method requireMethod(String name) {
        for (Method m : RecruitmentResource.class.getDeclaredMethods()) {
            if (name.equals(m.getName())) {
                return m;
            }
        }
        throw new AssertionError("RecruitmentResource must expose " + name + "(...)");
    }
}
