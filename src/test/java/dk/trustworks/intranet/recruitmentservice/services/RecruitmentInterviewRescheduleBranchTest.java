package dk.trustworks.intranet.recruitmentservice.services;

import org.junit.jupiter.api.Test;

import static dk.trustworks.intranet.recruitmentservice.services.RecruitmentInterviewService.shouldCreateMissingEvent;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reschedule create-vs-update branch (plan Phase 2.4) — the recovery
 * path for every unsynced interview (pre-toggle rows and the Airtable
 * migration): {@code updateEvent} returns immediately when
 * {@code graph_event_id} is NULL, so without this branch those rows can
 * never gain an invitation by editing. Pinned DB-free because the branch
 * gates a Graph WRITE — flipping it silently would either spam events or
 * bring the dead-end back.
 */
class RecruitmentInterviewRescheduleBranchTest {

    @Test
    void unsyncedRow_explicitOptIn_toggleOn_creates() {
        assertTrue(shouldCreateMissingEvent(null, true, true));
    }

    @Test
    void syncedRow_neverCreatesASecondEvent() {
        assertFalse(shouldCreateMissingEvent("evt-1", true, true),
                "an existing event must be UPDATED, opt-in flag or not");
    }

    @Test
    void withoutExplicitOptIn_staysOnTheUpdatePath() {
        assertFalse(shouldCreateMissingEvent(null, null, true),
                "null = the checkbox was not shown/ticked — old clients keep old behavior");
        assertFalse(shouldCreateMissingEvent(null, false, true));
    }

    @Test
    void toggleOff_neverCreates() {
        assertFalse(shouldCreateMissingEvent(null, true, false));
    }

    // ---- F17: the create-side no-calendar toggle --------------------------

    @Test
    void create_skipsOutlookOnlyOnExplicitFalse() {
        // F17: only a deliberate createCalendarEvent=false records the
        // interview without an Outlook event; null and TRUE keep every
        // existing caller (Method A dialog, Method B finalization) on
        // the create path.
        assertTrue(RecruitmentInterviewService.shouldCreateEventOnCreate(null));
        assertTrue(RecruitmentInterviewService.shouldCreateEventOnCreate(true));
        assertFalse(RecruitmentInterviewService.shouldCreateEventOnCreate(false));
    }
}
