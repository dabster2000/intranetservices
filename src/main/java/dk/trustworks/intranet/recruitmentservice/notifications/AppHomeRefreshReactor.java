package dk.trustworks.intranet.recruitmentservice.notifications;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentActorType;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentReactor;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentInterview;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentReferral;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentSlaService;
import dk.trustworks.intranet.recruitmentservice.slack.SlackAppHomeService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Targeted App Home refresh (P23, Slack spec §5.7): when an event changes
 * someone's task set — a scorecard lands, an interview moves, a referral
 * progresses — republish exactly the affected users' Home dashboards, so
 * the tab stays roughly current between {@code app_home_opened} repaints.
 * <p>
 * Deliberately narrow and debounced: App Home is a convenience mirror
 * that tolerates staleness by design, so this reactor refreshes only the
 * users an event <em>directly</em> concerns (the submitting interviewer,
 * the assigned interviewers, the nudged owners, the referrer) — never
 * "every recruiter" — and skips a user republished within the debounce
 * window ({@code dk.trustworks.recruitment.slack.app-home.debounce-seconds},
 * default 60). A skipped refresh costs nothing: the next open or the next
 * event repaints. The debounce map is in-memory per instance — two
 * instances during ECS cutover may double-publish, which
 * {@code views.publish} makes harmless (last write wins).
 * <p>
 * All flag gating (master gate + pipeline + app-home toggle) lives in
 * {@link SlackAppHomeService}; with any gate off the reactor advances its
 * offset silently — no backfill on later enable (Slack spec §3.3).
 * Publishes are best-effort (the service swallows failures), so this
 * reactor never blocks the watermark on Slack trouble.
 */
@JBossLog
@ApplicationScoped
public class AppHomeRefreshReactor extends RecruitmentReactor {

    public static final String NAME = "slack-app-home";

    /** The task-set-changing events this reactor reacts to (spec §5.7). */
    static final Set<RecruitmentEventType> REFRESH_TYPES = EnumSet.of(
            RecruitmentEventType.SCORECARD_SUBMITTED,
            RecruitmentEventType.SCORECARD_NUDGED,
            RecruitmentEventType.DEBRIEF_STALLED_NUDGED,
            RecruitmentEventType.CANDIDATE_IDLE_NUDGED,
            RecruitmentEventType.INTERVIEW_SCHEDULED,
            RecruitmentEventType.INTERVIEW_RESCHEDULED,
            RecruitmentEventType.INTERVIEW_CANCELLED,
            RecruitmentEventType.REFERRAL_SUBMITTED,
            RecruitmentEventType.REFERRAL_TRIAGED,
            RecruitmentEventType.APPLICATION_STAGE_CHANGED,
            RecruitmentEventType.APPLICATION_REJECTED,
            RecruitmentEventType.APPLICATION_WITHDRAWN,
            RecruitmentEventType.CANDIDATE_HIRED);

    private static final com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>> JSON_OBJECT =
            new com.fasterxml.jackson.core.type.TypeReference<>() {
            };

    @Inject
    ObjectMapper objectMapper;

    @Inject
    SlackAppHomeService appHomeService;

    @Inject
    RecruitmentSlaService slaService;

    @ConfigProperty(name = "dk.trustworks.recruitment.slack.app-home.debounce-seconds",
            defaultValue = "60")
    long debounceSeconds;

    /** Last publish attempt per user (millis) — bounded by the employee count. */
    private final ConcurrentHashMap<String, Long> lastAttempt = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return NAME;
    }

    /** Refreshes are disposable — never let a poison event block the watermark. */
    @Override
    protected int maxDeliveryAttempts() {
        return 3;
    }

    @Override
    protected void handle(RecruitmentEvent event) {
        if (!REFRESH_TYPES.contains(event.getEventType())) {
            return; // not ours — silent advance
        }
        if (!appHomeService.enabled()) {
            return; // gates off — offset advances, no backfill on later enable
        }
        for (String userUuid : affectedUsers(event)) {
            if (debounced(userUuid)) {
                continue;
            }
            appHomeService.publishFor(userUuid); // best-effort inside
        }
    }

    /**
     * The users whose task set this event directly changed. Deliberately
     * narrow (class javadoc): direct subjects only, resolved from live
     * rows / structural payload facts — never a broadcast.
     */
    private Set<String> affectedUsers(RecruitmentEvent event) {
        Set<String> users = new LinkedHashSet<>();
        Map<String, Object> payload = parse(event.getPayload());
        switch (event.getEventType()) {
            // The submitter's own task disappears; the decision owner may
            // have just become debrief-ready.
            case SCORECARD_SUBMITTED -> {
                if (event.getActorType() == RecruitmentActorType.USER
                        && event.getActorUuid() != null) {
                    users.add(event.getActorUuid());
                }
                users.addAll(slaService.resolveOwners(position(event.getPositionUuid())));
            }
            // The SLA sweep just told these users about a task — their Home
            // should show the same thing the DM does.
            case SCORECARD_NUDGED -> addString(users, payload.get("nudged_user_uuid"));
            case DEBRIEF_STALLED_NUDGED, CANDIDATE_IDLE_NUDGED ->
                    addStrings(users, payload.get("nudged_user_uuids"));
            // Interview lifecycle: the assigned interviewers' upcoming list
            // and scorecard tasks changed.
            case INTERVIEW_SCHEDULED, INTERVIEW_RESCHEDULED, INTERVIEW_CANCELLED -> {
                Object interviewUuid = payload.get("interview_uuid");
                RecruitmentInterview interview = interviewUuid == null ? null
                        : RecruitmentInterview.findById(interviewUuid.toString());
                if (interview != null && interview.getInterviewerUuids() != null) {
                    users.addAll(interview.getInterviewerUuids());
                }
            }
            // Referral lifecycle: the referrer's "Your referrals" section.
            case REFERRAL_SUBMITTED, REFERRAL_TRIAGED -> {
                Object referralUuid = payload.get("referral_uuid");
                RecruitmentReferral referral = referralUuid == null ? null
                        : RecruitmentReferral.findById(referralUuid.toString());
                if (referral != null && referral.getReferrerUuid() != null) {
                    users.add(referral.getReferrerUuid());
                }
            }
            // Pipeline moves on a referred candidate change the referrer's
            // derived status (the P12 referrer-DM triggers, mirrored).
            case APPLICATION_STAGE_CHANGED, APPLICATION_REJECTED,
                 APPLICATION_WITHDRAWN, CANDIDATE_HIRED -> {
                RecruitmentCandidate candidate = event.getCandidateUuid() == null ? null
                        : RecruitmentCandidate.findById(event.getCandidateUuid());
                if (candidate != null && candidate.getReferredByUserUuid() != null) {
                    users.add(candidate.getReferredByUserUuid());
                }
            }
            default -> {
            }
        }
        return users;
    }

    /** True when this user was refreshed within the debounce window. */
    private boolean debounced(String userUuid) {
        long now = System.currentTimeMillis();
        Long last = lastAttempt.get(userUuid);
        if (last != null && now - last < debounceSeconds * 1000) {
            log.debugf("App Home refresh: user %s debounced", userUuid);
            return true;
        }
        lastAttempt.put(userUuid, now);
        return false;
    }

    private static RecruitmentPosition position(String uuid) {
        return uuid == null ? null : RecruitmentPosition.findById(uuid);
    }

    private static void addString(Set<String> users, Object value) {
        if (value instanceof String s && !s.isBlank()) {
            users.add(s);
        }
    }

    private static void addStrings(Set<String> users, Object value) {
        if (value instanceof List<?> list) {
            list.forEach(v -> addString(users, v));
        }
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
}
