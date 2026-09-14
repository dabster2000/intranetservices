package dk.trustworks.intranet.aggregates.crm.retention.services;

import dk.trustworks.intranet.aggregates.crm.retention.model.enums.CrmPurgeStatus;
import dk.trustworks.intranet.aggregates.crm.retention.services.CrmRetentionPurgeService.PurgeSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a run ends, and the one ending that must never be mistaken for another.
 *
 * <p>This table exists to answer whether a promise made in eight migration headers is being
 * kept, so the states have to stay distinguishable. {@code DONE} with three failures is a
 * sweep that reached the end and left three accounts for tomorrow. {@code STOPPED} is a job
 * that fired while disarmed and deleted nothing — the state the purge ships in, and the one a
 * reader must be able to tell apart from a sweep that simply found nothing to erase. A null
 * summary still closes the row: leaving it {@code RUNNING} would turn a run that did nothing
 * into one that looks like it is still going.
 */
class CrmRetentionPurgeStatusTest {

    @Test
    @DisplayName("a sweep that reached the end is DONE, even with failures on it")
    void aCompletedSweepIsDone() {
        PurgeSummary withFailures = new PurgeSummary(false, 12, 9, 300, 4, 7, 40, 2, 11,
                3, CrmRetentionPurgeService.FAILURE_ACCOUNT);
        assertEquals(CrmPurgeStatus.DONE, CrmRetentionPurgeService.statusOf(withFailures),
                "three accounts that threw are unchanged and are found again tomorrow; the run "
                        + "itself finished");
    }

    @Test
    @DisplayName("a run that fired while disarmed is STOPPED, not DONE with zeros")
    void aDisarmedRunIsStopped() {
        assertEquals(CrmPurgeStatus.STOPPED,
                CrmRetentionPurgeService.statusOf(PurgeSummary.switchedOff()));
    }

    @Test
    @DisplayName("a null summary still closes the row rather than leaving it RUNNING for ever")
    void aNullSummaryStillCloses() {
        assertEquals(CrmPurgeStatus.DONE, CrmRetentionPurgeService.statusOf(null));
    }

    @Test
    @DisplayName("the disarmed summary carries nothing but the fact that it was disarmed")
    void theDisarmedSummaryIsAllZeroes() {
        PurgeSummary summary = PurgeSummary.switchedOff();

        assertTrue(summary.disarmed());
        assertEquals(0, summary.accountsConsidered());
        assertEquals(0, summary.accountsPurged());
        assertEquals(0, summary.meetingAttendees());
        assertEquals(0, summary.signalsRedacted());
        assertEquals(0, summary.slackMentions());
        assertEquals(0, summary.peopleDeleted());
        assertEquals(0, summary.stakeholdersCleared());
        assertEquals(0, summary.trustlinkConnections());
        assertEquals(0, summary.failures());
        assertNull(summary.failureCode(),
                "being switched off is not a failure — it is the state this job ships in");
    }

    @Test
    @DisplayName("every failure code is a code, short enough for the column and free of any row content")
    void failureCodesAreCodes() {
        for (String code : new String[]{
                CrmRetentionPurgeService.FAILURE_UNEXPECTED,
                CrmRetentionPurgeService.FAILURE_SUBMIT_REJECTED,
                CrmRetentionPurgeService.FAILURE_ACCOUNT,
                CrmRetentionPurgeService.FAILURE_TRUSTLINK}) {
            assertTrue(code.matches("[A-Z_]{1,40}"),
                    code + " must be a CODE: failure_code is VARCHAR(40) and an exception text here "
                            + "would echo the very row the purge is erasing");
        }
    }

    @Test
    @DisplayName("the redaction placeholder is a placeholder, because signal_text is NOT NULL")
    void theRedactionPlaceholderIsNotNull() {
        assertEquals("[redacted 24 m]", CrmRetentionPurgeService.REDACTED_SIGNAL_TEXT);
    }
}
