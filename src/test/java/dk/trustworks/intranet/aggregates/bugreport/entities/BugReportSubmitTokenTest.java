package dk.trustworks.intranet.aggregates.bugreport.entities;

import dk.trustworks.intranet.utils.TemporalParams;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the two contracts that let a bug report leave DRAFT.
 * <p>
 * Between 2026-03 and 2026-09, 24 of 76 production bug reports (32%) never got past DRAFT.
 * The submit call carries the report's {@code updatedAt} as an {@code If-Match} concurrency
 * token, and {@code BugReportService.checkOptimisticLock} rejects a mismatch with 409 before any
 * write happens — so a token the client cannot keep exact is a report the user can never file.
 * Nothing on either side of the wire covered that path, which is why it survived six months.
 * <p>
 * Plain unit test — no Quarkus boot, DB-free tier (the CI deploy gate).
 */
class BugReportSubmitTokenTest {

    private static final String REPORTER = "7948c5e8-162c-4053-b905-0f59a21d7746";

    // ---- The token contract ----

    @Test
    void analyzeTokenSurvivesTheRoundTripBackIntoAnIfMatch() {
        // What POST /{uuid}/analyze puts on the wire: a DB-loaded value, rendered by
        // Instant.toString(), which emits "Z" and drops all-zero fractions.
        LocalDateTime fromDb = LocalDateTime.of(2026, 9, 3, 20, 36, 57);
        String onTheWire = fromDb.toInstant(ZoneOffset.UTC).toString();
        assertEquals("2026-09-03T20:36:57Z", onTheWire);

        // What BugReportResource.parseIfMatch makes of it when the client echoes it back.
        LocalDateTime parsed = TemporalParams.parseUtcInstant(onTheWire);

        assertEquals(fromDb, parsed, "a DB-loaded token must come back byte-for-byte equal");
    }

    @Test
    void truncationRescuesATokenThatRoundTrippedThroughTheColumn() {
        // bug_reports.updated_at is DATETIME with NO fractional seconds (V247), so whatever the
        // client holds, the persisted value has zero nanos. Comparing at second precision loses
        // nothing the column could have stored.
        LocalDateTime persisted = LocalDateTime.of(2026, 9, 3, 20, 36, 57);
        LocalDateTime clientHeld = persisted.withNano(215_160_563);

        assertNotEquals(persisted, clientHeld, "exact equality is what used to 409");
        assertEquals(persisted.truncatedTo(ChronoUnit.SECONDS),
                clientHeld.truncatedTo(ChronoUnit.SECONDS),
                "truncating both sides makes the echoed token usable");
    }

    @Test
    void truncationAloneIsNotEnough_theServiceMustHandOutThePersistedValue() {
        // The reason update() has to flush+refresh rather than lean on truncation: MariaDB
        // ROUNDS a fractional value into a DATETIME(0) column, it does not truncate. An
        // in-memory ...:57.7 lands in the row as ...:58, so the client's truncated ...:57 still
        // misses. Roughly half of all sub-second tokens fail this way.
        //
        // If this test ever starts failing, someone has changed the column's precision — go and
        // re-read BugReportService.flushAndRefresh before deleting it as redundant.
        LocalDateTime inMemory = LocalDateTime.of(2026, 9, 3, 20, 36, 57, 700_000_000);
        LocalDateTime asStoredAfterRounding = LocalDateTime.of(2026, 9, 3, 20, 36, 58);

        assertNotEquals(asStoredAfterRounding.truncatedTo(ChronoUnit.SECONDS),
                inMemory.truncatedTo(ChronoUnit.SECONDS),
                "a rounded value is one second ahead — only handing back the persisted "
                        + "token makes the next request match");
    }

    // ---- The state machine behind the submit ----

    @Test
    void aDraftCanBeSubmitted() {
        BugReport report = draft();

        assertTrue(report.canTransitionTo(BugReportStatus.SUBMITTED));
        report.transitionTo(BugReportStatus.SUBMITTED);

        assertEquals(BugReportStatus.SUBMITTED, report.getStatus());
    }

    @Test
    void resubmittingAnAlreadySubmittedReportIsRefusedByTheEntity() {
        // This is why BugReportService.update() guards the transition instead of calling
        // transitionTo() unconditionally: a submit that committed server-side but whose response
        // the client lost would otherwise be un-retryable, answering 409 forever.
        BugReport report = draft();
        report.transitionTo(BugReportStatus.SUBMITTED);

        assertFalse(report.canTransitionTo(BugReportStatus.SUBMITTED),
                "the entity refuses a no-op transition -- the service must not ask for one");
    }

    @Test
    void everyWriteRestampsTheToken_soOneHeldAcrossASaveIsStale() {
        // The stranding bug in one assertion: the modal held the token from createDraft while
        // handleAnalyze's pre-save PUT ran updateFields() on the server. Both writes restamp
        // updatedAt, so the held token addressed a version that no longer existed.
        BugReport report = draft();
        report.setUpdatedAt(LocalDateTime.of(2026, 9, 2, 14, 22, 6));
        LocalDateTime tokenTheClientHolds = report.getUpdatedAt();

        report.updateFields(REPORTER, "Kan ikke timeregistrere", "...", null, null, null, null);

        assertNotEquals(tokenTheClientHolds, report.getUpdatedAt(),
                "a save moves the token, so the client's copy is now stale");
    }

    @Test
    void aDraftCannotSkipStraightToResolved() {
        assertFalse(draft().canTransitionTo(BugReportStatus.RESOLVED));
    }

    @Test
    void anyLiveStatusCanBeForceClosedOrRejected() {
        // The cleanup job relies on DRAFT -> CLOSED being allowed even though it is not in
        // DRAFT's allowedTransitions().
        assertTrue(draft().canTransitionTo(BugReportStatus.CLOSED));
        assertTrue(draft().canTransitionTo(BugReportStatus.REJECTED));
    }

    private static BugReport draft() {
        BugReport report = BugReport.createDraft(
                REPORTER, "/timesheet", "unit-test", 1440, 900, "[]", "USER");
        assertEquals(BugReportStatus.DRAFT, report.getStatus());
        return report;
    }
}
