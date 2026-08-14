package dk.trustworks.intranet.competenceservice.resources;

import dk.trustworks.intranet.competenceservice.content.CompetencePayloadCodec;
import dk.trustworks.intranet.competenceservice.domain.CompetenceStatus;
import dk.trustworks.intranet.competenceservice.domain.CompetenceStatusCalculator;
import dk.trustworks.intranet.competenceservice.domain.CompetenceStatusCalculator.AttemptFact;
import dk.trustworks.intranet.competenceservice.domain.CompetenceStatusCalculator.CompletionFact;
import dk.trustworks.intranet.competenceservice.dto.AttemptDTO;
import dk.trustworks.intranet.competenceservice.dto.AttemptHistoryDTO;
import dk.trustworks.intranet.competenceservice.dto.AttemptResultDTO;
import dk.trustworks.intranet.competenceservice.dto.AttemptSubmissionRequest;
import dk.trustworks.intranet.competenceservice.dto.CourseContentDTO;
import dk.trustworks.intranet.competenceservice.dto.MyRequirementDTO;
import dk.trustworks.intranet.competenceservice.model.CompetenceAttempt;
import dk.trustworks.intranet.competenceservice.model.CompetenceAttemptDecision;
import dk.trustworks.intranet.competenceservice.model.CompetenceContentVersion;
import dk.trustworks.intranet.competenceservice.model.CompetenceCourseCompletion;
import dk.trustworks.intranet.competenceservice.model.CompetenceRequirement;
import dk.trustworks.intranet.competenceservice.model.ContentKind;
import dk.trustworks.intranet.competenceservice.services.CompetenceAttemptService;
import dk.trustworks.intranet.competenceservice.services.CompetenceCompletionService;
import dk.trustworks.intranet.competenceservice.services.CompetenceRequirementService;
import dk.trustworks.intranet.competenceservice.services.CompetenceSettingsService;
import dk.trustworks.intranet.competenceservice.services.CompetenceStatusService;
import dk.trustworks.intranet.competenceservice.services.CompetenceStatusService.Snapshot;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

/**
 * Everything an employee does with their own competence requirements (spec §6.1).
 *
 * <h2>Why there is no useruuid anywhere on this class</h2>
 *
 * <p>Every path here addresses a requirement or an attempt, never a person. The subject is
 * always {@link RequestHeaderHolder#getUserUuid()}, so there is no parameter to tamper with
 * and therefore no IDOR surface to defend (§10.1, CWE-639). That is a stronger property than
 * an ownership check on a supplied uuid, because it cannot be forgotten on a method somebody
 * adds later: there is no way to express "somebody else" in this API at all.
 *
 * <p>Where ownership still has to be checked — an attempt uuid is a uuid the caller could
 * guess — the answer for somebody else's row is <strong>404, never 403</strong>. A 403
 * confirms the uuid exists, which turns the endpoint into an existence oracle over other
 * people's compliance records. The same applies to a requirement the caller is not in the
 * audience for: "exists but not yours" and "does not exist" are deliberately
 * indistinguishable.
 *
 * <h2>What {@code @RolesAllowed} does and does not gate here</h2>
 *
 * <p>The JWT reaching this backend is a system/client credentials token carrying no user
 * identity (the user travels in {@code X-Requested-By}). So the class-level
 * {@code @RolesAllowed({"competence:read"})} gates the API <em>client</em> — it says which
 * integration may call the competence learner API at all — and not the human being. Per-user
 * authorization is the BFF's {@code requireSession()} plus the fact that every read and write
 * below is scoped to {@code RequestHeaderHolder.getUserUuid()}. Both halves are load-bearing:
 * the scope stops another service from reading this API, and the actor scoping stops a
 * session from reading anyone but itself.
 *
 * <p>No per-method {@code @RolesAllowed} overrides. Recording a completion and sitting a test
 * are things an employee does about themselves — the write is not privileged, it is personal,
 * and gating it behind {@code competence:write} would mean handing every employee the
 * permission that also edits course content.
 *
 * <p><strong>Every helper on this class is {@code private}.</strong> A class-level
 * {@code @RolesAllowed} installs an interceptor on every non-private method, helpers included,
 * so a package-private helper would be authorization-checked, appear in the interceptor chain
 * and — worse — read as an endpoint to the next person editing the file.
 *
 * <h2>Logging</h2>
 *
 * <p>Domain events are logged by the services that write them, with the stable tokens the
 * production log sweep filters on ({@code COMPETENCE_ATTEMPT_STARTED},
 * {@code COMPETENCE_ATTEMPT_SUBMITTED}, {@code COMPETENCE_COURSE_COMPLETED}). This class adds
 * nothing at INFO: a REST layer that logged the same events again would double every count in
 * monitoring. Nothing anywhere logs a payload, an answer or an option id (§10.7).
 */
@JBossLog
@Tag(name = "competence")
@Path("/knowledge/competence/me")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"competence:read"})
public class CompetenceLearnerResource {

    @Inject
    CompetenceRequirementService requirementService;

    @Inject
    CompetenceStatusService statusService;

    @Inject
    CompetenceSettingsService settingsService;

    @Inject
    CompetenceAttemptService attemptService;

    @Inject
    CompetenceCompletionService completionService;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    // -----------------------------------------------------------------------
    // requirements
    // -----------------------------------------------------------------------

    /**
     * The caller's own requirements, both statuses resolved (spec §6.1).
     *
     * <p>Resolved from <strong>one</strong> status snapshot and one open-attempt query for
     * the whole page rather than per card (§10.10). The per-card alternative is not merely
     * slower: {@code CompetenceStatusService.courseGateOpen} takes its own snapshot, so
     * calling it once per requirement would re-read four tables per row and could hand two
     * cards statuses derived from two different instants.
     *
     * @return one row per visible requirement, in the requirements' sort order; empty when
     *         the caller is in no audience — which is a legitimate answer, not an error
     */
    @GET
    @Path("/requirements")
    public List<MyRequirementDTO> myRequirements() {
        String useruuid = actor();
        List<CompetenceRequirement> requirements = requirementService.visibleTo(useruuid);
        if (requirements.isEmpty()) {
            return List.of();
        }

        Snapshot snapshot = statusService.snapshot(List.of(useruuid));
        Map<String, String> openAttempts = openAttemptsByRequirement(useruuid);
        LocalDateTime now = LocalDateTime.now();

        List<MyRequirementDTO> out = new ArrayList<>(requirements.size());
        for (CompetenceRequirement requirement : requirements) {
            out.add(toMyRequirement(requirement, snapshot, openAttempts, useruuid, now));
        }
        return out;
    }

    /**
     * The ACTIVE microcourse, compiled for the player (spec §6.1, §9.2).
     *
     * <p>There is no parameter for asking after a DRAFT or an ARCHIVED version. A draft must
     * never reach an employee (§6.3), and an archived one would let somebody satisfy a
     * requirement by reading the content it was republished to correct.
     *
     * @throws WebApplicationException 404 when the requirement is not visible to the caller
     */
    @GET
    @Path("/requirements/{uuid}/course")
    public CourseContentDTO course(@PathParam("uuid") String requirementUuid) {
        String useruuid = actor();
        CompetenceRequirement requirement = requirementService.requireVisible(requirementUuid, useruuid);

        CompetenceContentVersion version =
                CompetenceContentVersion.findActive(requirement.getUuid(), ContentKind.COURSE);
        if (version == null) {
            // Unreachable: visibility already requires both tracks published (§5.1). Kept,
            // and kept as the same 404 with the same message, so a requirement that lost its
            // ACTIVE course between the two reads degrades to "no such requirement" rather
            // than to a 500 that tells the caller the row exists.
            throw new WebApplicationException("No such requirement", Response.Status.NOT_FOUND);
        }
        return CourseContentDTO.of(
                requirement, version, CompetencePayloadCodec.readCourse(version.getPayloadJson()));
    }

    /**
     * Records that the caller read the ACTIVE microcourse version (spec §6.1).
     *
     * <p>No body: there is nothing for the client to tell the server. The subject comes from
     * the header, the requirement from the path, and the version is resolved server-side —
     * see {@link CompetenceCompletionService#recordCompletion}.
     *
     * <p>Always {@code 201}, including for an idempotent replay inside the 60 s window, which
     * then carries the pre-existing row. The status is fixed rather than 201-or-200 on
     * purpose: whether a double-click's second request lands inside the window is a matter of
     * milliseconds, and a client that branched on it would branch on a race. §6.1 pins 201.
     *
     * <p>No {@code Location} header — a single completion is not addressable, so a Location
     * would point at a URL that 404s.
     *
     * @throws WebApplicationException 403 while impersonating (§10.4) — impersonated evidence
     *                                 is worse than no evidence; 404 when the requirement is
     *                                 not visible to the caller
     */
    @POST
    @Path("/requirements/{uuid}/course/completions")
    public Response recordCourseCompletion(@PathParam("uuid") String requirementUuid) {
        String useruuid = actor();
        CompetenceCourseCompletion completion =
                completionService.recordCompletion(requirementUuid, useruuid);
        return Response.status(Response.Status.CREATED).entity(completion).build();
    }

    // -----------------------------------------------------------------------
    // attempts
    // -----------------------------------------------------------------------

    /**
     * Starts a test attempt (spec §6.1).
     *
     * <p>Everything that decides the verdict — the content version, its label, the pass
     * threshold and the option shuffle — is frozen onto the row here, so republishing the
     * test or moving the threshold mid-attempt cannot change what this attempt means.
     *
     * <p>Returns the same {@link AttemptDTO} as {@link #resumeAttempt}, with plain {@code 200}
     * rather than a {@code 201} plus {@code Location}. Start and resume are the two ways the
     * test page acquires an attempt and they must hand it exactly the same object; a status
     * that differed between them would be a second thing for the page to branch on for no
     * gain, since the attempt's address is its {@code uuid} in the body either way.
     *
     * @throws WebApplicationException 403 while impersonating (§10.4); 404 when the
     *                                 requirement is not visible; 409 when the course gate is
     *                                 closed, when an attempt is already open — the message
     *                                 names its uuid so the page can offer to resume it — or
     *                                 when there is no usable published test
     */
    @POST
    @Path("/requirements/{uuid}/attempts")
    public AttemptDTO startAttempt(@PathParam("uuid") String requirementUuid) {
        String useruuid = actor();
        return toAttempt(attemptService.start(requirementUuid, useruuid));
    }

    /**
     * Resumes an in-progress attempt — the same questions, in the same recorded option order
     * (spec §6.1).
     *
     * <p><strong>A submitted attempt is a {@code 409}, not its questions.</strong> Of the two
     * options the spec allows — 409 pointing at the result, or returning the result DTO — this
     * takes the 409, so the endpoint has one return type and one shape. Handing back the
     * question list after submission would let a candidate re-read the paper they have already
     * been scored on, which is a slow way to farm the answer key across attempts; the result
     * itself is available from {@code GET /me/attempts}, which the message says.
     *
     * <p>An abandoned attempt is also a 409: its questions are unsubmittable, and showing them
     * would let somebody answer for several minutes before the submit told them so.
     *
     * @throws WebApplicationException 404 when the attempt is not the caller's or does not
     *                                 exist — the two are indistinguishable on purpose (§10.1);
     *                                 409 when it is already submitted or abandoned
     */
    @GET
    @Path("/attempts/{attemptUuid}")
    public AttemptDTO resumeAttempt(@PathParam("attemptUuid") String attemptUuid) {
        String useruuid = actor();
        CompetenceAttempt attempt = attemptService.requireOwned(attemptUuid, useruuid);

        if (attempt.getSubmittedAt() != null) {
            throw new WebApplicationException(
                    "This attempt has already been submitted — its result is in your attempt history",
                    Response.Status.CONFLICT);
        }
        if (attempt.isAbandoned()) {
            throw new WebApplicationException(
                    "This attempt timed out — start a new one", Response.Status.CONFLICT);
        }
        return toAttempt(attempt);
    }

    /**
     * Scores an attempt server-side and freezes the row (spec §6.1, §6.4).
     *
     * <p>The body carries option <em>ids</em>, never texts or indices, and the scoring happens
     * against the payload frozen onto the attempt using the threshold frozen onto the attempt.
     * The correctness flags never leave the server — the learner types have no such field at
     * all (see {@code LearnerOption}).
     *
     * <p>A {@code null} body is passed through rather than normalised to an empty map: the
     * scorer answers it with {@code 400 "No answers submitted"}, and quietly turning a missing
     * body into a zero-score submission would burn the candidate's attempt on a client bug.
     *
     * @throws WebApplicationException 400 on unknown, duplicated or missing ids — no partial
     *                                 score; 403 while impersonating (§10.4); 404 when the
     *                                 attempt is not the caller's; 409 when it has already
     *                                 been submitted or has been abandoned
     */
    @POST
    @Path("/attempts/{attemptUuid}/submit")
    public AttemptResultDTO submitAttempt(@PathParam("attemptUuid") String attemptUuid,
                                          AttemptSubmissionRequest request) {
        String useruuid = actor();
        CompetenceAttempt attempt = attemptService.submit(
                attemptUuid, useruuid, request == null ? null : request.answers());
        return AttemptResultDTO.of(attempt, CompetenceRequirement.findByUuid(attempt.getRequirementUuid()));
    }

    /**
     * The caller's own attempt history, newest first (spec §6.1).
     *
     * <p>This is the employee's copy of the evidence held about them, which is part of what
     * makes the module defensible under §10.9 — nobody keeps a compliance file on a person
     * that the person cannot read. Revoked approvals stay in the list rather than disappearing:
     * somebody whose approval was taken away has to be able to see that it was, and when.
     *
     * <p>In-progress and abandoned attempts are absent — {@code listForUser} returns submitted
     * rows only. An unfinished attempt is not history, and the requirements page already
     * offers it as {@code RESUME_ATTEMPT}.
     */
    @GET
    @Path("/attempts")
    public List<AttemptHistoryDTO> myAttempts() {
        String useruuid = actor();
        List<CompetenceAttempt> attempts = CompetenceAttempt.listForUser(useruuid);
        if (attempts.isEmpty()) {
            return List.of();
        }

        // Two batched lookups for the whole list — never one per row.
        Map<String, CompetenceAttemptDecision> decisions = statusService.latestDecisionsFor(
                attempts.stream().map(CompetenceAttempt::getUuid).toList());
        Map<String, CompetenceRequirement> requirements = requirementIndex(attempts);

        List<AttemptHistoryDTO> out = new ArrayList<>(attempts.size());
        for (CompetenceAttempt attempt : attempts) {
            out.add(AttemptHistoryDTO.of(
                    attempt,
                    requirements.get(attempt.getRequirementUuid()),
                    decisions.get(attempt.getUuid())));
        }
        return out;
    }

    // -----------------------------------------------------------------------
    // helpers — all private, see the class javadoc
    // -----------------------------------------------------------------------

    /**
     * The subject of every read and write on this class.
     *
     * <p>Rejects a missing {@code X-Requested-By} rather than proceeding with {@code null}.
     * A null subject would not throw anywhere useful — it would return an empty requirement
     * list and an empty attempt history, which renders as "you have nothing to do". For a
     * compliance module that is the single most dangerous way to fail, and it fails silently:
     * nobody reports a page that says they are up to date.
     *
     * <p>400 rather than 401: the API client authenticated correctly and the request is simply
     * incomplete. A 401 would send the BFF into a token refresh that cannot fix it.
     */
    private String actor() {
        String useruuid = requestHeaderHolder.getUserUuid();
        if (useruuid == null || useruuid.isBlank()) {
            log.warn("Competence learner request without X-Requested-By — refusing");
            throw new WebApplicationException(
                    "X-Requested-By is required — the competence API always acts for a named user",
                    Response.Status.BAD_REQUEST);
        }
        return useruuid;
    }

    /**
     * Assembles one card. Pure derivation from the shared snapshot — no queries.
     *
     * <p>{@code courseGateOpen} is taken from the calculator against the course status already
     * derived here, not from {@code CompetenceStatusService.courseGateOpen}, which would take
     * a second snapshot per row. Same rule, same input, one read.
     */
    private MyRequirementDTO toMyRequirement(CompetenceRequirement requirement,
                                             Snapshot snapshot,
                                             Map<String, String> openAttempts,
                                             String useruuid,
                                             LocalDateTime now) {
        CompetenceStatus.Course courseStatus =
                statusService.courseStatus(requirement, snapshot, useruuid, now);
        CompetenceStatus.Test testStatus =
                statusService.testStatus(requirement, snapshot, useruuid, now);
        CompetenceStatus.Cell cellStatus = CompetenceStatusCalculator.cellStatus(courseStatus, testStatus);

        boolean gateOpen = CompetenceStatusCalculator.courseGateOpen(courseStatus);
        String openAttemptUuid = openAttempts.get(requirement.getUuid());

        LocalDateTime lastCompletedAt = lastCompletedAt(snapshot, useruuid, requirement.getUuid());
        LocalDateTime lastPassedAt = lastPassedAt(snapshot, useruuid, requirement.getUuid());
        int cadenceDays = settingsService.effectiveCadenceDays(requirement);

        return new MyRequirementDTO(
                requirement.getUuid(),
                requirement.getCompId(),
                requirement.getKref(),
                requirement.getName(),
                requirement.getDescription(),
                courseStatus,
                testStatus,
                cellStatus,
                snapshot.activeCourseLabels().get(requirement.getUuid()),
                snapshot.activeTestLabels().get(requirement.getUuid()),
                lastCompletedAt,
                lastPassedAt,
                cadenceDays,
                MyRequirementDTO.dueAt(lastCompletedAt, lastPassedAt, cadenceDays),
                gateOpen,
                openAttemptUuid,
                MyRequirementDTO.NextAction.derive(courseStatus, testStatus, gateOpen, openAttemptUuid));
    }

    /**
     * The most recent completion timestamp for one requirement, any version.
     *
     * <p>Maximised rather than "take the first" even though the snapshot's query orders newest
     * first: this is a handful of rows, and a card that reported the wrong renewal date because
     * an ordering clause moved elsewhere would be a quiet, plausible-looking wrong answer.
     */
    private LocalDateTime lastCompletedAt(Snapshot snapshot, String useruuid, String requirementUuid) {
        LocalDateTime latest = null;
        for (CompletionFact fact : snapshot.completionsFor(useruuid, requirementUuid)) {
            if (fact.completedAt() != null && (latest == null || fact.completedAt().isAfter(latest))) {
                latest = fact.completedAt();
            }
        }
        return latest;
    }

    /**
     * The most recent <em>qualifying</em> pass for one requirement, any version.
     *
     * <p>Revoked passes are excluded, matching {@code CompetenceStatusCalculator.testStatus}.
     * A revoked pass leaves the track red, and anchoring a renewal date on it would print a
     * future due date next to a status that says the person must sit the test again.
     */
    private LocalDateTime lastPassedAt(Snapshot snapshot, String useruuid, String requirementUuid) {
        LocalDateTime latest = null;
        for (AttemptFact fact : snapshot.attemptsFor(useruuid, requirementUuid)) {
            if (!fact.countsAsPass() || fact.submittedAt() == null) {
                continue;
            }
            if (latest == null || fact.submittedAt().isAfter(latest)) {
                latest = fact.submittedAt();
            }
        }
        return latest;
    }

    /**
     * requirementUuid → the caller's open attempt, in one query.
     *
     * <p>At most one open attempt per requirement is what {@code CompetenceAttemptService.start}
     * enforces, but nothing in the schema does, so the first row of a newest-first list wins
     * and any older stragglers are ignored — the page must offer one "resume", not two.
     */
    private Map<String, String> openAttemptsByRequirement(String useruuid) {
        Map<String, String> open = new HashMap<>();
        for (CompetenceAttempt attempt : CompetenceAttempt.listOpenForUser(useruuid)) {
            open.putIfAbsent(attempt.getRequirementUuid(), attempt.getUuid());
        }
        return open;
    }

    /**
     * The single mapping from an owned attempt to the learner wire shape, used by both start
     * and resume so the two can never drift apart.
     */
    private AttemptDTO toAttempt(CompetenceAttempt attempt) {
        return AttemptDTO.of(
                attempt,
                CompetenceRequirement.findByUuid(attempt.getRequirementUuid()),
                attemptService.frozenPayload(attempt),
                attemptService.optionOrder(attempt));
    }

    /** requirementUuid → requirement, for a whole history page in one query. */
    private Map<String, CompetenceRequirement> requirementIndex(List<CompetenceAttempt> attempts) {
        List<String> uuids = attempts.stream()
                .map(CompetenceAttempt::getRequirementUuid)
                .distinct()
                .toList();
        Map<String, CompetenceRequirement> index = new HashMap<>();
        if (uuids.isEmpty()) {
            return index;
        }
        List<CompetenceRequirement> found = CompetenceRequirement.list("uuid in ?1", uuids);
        for (CompetenceRequirement requirement : found) {
            index.put(requirement.getUuid(), requirement);
        }
        return index;
    }
}
