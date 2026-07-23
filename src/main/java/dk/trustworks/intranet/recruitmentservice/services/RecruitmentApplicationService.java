package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.domain.user.entity.Team;
import dk.trustworks.intranet.recruitmentservice.dto.ApplicationRejectRequest;
import dk.trustworks.intranet.recruitmentservice.dto.ApplicationResponse;
import dk.trustworks.intranet.recruitmentservice.dto.CandidateApplicationInfo;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventBuilder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventRecorder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventVisibility;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication.StageMove;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentConsent;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidatePoolStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentConsentKind;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentConsentStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentHiringTrack;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentPositionStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentStage;
import dk.trustworks.intranet.recruitmentservice.model.exception.BusinessRuleViolation;
import dk.trustworks.intranet.recruitmentservice.security.RecruitmentVisibility;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Command handlers for the applications aggregate (ATS plan §P4). Every
 * mutation persists state and appends its {@code APPLICATION_*} /
 * {@code TEAM_ASSIGNED} / {@code CONSENT_REQUESTED} event through
 * {@link RecruitmentEventRecorder} in the same transaction.
 * <p>
 * The stage machine itself lives on {@link RecruitmentApplication}; this
 * service adds the cross-aggregate rules from spec §4.2:
 * <ul>
 *   <li>forward stage <em>skips</em> are recruiter/owner-only (invariant 1
 *       — the caller resolves the actor's tier via
 *       {@link RecruitmentVisibility#isRecruiterOrHiringOwner});</li>
 *   <li>the partner-referral reject block (invariant 2);</li>
 *   <li>{@code HIRED} unreachable through this API (invariant 3, enforced
 *       by the entity);</li>
 *   <li>terminal on the candidate's LAST open application sets
 *       {@code process_ended_at} + a 6-month {@code retention_deadline}
 *       (data only — the clock reactor is P19);</li>
 *   <li>return-to-pool → candidate pooled as {@code SILVER_MEDALIST} +
 *       a {@code REQUESTED} pool-retention consent.</li>
 * </ul>
 * Partner-track events carry {@code visibility=CIRCLE}, mirroring the
 * position/candidate emitters (P2 carry-over).
 */
@JBossLog
@ApplicationScoped
public class RecruitmentApplicationService {

    /** Spec §4.2: a terminal on the last open application starts a 6-month clock. */
    static final int RETENTION_MONTHS = 6;

    /**
     * Synthetic actor for domain-method signatures on the public-intake
     * path (P5): the candidate has no user UUID, but domain methods like
     * {@link RecruitmentCandidate#unpool(UUID)} null-check their audit
     * actor. Events on that path carry {@code actor_type=CANDIDATE} with
     * no actor UUID — this constant never reaches the event stream.
     */
    static final UUID PUBLIC_FORM_DOMAIN_ACTOR = new UUID(0L, 0L);

    /**
     * How the acting party is stamped onto emitted events — a user UUID
     * for the manual path, {@code actorCandidate()} for the P5 public
     * forms. Lets {@link #createCore} share the invariants without
     * duplicating them per actor kind.
     */
    @FunctionalInterface
    interface EventActor {
        RecruitmentEventBuilder stamp(RecruitmentEventBuilder builder);
    }

    @Inject
    RecruitmentEventRecorder eventRecorder;

    @Inject
    RecruitmentVisibility visibility;

    @Inject
    RecruitmentOfferBridge offerBridge;

    // ---- Create (attach candidate → position) ---------------------------------

    /**
     * Attach a candidate to a position. The application starts in the first
     * stage of the position's {@code stage_set} (always SCREENING).
     * <p>
     * Side effects beyond the new row + {@code APPLICATION_CREATED}:
     * a {@code POOLED} candidate is brought back to {@code ACTIVE} first
     * (pool → pipeline, with a proper {@code CANDIDATE_UNPOOLED} event), and
     * any retention bookkeeping from an earlier process end is cleared —
     * the process has resumed, so the 6-month clock must stop.
     *
     * @throws BusinessRuleViolation candidate terminal, position not OPEN,
     *         or an open application for this pair already exists (409)
     */
    @Transactional
    public RecruitmentApplication create(RecruitmentCandidate candidate,
                                         RecruitmentPosition position, UUID actor) {
        Objects.requireNonNull(actor, "actor must not be null");
        return createCore(candidate, position,
                builder -> builder.actorUser(actor.toString()),
                "manual", false, actor, actor.toString());
    }

    /**
     * P5 public-intake variant of {@link #create}: same invariants (shared
     * via {@link #createCore} — never duplicated), but events carry
     * {@code actor_type=CANDIDATE} and {@code origin=public_form}. When the
     * candidate was reused via the public dedupe path,
     * {@code dedupe_review=true} flags the application for recruiter
     * attention.
     *
     * @throws BusinessRuleViolation same conflicts as {@link #create}
     */
    @Transactional
    public RecruitmentApplication createFromPublicForm(RecruitmentCandidate candidate,
                                                       RecruitmentPosition position,
                                                       boolean dedupeReview) {
        return createCore(candidate, position,
                RecruitmentEventBuilder::actorCandidate,
                "public_form", dedupeReview, PUBLIC_FORM_DOMAIN_ACTOR, "candidate(public_form)");
    }

    /**
     * The shared create invariants (spec §4.2): candidate-terminal 409,
     * position OPEN check, duplicate-open-application guard, pooled
     * auto-unpool, retention-clock reset, first stage of the position's
     * stage set, UTC {@code stage_entered_at}, {@code APPLICATION_CREATED}
     * in the same transaction.
     */
    private RecruitmentApplication createCore(RecruitmentCandidate candidate,
                                              RecruitmentPosition position,
                                              EventActor eventActor, String origin,
                                              boolean dedupeReview, UUID domainActor,
                                              String actorForLog) {
        if (candidate.isTerminal()) {
            throw new BusinessRuleViolation(
                    "Candidate %s is %s and cannot be attached to a position"
                            .formatted(candidate.getUuid(), candidate.getStatus()));
        }
        if (position.getStatus() != RecruitmentPositionStatus.OPEN) {
            throw new BusinessRuleViolation(
                    "Position %s is %s — applications can only be attached to OPEN positions"
                            .formatted(position.getUuid(), position.getStatus()));
        }
        long openOnSamePosition = RecruitmentApplication.count(
                "candidateUuid = ?1 and positionUuid = ?2 and terminal is null",
                candidate.getUuid(), position.getUuid());
        if (openOnSamePosition > 0) {
            throw new BusinessRuleViolation(
                    "Candidate %s already has an open application on position %s"
                            .formatted(candidate.getUuid(), position.getUuid()));
        }

        // Pool → pipeline: attaching a pooled candidate implies re-activation.
        if (candidate.getStatus() == CandidateStatus.POOLED) {
            candidate.unpool(domainActor);
            eventRecorder.record(eventActor.stamp(
                    RecruitmentEventBuilder.event(RecruitmentEventType.CANDIDATE_UNPOOLED)
                            .candidate(candidate.getUuid()))
                    .payload("reason", "ATTACHED_TO_POSITION")
                    .payload("position_uuid", position.getUuid()));
        }
        // The process resumed — stop any running retention clock (P19 reads these).
        candidate.setProcessEndedAt(null);
        candidate.setRetentionDeadline(null);

        RecruitmentApplication application = new RecruitmentApplication();
        application.setCandidateUuid(candidate.getUuid());
        application.setPositionUuid(position.getUuid());
        application.setStage(firstStageOf(position));
        application.setStageEnteredAt(LocalDateTime.now(ZoneOffset.UTC));
        application.persist();

        RecruitmentEventBuilder created = applicationEvent(RecruitmentEventType.APPLICATION_CREATED,
                application, position, eventActor)
                .payload("position_title", position.getTitle())
                .payload("hiring_track", position.getHiringTrack().name())
                .payload("initial_stage", application.getStage().name())
                .payload("origin", origin);
        if (dedupeReview) {
            created.payload("dedupe_review", true);
        }
        eventRecorder.record(created);

        log.infof("Application created: %s (candidate=%s, position=%s) by actor=%s",
                application.getUuid(), candidate.getUuid(), position.getUuid(), actorForLog);
        return application;
    }

    // ---- Stage moves -----------------------------------------------------------

    /**
     * Move an application to another stage of its position's stage set.
     *
     * @param mayFastTrack whether the actor is recruiter/owner tier —
     *        forward moves that skip stages are restricted to that tier
     *        (spec §4.2 invariant 1); resolved by the resource via
     *        {@link RecruitmentVisibility#isRecruiterOrHiringOwner}
     * @throws BusinessRuleViolation illegal transition (409)
     * @throws WebApplicationException 403 when a skip is attempted without
     *         recruiter/owner rights
     */
    @Transactional
    public RecruitmentApplication changeStage(RecruitmentApplication application,
                                              RecruitmentPosition position,
                                              RecruitmentStage target,
                                              boolean mayFastTrack, UUID actor) {
        Objects.requireNonNull(actor, "actor must not be null");
        StageMove move = application.moveToStage(target, position.getStageSet());
        if (move.direction() == RecruitmentApplication.MoveDirection.FORWARD
                && move.skippedStages() && !mayFastTrack) {
            throw new WebApplicationException(Response
                    .status(Response.Status.FORBIDDEN)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(Map.of(
                            "message", "Skipping stages forward is reserved for the recruiter or the hiring owner",
                            "guidance", "Advance one stage at a time, or ask the recruiter/hiring owner to fast-track"))
                    .build());
        }

        eventRecorder.record(applicationEvent(RecruitmentEventType.APPLICATION_STAGE_CHANGED,
                application, position, actor)
                .payload("from", move.from().name())
                .payload("to", move.to().name())
                .payload("direction", move.direction().name())
                .payload("skipped_stages", move.skippedStages()));

        // P10 offer bridge: every entry into OFFER (including re-entries and
        // fast-track skips) emits OFFER_OPENED + links an existing dossier —
        // same transaction, so the timeline never diverges from the move.
        if (move.to() == RecruitmentStage.OFFER) {
            offerBridge.onOfferEntered(application, position, move.from(), actor);
        }

        log.infof("Application %s stage %s -> %s (%s) by actor=%s",
                application.getUuid(), move.from(), move.to(), move.direction(), actor);
        return application;
    }

    // ---- Terminals -------------------------------------------------------------

    /**
     * Reject an application (Trustworks said no). The coded reason is
     * mandatory; free text goes to the event's {@code pii} block.
     * <p>
     * The partner-referral guard (spec §4.2 invariant 2) is enforced HERE,
     * not only in the UI: a candidate with a partner mandate
     * ({@code sponsoring_partner_uuid} set) can only be rejected by the
     * recruiter or the hiring owner — a teamlead gets 403 with guidance.
     */
    @Transactional
    public RecruitmentApplication reject(RecruitmentApplication application,
                                         RecruitmentPosition position,
                                         RecruitmentCandidate candidate,
                                         ApplicationRejectRequest request,
                                         boolean isRecruiterOrOwner, UUID actor) {
        Objects.requireNonNull(actor, "actor must not be null");
        if (candidate.getSponsoringPartnerUuid() != null && !isRecruiterOrOwner) {
            throw new WebApplicationException(Response
                    .status(Response.Status.FORBIDDEN)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(Map.of(
                            "message", "This candidate was referred under a partner mandate — only the recruiter or the hiring owner can reject",
                            "guidance", "Escalate to the recruiter: describe your concern and let them make the rejection call"))
                    .build());
        }
        RecruitmentStage fromStage = application.getStage();
        application.reject(request.reasonCode());

        RecruitmentEventBuilder event = applicationEvent(RecruitmentEventType.APPLICATION_REJECTED,
                application, position, actor)
                .payload("reason_code", request.reasonCode().name())
                .payload("from_stage", fromStage.name());
        if (request.note() != null && !request.note().isBlank()) {
            event.pii("note", request.note());
        }
        eventRecorder.record(event);

        closeRetentionClockIfLastApplication(candidate);
        log.infof("Application %s rejected (%s) by actor=%s",
                application.getUuid(), request.reasonCode(), actor);
        return application;
    }

    /** The candidate backed out. Optional note goes to the event's pii block. */
    @Transactional
    public RecruitmentApplication withdraw(RecruitmentApplication application,
                                           RecruitmentPosition position,
                                           RecruitmentCandidate candidate,
                                           String note, UUID actor) {
        Objects.requireNonNull(actor, "actor must not be null");
        RecruitmentStage fromStage = application.getStage();
        application.withdraw();

        RecruitmentEventBuilder event = applicationEvent(RecruitmentEventType.APPLICATION_WITHDRAWN,
                application, position, actor)
                .payload("from_stage", fromStage.name());
        if (note != null && !note.isBlank()) {
            event.pii("note", note);
        }
        eventRecorder.record(event);

        closeRetentionClockIfLastApplication(candidate);
        log.infof("Application %s withdrawn by actor=%s", application.getUuid(), actor);
        return application;
    }

    /**
     * Close the application as a silver-medalist outcome (spec §4.2
     * terminal note): the candidate is pooled as {@code SILVER_MEDALIST}
     * through the P3 pool path ({@link RecruitmentCandidate#pool} — never
     * raw field assignment), and a {@code REQUESTED} pool-retention consent
     * row is created ({@code CONSENT_REQUESTED} event). Until the P19
     * consent page exists, the recruiter/DPO follows up on that consent
     * manually — a documented plan §P4 limitation.
     */
    @Transactional
    public RecruitmentApplication returnToPool(RecruitmentApplication application,
                                               RecruitmentPosition position,
                                               RecruitmentCandidate candidate, UUID actor) {
        Objects.requireNonNull(actor, "actor must not be null");
        RecruitmentStage fromStage = application.getStage();
        application.returnToPool();

        // Candidate → talent pool via the domain pool path (P3 carry-over).
        candidate.pool(CandidatePoolStatus.SILVER_MEDALIST, actor);
        eventRecorder.record(applicationEvent(RecruitmentEventType.CANDIDATE_POOLED,
                application, position, actor)
                .payload("pool_status", CandidatePoolStatus.SILVER_MEDALIST.name())
                .payload("terminal", application.getTerminal().name())
                .payload("from_stage", fromStage.name()));

        // Pool retention needs consent — request it now, collect it in P19.
        RecruitmentConsent consent = new RecruitmentConsent();
        consent.setCandidateUuid(candidate.getUuid());
        consent.setKind(RecruitmentConsentKind.TALENT_POOL_RETENTION);
        consent.setStatus(RecruitmentConsentStatus.REQUESTED);
        consent.persist();
        eventRecorder.record(applicationEvent(RecruitmentEventType.CONSENT_REQUESTED,
                application, position, actor)
                .payload("kind", RecruitmentConsentKind.TALENT_POOL_RETENTION.name())
                .payload("consent_uuid", consent.getUuid()));

        closeRetentionClockIfLastApplication(candidate);
        log.infof("Application %s returned to pool (candidate=%s silver medalist) by actor=%s",
                application.getUuid(), candidate.getUuid(), actor);
        return application;
    }

    // ---- Plain updates -----------------------------------------------------------

    /**
     * Record the offer-stage team decision. ANY existing team is accepted —
     * cross-practice and practice-less included (spec §4.1: the position's
     * practice is a grouping attribute, never a constraint on team choice).
     */
    @Transactional
    public RecruitmentApplication assignTeam(RecruitmentApplication application,
                                             RecruitmentPosition position,
                                             String teamUuid, UUID actor) {
        Objects.requireNonNull(actor, "actor must not be null");
        if (Team.findById(teamUuid) == null) {
            throw new WebApplicationException(
                    "Unknown team: " + teamUuid, Response.Status.BAD_REQUEST);
        }
        String previous = application.getAssignedTeamUuid();
        application.assignTeam(teamUuid);

        eventRecorder.record(applicationEvent(RecruitmentEventType.TEAM_ASSIGNED,
                application, position, actor)
                .payload("team_uuid", teamUuid)
                .payload("previous_team_uuid", previous));

        log.infof("Application %s team assigned: %s by actor=%s",
                application.getUuid(), teamUuid, actor);
        return application;
    }

    /**
     * Set the expected start date (Airtable <em>Ansættelsesdato</em>).
     * Appends {@code APPLICATION_UPDATED} — the P4 catalog addition for
     * structural application edits (findings §P4). A no-op set appends
     * nothing.
     */
    @Transactional
    public RecruitmentApplication setExpectedStartDate(RecruitmentApplication application,
                                                       RecruitmentPosition position,
                                                       LocalDate expectedStartDate, UUID actor) {
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(expectedStartDate, "expectedStartDate must not be null");
        if (application.isTerminal()) {
            throw new BusinessRuleViolation(
                    "Cannot set an expected start date on application %s: it is closed (%s)"
                            .formatted(application.getUuid(), application.getTerminal()));
        }
        LocalDate previous = application.getExpectedStartDate();
        if (expectedStartDate.equals(previous)) {
            return application; // No-op: no event, no audit churn.
        }
        application.setExpectedStartDate(expectedStartDate);

        Map<String, Object> change = new LinkedHashMap<>();
        change.put("before", previous != null ? previous.toString() : null);
        change.put("after", expectedStartDate.toString());
        eventRecorder.record(applicationEvent(RecruitmentEventType.APPLICATION_UPDATED,
                application, position, actor)
                .payload("changed_fields", List.of("expected_start_date"))
                .payload("expected_start_date", change));
        return application;
    }

    // ---- Reads -----------------------------------------------------------------

    /**
     * The candidate's applications visible to the viewer, hydrated with
     * position facts. Partner-track applications are absent for non-circle
     * viewers ({@link RecruitmentVisibility#filterApplications}).
     */
    public List<ApplicationResponse> listForCandidate(String viewerUuid, String candidateUuid) {
        List<RecruitmentApplication> applications =
                visibility.filterApplications(viewerUuid, candidateUuid);
        Map<String, RecruitmentPosition> positions = positionsOf(applications);
        return applications.stream()
                .map(a -> toResponse(a, positions.get(a.getPositionUuid())))
                .toList();
    }

    /**
     * Open-application facts for candidate LIST rows, visibility-filtered
     * per viewer and batched (two queries for a whole page). Candidates
     * with no visible open application are absent from the map.
     */
    public Map<String, List<CandidateApplicationInfo>> openApplicationInfoByCandidate(
            String viewerUuid, Collection<String> candidateUuids) {
        Map<String, List<RecruitmentApplication>> byCandidate =
                visibility.filterOpenApplicationsByCandidate(viewerUuid, candidateUuids);
        Map<String, RecruitmentPosition> positions = positionsOf(
                byCandidate.values().stream().flatMap(List::stream).toList());
        return byCandidate.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> e.getValue().stream()
                        .map(a -> new CandidateApplicationInfo(
                                a.getUuid(),
                                a.getPositionUuid(),
                                titleOf(positions.get(a.getPositionUuid())),
                                a.getStage()))
                        .toList()));
    }

    /** Wire mapping with the position facts denormalized in. */
    public ApplicationResponse toResponse(RecruitmentApplication application,
                                          RecruitmentPosition position) {
        return new ApplicationResponse(
                application.getUuid(),
                application.getCandidateUuid(),
                application.getPositionUuid(),
                titleOf(position),
                position != null ? position.getHiringTrack() : null,
                position != null ? position.getStageSet() : List.of(),
                application.getStage(),
                application.getTerminal(),
                application.getRejectionReasonCode(),
                application.getAssignedTeamUuid(),
                application.getExpectedStartDate(),
                application.getStageEnteredAt(),
                application.getCreatedAt());
    }

    // ---- Helpers ---------------------------------------------------------------

    /**
     * Terminal on the candidate's LAST open application sets
     * {@code process_ended_at} and a {@code RETENTION_MONTHS}-month
     * {@code retention_deadline} (spec §4.2) — data only; the clock reactor
     * that acts on the deadline is P19. Closing one of several open
     * applications sets nothing.
     */
    private void closeRetentionClockIfLastApplication(RecruitmentCandidate candidate) {
        long stillOpen = RecruitmentApplication.count(
                "candidateUuid = ?1 and terminal is null", candidate.getUuid());
        if (stillOpen == 0) {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            candidate.setProcessEndedAt(now);
            candidate.setRetentionDeadline(now.plusMonths(RETENTION_MONTHS));
        }
    }

    /**
     * Event skeleton for this application: all three subjects + acting
     * user, {@code visibility=CIRCLE} on partner track so the timeline
     * applies the same hard filter as the state tables (P2 carry-over).
     */
    private static RecruitmentEventBuilder applicationEvent(RecruitmentEventType type,
                                                            RecruitmentApplication application,
                                                            RecruitmentPosition position,
                                                            UUID actor) {
        return applicationEvent(type, application, position,
                builder -> builder.actorUser(actor.toString()));
    }

    /** {@link #applicationEvent} with the actor stamped by kind (P5 public intake). */
    private static RecruitmentEventBuilder applicationEvent(RecruitmentEventType type,
                                                            RecruitmentApplication application,
                                                            RecruitmentPosition position,
                                                            EventActor eventActor) {
        RecruitmentEventBuilder builder = eventActor.stamp(RecruitmentEventBuilder.event(type)
                .candidate(application.getCandidateUuid())
                .application(application.getUuid())
                .position(application.getPositionUuid()));
        if (position.getHiringTrack() == RecruitmentHiringTrack.PARTNER) {
            builder.visibility(RecruitmentEventVisibility.CIRCLE);
        }
        return builder;
    }

    /** The first stage of the position's stage set (SCREENING by construction). */
    private static RecruitmentStage firstStageOf(RecruitmentPosition position) {
        List<String> stageSet = position.getStageSet();
        if (stageSet == null || stageSet.isEmpty()) {
            return RecruitmentStage.SCREENING;
        }
        return RecruitmentStage.valueOf(stageSet.get(0));
    }

    private static Map<String, RecruitmentPosition> positionsOf(
            List<RecruitmentApplication> applications) {
        if (applications.isEmpty()) {
            return Map.of();
        }
        List<String> uuids = applications.stream()
                .map(RecruitmentApplication::getPositionUuid)
                .distinct()
                .toList();
        return RecruitmentPosition.<RecruitmentPosition>list("uuid in ?1", uuids).stream()
                .collect(Collectors.toMap(RecruitmentPosition::getUuid, p -> p));
    }

    private static String titleOf(RecruitmentPosition position) {
        return position != null ? position.getTitle() : null;
    }
}
