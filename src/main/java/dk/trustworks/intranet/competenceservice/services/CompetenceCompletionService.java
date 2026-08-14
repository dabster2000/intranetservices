package dk.trustworks.intranet.competenceservice.services;

import dk.trustworks.intranet.competenceservice.model.CompetenceContentVersion;
import dk.trustworks.intranet.competenceservice.model.CompetenceCourseCompletion;
import dk.trustworks.intranet.competenceservice.model.CompetenceRequirement;
import dk.trustworks.intranet.competenceservice.model.ContentKind;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Recording that someone read a microcourse (spec §6.1,
 * {@code POST /me/requirements/{uuid}/course/completions}).
 *
 * <p>One write, but it is evidence, so it gets a service of its own rather than a few lines
 * in the resource. Three things have to be true of it and none of them belongs in a REST
 * method: the subject must be in the requirement's audience, the write must be refused while
 * somebody is impersonating them, and pressing "Færdig" twice must not produce two records of
 * one reading.
 *
 * <p>The completion table is append-only at the database level — its UPDATE and DELETE
 * triggers refuse both unconditionally — so there is no "upsert" available and no way to
 * clean up afterwards. Whatever is inserted here is permanent, which is exactly why the
 * idempotency window exists.
 */
@JBossLog
@ApplicationScoped
public class CompetenceCompletionService {

    /**
     * How long a completion of the same version is treated as a replay rather than a new
     * reading (spec §6.1).
     *
     * <p>Sixty seconds is long enough to cover the things that actually cause a second POST —
     * a double-click, a React strict-mode double effect, a player re-mounting after a
     * navigation, a retried request on a flaky connection — and short enough that it cannot
     * swallow a genuine second reading, since nobody reads a microcourse twice in a minute.
     */
    static final int IDEMPOTENCY_WINDOW_SECONDS = 60;

    @Inject
    CompetenceRequirementService requirementService;

    /**
     * Injected for {@link CompetenceAttemptService#refuseWhileImpersonating} alone.
     *
     * <p>The §10.4 rule is one rule — "no evidence about a person may be written by somebody
     * pretending to be them" — and it reads the same header for completions, attempts and
     * decisions. Copying the three lines here would give two places to keep the message and
     * the status in step, and the first divergence would be a completion that quietly
     * succeeded under impersonation while an attempt was refused.
     */
    @Inject
    CompetenceAttemptService attemptService;

    /**
     * Records that this person completed the ACTIVE microcourse version, or returns the row
     * that says they just did.
     *
     * <p>The version is resolved server-side. A client that told the server which version it
     * had shown would be recording an unfalsifiable claim — the whole point of stamping
     * {@code content_version_uuid} onto the row is that §5.3 can later ask "was that the
     * version that is live now", and an answer supplied by the browser cannot settle it.
     *
     * <p><strong>Idempotency is a de-duplicator, not a lock.</strong> Two POSTs that overlap
     * inside the window can both find nothing and both insert, because the table has no
     * unique key to serialise them on and taking a lock for a double-click would be a strange
     * trade. That is harmless: status reads the newest completion for the pair (§5.3), so a
     * duplicated row changes nothing an employee or an auditor sees. What the window
     * eliminates is the common case — the second click, the re-mount, the retry — which
     * otherwise accumulates noise in a table nobody may ever delete from.
     *
     * @param requirementUuid the krav; 404 when it is not visible to this person, so "exists
     *                        but not yours" and "does not exist" stay indistinguishable
     * @param useruuid        always {@code RequestHeaderHolder.getUserUuid()}, never a
     *                        parameter from the request (§10.1)
     * @return the recorded completion, or the existing one when this is a replay
     */
    @Transactional
    public CompetenceCourseCompletion recordCompletion(String requirementUuid, String useruuid) {
        attemptService.refuseWhileImpersonating("record a microcourse completion");

        CompetenceRequirement requirement = requirementService.requireVisible(requirementUuid, useruuid);
        CompetenceContentVersion version =
                CompetenceContentVersion.findActive(requirement.getUuid(), ContentKind.COURSE);
        if (version == null) {
            // Unreachable while visibility requires both tracks published (§5.1), but a
            // requirement that lost its ACTIVE course between the two reads must not produce
            // a completion pointing at nothing.
            throw new WebApplicationException(
                    "No published microcourse for this requirement", Response.Status.CONFLICT);
        }

        LocalDateTime now = LocalDateTime.now();
        CompetenceCourseCompletion recent = CompetenceCourseCompletion.findRecent(
                useruuid, requirement.getUuid(), version.getUuid(),
                now.minusSeconds(IDEMPOTENCY_WINDOW_SECONDS));
        if (recent != null) {
            // Deliberately not an INFO event: nothing happened. Logging a domain event here
            // would double-count every double-click in the monitoring the token feeds.
            log.debugf("Duplicate course completion for user=%s requirement=%s version=%s "
                            + "within %ds — returning %s",
                    useruuid, requirement.getCompId(), version.getVersionLabel(),
                    IDEMPOTENCY_WINDOW_SECONDS, recent.getUuid());
            return recent;
        }

        CompetenceCourseCompletion completion = new CompetenceCourseCompletion();
        completion.setUuid(UUID.randomUUID().toString());
        completion.setUseruuid(useruuid);
        completion.setRequirementUuid(requirement.getUuid());
        completion.setContentVersionUuid(version.getUuid());
        // Denormalised so the status rules and the exports keep working after the version
        // row is purged — the label is what §5.3 compares.
        completion.setVersionLabel(version.getVersionLabel());
        completion.setCompletedAt(now);
        completion.persist();

        log.infof("COMPETENCE_COURSE_COMPLETED completion=%s user=%s requirement=%s version=%s",
                completion.getUuid(), useruuid, requirement.getCompId(), version.getVersionLabel());
        return completion;
    }
}
