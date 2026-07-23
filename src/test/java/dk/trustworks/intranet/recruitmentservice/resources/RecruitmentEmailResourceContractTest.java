package dk.trustworks.intranet.recruitmentservice.resources;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * P15 contract lock for the candidate-email endpoints (plan §P15):
 * reads inherit the class-level {@code recruitment:read}; every mutation
 * carries {@code recruitment:write}. The class roots at
 * {@code /recruitment} with method-level literal paths — the JAX-RS
 * longest-literal-prefix lesson from findings §P9.5.
 */
class RecruitmentEmailResourceContractTest {

    @Test
    void resource_rootsAtRecruitment_withReadScope() {
        Path path = RecruitmentEmailResource.class.getAnnotation(Path.class);
        assertNotNull(path);
        assertEquals("/recruitment", path.value());

        RolesAllowed roles = RecruitmentEmailResource.class.getAnnotation(RolesAllowed.class);
        assertNotNull(roles);
        assertEquals(Set.of("recruitment:read"), Set.of(roles.value()));
    }

    @Test
    void readEndpoints_inheritTheClassReadScope() {
        assertRead("listTemplates", GET.class, "/email-templates");
        assertRead("render", POST.class, "/candidates/{uuid}/emails/render");
        assertRead("listPending", GET.class, "/emails/pending");
    }

    @Test
    void mutationEndpoints_requireWriteScope() {
        assertWrite("createTemplate", POST.class, "/email-templates");
        assertWrite("updateTemplate", PUT.class, "/email-templates/{uuid}");
        assertWrite("send", POST.class, "/candidates/{uuid}/emails/send");
        assertWrite("approve", POST.class, "/emails/pending/{uuid}/approve");
        assertWrite("dismiss", POST.class, "/emails/pending/{uuid}/dismiss");
    }

    private static void assertRead(String name, Class<? extends java.lang.annotation.Annotation> verb,
                                   String path) {
        Method method = method(name);
        assertNotNull(method.getAnnotation(verb), name + " verb");
        assertEquals(path, method.getAnnotation(Path.class).value());
        assertNull(method.getAnnotation(RolesAllowed.class),
                name + " must inherit the class-level recruitment:read scope");
    }

    private static void assertWrite(String name, Class<? extends java.lang.annotation.Annotation> verb,
                                    String path) {
        Method method = method(name);
        assertNotNull(method.getAnnotation(verb), name + " verb");
        assertEquals(path, method.getAnnotation(Path.class).value());
        RolesAllowed roles = method.getAnnotation(RolesAllowed.class);
        assertNotNull(roles, name + " must carry recruitment:write");
        assertEquals(Set.of("recruitment:write"), Set.of(roles.value()));
    }

    private static Method method(String name) {
        for (Method m : RecruitmentEmailResource.class.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        fail("RecruitmentEmailResource must declare method " + name);
        return null;
    }
}
