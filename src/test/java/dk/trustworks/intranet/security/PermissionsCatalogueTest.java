package dk.trustworks.intranet.security;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the permission catalogue extracted in Phase 4 (task 4.1).
 *
 * <p>The catalogue began as the promotion of the 85 scope strings that already existed in
 * {@code AdminScopeAugmentor.ALL_SCOPES}. These tests pin (a) that the augmentor and
 * the catalogue can never diverge, and (b) that every key keeps the {@code domain:action}
 * colon-scope shape — task 4.2 removed the single non-scope value ({@code "ADMIN"}),
 * and nothing may reintroduce one.
 */
class PermissionsCatalogueTest {

    private static final Pattern SCOPE_SHAPE = Pattern.compile("^[a-z_-]+:[a-z*_-]+$");

    /**
     * 85 at extraction from {@code AdminScopeAugmentor.ALL_SCOPES};
     * 86 since the recruitment go-live added {@code recruitment:manage}
     * (2026-08-10) to separate the recruiter tier from {@code
     * recruitment:write}, which every team lead holds;
     * 89 since the SKI 7.b competence module added {@code competence:read},
     * {@code :write} and {@code :approve} (2026-08-14) — deliberately not
     * reusing {@code knowledge:*}, which gates elective CKO courses rather
     * than mandatory, assessed compliance training;
     * 90 since team-lead candidate intake added {@code recruitment:intake}
     * (2026-08-18) — the create-and-attach slice of the candidate database,
     * split out of {@code recruitment:manage} so a team lead can put a name
     * into the funnel without also getting edit, delete, pool/unpool, bulk
     * tag, candidate e-mail, templates, the dossier or record-check outcomes;
     * 92 since the recruitment nav-gate split added {@code recruitment:triage}
     * and {@code recruitment:settings} (2026-08-23) — the Inbox and Settings
     * surfaces both hung off {@code recruitment:write}, which every team lead
     * holds, so the two tabs could not diverge and team leads saw surfaces
     * whose every request 403s (recruitment-access-model-target §5.2).
     */
    private static final int EXPECTED_PERMISSIONS = 92;

    @Test
    void catalogueHoldsExpectedNumberOfPermissions() {
        assertEquals(EXPECTED_PERMISSIONS, Permissions.CATALOGUE.size(),
                "The catalogue size changed. If that was deliberate, regenerate V464 "
                        + "(see PermissionSeedSql) and the vendored artifact (see "
                        + "PermissionCatalogueJson), then update this number.");
        assertEquals(EXPECTED_PERMISSIONS, Permissions.allKeys().size(),
                "Duplicate keys would collapse in the key set");
    }

    @Test
    void augmentorViewIsTheCatalogue() {
        assertEquals(Permissions.allKeysAsSet(), AdminScopeAugmentor.ALL_SCOPES,
                "AdminScopeAugmentor must delegate to Permissions — the two diverging would "
                        + "recreate the split catalogue Phase 4 exists to remove");
    }

    @Test
    void everyKeyIsAColonScope() {
        for (String key : Permissions.allKeys()) {
            assertTrue(SCOPE_SHAPE.matcher(key).matches(),
                    "Permission key '" + key + "' is not a domain:action scope string. "
                            + "Role names (like the removed \"ADMIN\") are not permission keys.");
        }
    }

    @Test
    void adminWildcardIsPresent() {
        assertTrue(Permissions.allKeys().contains(Permissions.ADMIN_WILDCARD),
                "admin:* must stay in the catalogue — the augmentor keys its expansion off it");
    }

    @Test
    void displayMetadataIsComplete() {
        Set<String> seen = new HashSet<>();
        for (Permissions.Permission p : Permissions.CATALOGUE) {
            assertTrue(seen.add(p.key()), "Duplicate catalogue entry: " + p.key());
            assertFalse(p.displayName() == null || p.displayName().isBlank(),
                    "display_name is NOT NULL in the permission table — blank for " + p.key());
            assertFalse(p.category() == null || p.category().isBlank(),
                    "category should be set for " + p.key());
            assertTrue(p.displayName().length() <= 128, "display_name column is VARCHAR(128): " + p.key());
            assertTrue(p.key().length() <= 64, "permission_key column is VARCHAR(64): " + p.key());
        }
    }
}
