package dk.trustworks.intranet.recruitmentservice.model.enums;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The copy-role CSV is a persisted wire format
 * ({@code recruitment_email_templates.copy_roles}) — round-tripping and
 * tolerance of junk are contract, not convenience.
 */
class RecruitmentEmailCopyRoleTest {

    @Test
    void emptyAndNullCsv_meanCopyNobody() {
        assertTrue(RecruitmentEmailCopyRole.parseCsv(null).isEmpty());
        assertTrue(RecruitmentEmailCopyRole.parseCsv("").isEmpty());
        assertTrue(RecruitmentEmailCopyRole.parseCsv("   ").isEmpty());
    }

    @Test
    void csvRoundTrips_inStableEnumOrder() {
        // Deliberately supplied out of order — the stored form must not
        // depend on the order the UI happened to send.
        Set<RecruitmentEmailCopyRole> roles = new LinkedHashSet<>();
        roles.add(RecruitmentEmailCopyRole.HIRING_OWNER);
        roles.add(RecruitmentEmailCopyRole.INTERVIEWERS);

        String csv = RecruitmentEmailCopyRole.toCsv(roles);

        assertEquals("INTERVIEWERS,HIRING_OWNER", csv);
        assertEquals(roles, RecruitmentEmailCopyRole.parseCsv(csv));
    }

    @Test
    void unknownAndBlankTokens_areIgnoredNotFatal() {
        // A stored value written by an older/newer deploy must never break
        // a send — the email to the candidate matters more than a copy.
        Set<RecruitmentEmailCopyRole> roles =
                RecruitmentEmailCopyRole.parseCsv("INTERVIEWERS,,SOMETHING_ELSE, SENDER ");

        assertEquals(Set.of(RecruitmentEmailCopyRole.INTERVIEWERS,
                RecruitmentEmailCopyRole.SENDER), roles);
    }

    @Test
    void parse_isCaseInsensitiveAndRejectsJunk() {
        assertEquals(RecruitmentEmailCopyRole.SENDER,
                RecruitmentEmailCopyRole.parse(" sender ").orElseThrow());
        assertTrue(RecruitmentEmailCopyRole.parse("cc").isEmpty());
        assertTrue(RecruitmentEmailCopyRole.parse(null).isEmpty());
    }

    @Test
    void toCsv_ofEmptySet_isTheEmptyStringNotNull() {
        // The column is NOT NULL DEFAULT '' — a null here would fail the
        // insert rather than mean "nobody".
        assertEquals("", RecruitmentEmailCopyRole.toCsv(Set.of()));
        assertEquals("", RecruitmentEmailCopyRole.toCsv(null));
    }
}
