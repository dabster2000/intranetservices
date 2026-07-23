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
 * P13 contract lock for the two Slack endpoints (Slack spec §6.1):
 * <ul>
 *   <li>{@code GET /recruitment/slack/flags} — {@code recruitment:read}
 *       (the settings tab / BFF middleware read surface).</li>
 *   <li>{@code POST /recruitment/slack/inbound} —
 *       {@code recruitment:admin} (machine scope; only the BFF's system
 *       token reaches it, marked with the internal source header).</li>
 * </ul>
 * Path roots are deliberately distinct classes
 * ({@code /recruitment/slack} vs {@code /recruitment/slack/inbound}) —
 * the JAX-RS longest-literal-prefix lesson from findings §P9.5.
 */
class RecruitmentSlackResourceContractTest {

    @Test
    void flagsResource_pathAndReadScope() {
        Path path = RecruitmentSlackResource.class.getAnnotation(Path.class);
        assertNotNull(path);
        assertEquals("/recruitment/slack", path.value());

        RolesAllowed roles = RecruitmentSlackResource.class.getAnnotation(RolesAllowed.class);
        assertNotNull(roles);
        assertEquals(Set.of("recruitment:read"), Set.of(roles.value()));
    }

    @Test
    void flagsEndpoint_isGetOnFlags_underTheClassReadScope() {
        Method flags = method(RecruitmentSlackResource.class, "flags");
        assertNotNull(flags.getAnnotation(GET.class), "flags must be @GET");
        assertEquals("/flags", flags.getAnnotation(Path.class).value());
        assertNull(flags.getAnnotation(RolesAllowed.class),
                "flags must inherit the class-level recruitment:read scope");
    }

    @Test
    void inboundResource_pathAndAdminScope() {
        Path path = SlackInboundResource.class.getAnnotation(Path.class);
        assertNotNull(path);
        assertEquals("/recruitment/slack/inbound", path.value());

        RolesAllowed roles = SlackInboundResource.class.getAnnotation(RolesAllowed.class);
        assertNotNull(roles);
        assertEquals(Set.of("recruitment:admin"), Set.of(roles.value()));
    }

    @Test
    void inboundEndpoint_isPostAtTheClassRoot_underTheAdminScope() {
        Method dispatch = method(SlackInboundResource.class, "dispatch");
        assertNotNull(dispatch.getAnnotation(POST.class), "dispatch must be @POST");
        assertNull(dispatch.getAnnotation(Path.class),
                "dispatch must sit at the class root — no method-level path");
        assertNull(dispatch.getAnnotation(RolesAllowed.class),
                "dispatch must inherit the class-level recruitment:admin scope");
    }

    private static Method method(Class<?> resource, String name) {
        for (Method m : resource.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        fail(resource.getSimpleName() + " must declare method " + name);
        return null;
    }
}
