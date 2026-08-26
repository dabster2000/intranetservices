package dk.trustworks.intranet.recruitmentservice.slack;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The discriminator behind the two log levels on an unknown handler key
 * ({@link SlackInboundDispatchService#isAuthoredKey(String)}).
 *
 * <p>Every unknown key used to log at WARN, which is why
 * {@code digest_open_reports} — a primary button in the weekly
 * recruitment digest with no handler behind it — read as ordinary noise
 * for weeks. It sat among the ids Slack mints for its own
 * non-interactive elements (a collapsible {@code container} fires one on
 * every expand), and only a manual prod-log sweep on 2026-08-24, after two
 * people had clicked it, told them apart.</p>
 *
 * <p>Now an id <em>we</em> authored logs at ERROR with a fixed marker to
 * alarm on, and Slack's own generated ids drop to DEBUG. Getting that
 * split right is what this test pins. Slack inbound is prod-only (no
 * staging signing secret), so this classification can never be verified
 * end-to-end — the unit test is the whole safety net.</p>
 */
class SlackInboundDeadControlSignalTest {

    @DisplayName("keys this codebase authors are dead controls and must shout")
    @ParameterizedTest(name = "authored: {0}")
    @ValueSource(strings = {
            // The three the digest actually shipped without handlers.
            "digest_open_reports",
            "digest_open_scorecards",
            "digest_feedback",
            // The shapes every real handler key takes.
            "recruitment_scorecard_open",
            "recruitment_sched_evidence_correct_submit",
            "app_home_opened",
            "app_mention",
            "/refer",
            "/candidates",
            // A bare lowercase Events API type — short, but ours.
            "message",
    })
    void authoredKeysAreLoud(String key) {
        assertTrue(SlackInboundDispatchService.isAuthoredKey(key),
                key + " is a key we author; an unhandled one is a dead button and must "
                        + "log at ERROR, not vanish into DEBUG");
    }

    @DisplayName("ids Slack mints for its own elements are noise and must stay quiet")
    @ParameterizedTest(name = "generated: {0}")
    @ValueSource(strings = {
            // Observed in prod on 2026-08-24 from the digest's collapsible
            // container — benign, and the reason the real drop hid so well.
            "om3Vo",
            "KlJ8f",
            // The rest of the base64-ish alphabet Slack draws from.
            "aB3",
            "Zq9k",
            "8x/Vd",
            "+p2Fq=",
    })
    void generatedIdsAreQuiet(String key) {
        assertFalse(SlackInboundDispatchService.isAuthoredKey(key),
                key + " is a Slack-generated id — alarming on these is what buried the "
                        + "real dead button in the first place");
    }

    @DisplayName("a missing key is not a dead control")
    @ParameterizedTest(name = "blank: [{0}]")
    @ValueSource(strings = {"", "   "})
    void blankKeysAreNotAuthored(String key) {
        assertFalse(SlackInboundDispatchService.isAuthoredKey(key));
    }

    @org.junit.jupiter.api.Test
    @DisplayName("null is not a dead control")
    void nullKeyIsNotAuthored() {
        assertFalse(SlackInboundDispatchService.isAuthoredKey(null));
    }
}
