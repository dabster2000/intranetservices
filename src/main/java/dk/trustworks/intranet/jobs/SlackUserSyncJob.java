package dk.trustworks.intranet.jobs;

import dk.trustworks.intranet.communicationsservice.services.SlackConfigurationException;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Links intranet users to their Slack member id, nightly, via JBeret job
 * {@code slack-user-sync} (02:30 UTC — see {@code BatchScheduler}).
 *
 * <p><b>Why the link matters.</b> {@code SlackService.sendMessage(User, ...)}
 * posts to {@code user.getSlackusername()} as the channel id. A user whose
 * {@code slackusername} is blank is therefore not "not yet synced" — they are
 * <em>silently unreachable</em>: every Slack feature the intranet has (expense
 * send-back notices, reminders, digests) addresses an empty channel and the
 * person is never told anything. That makes an unlinked <em>employed</em> user
 * an incident, not a backlog item.
 *
 * <p><b>What this job used to do.</b> It selected every user with a blank
 * {@code slackusername} and looked each one up by e-mail, with no notion of
 * who could ever succeed. In production that was 25 users of whom 14 were
 * TERMINATED and one had an empty e-mail — none of which can ever resolve —
 * so the job re-attempted them every night and emitted ~25 identical
 * {@code users_not_found} WARNs. The six genuinely unreachable employees in
 * that list were indistinguishable from the noise and went unnoticed.
 *
 * <p><b>What it does now.</b> Candidates are classified by their current
 * employment status before any Slack call:
 * <ul>
 *   <li>TERMINATED, and anyone of {@link ConsultantType#EXTERNAL} — never
 *       looked up. They are not workspace members and never will be.</li>
 *   <li>PREBOARDING — looked up, but a miss is expected until they start, so
 *       it never alerts.</li>
 *   <li>Employed today, or carrying no {@code userstatus} row at all — looked
 *       up, and a miss <em>does</em> alert, because it means a real person is
 *       receiving nothing.</li>
 * </ul>
 * One INFO summary line replaces the per-user chatter, and the unreachable
 * employees are named in a single WARN and posted to the ops Slack channel.
 */
@JBossLog
@ApplicationScoped
public class SlackUserSyncJob {

    /**
     * How long an unchanged set of unreachable employees stays quiet on Slack.
     * A week, not a day: correcting these rows is a manual HR/IT action that
     * takes longer than a night, and the same names arriving every morning is
     * how an alert channel gets muted.
     *
     * <p>Held in memory, so a redeploy re-arms it. Worst case that is one extra
     * Slack message per deploy naming people who genuinely need a manual fix —
     * cheap, and the opposite failure (state that never re-arms) would hide the
     * alert entirely.
     */
    static final Duration ALERT_REPEAT_INTERVAL = Duration.ofDays(7);

    /**
     * Candidates and their <em>current</em> employment status in one pass.
     *
     * <p>{@code LEFT JOIN}, deliberately: a user with no {@code userstatus} row
     * at all (three in production) is a data defect that has to be visible, and
     * an inner join would silently drop exactly those users from the report.
     *
     * <p>The tie-break mirrors {@link User#getUserStatus(java.time.LocalDate)}:
     * when two rows share a {@code statusdate}, the non-TERMINATED one wins, so
     * a same-day termination-and-rehire reads as employed. Erring that way
     * surfaces a person rather than hiding them.
     */
    static final String CANDIDATE_SQL = """
            WITH latest_status AS (
                SELECT us.useruuid, us.status, us.type,
                       ROW_NUMBER() OVER (
                           PARTITION BY us.useruuid
                           ORDER BY us.statusdate DESC,
                                    CASE WHEN us.status = 'TERMINATED' THEN 1 ELSE 0 END
                       ) AS rn
                  FROM userstatus us
                 WHERE us.statusdate <= CURDATE()
            )
            SELECT u.uuid, u.username, u.firstname, u.lastname, u.email, ls.status, ls.type
              FROM user u
              LEFT JOIN latest_status ls ON ls.useruuid = u.uuid AND ls.rn = 1
             WHERE u.slackusername IS NULL OR u.slackusername = ''
             ORDER BY u.username
            """;

    @Inject
    SlackService slackService;

    @Inject
    EntityManager em;

    @ConfigProperty(name = "slack.opsAlertChannel", defaultValue = "C0B2VQ2CFU1")
    String opsAlertChannel;

    final AtomicReference<Instant> lastAlertSent = new AtomicReference<>(null);

    /** A user with a blank {@code slackusername}, plus the status that decides what to do about it. */
    public record Candidate(String uuid, String username, String firstname, String lastname,
                            String email, String status, String type) {

        public String fullname() {
            return ((firstname == null ? "" : firstname) + " " + (lastname == null ? "" : lastname)).trim();
        }

        /** Human-readable employment state, including the "no row at all" case. */
        public String statusLabel() {
            String s = status == null ? "no status row" : status;
            return type == null ? s : s + "/" + type;
        }
    }

    /** What the candidate's employment status says should happen to them. */
    public enum Disposition {

        /** Left the company. Not a workspace member; nothing to look up, nothing to report. */
        SKIP_TERMINATED(false, false),

        /** {@link ConsultantType#EXTERNAL}. Not on the Trustworks Slack; excluded by policy. */
        SKIP_EXTERNAL(false, false),

        /** Hired but not started. A miss is the expected answer until their first day. */
        PREBOARDING(true, false),

        /** Employed today. A miss means this person gets nothing from the intranet. */
        EMPLOYED(true, true),

        /**
         * No {@code userstatus} row on or before today. Their employment is unknown,
         * which is its own data defect — and they are unreachable either way.
         */
        NO_STATUS(true, true);

        private final boolean lookup;
        private final boolean alertIfUnlinked;

        Disposition(boolean lookup, boolean alertIfUnlinked) {
            this.lookup = lookup;
            this.alertIfUnlinked = alertIfUnlinked;
        }

        /** Whether to spend a Slack API call on this candidate. */
        public boolean lookup() {
            return lookup;
        }

        /** Whether failing to link this candidate needs a human to look at it. */
        public boolean alertIfUnlinked() {
            return alertIfUnlinked;
        }
    }

    /** Why a candidate that was worth looking up still has no Slack id. */
    public enum Reason {

        /** Slack answered {@code users_not_found}: no workspace member uses that address. */
        NO_SLACK_ACCOUNT("no Slack member uses that e-mail address"),

        /** The intranet e-mail is blank or malformed, so the lookup cannot even be attempted. */
        UNUSABLE_EMAIL("intranet e-mail is blank or malformed");

        private final String description;

        Reason(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    /** One candidate that ended the run without a Slack id. */
    public record Unlinked(Candidate candidate, Disposition disposition, Reason reason) {
    }

    /** Everything one run did, in a shape the summary and the alert can both be derived from. */
    public record Outcome(int candidates, int linked, int skippedTerminated, int skippedExternal,
                          List<Unlinked> unlinked, int errors) {

        /** The unlinked users a human has to act on — everyone who should be reachable but is not. */
        public List<Unlinked> alertable() {
            return unlinked.stream().filter(u -> u.disposition().alertIfUnlinked()).toList();
        }

        long unlinkedWith(Disposition disposition) {
            return unlinked.stream().filter(u -> u.disposition() == disposition).count();
        }

        long unlinkedWith(Reason reason) {
            return unlinked.stream().filter(u -> u.reason() == reason).count();
        }
    }

    public Outcome syncSlackUserIds() {
        List<Candidate> candidates = QuarkusTransaction.requiringNew().call(this::loadCandidates);

        int linked = 0;
        int skippedTerminated = 0;
        int skippedExternal = 0;
        int errors = 0;
        List<Unlinked> unlinked = new ArrayList<>();

        for (Candidate candidate : candidates) {
            Disposition disposition = classify(candidate);
            if (!disposition.lookup()) {
                if (disposition == Disposition.SKIP_TERMINATED) skippedTerminated++;
                else skippedExternal++;
                continue;
            }
            if (!isUsableEmail(candidate.email())) {
                unlinked.add(new Unlinked(candidate, disposition, Reason.UNUSABLE_EMAIL));
                continue;
            }
            try {
                String slackId = slackService.findUserIdByEmail(candidate.email());
                if (slackId == null) {
                    unlinked.add(new Unlinked(candidate, disposition, Reason.NO_SLACK_ACCOUNT));
                    continue;
                }
                QuarkusTransaction.requiringNew().run(() ->
                        User.update("slackusername = ?1 where uuid = ?2", slackId, candidate.uuid()));
                log.infof("slack-user-sync: linked %s -> %s", candidate.username(), slackId);
                linked++;
            } catch (SlackConfigurationException e) {
                // Permanent: every remaining lookup answers identically until a
                // workspace admin fixes the app. Stop rather than repeat it 20 more times.
                errors++;
                log.errorf(e, "slack-user-sync: aborting — Slack app misconfigured (error=%s needed=%s provided=%s)",
                        e.getSlackError(), e.getNeeded(), e.getProvided());
                break;
            } catch (Exception e) {
                errors++;
                log.warnf(e, "slack-user-sync: lookup failed for %s: %s", candidate.username(), e.getMessage());
            }
        }

        Outcome outcome = new Outcome(candidates.size(), linked, skippedTerminated, skippedExternal,
                List.copyOf(unlinked), errors);
        log.info(formatSummary(outcome));
        reportAlertable(outcome);
        return outcome;
    }

    List<Candidate> loadCandidates() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(CANDIDATE_SQL).getResultList();
        List<Candidate> candidates = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            candidates.add(new Candidate((String) row[0], (String) row[1], (String) row[2],
                    (String) row[3], (String) row[4], (String) row[5], (String) row[6]));
        }
        return candidates;
    }

    /**
     * Decides what a candidate's current employment says about their Slack link.
     * TERMINATED is tested before EXTERNAL only so a departed external reads as
     * "left" rather than "external" — neither is looked up either way.
     */
    static Disposition classify(Candidate candidate) {
        if (StatusType.TERMINATED.name().equals(candidate.status())) return Disposition.SKIP_TERMINATED;
        if (ConsultantType.EXTERNAL.name().equals(candidate.type())) return Disposition.SKIP_EXTERNAL;
        if (candidate.status() == null) return Disposition.NO_STATUS;
        if (StatusType.PREBOARDING.name().equals(candidate.status())) return Disposition.PREBOARDING;
        return Disposition.EMPLOYED;
    }

    /**
     * Whether an address is worth spending a Slack lookup on.
     *
     * <p>Not a validator — just enough to reject the two shapes production
     * actually holds: an empty string, and a double-domain address such as
     * {@code first.last@external.dk@trustworks.dk}. Both are data defects that
     * no retry can fix, and sending them to Slack only buys another
     * {@code users_not_found}.
     */
    static boolean isUsableEmail(String email) {
        if (email == null || email.isBlank()) return false;
        String trimmed = email.trim();
        if (trimmed.chars().anyMatch(Character::isWhitespace)) return false;
        int at = trimmed.indexOf('@');
        if (at <= 0 || at != trimmed.lastIndexOf('@')) return false;
        String domain = trimmed.substring(at + 1);
        return domain.contains(".") && !domain.startsWith(".") && !domain.endsWith(".");
    }

    /**
     * The one line a run leaves behind when nothing needs a human. Every candidate
     * is accounted for exactly once across {@code linked}, {@code skipped},
     * {@code unlinked} and {@code errors}; {@code bad-email} is a reason within
     * {@code unlinked}, not a fourth bucket.
     */
    static String formatSummary(Outcome outcome) {
        return String.format(
                "slack-user-sync: candidates=%d linked=%d skipped=%d (terminated=%d, external=%d) "
                        + "unlinked=%d (employed=%d, no-status=%d, preboarding=%d; bad-email=%d) errors=%d",
                outcome.candidates(), outcome.linked(),
                outcome.skippedTerminated() + outcome.skippedExternal(),
                outcome.skippedTerminated(), outcome.skippedExternal(),
                outcome.unlinked().size(),
                outcome.unlinkedWith(Disposition.EMPLOYED),
                outcome.unlinkedWith(Disposition.NO_STATUS),
                outcome.unlinkedWith(Disposition.PREBOARDING),
                outcome.unlinkedWith(Reason.UNUSABLE_EMAIL),
                outcome.errors());
    }

    /** One WARN naming everyone who should be reachable and is not, plus a rate-limited Slack alert. */
    void reportAlertable(Outcome outcome) {
        List<Unlinked> alertable = outcome.alertable();
        if (alertable.isEmpty()) {
            lastAlertSent.set(null);
            return;
        }
        log.warnf("slack-user-sync: %d employed user(s) have no Slack link and receive NO intranet Slack messages: %s",
                alertable.size(),
                alertable.stream()
                        .map(u -> String.format("%s (%s, %s)",
                                u.candidate().username(), u.candidate().statusLabel(), u.reason().description()))
                        .collect(Collectors.joining("; ")));
        fireSlackAlertIfNeeded(alertable);
    }

    void fireSlackAlertIfNeeded(List<Unlinked> alertable) {
        Instant now = Instant.now();
        Instant previous = lastAlertSent.get();
        if (previous != null && Duration.between(previous, now).compareTo(ALERT_REPEAT_INTERVAL) < 0) {
            log.debugf("slack-user-sync: unreachable users unchanged — suppressing duplicate Slack alert (last sent %s)",
                    previous);
            return;
        }
        slackService.sendMessage(opsAlertChannel, formatAlertMessage(alertable), "mother");
        lastAlertSent.set(now);
    }

    /**
     * Formats the ops alert. Pure and deterministic (no clock, no DB) so it can be
     * asserted in a unit test; mirrors the shape of the finance health checks.
     */
    static String formatAlertMessage(List<Unlinked> alertable) {
        StringBuilder msg = new StringBuilder(":warning: *No Slack account linked* — ")
                .append(alertable.size())
                .append(" employed user(s) receive NO Slack messages from the intranet:\n");
        for (Unlinked u : alertable) {
            Candidate c = u.candidate();
            String email = isUsableEmail(c.email()) ? c.email()
                    : (c.email() == null || c.email().isBlank() ? "(no e-mail)" : c.email());
            msg.append(String.format("• %s `%s` — %s — %s%n",
                    c.fullname().isEmpty() ? c.username() : c.fullname(),
                    email, c.statusLabel(), u.reason().description()));
        }
        msg.append("• impact: every intranet Slack feature skips these people silently — ")
                .append("expense send-back notices, reminders and digests are posted to an empty channel id.\n")
                .append("• action: correct the e-mail on the intranet user so it matches their Slack address, ")
                .append("or have a workspace admin create/activate their Slack account. ")
                .append("The nightly sync links them by itself once the two agree.");
        return msg.toString();
    }
}
