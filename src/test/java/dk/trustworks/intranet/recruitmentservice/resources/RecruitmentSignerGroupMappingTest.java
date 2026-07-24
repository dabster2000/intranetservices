package dk.trustworks.intranet.recruitmentservice.resources;

import dk.trustworks.intranet.recruitmentservice.dto.SignerConfigDto;
import dk.trustworks.intranet.utils.dto.signing.SignerInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards the dossier signer → NextSign recipient group mapping.
 *
 * <p>NextSign treats the recipient {@code order} as sequential: a recipient is
 * only emailed once every lower order has signed. {@code mapSigners} used to
 * assign the group from list position, putting every signer in their own round
 * so only the first was ever notified. These tests pin the corrected contract:
 * the configured {@code signer_group} decides the round, and equal groups sign
 * in parallel.
 */
class RecruitmentSignerGroupMappingTest {

    private static SignerConfigDto signer(String group, String name) {
        return new SignerConfigDto(group, name, name + "@example.com", true, false, "signer", null);
    }

    @Test
    void signersSharingAGroupLandInTheSameRound() {
        List<SignerInfo> mapped = RecruitmentResource.mapSigners(List.of(
                signer("1", "candidate"),
                signer("1", "manager"),
                signer("1", "ceo")));

        // All three in group 1 → NextSign order 0 → all emailed immediately.
        assertEquals(List.of(1, 1, 1), mapped.stream().map(SignerInfo::group).toList());
    }

    @Test
    void distinctGroupsStaySequentialInConfiguredOrder() {
        List<SignerInfo> mapped = RecruitmentResource.mapSigners(List.of(
                signer("1", "candidate"),
                signer("2", "manager"),
                signer("2", "ceo")));

        assertEquals(List.of(1, 2, 2), mapped.stream().map(SignerInfo::group).toList());
    }

    @Test
    void groupDrivesTheRoundNotListPosition() {
        // The candidate is listed last but configured into the first group —
        // the exact case that previously left them un-emailed.
        List<SignerInfo> mapped = RecruitmentResource.mapSigners(List.of(
                signer("2", "manager"),
                signer("2", "ceo"),
                signer("1", "candidate")));

        assertEquals(List.of(2, 2, 1), mapped.stream().map(SignerInfo::group).toList());
    }

    @Test
    void groupsAreDenseRankedSoTheLowestAlwaysBecomesOrderZero() {
        // Groups start at 4 (lower groups deleted in the editor). Without
        // dense ranking these map to NextSign orders 3 and 6 — no order-0
        // recipient, so NextSign would notify nobody.
        List<SignerInfo> mapped = RecruitmentResource.mapSigners(List.of(
                signer("4", "candidate"),
                signer("7", "ceo")));

        assertEquals(List.of(1, 2), mapped.stream().map(SignerInfo::group).toList());
    }

    @Test
    void unusableGroupsFallBackToTheFirstRound() {
        List<SignerInfo> mapped = RecruitmentResource.mapSigners(List.of(
                signer(null, "candidate"),
                signer("  ", "manager"),
                signer("Trustworks", "ceo"),
                signer("0", "cfo"),
                signer("-3", "cto")));

        assertEquals(List.of(1, 1, 1, 1, 1), mapped.stream().map(SignerInfo::group).toList());
    }

    @Test
    void roleDefaultsToCopyForNonSigningRecipients() {
        List<SignerInfo> mapped = RecruitmentResource.mapSigners(List.of(
                new SignerConfigDto("1", "hr", "hr@example.com", false, false, null, null),
                new SignerConfigDto("1", "candidate", "c@example.com", true, false, null, null)));

        assertEquals("copy", mapped.get(0).role());
        assertEquals("signer", mapped.get(1).role());
    }

    @Test
    void parseSignerGroupReadsDecimalStrings() {
        assertEquals(1, RecruitmentResource.parseSignerGroup("1"));
        assertEquals(3, RecruitmentResource.parseSignerGroup(" 3 "));
        assertEquals(1, RecruitmentResource.parseSignerGroup(null));
        assertEquals(1, RecruitmentResource.parseSignerGroup(""));
        assertEquals(1, RecruitmentResource.parseSignerGroup("Candidate"));
    }

    @Test
    void emptySignerListMapsToEmptyRecipients() {
        assertEquals(List.of(), RecruitmentResource.mapSigners(List.of()));
    }
}
