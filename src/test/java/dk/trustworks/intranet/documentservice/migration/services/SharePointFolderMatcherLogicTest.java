package dk.trustworks.intranet.documentservice.migration.services;

import dk.trustworks.intranet.documentservice.migration.model.SharePointMigrationFolder.AiConfidence;
import dk.trustworks.intranet.documentservice.migration.services.SharePointFolderMatcherService.DirectoryEntry;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Stage-1 matcher logic (runbook 2a-3 verify): both exact tiers, æøå
 * normalization in both directions, tier precedence.
 */
class SharePointFolderMatcherLogicTest {

    private static final DirectoryEntry BIRGER =
            new DirectoryEntry("uuid-birger", "birger.puschl", "Birger Püschl", "2015-01-01 to present (active)");
    private static final DirectoryEntry SOEREN =
            new DirectoryEntry("uuid-soeren", "soeren.skov", "Søren Skov", "2018-01-01 to present (active)");
    private static final DirectoryEntry ALBERTE =
            new DirectoryEntry("uuid-alberte", "alberte.bang", "Alberte Bang", "2020-01-01 to present (active)");

    private static final Map<String, DirectoryEntry> BY_USERNAME = Map.of(
            "birger.puschl", BIRGER,
            "soeren.skov", SOEREN,
            "alberte.bang", ALBERTE);

    private static final Map<String, DirectoryEntry> BY_NAME = Map.of(
            SharePointFolderMatcherService.canonicalName(BIRGER.fullName()), BIRGER,
            SharePointFolderMatcherService.canonicalName(SOEREN.fullName()), SOEREN,
            SharePointFolderMatcherService.canonicalName(ALBERTE.fullName()), ALBERTE);

    private static DirectoryEntry match(String folderName) {
        return SharePointFolderMatcherService.exactMatch(folderName, BY_USERNAME, BY_NAME);
    }

    // ── USERNAME tier ──────────────────────────────────────────────────────

    @Test
    void usernameTierMatchesCaseInsensitive() {
        assertEquals(BIRGER, match("birger.puschl"));
        assertEquals(BIRGER, match("Birger.Puschl"));
        assertEquals(BIRGER, match("  birger.puschl  "));
    }

    // ── FULLNAME tier, æøå both directions ─────────────────────────────────

    @Test
    void fullnameTierMatchesExactName() {
        assertEquals(ALBERTE, match("Alberte Bang"));
        assertEquals(ALBERTE, match("alberte bang"));
        assertEquals(ALBERTE, match("Alberte   Bang"));
    }

    @Test
    void danishCharactersNormalizeFolderToDirectory() {
        // Folder written with ae/oe/aa, directory name with æ/ø/å.
        assertEquals(SOEREN, match("Soeren Skov"));
    }

    @Test
    void danishCharactersNormalizeDirectoryToFolder() {
        // Folder written with ø, directory name matches after folding.
        assertEquals(SOEREN, match("Søren Skov"));
    }

    @Test
    void noMatchReturnsNull() {
        assertNull(match("Arkiv gamle kontrakter"));
        assertNull(match("Cleo W Brunse - (Marketing Junior)"));
        assertNull(match(""));
        assertNull(match(null));
    }

    // ── canonicalName ──────────────────────────────────────────────────────

    @Test
    void canonicalNameFoldsBothDirectionsToOneForm() {
        assertEquals(
                SharePointFolderMatcherService.canonicalName("Søren Kjærgaard Ås"),
                SharePointFolderMatcherService.canonicalName("Soeren Kjaergaard Aas"));
        assertEquals("soeren skov", SharePointFolderMatcherService.canonicalName("  SØREN   skov "));
    }

    // ── confidence parsing (hard validation) ───────────────────────────────

    @Test
    void unknownConfidenceFallsBackToLow() {
        assertEquals(AiConfidence.HIGH, SharePointFolderMatcherService.parseConfidence("HIGH"));
        assertEquals(AiConfidence.MEDIUM, SharePointFolderMatcherService.parseConfidence("medium"));
        assertEquals(AiConfidence.LOW, SharePointFolderMatcherService.parseConfidence("certain"));
        assertEquals(AiConfidence.LOW, SharePointFolderMatcherService.parseConfidence(null));
    }

    // ── employment period text ─────────────────────────────────────────────

    @Test
    void employmentPeriodFormatsActiveAndTerminated() {
        UserStatus active = new UserStatus(ConsultantType.CONSULTANT, StatusType.ACTIVE,
                LocalDate.of(2015, 3, 1), 37, "u1");
        UserStatus terminated = new UserStatus(ConsultantType.CONSULTANT, StatusType.TERMINATED,
                LocalDate.of(2019, 6, 30), 0, "u1");

        assertEquals("2015-03-01 to present (active)",
                SharePointFolderMatcherService.employmentPeriod(List.of(active)));
        assertEquals("2015-03-01 to 2019-06-30 (former)",
                SharePointFolderMatcherService.employmentPeriod(List.of(active, terminated)));
        assertEquals("employment unknown",
                SharePointFolderMatcherService.employmentPeriod(List.of()));
    }
}
