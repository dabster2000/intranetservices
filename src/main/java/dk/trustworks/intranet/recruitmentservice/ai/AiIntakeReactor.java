package dk.trustworks.intranet.recruitmentservice.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentReactor;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentAiFeatureFlag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.util.List;
import java.util.Map;

/**
 * P9 intake reactor (AI spec §3/§4.1, contract §5.1): reacts to
 * {@code APPLICATION_CREATED} and CV {@code DOCUMENT_UPLOADED} events and
 * runs the intake generation pipeline
 * ({@link AiIntakeGenerationService}) when the intake and/or brief toggle
 * is on.
 * <p>
 * Chassis contract (dossier §1): plain {@code @ApplicationScoped} bean —
 * discovery is CDI, the dispatcher owns the single {@code @ConsumeEvent};
 * event-type filtering is in-handle (which also makes the reactor ignore
 * its own {@code AI_*} events); {@link #maxDeliveryAttempts()} {@code == 2}
 * (one in-JVM try + one catch-up retry, then durable SKIPPED — the AI
 * failure posture).
 * <p>
 * Flag OFF ⇒ silent return: the event is marked PROCESSED and permanently
 * skipped — enabling the toggle later does NOT backfill (the Regenerate
 * button is the recovery path). Deliberate: retroactively processing a
 * backlog of historic candidates on a toggle flip would be surprising and
 * expensive (contract §5, decision §9 of the dossier's open questions).
 * <p>
 * {@code DOCUMENT_UPLOADED} handling is the "CV committed separately"
 * catch-up: it only fires when the upload is a CV, the candidate has an
 * open application AND no AI generation exists yet for the candidate —
 * which also prevents double round-trips on the common single-transaction
 * public submission (APPLICATION_CREATED, handled first in seq order,
 * already generated) and on duplicate re-submissions.
 */
@JBossLog
@ApplicationScoped
public class AiIntakeReactor extends RecruitmentReactor {

    public static final String NAME = "ai-intake";

    private static final com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>> JSON_OBJECT =
            new com.fasterxml.jackson.core.type.TypeReference<>() {
            };

    @Inject
    ObjectMapper objectMapper;

    @Inject
    RecruitmentAiFeatureFlag aiFlags;

    @Inject
    AiIntakeGenerationService generationService;

    @Override
    public String name() {
        return NAME;
    }

    /** One in-JVM try + one catch-up retry, then swallow and advance (AI spec §3.3). */
    @Override
    protected int maxDeliveryAttempts() {
        return 2;
    }

    @Override
    protected void handle(RecruitmentEvent event) throws Exception {
        RecruitmentEventType type = event.getEventType();
        if (type != RecruitmentEventType.APPLICATION_CREATED
                && type != RecruitmentEventType.DOCUMENT_UPLOADED) {
            return; // not ours (also ignores our own AI_* events)
        }
        if (!aiFlags.isIntakeEnabled() && !aiFlags.isBriefEnabled()) {
            return; // flag off ⇒ silent advance, no backfill on later enable
        }
        if (type == RecruitmentEventType.APPLICATION_CREATED) {
            handleApplicationCreated(event);
        } else {
            handleDocumentUploaded(event);
        }
    }

    private void handleApplicationCreated(RecruitmentEvent event) {
        RecruitmentApplication anchor = event.getApplicationUuid() == null ? null
                : RecruitmentApplication.findById(event.getApplicationUuid());
        RecruitmentCandidate candidate = event.getCandidateUuid() == null ? null
                : RecruitmentCandidate.findById(event.getCandidateUuid());
        if (anchor == null || candidate == null) {
            log.warnf("AI intake: APPLICATION_CREATED seq %d without loadable subjects — skipping",
                    event.getSeq());
            return;
        }
        generationService.generate(candidate, anchor,
                AiIntakeGenerationService.ORIGIN_REACTOR, event.getSeq(), event.getVisibility());
    }

    private void handleDocumentUploaded(RecruitmentEvent event) {
        Map<String, Object> payload = parse(event.getPayload());
        if (!"CV".equals(payload.get("kind"))) {
            return;
        }
        String candidateUuid = event.getCandidateUuid();
        if (candidateUuid == null) {
            return;
        }
        // "CV committed separately" catch-up only: never re-generate when a
        // generation already exists (the single-tx public submission's
        // APPLICATION_CREATED ran first in seq order).
        long priorAiEvents = RecruitmentEvent.count("candidateUuid = ?1 and eventType in ?2",
                candidateUuid, List.of(RecruitmentEventType.AI_SUGGESTIONS_GENERATED,
                        RecruitmentEventType.AI_BRIEF_GENERATED));
        if (priorAiEvents > 0) {
            return;
        }
        RecruitmentApplication anchor = latestOpenApplication(candidateUuid);
        if (anchor == null) {
            return; // no open application — nothing to anchor a generation on
        }
        RecruitmentCandidate candidate = RecruitmentCandidate.findById(candidateUuid);
        if (candidate == null) {
            return;
        }
        generationService.generate(candidate, anchor,
                AiIntakeGenerationService.ORIGIN_REACTOR, event.getSeq(), event.getVisibility());
    }

    /** The candidate's most recently created open application (shared with the regenerate endpoint). */
    public static RecruitmentApplication latestOpenApplication(String candidateUuid) {
        return RecruitmentApplication.find(
                        "candidateUuid = ?1 and terminal is null order by createdAt desc, uuid desc",
                        candidateUuid)
                .firstResult();
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
