package dk.trustworks.intranet.recruitmentservice.resources;

import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P19 contract locks:
 * <ul>
 *   <li>{@link RecruitmentGdprResource} — module rooting at
 *       {@code /recruitment} (the P9 JAX-RS longest-literal-prefix lesson:
 *       new resources must match the module's class-path rooting) with the
 *       dedicated {@code recruitment:gdpr} scope and the spec §6.2 method
 *       paths;</li>
 *   <li>{@link PublicConsentResource} — {@code /consent} with
 *       {@code @PermitAll} on exactly the two token endpoints and no scope
 *       annotation anywhere (the token is the credential).</li>
 * </ul>
 */
class RecruitmentGdprResourceContractTest {

    @Test
    void gdprResource_isRootedOnRecruitment_withTheGdprScope() {
        Path path = RecruitmentGdprResource.class.getAnnotation(Path.class);
        assertNotNull(path);
        assertEquals("/recruitment", path.value(),
                "module rooting — a /recruitment/gdpr class root would be the P9 shadowing bite");

        RolesAllowed roles = RecruitmentGdprResource.class.getAnnotation(RolesAllowed.class);
        assertNotNull(roles);
        assertEquals(Set.of("recruitment:gdpr"), Set.of(roles.value()),
                "the DPO surface uses ONLY the dedicated scope (spec §7.1)");
    }

    @Test
    void gdprEndpoints_matchTheSpecPaths() {
        assertEndpoint("queue", GET.class, "/gdpr/queue");
        assertEndpoint("candidateStatus", GET.class, "/gdpr/candidates/{uuid}/status");
        assertEndpoint("sendArt14", POST.class, "/gdpr/candidates/{uuid}/art14-send");
        assertEndpoint("recordDsar", POST.class, "/gdpr/candidates/{uuid}/dsar");
        assertEndpoint("dsarExport", POST.class, "/gdpr/candidates/{uuid}/dsar-export");
        assertEndpoint("anonymize", POST.class, "/gdpr/candidates/{uuid}/anonymize");
    }

    @Test
    void noGdprEndpoint_overridesTheClassScope() {
        for (Method method : RecruitmentGdprResource.class.getDeclaredMethods()) {
            assertNull(method.getAnnotation(RolesAllowed.class),
                    method.getName() + " must inherit the class-level recruitment:gdpr scope");
        }
    }

    @Test
    void publicConsentResource_isPermitAllOnExactlyTheTokenEndpoints() {
        Path path = PublicConsentResource.class.getAnnotation(Path.class);
        assertNotNull(path);
        assertEquals("/consent", path.value());
        assertNull(PublicConsentResource.class.getAnnotation(RolesAllowed.class),
                "no scope — the token is the credential");

        List<String> publicMethods = List.of("state", "decide");
        for (String name : publicMethods) {
            Method method = Arrays.stream(PublicConsentResource.class.getDeclaredMethods())
                    .filter(m -> m.getName().equals(name))
                    .findFirst().orElseThrow();
            assertNotNull(method.getAnnotation(PermitAll.class),
                    name + " must be @PermitAll (anonymous public page)");
            assertEquals("/{token}", method.getAnnotation(Path.class).value());
        }
        long permitAll = Arrays.stream(PublicConsentResource.class.getDeclaredMethods())
                .filter(m -> m.getAnnotation(PermitAll.class) != null)
                .count();
        assertEquals(2, permitAll, "exactly the two token endpoints are public — nothing else");
        assertTrue(Arrays.stream(PublicConsentResource.class.getDeclaredFields())
                        .noneMatch(f -> f.getName().toLowerCase().contains("token")),
                "the resource never holds token state");
    }

    private static void assertEndpoint(String name, Class<? extends java.lang.annotation.Annotation> verb,
                                       String expectedPath) {
        Method method = Arrays.stream(RecruitmentGdprResource.class.getDeclaredMethods())
                .filter(m -> m.getName().equals(name))
                .findFirst().orElseThrow(() -> new AssertionError("missing endpoint " + name));
        assertNotNull(method.getAnnotation(verb), name + " must be @" + verb.getSimpleName());
        assertEquals(expectedPath, method.getAnnotation(Path.class).value(),
                name + " path is spec §6.2 contract");
    }
}
