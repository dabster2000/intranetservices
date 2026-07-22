package dk.trustworks.intranet.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for {@link AdminScopeAugmentor}.
 * <p>
 * The AI Validation Console (frontend) depends on the BFF being able to call
 * {@code /admin/ai-config/*} endpoints, which are annotated with
 * {@code @RolesAllowed({"admin:write"})}. The BFF holds the {@code admin:*}
 * scope on its client credentials JWT, and the augmentor expands that into
 * all defined scopes. If {@code admin:write} ever disappears from
 * {@link AdminScopeAugmentor#ALL_SCOPES}, the entire AI Validation Console
 * goes 403. This test locks that contract.
 */
class AdminScopeAugmentorTest {

    @Test
    void allScopes_contains_admin_write_for_ai_validation_console() {
        assertTrue(
                AdminScopeAugmentor.ALL_SCOPES.contains("admin:write"),
                "AI Validation Console endpoints require admin:write; the BFF receives it via admin:* expansion"
        );
    }

    @Test
    void allScopes_contains_admin_wildcard() {
        assertTrue(
                AdminScopeAugmentor.ALL_SCOPES.contains(AdminScopeAugmentor.ADMIN_WILDCARD),
                "admin:* wildcard itself must be present so it does not get stripped from augmented identities"
        );
    }

    @Test
    void allScopes_contains_practices_scopes_for_bff_admin_expansion() {
        assertTrue(
                AdminScopeAugmentor.ALL_SCOPES.contains("practices:read"),
                "The practice registry endpoints require practices:read; the BFF receives it via admin:* expansion"
        );
        assertTrue(
                AdminScopeAugmentor.ALL_SCOPES.contains("practices:write"),
                "Practice registry mutations require practices:write; the BFF receives it via admin:* expansion"
        );
    }

    @Test
    void allScopes_contains_recruitment_ats_scopes() {
        // ATS expansion P1 registers all five upfront (plan §P1) so later
        // phases' endpoints never 403 the BFF. Removing one breaks the
        // corresponding phase's surface.
        for (String scope : new String[]{
                "recruitment:read", "recruitment:write",
                "recruitment:interview", "recruitment:refer",
                "recruitment:comp", "recruitment:gdpr", "recruitment:admin"}) {
            assertTrue(
                    AdminScopeAugmentor.ALL_SCOPES.contains(scope),
                    "Recruitment endpoints require " + scope + "; the BFF receives it via admin:* expansion"
            );
        }
    }

    @Test
    void allScopes_has_reasonable_size() {
        assertTrue(
                AdminScopeAugmentor.ALL_SCOPES.size() >= 50,
                "ALL_SCOPES should never shrink unexpectedly; sanity-check against accidental deletions. " +
                "Current size: " + AdminScopeAugmentor.ALL_SCOPES.size()
        );
    }
}
