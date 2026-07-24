package dk.trustworks.intranet.recruitmentservice.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.domain.user.entity.Role;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.ai.AiDigestService;
import dk.trustworks.intranet.recruitmentservice.dto.GdprQueueResponse;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventBuilder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventRecorder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCircleMember;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateStatus;
import dk.trustworks.intranet.recruitmentservice.notifications.RecruitmentSlackChannel;
import dk.trustworks.intranet.recruitmentservice.notifications.SlackCandidateFacts;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The P24 DPO exception digest (Slack spec §5.10, plan §P24): one weekly
 * DM to every user holding the DPO role — Art. 14 notices due, consents
 * expiring unanswered, open DSARs with their Art. 12(3) deadlines,
 * upcoming automatic anonymizations, and the circle/channel-membership
 * drift check (Slack spec §7). An empty week still DMs
 * <em>"Nothing needs you this week"</em> — the digest doubles as proof
 * that the GDPR engine runs.
 *
 * <h3>Inputs</h3>
 * {@link RecruitmentGdprQueueService#queue()} is the single queue input
 * (the §P19 carry-over — the digest and {@code /recruitment/gdpr} can
 * never disagree), plus a retention-deadline look-ahead for the upcoming
 * auto-anonymizations and the P22 projections
 * ({@code recruitment_slack_channels} × {@code recruitment_circle_members})
 * diffed against live Slack membership for the drift check. Candidate
 * names and deadline dates are within the moderate-PII rule here: the DM
 * goes exclusively to the one role already authorized for the GDPR queue.
 *
 * <h3>Idempotency &amp; delivery (the P17/P23 shape, fourth instance)</h3>
 * Event-derived per (recipient, ISO week): every delivered DM appends a
 * {@code DPO_DIGEST_SENT} event; the daily batchlet run only DMs
 * recipients with no event for the current week — normally Monday, with
 * automatic catch-up on later weekdays after a failure. DM before
 * bookkeeping in one transaction per recipient: a Slack failure rolls the
 * event back and the next run retries exactly the missed recipients.
 *
 * <h3>Gating</h3>
 * {@code recruitment.gdpr.enabled} — the digest mirrors the GDPR engine,
 * so it rides the engine's own operational flag, NOT the pipeline flag —
 * AND {@code recruitment.slack.dpo-digest.enabled}, both read fresh per
 * run. Outbound only: the P13 master gate is deliberately irrelevant.
 */
@JBossLog
@ApplicationScoped
public class RecruitmentDpoDigestService {

    /** Look-ahead window for the "upcoming automatic deletions" section. */
    static final int ANONYMIZATION_LOOKAHEAD_DAYS = 7;

    /** Per-section row cap in the DM — the queue page holds the full lists. */
    static final int SECTION_ROW_CAP = 5;

    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private static final com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>> JSON_OBJECT =
            new com.fasterxml.jackson.core.type.TypeReference<>() {
            };

    @Inject
    ObjectMapper objectMapper;

    @Inject
    RecruitmentFeatureFlag featureFlag;

    @Inject
    RecruitmentSlackFeatureFlag slackFlags;

    @Inject
    RecruitmentGdprQueueService queueService;

    @Inject
    SlackService slackService;

    @Inject
    RecruitmentEventRecorder eventRecorder;

    @ConfigProperty(name = "dk.trustworks.recruitment.slack.base-url",
            defaultValue = "https://intra.trustworks.dk")
    String baseUrl;

    /** Result of one run, for logs and the batchlet exit status. */
    public record DpoDigestSummary(boolean enabled, int digestsSent, int failures,
                                   boolean driftChecked) {

        @Override
        public String toString() {
            if (!enabled) {
                return "dpo-digest[disabled]";
            }
            return "dpo-digest[sent=%d%s%s]".formatted(digestsSent,
                    failures > 0 ? ", failures=" + failures : "",
                    driftChecked ? "" : ", drift-check-unavailable");
        }
    }

    /**
     * Run one digest pass for the current ISO week. Safe to call daily and
     * concurrently — idempotency is event-derived (class javadoc).
     */
    public DpoDigestSummary run() {
        if (!inTx(featureFlag::isGdprEnabled) || !inTx(slackFlags::isDpoDigestEnabled)) {
            log.debug("recruitment-dpo-digest skipped: flag off");
            return new DpoDigestSummary(false, 0, 0, false);
        }
        String week = AiDigestService.isoWeekKey(LocalDate.now(AiDigestService.COPENHAGEN));

        List<User> recipients = inTx(this::dpoRoleHolders);
        if (recipients.isEmpty()) {
            log.warn("DPO digest: no user holds the DPO role — nothing to send");
            return new DpoDigestSummary(true, 0, 0, false);
        }
        Set<String> alreadySent = inTx(() -> recipientsAlreadySent(week));

        List<User> pending = recipients.stream()
                .filter(u -> !alreadySent.contains(u.getUuid()))
                .toList();
        if (pending.isEmpty()) {
            return new DpoDigestSummary(true, 0, 0, true);
        }

        // Content is identical for every recipient — build once.
        GdprQueueResponse queue = inTx(queueService::queue);
        List<UpcomingAnonymization> upcoming = inTx(this::upcomingAnonymizations);
        DriftReport drift = checkChannelDrift();

        int sent = 0;
        int failures = 0;
        for (User recipient : pending) {
            if (recipient.getSlackusername() == null || recipient.getSlackusername().isBlank()) {
                log.infof("DPO digest: recipient %s has no Slack link — skipping "
                        + "(no event, a later Slack link picks up naturally)", recipient.getUuid());
                continue;
            }
            try {
                // DM before bookkeeping, one transaction per recipient (the
                // P17 order — a Slack failure rolls the event back).
                QuarkusTransaction.requiringNew().run(() -> {
                    try {
                        slackService.sendMessage(recipient, digestText(queue, upcoming, drift, week),
                                digestBlocks(queue, upcoming, drift, week));
                    } catch (Exception e) {
                        throw new IllegalStateException("Slack DM failed", e);
                    }
                    recordSent(recipient.getUuid(), week, queue, upcoming, drift);
                });
                sent++;
            } catch (Exception e) {
                failures++;
                log.warnf(e, "DPO digest: DM to %s failed — continuing (the next run retries)",
                        recipient.getUuid());
            }
        }
        return new DpoDigestSummary(true, sent, failures, drift.checked());
    }

    // ------------------------------------------------------------------
    // Recipients & idempotency
    // ------------------------------------------------------------------

    /** Every user currently holding the DPO role, deduplicated. */
    private List<User> dpoRoleHolders() {
        Set<String> userUuids = new LinkedHashSet<>();
        Role.<Role>list("role", "DPO").forEach(r -> userUuids.add(r.getUseruuid()));
        List<User> users = new ArrayList<>();
        for (String uuid : userUuids) {
            User user = User.findById(uuid);
            if (user != null) {
                users.add(user);
            }
        }
        return users;
    }

    /** Recipient uuids already digested this week (event-derived). */
    private Set<String> recipientsAlreadySent(String week) {
        Set<String> recipients = new HashSet<>();
        List<RecruitmentEvent> events = RecruitmentEvent.list(
                "eventType", RecruitmentEventType.DPO_DIGEST_SENT);
        for (RecruitmentEvent event : events) {
            Map<String, Object> payload = parse(event.getPayload());
            if (!week.equals(payload.get("digest_week"))) {
                continue;
            }
            if (payload.get("nudged_user_uuids") instanceof List<?> list) {
                list.forEach(u -> recipients.add(String.valueOf(u)));
            }
        }
        return recipients;
    }

    private void recordSent(String recipientUuid, String week, GdprQueueResponse queue,
                            List<UpcomingAnonymization> upcoming, DriftReport drift) {
        eventRecorder.record(RecruitmentEventBuilder
                .event(RecruitmentEventType.DPO_DIGEST_SENT)
                .actorScheduler()
                .payload("digest_week", week)
                .payload("nudged_user_uuids", List.of(recipientUuid))
                .payload("art14_due", queue.art14Due().size())
                .payload("consents_expiring", queue.consentUnanswered().size())
                .payload("open_dsars", queue.openDsars().size())
                .payload("upcoming_anonymizations", upcoming.size())
                .payload("drift_checked", drift.checked())
                .payload("drift_members", drift.totalDriftMembers()));
    }

    // ------------------------------------------------------------------
    // Upcoming automatic anonymizations (the sweep's next targets)
    // ------------------------------------------------------------------

    record UpcomingAnonymization(String candidateName, LocalDateTime deadline) {
    }

    private List<UpcomingAnonymization> upcomingAnonymizations() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<RecruitmentCandidate> candidates = RecruitmentCandidate.list(
                "retentionDeadline is not null and retentionDeadline <= ?1 "
                        + "and status not in ?2 ORDER BY retentionDeadline",
                now.plusDays(ANONYMIZATION_LOOKAHEAD_DAYS),
                List.of(CandidateStatus.HIRED, CandidateStatus.ANONYMIZED));
        return candidates.stream()
                .map(c -> new UpcomingAnonymization(displayName(c), c.getRetentionDeadline()))
                .toList();
    }

    // ------------------------------------------------------------------
    // Circle/channel-membership drift check (Slack spec §7)
    // ------------------------------------------------------------------

    /** One partner channel with human members who are not circle members. */
    record ChannelDrift(String positionTitle, List<String> unexpectedMembers) {
    }

    record DriftReport(boolean checked, List<ChannelDrift> drifts) {

        int totalDriftMembers() {
            return drifts.stream().mapToInt(d -> d.unexpectedMembers().size()).sum();
        }
    }

    /**
     * Diff every LIVE partner channel's human Slack membership against the
     * position's circle. Best-effort as a whole: a Slack API failure makes
     * the report "unavailable" (stated in the DM), never blocks the digest.
     */
    private DriftReport checkChannelDrift() {
        try {
            List<RecruitmentSlackChannel> channels = inTx(() ->
                    RecruitmentSlackChannel.list("archivedAt is null"));
            List<ChannelDrift> drifts = new ArrayList<>();
            for (RecruitmentSlackChannel channel : channels) {
                Set<String> expectedSlackIds = inTx(() -> circleSlackIds(channel.getPositionUuid()));
                List<String> actualHumans = slackService.listHumanChannelMembers(channel.getChannelId());
                List<String> unexpected = actualHumans.stream()
                        .filter(id -> !expectedSlackIds.contains(id))
                        .map(id -> inTx(() -> describeSlackMember(id)))
                        .toList();
                if (!unexpected.isEmpty()) {
                    String title = inTx(() -> positionTitle(channel.getPositionUuid()));
                    drifts.add(new ChannelDrift(title, unexpected));
                }
            }
            return new DriftReport(true, drifts);
        } catch (Exception e) {
            log.warnf(e, "DPO digest: channel drift check failed — reporting it as unavailable");
            return new DriftReport(false, List.of());
        }
    }

    private Set<String> circleSlackIds(String positionUuid) {
        Set<String> slackIds = new HashSet<>();
        for (RecruitmentCircleMember member : RecruitmentCircleMember
                .<RecruitmentCircleMember>list("positionUuid", positionUuid)) {
            User user = User.findById(member.getUserUuid());
            if (user != null && user.getSlackusername() != null
                    && !user.getSlackusername().isBlank()) {
                slackIds.add(user.getSlackusername());
            }
        }
        return slackIds;
    }

    /** Intranet name for a Slack member id; the raw id when unmapped. */
    private String describeSlackMember(String slackId) {
        User user = User.find("slackusername", slackId).firstResult();
        if (user == null) {
            return slackId + " (not an intranet user)";
        }
        String name = (nullToEmpty(user.getFirstname()) + " " + nullToEmpty(user.getLastname())).trim();
        return name.isEmpty() ? slackId : name;
    }

    private String positionTitle(String positionUuid) {
        RecruitmentPosition position = RecruitmentPosition.findById(positionUuid);
        return position == null || position.getTitle() == null
                ? "(unknown position)" : position.getTitle();
    }

    // ------------------------------------------------------------------
    // Message builders — names + deadlines to the authorized role only
    // ------------------------------------------------------------------

    /** The digest as plain text (Block Kit fallback / notification preview). */
    String digestText(GdprQueueResponse queue, List<UpcomingAnonymization> upcoming,
                      DriftReport drift, String week) {
        if (isEmpty(queue, upcoming, drift)) {
            return ":shield: GDPR weekly digest " + week
                    + " — Nothing needs you this week. The retention engine is running normally.";
        }
        return ":shield: GDPR weekly digest " + week + " — "
                + queue.art14Due().size() + " notice(s) due, "
                + queue.consentUnanswered().size() + " consent(s) expiring, "
                + queue.openDsars().size() + " open request(s), "
                + upcoming.size() + " upcoming deletion(s)"
                + (drift.checked() && !drift.drifts().isEmpty()
                        ? ", channel membership drift detected" : "")
                + ". Details: " + baseUrl + "/recruitment/gdpr";
    }

    /** The digest as Block Kit: one section per non-empty category, capped rows. */
    List<com.slack.api.model.block.LayoutBlock> digestBlocks(
            GdprQueueResponse queue, List<UpcomingAnonymization> upcoming,
            DriftReport drift, String week) {
        List<com.slack.api.model.block.LayoutBlock> blocks = new ArrayList<>();
        blocks.add(com.slack.api.model.block.Blocks.header(h -> h.text(
                com.slack.api.model.block.composition.BlockCompositions.plainText(
                        "GDPR weekly digest · " + week))));

        if (isEmpty(queue, upcoming, drift)) {
            addSection(blocks, ":white_check_mark: *Nothing needs you this week.* "
                    + "The retention engine is running normally — no notices due, no consents "
                    + "expiring, no open requests, no deletions coming up.");
        } else {
            addRows(blocks, ":envelope: *Art. 14 notices due* — candidates who must be told "
                            + "we hold their data",
                    queue.art14Due().stream().map(r -> "• " + safe(r.candidateName())
                            + " — deadline " + r.deadline().format(DAY)
                            + daysLeft(r.daysLeft())
                            + (r.hasEmail() ? "" : " · no email on file, handle manually")).toList());
            addRows(blocks, ":hourglass_flowing_sand: *Consents expiring* — pooled candidates "
                            + "whose retention runs out soon",
                    queue.consentUnanswered().stream().map(r -> "• " + safe(r.candidateName())
                            + " — deadline " + r.retentionDeadline().format(DAY)
                            + daysLeft(r.daysLeft())
                            + " · " + r.renewalsSent() + " renewal email(s) sent").toList());
            addRows(blocks, ":inbox_tray: *Open data-subject requests* — answer within one month",
                    queue.openDsars().stream().map(r -> "• " + safe(r.candidateName())
                            + " — respond by " + r.deadline().format(DAY)
                            + daysLeft(r.daysLeft())).toList());
            addRows(blocks, ":wastebasket: *Upcoming automatic deletions* — anonymized by the "
                            + "nightly sweep unless consent arrives first",
                    upcoming.stream().map(r -> "• " + safe(r.candidateName())
                            + " — " + r.deadline().format(DAY)).toList());
            if (drift.checked()) {
                addRows(blocks, ":lock: *Partner channel membership drift* — people in a "
                                + "confidential channel who are not circle members "
                                + "(remove them or add them to the circle)",
                        drift.drifts().stream().map(d -> "• " + safe(d.positionTitle()) + ": "
                                + d.unexpectedMembers().stream().map(this::safe)
                                        .collect(java.util.stream.Collectors.joining(", "))).toList());
            }
        }
        if (!drift.checked()) {
            addSection(blocks, ":warning: The partner-channel membership check could not run "
                    + "this week (Slack lookup failed) — memberships were NOT verified.");
        }
        blocks.add(com.slack.api.model.block.Blocks.context(
                com.slack.api.model.block.element.BlockElements.asContextElements(
                        com.slack.api.model.block.composition.BlockCompositions.markdownText(
                                "Act on everything from the <" + baseUrl
                                        + "/recruitment/gdpr|GDPR queue page>. "
                                        + "You receive this as a holder of the DPO role."))));
        return blocks;
    }

    /** One category: header line + capped rows + "+N more" overflow. */
    private void addRows(List<com.slack.api.model.block.LayoutBlock> blocks, String title,
                         List<String> rows) {
        if (rows.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder(title);
        rows.stream().limit(SECTION_ROW_CAP).forEach(row -> sb.append('\n').append(row));
        if (rows.size() > SECTION_ROW_CAP) {
            sb.append("\n… and ").append(rows.size() - SECTION_ROW_CAP)
                    .append(" more on the queue page");
        }
        addSection(blocks, sb.toString());
    }

    private void addSection(List<com.slack.api.model.block.LayoutBlock> blocks, String text) {
        blocks.add(com.slack.api.model.block.Blocks.section(s -> s.text(
                com.slack.api.model.block.composition.BlockCompositions.markdownText(
                        text.length() > 3000 ? text.substring(0, 2999) + "…" : text))));
    }

    private static boolean isEmpty(GdprQueueResponse queue, List<UpcomingAnonymization> upcoming,
                                   DriftReport drift) {
        return queue.art14Due().isEmpty() && queue.consentUnanswered().isEmpty()
                && queue.openDsars().isEmpty() && upcoming.isEmpty()
                && (!drift.checked() || drift.drifts().isEmpty());
    }

    private static String daysLeft(long days) {
        if (days < 0) {
            return " (*overdue by " + Math.abs(days) + " day(s)*)";
        }
        return " (" + days + " day(s) left)";
    }

    private String safe(String value) {
        return SlackCandidateFacts.mrkdwnSafe(value == null ? "" : value);
    }

    private static String displayName(RecruitmentCandidate candidate) {
        String first = candidate.getFirstName() == null ? "" : candidate.getFirstName();
        String last = candidate.getLastName() == null ? "" : candidate.getLastName();
        String name = (first + " " + last).trim();
        return name.isEmpty() ? "(no name)" : name;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, JSON_OBJECT);
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** Reads on batch threads need a transaction (lazily-bound EntityManager). */
    private <T> T inTx(java.util.function.Supplier<T> work) {
        return QuarkusTransaction.requiringNew().call(work::get);
    }
}
