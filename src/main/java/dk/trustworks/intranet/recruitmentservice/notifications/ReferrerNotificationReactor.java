package dk.trustworks.intranet.recruitmentservice.notifications;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventBuilder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventRecorder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentReactor;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentReferral;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentReferralDerivedStatus;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentFeatureFlag;
import dk.trustworks.intranet.recruitmentservice.services.ReferralService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType.APPLICATION_CREATED;
import static dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType.APPLICATION_REJECTED;
import static dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType.APPLICATION_STAGE_CHANGED;
import static dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType.APPLICATION_WITHDRAWN;
import static dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType.CANDIDATE_HIRED;
import static dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType.CANDIDATE_POOLED;

/**
 * P12 referrer notifications (plan §P12): milestone-level Slack DMs to the
 * referring employee as their referral travels the pipeline — never every
 * micro-move. The milestone is the {@link RecruitmentReferralDerivedStatus}
 * bucket computed by the exact same code as the "My referrals" page
 * ({@link ReferralService#deriveCandidateMilestone}) so DMs and the page
 * can never disagree (findings §P6 carry-over).
 * <p>
 * Cadence rule: each milestone value is DM'ed <b>at most once per
 * candidate</b> — bookkept durably as {@code REFERRAL_OUTCOME_NOTIFIED}
 * events (event-derived state, the P9 idiom; no new table). A monotonic
 * funnel run therefore produces exactly four DMs: screening entered →
 * interviewing → offer → outcome. Back-moves re-enter an
 * already-notified milestone silently.
 * <p>
 * The DM deliberately carries only the candidate's name and the milestone
 * — no position title, no stage codes, no other candidates (the §P6
 * "no candidate handle" rule, mirrored to Slack).
 */
@JBossLog
@ApplicationScoped
public class ReferrerNotificationReactor extends RecruitmentReactor {

    public static final String NAME = "referrer-notifications";

    private static final Set<RecruitmentEventType> TRIGGERS = EnumSet.of(
            APPLICATION_CREATED, APPLICATION_STAGE_CHANGED, APPLICATION_REJECTED,
            APPLICATION_WITHDRAWN, CANDIDATE_POOLED, CANDIDATE_HIRED);

    /** The four plan milestones: screening, interviewing, offer, and the outcomes. */
    private static final Set<RecruitmentReferralDerivedStatus> NOTIFIABLE = EnumSet.of(
            RecruitmentReferralDerivedStatus.IN_SCREENING,
            RecruitmentReferralDerivedStatus.INTERVIEWING,
            RecruitmentReferralDerivedStatus.OFFER,
            RecruitmentReferralDerivedStatus.HIRED,
            RecruitmentReferralDerivedStatus.NOT_PROCEEDING,
            RecruitmentReferralDerivedStatus.IN_TALENT_POOL);

    private static final com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>> JSON_OBJECT =
            new com.fasterxml.jackson.core.type.TypeReference<>() {
            };

    @Inject
    ObjectMapper objectMapper;

    @Inject
    RecruitmentFeatureFlag featureFlag;

    @Inject
    SlackService slackService;

    @Inject
    RecruitmentEventRecorder eventRecorder;

    @Override
    public String name() {
        return NAME;
    }

    /**
     * One live try + two catch-up retries, then durable SKIPPED.
     * ponytail: a referrer DM is best-effort — never block the watermark on
     * persistent Slack trouble; the "My referrals" page always has the truth.
     */
    @Override
    protected int maxDeliveryAttempts() {
        return 3;
    }

    @Override
    protected void handle(RecruitmentEvent event) throws Exception {
        if (!TRIGGERS.contains(event.getEventType())) {
            return; // not ours (also ignores our own REFERRAL_OUTCOME_NOTIFIED)
        }
        if (!featureFlag.isPipelineEnabled()) {
            return; // side effects gated; offset advances, no backfill on later enable
        }
        String candidateUuid = event.getCandidateUuid();
        if (candidateUuid == null) {
            return;
        }
        RecruitmentCandidate candidate = RecruitmentCandidate.findById(candidateUuid);
        if (candidate == null || candidate.getReferredByUserUuid() == null) {
            return; // not a referred candidate — the common case
        }
        List<RecruitmentApplication> applications =
                RecruitmentApplication.list("candidateUuid = ?1", candidateUuid);
        RecruitmentReferralDerivedStatus milestone =
                ReferralService.deriveCandidateMilestone(candidate, applications);
        if (!NOTIFIABLE.contains(milestone) || alreadyNotified(candidateUuid, milestone)) {
            return;
        }
        User referrer = User.findById(candidate.getReferredByUserUuid());
        if (referrer == null || referrer.getSlackusername() == null
                || referrer.getSlackusername().isBlank()) {
            log.infof("Referrer notification: user %s has no Slack link — skipping DM for candidate %s",
                    candidate.getReferredByUserUuid(), candidateUuid);
            return; // no bookkeeping event — a later Slack link picks up from the next milestone
        }

        // DM first, bookkeeping second, both inside the delivery transaction:
        // the REFERRAL_OUTCOME_NOTIFIED event only commits when the DM went
        // out. A crash between send and commit re-delivers (rare duplicate
        // DM — the chassis' documented at-least-once residual).
        slackService.sendMessage(referrer, dmText(milestone, candidate));

        RecruitmentEventBuilder notified = RecruitmentEventBuilder
                .event(RecruitmentEventType.REFERRAL_OUTCOME_NOTIFIED)
                .candidate(candidateUuid)
                .actorSystem()
                .visibility(event.getVisibility()) // strictly-safer copy (P9 precedent)
                .payload("milestone", milestone.name())
                .payload("notified_user_uuid", candidate.getReferredByUserUuid())
                .payload("trigger_seq", event.getSeq());
        RecruitmentReferral referral = RecruitmentReferral.find(
                        "candidateUuid = ?1 and referrerUuid = ?2 order by submittedAt desc",
                        candidateUuid, candidate.getReferredByUserUuid())
                .firstResult();
        if (referral != null) {
            notified.payload("referral_uuid", referral.getUuid());
        }
        eventRecorder.record(notified);
    }

    /** Milestone values already DM'ed for this candidate, from the durable bookkeeping events. */
    private boolean alreadyNotified(String candidateUuid, RecruitmentReferralDerivedStatus milestone) {
        List<RecruitmentEvent> prior = RecruitmentEvent.list(
                "candidateUuid = ?1 and eventType = ?2",
                candidateUuid, RecruitmentEventType.REFERRAL_OUTCOME_NOTIFIED);
        return prior.stream()
                .map(e -> parse(e.getPayload()).get("milestone"))
                .anyMatch(m -> milestone.name().equals(m));
    }

    /**
     * The DM body: candidate name + milestone only — no position, no stage
     * codes, nothing about other candidates (§P6 rule). English, matching
     * the module's UI voice (findings §P9).
     */
    String dmText(RecruitmentReferralDerivedStatus milestone, RecruitmentCandidate candidate) {
        String name = SlackCandidateFacts.mrkdwnSafe(
                ((candidate.getFirstName() == null ? "" : candidate.getFirstName()) + " "
                        + (candidate.getLastName() == null ? "" : candidate.getLastName())).trim());
        if (name.isEmpty()) {
            name = "your referral";
        }
        return switch (milestone) {
            case IN_SCREENING -> ":seedling: Your referral *" + name
                    + "* has entered screening. We'll keep you posted at every milestone.";
            case INTERVIEWING -> ":speech_balloon: Your referral *" + name + "* is now interviewing.";
            case OFFER -> ":page_facing_up: Your referral *" + name + "* has received an offer.";
            case HIRED -> ":tada: Your referral *" + name
                    + "* has been hired. Thank you for the referral!";
            case NOT_PROCEEDING -> "Your referral *" + name
                    + "* is not proceeding further this time. Thank you for referring!";
            case IN_TALENT_POOL -> ":file_cabinet: Your referral *" + name
                    + "* has been added to our talent pool for future openings.";
            default -> throw new IllegalStateException("Non-notifiable milestone: " + milestone);
        };
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
