package dk.trustworks.intranet.competenceservice.services;

import com.fasterxml.jackson.core.type.TypeReference;
import dk.trustworks.intranet.competenceservice.content.CompetenceContent;
import dk.trustworks.intranet.competenceservice.content.CompetencePayloadCodec;
import dk.trustworks.intranet.competenceservice.domain.CompetenceTestScorer;
import dk.trustworks.intranet.competenceservice.model.CompetenceAttempt;
import dk.trustworks.intranet.competenceservice.model.CompetenceContentVersion;
import dk.trustworks.intranet.competenceservice.model.CompetenceRequirement;
import dk.trustworks.intranet.competenceservice.model.ContentKind;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * The attempt lifecycle: start, resume, submit, reap.
 *
 * <p>Three properties matter more than anything else here.
 *
 * <ol>
 *   <li><strong>The gate is server-enforced.</strong> Starting an attempt re-evaluates the
 *       microcourse status at that instant, so hiding the button is a convenience and this
 *       is the control. Someone whose completion fell due mid-session is gated again.</li>
 *   <li><strong>Everything that decides the verdict is frozen at start.</strong> The
 *       content version, its label and the pass threshold go onto the row, so republishing
 *       the test or moving the threshold mid-attempt cannot change what this attempt
 *       means.</li>
 *   <li><strong>Scoring happens here, never in the browser</strong>, against the frozen
 *       payload, and the correct flag never leaves the server.</li>
 * </ol>
 *
 * <p>Impersonated writes are refused outright. While an admin impersonates someone, the
 * backend otherwise behaves exactly as it would for the subject — which for evidence is
 * precisely the problem. Impersonated evidence is worse than no evidence.
 */
@JBossLog
@ApplicationScoped
public class CompetenceAttemptService {

    @Inject
    CompetenceStatusService statusService;

    @Inject
    CompetenceSettingsService settingsService;

    @Inject
    CompetenceRequirementService requirementService;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    // -----------------------------------------------------------------------
    // start
    // -----------------------------------------------------------------------

    /**
     * @return the new attempt, or the existing open one when the caller already has one —
     *         a reload of the test page must not silently start a second attempt
     */
    @Transactional
    public CompetenceAttempt start(String requirementUuid, String useruuid) {
        refuseWhileImpersonating("start a test attempt");

        CompetenceRequirement requirement = requirementService.requireVisible(requirementUuid, useruuid);

        CompetenceAttempt open = CompetenceAttempt.findOpen(useruuid, requirement.getUuid());
        if (open != null) {
            throw new WebApplicationException(
                    "An attempt is already in progress: " + open.getUuid(),
                    Response.Status.CONFLICT);
        }

        LocalDateTime now = LocalDateTime.now();
        if (!statusService.courseGateOpen(requirement, useruuid, now)) {
            throw new WebApplicationException(
                    "The microcourse must be completed for the current version before the test unlocks",
                    Response.Status.CONFLICT);
        }

        CompetenceContentVersion version =
                CompetenceContentVersion.findActive(requirement.getUuid(), ContentKind.TEST);
        if (version == null) {
            throw new WebApplicationException(
                    "No published test for this requirement", Response.Status.CONFLICT);
        }
        CompetenceContent.TestPayload payload = CompetencePayloadCodec.readTest(version.getPayloadJson());
        if (payload.questions().isEmpty()) {
            throw new WebApplicationException(
                    "The published test has no questions", Response.Status.CONFLICT);
        }

        CompetenceAttempt attempt = new CompetenceAttempt();
        attempt.setUuid(UUID.randomUUID().toString());
        attempt.setUseruuid(useruuid);
        attempt.setRequirementUuid(requirement.getUuid());
        attempt.setContentVersionUuid(version.getUuid());
        attempt.setVersionLabel(version.getVersionLabel());
        attempt.setKref(requirement.getKref());
        attempt.setThresholdSnapshot(settingsService.passThreshold());
        attempt.setOptionOrderJson(CompetencePayloadCodec.write(shuffleOptions(payload)));
        attempt.setStartedAt(now);
        attempt.setQuestionCount(payload.questions().size());
        attempt.persist();

        log.infof("COMPETENCE_ATTEMPT_STARTED attempt=%s user=%s requirement=%s version=%s questions=%d",
                attempt.getUuid(), useruuid, requirement.getCompId(), version.getVersionLabel(),
                attempt.getQuestionCount());
        return attempt;
    }

    /**
     * The per-attempt shuffle, held for the whole attempt so navigating back and forth does
     * not move the answers under the candidate. Question order is deliberately NOT
     * shuffled: stable numbering keeps "question 4 of 10" meaningful in a support
     * conversation, and the prototype does not shuffle it either.
     */
    Map<String, List<String>> shuffleOptions(CompetenceContent.TestPayload payload) {
        Map<String, List<String>> order = new LinkedHashMap<>();
        Random random = new Random();
        for (CompetenceContent.Question question : payload.questions()) {
            List<String> ids = new ArrayList<>(question.options().stream()
                    .map(CompetenceContent.Option::id).toList());
            Collections.shuffle(ids, random);
            order.put(question.id(), ids);
        }
        return order;
    }

    // -----------------------------------------------------------------------
    // read
    // -----------------------------------------------------------------------

    /**
     * Loads an attempt the caller owns.
     *
     * <p>Someone else's attempt is a <strong>404</strong>, not a 403 — a 403 confirms the
     * uuid exists, which is an existence oracle over other people's records.
     */
    public CompetenceAttempt requireOwned(String attemptUuid, String useruuid) {
        CompetenceAttempt attempt = CompetenceAttempt.findByUuid(attemptUuid);
        if (attempt == null || !attempt.getUseruuid().equals(useruuid)) {
            throw new WebApplicationException("No such attempt", Response.Status.NOT_FOUND);
        }
        return attempt;
    }

    /** The frozen payload for an attempt — the only version that may score it. */
    public CompetenceContent.TestPayload frozenPayload(CompetenceAttempt attempt) {
        CompetenceContentVersion version =
                CompetenceContentVersion.findByUuid(attempt.getContentVersionUuid());
        if (version == null) {
            throw new WebApplicationException(
                    "The version this attempt was taken under no longer exists",
                    Response.Status.INTERNAL_SERVER_ERROR);
        }
        return CompetencePayloadCodec.readTest(version.getPayloadJson());
    }

    /** The recorded shuffle, so a resumed attempt shows options in the same order. */
    public Map<String, List<String>> optionOrder(CompetenceAttempt attempt) {
        if (attempt.getOptionOrderJson() == null || attempt.getOptionOrderJson().isBlank()) {
            return Map.of();
        }
        try {
            return CompetencePayloadCodec.mapper().readValue(
                    attempt.getOptionOrderJson(), new TypeReference<LinkedHashMap<String, List<String>>>() {
                    });
        } catch (Exception e) {
            log.warnf("Unreadable option order on attempt %s — falling back to payload order",
                    attempt.getUuid());
            return Map.of();
        }
    }

    // -----------------------------------------------------------------------
    // submit
    // -----------------------------------------------------------------------

    @Transactional
    public CompetenceAttempt submit(String attemptUuid, String useruuid, Map<String, String> answers) {
        refuseWhileImpersonating("submit a test attempt");

        CompetenceAttempt attempt = requireOwned(attemptUuid, useruuid);
        if (attempt.getSubmittedAt() != null) {
            throw new WebApplicationException("This attempt has already been submitted",
                    Response.Status.CONFLICT);
        }
        if (attempt.isAbandoned()) {
            throw new WebApplicationException(
                    "This attempt timed out — start a new one", Response.Status.CONFLICT);
        }

        CompetenceContent.TestPayload payload = frozenPayload(attempt);
        // The threshold frozen at start, never the current global value.
        BigDecimal threshold = attempt.getThresholdSnapshot();
        CompetenceTestScorer.Result result = CompetenceTestScorer.score(payload, answers, threshold);

        attempt.setSubmittedAt(LocalDateTime.now());
        attempt.setCorrectCount(result.correctCount());
        attempt.setScore(result.score());
        attempt.setPassed(result.passed());

        // Never log the answers — only the aggregate. The stable token is what the
        // production log sweep filters on.
        log.infof("COMPETENCE_ATTEMPT_SUBMITTED attempt=%s user=%s requirement=%s version=%s "
                        + "score=%s threshold=%s passed=%s",
                attempt.getUuid(), useruuid, attempt.getKref(), attempt.getVersionLabel(),
                result.score().toPlainString(), threshold.toPlainString(), result.passed());
        return attempt;
    }

    // -----------------------------------------------------------------------
    // reaper
    // -----------------------------------------------------------------------

    /**
     * Marks stale in-progress attempts abandoned. Without this, a closed browser tab
     * leaves a row that looks like an unfinished obligation forever — and blocks the
     * person from ever starting again, since {@link #start} refuses a second open attempt.
     *
     * <p>Idempotent: a {@code @Scheduled} job can fire on the old task during an ECS
     * Express cutover, so running twice must be harmless.
     *
     * @return how many were reaped
     */
    @Transactional
    public int reapStaleAttempts() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(settingsService.attemptTimeoutMinutes());
        List<CompetenceAttempt> stale = CompetenceAttempt.listStale(cutoff);
        for (CompetenceAttempt attempt : stale) {
            attempt.setAbandoned(true);
        }
        if (!stale.isEmpty()) {
            log.infof("COMPETENCE_ATTEMPT_REAPER abandoned=%d cutoff=%s", stale.size(), cutoff);
        }
        return stale.size();
    }

    // -----------------------------------------------------------------------

    /**
     * Spec §10.4. The BFF discloses the real admin in {@code X-Acting-For} while
     * impersonating; any write that becomes evidence about a person is refused.
     */
    void refuseWhileImpersonating(String action) {
        if (requestHeaderHolder.isImpersonated()) {
            throw new WebApplicationException(
                    "You cannot " + action + " while impersonating another user",
                    Response.Status.FORBIDDEN);
        }
    }
}
