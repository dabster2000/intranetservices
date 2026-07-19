package dk.trustworks.intranet.resources;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Locks the scope contract on {@link PracticeResource}: the class defaults to
 * {@code practices:read}; every mutation overrides to {@code practices:write};
 * reads inherit the class default with no override. If a mutation ever loses its
 * write scope it would be callable with read-only credentials — this test fails first.
 */
class PracticeResourceSecurityTest {

    @Test
    void class_default_scope_is_practices_read() {
        RolesAllowed classRoles = PracticeResource.class.getAnnotation(RolesAllowed.class);
        assertNotNull(classRoles, "PracticeResource must be scope-gated at the class level");
        assertArrayEquals(new String[]{"practices:read"}, classRoles.value());

        assertEquals("/practices", PracticeResource.class.getAnnotation(Path.class).value());
    }

    @Test
    void mutations_require_practices_write() throws NoSuchMethodException {
        assertWriteScope("create", PracticeResource.CreatePracticeRequest.class);
        assertWriteScope("update", String.class, PracticeResource.UpdatePracticeRequest.class);
        assertWriteScope("startLead", String.class, PracticeResource.StartLeadRequest.class);
        assertWriteScope("endLead", String.class, String.class, PracticeResource.EndLeadRequest.class);
    }

    @Test
    void reads_inherit_the_class_scope_without_override() throws NoSuchMethodException {
        assertNoMethodScope("findAll");
        assertNoMethodScope("findLeads", String.class);
        assertNoMethodScope("findTeams", String.class);
    }

    private static void assertWriteScope(String method, Class<?>... params) throws NoSuchMethodException {
        RolesAllowed roles = PracticeResource.class.getDeclaredMethod(method, params).getAnnotation(RolesAllowed.class);
        assertNotNull(roles, method + " must carry @RolesAllowed");
        assertArrayEquals(new String[]{"practices:write"}, roles.value(), method + " must require practices:write");
    }

    private static void assertNoMethodScope(String method, Class<?>... params) throws NoSuchMethodException {
        assertNull(PracticeResource.class.getDeclaredMethod(method, params).getAnnotation(RolesAllowed.class),
                method + " must inherit the class-level practices:read (no override)");
    }
}
