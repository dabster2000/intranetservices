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
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /**
     * The create endpoint had no contract assertion at all until the atomic
     * create-with-position landed — nothing in CI pinned its verb, path or
     * scope. Added here rather than assumed: these reflection contracts are
     * hand-enumerated allowlists, so an endpoint nobody names is an endpoint
     * nobody checks.
     */
    @Test
    void createCandidate_isPost_atExpectedPath_writeScoped() {
        Method m = requireMethod("createCandidate");
        assertNotNull(m.getAnnotation(POST.class));
        assertEquals("/candidates", m.getAnnotation(Path.class).value());
        assertRequiresWrite(m);
    }

    /**
     * The hard delete is the one endpoint on this resource that does not use
     * {@code recruitment:write}, and it is the one where a wrong annotation is
     * unrecoverable: {@code recruitment:write} is held by every team lead
     * (V486), so a copy-paste of the neighbouring endpoints would hand an
     * irreversible PII delete to twenty people. Nothing else in CI would
     * catch that — these contract tests are hand-enumerated allowlists, so an
     * endpoint nobody names is an endpoint nobody checks.
     */
    @Test
    void hardDelete_isPost_atExpectedPath_adminScoped() {
        Method m = requireMethod("hardDeleteCandidate");
        assertNotNull(m.getAnnotation(POST.class),
                "POST, not DELETE — it carries a confirmation body, and the anonymize "
                        + "precedent it copies is a POST");
        assertEquals("/candidates/{uuid}/hard-delete", m.getAnnotation(Path.class).value());

        RolesAllowed roles = m.getAnnotation(RolesAllowed.class);
        assertNotNull(roles, "hardDeleteCandidate must override the class-level scope");
        assertTrue(Arrays.asList(roles.value()).contains("recruitment:admin"),
                "hard delete requires recruitment:admin (V465 grants it to ADMIN only)");
        assertFalse(Arrays.asList(roles.value()).contains("recruitment:write"),
                "recruitment:write is held by every team lead — it must not appear here");
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
