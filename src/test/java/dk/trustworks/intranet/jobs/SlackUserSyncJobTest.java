package dk.trustworks.intranet.jobs;

import dk.trustworks.intranet.jobs.SlackUserSyncJob.Candidate;
import dk.trustworks.intranet.jobs.SlackUserSyncJob.Disposition;
import dk.trustworks.intranet.jobs.SlackUserSyncJob.Outcome;
import dk.trustworks.intranet.jobs.SlackUserSyncJob.Reason;
import dk.trustworks.intranet.jobs.SlackUserSyncJob.Unlinked;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DB-free regression tests for the nightly Slack user sync.
 *
 * <p>Locks in the properties that made the production state (2026-08-24 →
 * 2026-08-28) unreadable: 25 candidates, 25 identical {@code users_not_found}
 * WARNs a night, and six ACTIVE employees receiving no intranet Slack messages
 * at all — indistinguishable, in the log, from the 14 terminated colleagues
 * who can never resolve.
 */
class SlackUserSyncJobTest {

    private static Candidate candidate(String username, String email, String status, String type) {
        return new Candidate("uuid-" + username, username, "First", "Last", email, status, type);
    }

    // ── classification ───────────────────────────────────────────────────────

    @Test
    void terminatedUsersAreNeverLookedUpAndNeverAlert() {
        // 14 of the 25 production candidates. They will never have a workspace account.
        Disposition d = SlackUserSyncJob.classify(candidate("msm", "m@trustworks.dk", "TERMINATED", "CONSULTANT"));
        assertEquals(Disposition.SKIP_TERMINATED, d);
        assertFalse(d.lookup(), "a terminated user must not cost a Slack API call");
        assertFalse(d.alertIfUnlinked(), "a terminated user without a Slack link is not a problem");
    }

    @Test
    void externalConsultantsAreNeverLookedUpAndNeverAlert() {
        Disposition d = SlackUserSyncJob.classify(candidate("ext", "e@partner.dk", "ACTIVE", "EXTERNAL"));
        assertEquals(Disposition.SKIP_EXTERNAL, d);
        assertFalse(d.lookup(), "an external is not on the Trustworks Slack");
        assertFalse(d.alertIfUnlinked());
    }

    @Test
    void terminationBeatsExternal() {
        // Neither is looked up; the label should just read as "left" rather than "external".
        assertEquals(Disposition.SKIP_TERMINATED,
                SlackUserSyncJob.classify(candidate("x", "x@trustworks.dk", "TERMINATED", "EXTERNAL")));
    }

    @Test
    void preboardingIsLookedUpButNeverAlerts() {
        // Two production candidates. A miss is the expected answer until their first day.
        Disposition d = SlackUserSyncJob.classify(candidate("mds", "m@trustworks.dk", "PREBOARDING", "CONSULTANT"));
        assertEquals(Disposition.PREBOARDING, d);
        assertTrue(d.lookup(), "a preboarder may already have an account — worth checking");
        assertFalse(d.alertIfUnlinked(), "a preboarder without Slack is expected, not an incident");
    }

    @Test
    void activeEmployeesAlertWhenUnlinked() {
        // The six that matter: a blank slackusername means sendMessage(User,...) posts to a null channel.
        Disposition d = SlackUserSyncJob.classify(candidate("amn", "a@trustworks.dk", "ACTIVE", "CONSULTANT"));
        assertEquals(Disposition.EMPLOYED, d);
        assertTrue(d.lookup());
        assertTrue(d.alertIfUnlinked());
    }

    @Test
    void everyPaidStatusCountsAsEmployed() {
        // Someone on paid or parental leave still gets intranet DMs, so they must alert too.
        for (String status : List.of("ACTIVE", "PAID_LEAVE", "MATERNITY_LEAVE", "NON_PAY_LEAVE")) {
            assertEquals(Disposition.EMPLOYED,
                    SlackUserSyncJob.classify(candidate("u", "u@trustworks.dk", status, "CONSULTANT")),
                    status + " must be treated as employed");
        }
    }

    @Test
    void aUserWithNoStatusRowAlertsRatherThanDisappearing() {
        // Three production users have no userstatus row at all — a data defect that
        // must stay visible, and they are unreachable either way.
        Disposition d = SlackUserSyncJob.classify(candidate("cb", "c@trustworks.dk", null, null));
        assertEquals(Disposition.NO_STATUS, d);
        assertTrue(d.lookup());
        assertTrue(d.alertIfUnlinked());
    }

    // ── e-mail usability ─────────────────────────────────────────────────────

    @Test
    void blankEmailsAreNeverSentToSlack() {
        assertFalse(SlackUserSyncJob.isUsableEmail(null));
        assertFalse(SlackUserSyncJob.isUsableEmail(""));
        assertFalse(SlackUserSyncJob.isUsableEmail("   "));
    }

    @Test
    void aDoubleDomainAddressIsRejectedWithoutCallingSlack() {
        // Mathias Ruggaard Pedersen's production row. No retry can fix it, and
        // sending it only buys another users_not_found.
        assertFalse(SlackUserSyncJob.isUsableEmail("mathias.pedersen@external.dk@trustworks.dk"));
    }

    @Test
    void ordinaryAddressesAreUsable() {
        assertTrue(SlackUserSyncJob.isUsableEmail("amalie.moeller.nielsen@trustworks.dk"));
        assertTrue(SlackUserSyncJob.isUsableEmail(" hans@trustworks.dk "));
    }

    @Test
    void structurallyBrokenAddressesAreRejected() {
        assertFalse(SlackUserSyncJob.isUsableEmail("@trustworks.dk"));
        assertFalse(SlackUserSyncJob.isUsableEmail("hans@trustworks"));
        assertFalse(SlackUserSyncJob.isUsableEmail("hans@.dk"));
        assertFalse(SlackUserSyncJob.isUsableEmail("hans@trustworks."));
        assertFalse(SlackUserSyncJob.isUsableEmail("hans lassen@trustworks.dk"));
    }

    // ── SQL shape ────────────────────────────────────────────────────────────

    @Test
    void candidateSqlLeftJoinsStatusSoUsersWithoutOneStayVisible() {
        String sql = SlackUserSyncJob.CANDIDATE_SQL;
        assertTrue(sql.contains("LEFT JOIN latest_status"),
                "an inner join would silently drop the users whose missing status row is the defect");
        assertTrue(sql.contains("u.slackusername IS NULL OR u.slackusername = ''"),
                "candidates are exactly the users with no Slack link");
        assertTrue(sql.contains("us.statusdate <= CURDATE()"),
                "a future-dated status must not decide today's employment");
        assertTrue(sql.contains("CASE WHEN us.status = 'TERMINATED' THEN 1 ELSE 0 END"),
                "same-day ties must resolve to the non-terminated row, as User.getUserStatus does");
    }

    // ── summary line ─────────────────────────────────────────────────────────

    @Test
    void summaryAccountsForEveryCandidateExactlyOnce() {
        // Production, 2026-08-28: 25 candidates, none linked, nothing actionable in the log.
        Outcome outcome = new Outcome(25, 0, 14, 1, List.of(
                new Unlinked(candidate("amn", "amalie.nielsen@trustworks.dk", "ACTIVE", "CONSULTANT"),
                        Disposition.EMPLOYED, Reason.NO_SLACK_ACCOUNT),
                new Unlinked(candidate("mrp", "mathias.pedersen@external.dk@trustworks.dk", "ACTIVE", "CONSULTANT"),
                        Disposition.EMPLOYED, Reason.UNUSABLE_EMAIL),
                new Unlinked(candidate("cb", "c@trustworks.dk", null, null),
                        Disposition.NO_STATUS, Reason.NO_SLACK_ACCOUNT),
                new Unlinked(candidate("mds", "m@trustworks.dk", "PREBOARDING", "CONSULTANT"),
                        Disposition.PREBOARDING, Reason.NO_SLACK_ACCOUNT)), 0);

        String summary = SlackUserSyncJob.formatSummary(outcome);

        assertEquals("slack-user-sync: candidates=25 linked=0 skipped=15 (terminated=14, external=1) "
                + "unlinked=4 (employed=2, no-status=1, preboarding=1; bad-email=1) errors=0", summary);
    }

    // ── alert ────────────────────────────────────────────────────────────────

    @Test
    void onlyEmployedAndStatuslessUsersReachTheAlert() {
        Outcome outcome = new Outcome(4, 0, 0, 0, List.of(
                new Unlinked(candidate("amn", "a@trustworks.dk", "ACTIVE", "CONSULTANT"),
                        Disposition.EMPLOYED, Reason.NO_SLACK_ACCOUNT),
                new Unlinked(candidate("cb", "c@trustworks.dk", null, null),
                        Disposition.NO_STATUS, Reason.NO_SLACK_ACCOUNT),
                new Unlinked(candidate("mds", "m@trustworks.dk", "PREBOARDING", "CONSULTANT"),
                        Disposition.PREBOARDING, Reason.NO_SLACK_ACCOUNT)), 0);

        List<Unlinked> alertable = outcome.alertable();

        assertEquals(2, alertable.size(), "the preboarder must not page anyone");
        assertEquals(List.of("amn", "cb"), alertable.stream().map(u -> u.candidate().username()).toList());
    }

    @Test
    void alertNamesEveryoneAndSaysWhatToDo() {
        String msg = SlackUserSyncJob.formatAlertMessage(List.of(
                new Unlinked(candidate("amn", "amalie.nielsen@trustworks.dk", "ACTIVE", "CONSULTANT"),
                        Disposition.EMPLOYED, Reason.NO_SLACK_ACCOUNT),
                new Unlinked(candidate("mrp", "mathias.pedersen@external.dk@trustworks.dk", "ACTIVE", "CONSULTANT"),
                        Disposition.EMPLOYED, Reason.UNUSABLE_EMAIL)));

        assertTrue(msg.startsWith(":warning: *No Slack account linked* — 2 employed user(s)"));
        assertTrue(msg.contains("amalie.nielsen@trustworks.dk"), "the e-mail is the actionable datum");
        assertTrue(msg.contains("mathias.pedersen@external.dk@trustworks.dk"),
                "a malformed address must be shown verbatim so the defect is obvious");
        assertTrue(msg.contains("ACTIVE/CONSULTANT"));
        assertTrue(msg.contains(Reason.NO_SLACK_ACCOUNT.description()));
        assertTrue(msg.contains(Reason.UNUSABLE_EMAIL.description()));
        assertTrue(msg.contains("• impact:"));
        assertTrue(msg.contains("• action:"));
    }

    @Test
    void alertHandlesAUserWithNoEmailAtAll() {
        String msg = SlackUserSyncJob.formatAlertMessage(List.of(
                new Unlinked(candidate("noe", "", "ACTIVE", "STAFF"),
                        Disposition.EMPLOYED, Reason.UNUSABLE_EMAIL)));

        assertTrue(msg.contains("(no e-mail)"), "an empty address must not render as an empty backtick pair");
    }

    @Test
    void alertLabelsAUserWithNoStatusRow() {
        String msg = SlackUserSyncJob.formatAlertMessage(List.of(
                new Unlinked(candidate("cb", "c@trustworks.dk", null, null),
                        Disposition.NO_STATUS, Reason.NO_SLACK_ACCOUNT)));

        assertTrue(msg.contains("no status row"), "the missing userstatus row is itself the finding");
    }

    @Test
    void alertRepeatIntervalIsWeeklyNotNightly() {
        // The fixes are manual HR/IT actions; the same names every morning is how a channel gets muted.
        assertEquals(7, SlackUserSyncJob.ALERT_REPEAT_INTERVAL.toDays());
    }
}
