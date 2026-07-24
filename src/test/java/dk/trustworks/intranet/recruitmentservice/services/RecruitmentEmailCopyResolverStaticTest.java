package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentEmailCopyRole;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shaping helpers around the copy list. These produce the values that
 * land in {@code mail.cc}/{@code mail.bcc} and in the queue row's
 * {@code copy_user_uuids} snapshot, so their null semantics are contract:
 * <em>null</em> means "no header at all", never the empty string.
 */
class RecruitmentEmailCopyResolverStaticTest {

    private static RecruitmentEmailCopyResolver.CopyRecipient recipient(String uuid, String email) {
        return new RecruitmentEmailCopyResolver.CopyRecipient(
                uuid, "Anna Jensen", email, RecruitmentEmailCopyRole.INTERVIEWERS);
    }

    @Test
    void emptyCopyList_producesNull_notAnEmptyHeader() {
        assertNull(RecruitmentEmailCopyResolver.addressesOf(List.of()));
        assertNull(RecruitmentEmailCopyResolver.addressesOf(null));
        assertNull(RecruitmentEmailCopyResolver.userUuidsOf(List.of()));
        assertNull(RecruitmentEmailCopyResolver.userUuidsOf(null));
    }

    @Test
    void addressesAndUuids_joinWithCommas_inOrder() {
        List<RecruitmentEmailCopyResolver.CopyRecipient> recipients = List.of(
                recipient("uuid-1", "anna@trustworks.dk"),
                recipient("uuid-2", "bo@trustworks.dk"));

        assertEquals("anna@trustworks.dk,bo@trustworks.dk",
                RecruitmentEmailCopyResolver.addressesOf(recipients));
        assertEquals("uuid-1,uuid-2",
                RecruitmentEmailCopyResolver.userUuidsOf(recipients));
    }

    @Test
    void uuidCsv_roundTrips_andSurvivesJunk() {
        assertEquals(List.of("uuid-1", "uuid-2"),
                RecruitmentEmailCopyResolver.splitUserUuids("uuid-1, uuid-2"));
        assertEquals(List.of("uuid-1"),
                RecruitmentEmailCopyResolver.splitUserUuids("uuid-1,,  "));
        assertTrue(RecruitmentEmailCopyResolver.splitUserUuids(null).isEmpty());
        assertTrue(RecruitmentEmailCopyResolver.splitUserUuids("").isEmpty());
    }

    @Test
    void overlongAddressList_isTruncated_notAllowedToFailTheInsert() {
        // mail.cc / mail.bcc are VARCHAR(1000) and production runs
        // STRICT_TRANS_TABLES: an over-long value is error 1406, which
        // would roll back the send and lose the candidate's email to save
        // a copy. Dropping the tail is the right trade.
        List<RecruitmentEmailCopyResolver.CopyRecipient> many = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add(recipient("uuid-" + i, ("verylongmailbox" + i).repeat(4) + "@trustworks.dk"));
        }

        String addresses = RecruitmentEmailCopyResolver.addressesOf(many);

        assertNotNull(addresses);
        assertTrue(addresses.length() <= RecruitmentEmailCopyResolver.ADDRESS_LIST_MAX_LENGTH,
                "must fit the column, got " + addresses.length());
        assertTrue(addresses.startsWith("verylongmailbox0"),
                "the earliest recipients survive");
    }

    @Test
    void uuidCsv_preservesCase() {
        // users.uuid is compared verbatim — lower-casing here would
        // silently drop every copy recipient on a mixed-case uuid.
        assertEquals(List.of("A1B2-C3D4"),
                RecruitmentEmailCopyResolver.splitUserUuids("A1B2-C3D4"));
    }
}
