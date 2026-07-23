package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventBuilder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventRecorder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventVisibility;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossier;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentHiringTrack;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentStage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The P10 bridge between the ATS pipeline (applications, stages) and the
 * pre-ATS dossier/signing/conversion machinery. Four responsibilities, all
 * appending events through {@link RecruitmentEventRecorder} <em>in the
 * caller's transaction</em> (same-tx command-path appends — the P4
 * TEAM_ASSIGNED idiom, never reactors):
 * <ol>
 *   <li><b>OFFER entry</b> ({@link #onOfferEntered}) — called from
 *       {@code RecruitmentApplicationService.changeStage} after every
 *       successful move into OFFER. Emits {@code OFFER_OPENED} and links an
 *       EXISTING dossier if one exists. Deliberately link-only: dossiers
 *       require a document template ({@code template_uuid NOT NULL}) and no
 *       position→template resolution rule exists, so dossier creation stays
 *       a manual recruiter step on the profile's Offer &amp; Contract tab.</li>
 *   <li><b>Signature-send gate</b> ({@link #assertSignatureSendAllowed}) —
 *       called from {@code RecruitmentResource.sendSignature} in its
 *       fail-fast zone, BEFORE the NextSign call. Blocks the send with 409
 *       {@code TEAM_NOT_ASSIGNED} when a practice-track application sits at
 *       OFFER without a team decision (spec §5.4).</li>
 *   <li><b>Signing completion</b> ({@link #recordSigningCompletedIfNew}) —
 *       called from the {@code RecruitmentSignatureCompletionListener}
 *       batchlet per completed dossier-linked case. Idempotency is
 *       DB-enforced: an atomic {@code INSERT IGNORE} claim on the
 *       {@code case_key} PK in {@code recruitment_signing_completed_cases}
 *       gates the append (the batchlet's in-memory dedup set is
 *       restart-lossy and stays email-only).</li>
 *   <li><b>Conversion</b> ({@link #onCandidateConverted}) — called from
 *       {@code CandidateConversionUseCase.execute} after the candidate's own
 *       {@code markHired}. Marks the OFFER application(s) HIRED and appends
 *       {@code APPLICATION_STAGE_CHANGED} + {@code CANDIDATE_HIRED}.</li>
 * </ol>
 * Partner-track positions stamp {@code visibility=CIRCLE} on every emitted
 * event (P2 carry-over); payloads carry structural facts only — never
 * salary, names or other personal data (the PII fixture forbids them).
 */
@JBossLog
@ApplicationScoped
public class RecruitmentOfferBridge {

    @Inject
    RecruitmentEventRecorder eventRecorder;

    @Inject
    EntityManager em;

    // ---- B1a: OFFER entry → OFFER_OPENED + dossier link ------------------------

    /**
     * Emit {@code OFFER_OPENED} for an application that just entered the
     * OFFER stage. Called by {@code changeStage} post-move in the same
     * transaction, for EVERY entry into OFFER — including re-entries after a
     * back-move and fast-track skips. The timeline is a truthful log of
     * entries; the dossier "link" is a lookup, so nothing duplicates.
     *
     * @param application the application that just moved (stage already OFFER)
     * @param position    its position (hydrated by the caller)
     * @param fromStage   the stage the application moved from
     * @param actor       the acting user (from X-Requested-By)
     */
    public void onOfferEntered(RecruitmentApplication application,
                               RecruitmentPosition position,
                               RecruitmentStage fromStage, UUID actor) {
        Objects.requireNonNull(actor, "actor must not be null");
        Optional<CandidateDossier> dossier = newestDossierOf(application.getCandidateUuid());

        RecruitmentEventBuilder event = applicationEvent(
                RecruitmentEventType.OFFER_OPENED, application, position)
                .actorUser(actor.toString())
                .payload("position_title", position.getTitle())
                .payload("dossier_linked", dossier.isPresent())
                .payload("from_stage", fromStage.name());
        dossier.ifPresent(d -> event.payload("dossier_uuid", d.getUuid()));
        eventRecorder.record(event);

        log.infof("OFFER_OPENED for application %s (candidate=%s, dossier_linked=%s) by actor=%s",
                application.getUuid(), application.getCandidateUuid(),
                dossier.isPresent(), actor);
    }

    // ---- B1b: signature-send gate ----------------------------------------------

    /**
     * Fail-fast gate for {@code POST /candidates/{uuid}/dossier/send-signature}:
     * a practice-track application at OFFER without a team decision blocks
     * the contract send with 409 {@code TEAM_NOT_ASSIGNED} (spec §5.4 — the
     * team must be decided before the contract goes out).
     * <p>
     * Explicitly non-blocking: candidates with no applications at all (the
     * pre-ATS dossier-only flow is untouched), open applications before
     * OFFER (legacy allows sending at any time), PARTNER/STAFF_ROLE tracks,
     * and already-HIRED applications ({@code stage = HIRED} never matches
     * {@code stage = OFFER}).
     *
     * @throws WebApplicationException 409 with JSON body
     *         {@code {error, message, guidance}} when the gate blocks
     */
    public void assertSignatureSendAllowed(RecruitmentCandidate candidate) {
        List<RecruitmentApplication> offerApplications = RecruitmentApplication.list(
                "candidateUuid = ?1 and terminal is null and stage = ?2",
                candidate.getUuid(), RecruitmentStage.OFFER);
        for (RecruitmentApplication application : offerApplications) {
            if (application.getAssignedTeamUuid() != null) {
                continue;
            }
            RecruitmentPosition position = RecruitmentPosition.findById(application.getPositionUuid());
            if (position != null
                    && position.getHiringTrack() == RecruitmentHiringTrack.PRACTICE_TEAM) {
                throw new WebApplicationException(Response
                        .status(Response.Status.CONFLICT)
                        .type(MediaType.APPLICATION_JSON)
                        .entity(Map.of(
                                "error", "TEAM_NOT_ASSIGNED",
                                "message", "This candidate is at Offer on a practice-track position with no team assigned yet — the contract cannot be sent before the team is decided.",
                                "guidance", "Assign a team on the pipeline board or the candidate's profile (any team can be chosen, including teams of other practices), then send the signature."))
                        .build());
            }
        }
    }

    // ---- B1c: SIGNING_COMPLETED append (durable idempotency) -------------------

    /**
     * Append {@code SIGNING_COMPLETED} for a completed, dossier-linked
     * signing case — once, durably. Idempotency is <b>DB-enforced</b>: an
     * atomic {@code INSERT IGNORE} claim on the {@code case_key} PRIMARY
     * KEY of {@code recruitment_signing_completed_cases} gates the append,
     * so two overlapping executions (e.g. the ECS Express cutover window
     * where old and new tasks run the batchlet concurrently) can never
     * both append — only the transaction whose claim insert affected a row
     * proceeds, and claim + event commit (or roll back) together in the
     * caller's transaction. The preceding event-store existence check on
     * the payload {@code case_key} is a fast path only (and a
     * belt-and-braces guard for pre-claim-table events).
     * <p>
     * Subjects: the candidate (from the dossier), plus application+position
     * when the candidate has an open application at OFFER.
     * <p>
     * Visibility is <b>fail-closed</b> (security finding MEDIUM-2):
     * dossiers/signing cases have no structural link to an application, so
     * the event is stamped {@code CIRCLE} when the candidate has ANY
     * application — open or terminal, any stage — on a PARTNER-track
     * position. Erring toward over-restriction is deliberate: a
     * CIRCLE-stamped event on a normal hire merely narrows one timeline
     * entry's audience, while the reverse would leak a confidential
     * partner-hire fact to recruiter-tier viewers.
     *
     * @return {@code true} if the event was appended, {@code false} if the
     *         case was already recorded/claimed (idempotent no-op) or the
     *         dossier is gone
     */
    public boolean recordSigningCompletedIfNew(String caseKey, String dossierUuid) {
        Objects.requireNonNull(caseKey, "caseKey must not be null");
        Objects.requireNonNull(dossierUuid, "dossierUuid must not be null");

        CandidateDossier dossier = CandidateDossier.findById(dossierUuid);
        if (dossier == null) {
            log.warnf("SIGNING_COMPLETED skipped: dossier %s for case %s not found",
                    dossierUuid, caseKey);
            return false;
        }
        String candidateUuid = dossier.getCandidateUuid();

        // Fast path (NOT the guarantee — the claim below is): skip the
        // claim insert when the event visibly exists already.
        Number existing = (Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM recruitment_events "
                                + "WHERE event_type = 'SIGNING_COMPLETED' "
                                + "  AND candidate_uuid = :candidate "
                                + "  AND JSON_UNQUOTE(JSON_EXTRACT(payload, '$.case_key')) = :caseKey")
                .setParameter("candidate", candidateUuid)
                .setParameter("caseKey", caseKey)
                .getSingleResult();
        if (existing.longValue() > 0) {
            return false;
        }

        // Atomic claim (MEDIUM-1): the case_key PK makes exactly ONE
        // transaction win; 0 affected rows = another transaction already
        // claimed (and is appending / has appended) this case.
        int claimed = em.createNativeQuery(
                        "INSERT IGNORE INTO recruitment_signing_completed_cases "
                                + "(case_key, candidate_uuid) VALUES (:caseKey, :candidate)")
                .setParameter("caseKey", caseKey)
                .setParameter("candidate", candidateUuid)
                .executeUpdate();
        if (claimed == 0) {
            log.infof("SIGNING_COMPLETED skipped: case %s already claimed (candidate=%s)",
                    caseKey, candidateUuid);
            return false;
        }

        RecruitmentEventBuilder event = RecruitmentEventBuilder
                .event(RecruitmentEventType.SIGNING_COMPLETED)
                .candidate(candidateUuid)
                .actorSystem()
                .payload("case_key", caseKey)
                .payload("dossier_uuid", dossierUuid);

        List<RecruitmentApplication> openApplications = RecruitmentApplication.list(
                "candidateUuid = ?1 and terminal is null", candidateUuid);
        RecruitmentApplication offerApplication = openApplications.stream()
                .filter(a -> a.getStage() == RecruitmentStage.OFFER)
                .findFirst()
                .orElse(null);
        if (offerApplication != null) {
            event.application(offerApplication.getUuid())
                    .position(offerApplication.getPositionUuid());
        }

        // Fail-closed CIRCLE (MEDIUM-2): ANY application the candidate has
        // ever held on a PARTNER-track position — open or terminal — makes
        // the completion circle-scoped.
        Long partnerApplications = em.createQuery(
                        "select count(a) from RecruitmentApplication a, RecruitmentPosition p "
                                + "where p.uuid = a.positionUuid "
                                + "  and a.candidateUuid = :candidate "
                                + "  and p.hiringTrack = :track", Long.class)
                .setParameter("candidate", candidateUuid)
                .setParameter("track", RecruitmentHiringTrack.PARTNER)
                .getSingleResult();
        if (partnerApplications > 0) {
            event.visibility(RecruitmentEventVisibility.CIRCLE);
        }

        eventRecorder.record(event);
        log.infof("SIGNING_COMPLETED appended for case=%s candidate=%s dossier=%s",
                caseKey, candidateUuid, dossierUuid);
        return true;
    }

    // ---- B1d: conversion → application HIRED + CANDIDATE_HIRED -----------------

    /**
     * Bridge a completed conversion into the applications aggregate and the
     * event stream. Runs inside the conversion's single transaction ("no
     * partial hires" — a bridge failure rolls the whole conversion back, so
     * state and events never diverge).
     * <ul>
     *   <li>Every open application at OFFER (expected 0..1) is marked HIRED
     *       via {@link RecruitmentApplication#markHired()} and gets an
     *       {@code APPLICATION_STAGE_CHANGED} (OFFER → HIRED, FORWARD).</li>
     *   <li>Open applications NOT at OFFER are left untouched — nothing
     *       auto-closes them today (spec is silent; documented follow-up).</li>
     *   <li>{@code CANDIDATE_HIRED} is ALWAYS appended — also for legacy
     *       dossier-only conversions with no application rows. Payload:
     *       {@code user_uuid}, {@code team_uuid}, {@code planned_start_date}.
     *       NO salary, NO names (PII fixture forbids them).</li>
     * </ul>
     *
     * @param candidate        the candidate that was just converted
     * @param userUuid         the new employee's user uuid
     * @param teamUuid         the team from the convert request (may be null)
     * @param plannedStartDate the planned start date from the convert request
     * @param actor            the user who triggered Convert
     */
    public void onCandidateConverted(RecruitmentCandidate candidate, String userUuid,
                                     String teamUuid, LocalDate plannedStartDate, UUID actor) {
        Objects.requireNonNull(actor, "actor must not be null");
        List<RecruitmentApplication> openApplications = RecruitmentApplication.list(
                "candidateUuid = ?1 and terminal is null", candidate.getUuid());

        RecruitmentApplication hiredApplication = null;
        RecruitmentPosition hiredPosition = null;
        for (RecruitmentApplication application : openApplications) {
            if (application.getStage() != RecruitmentStage.OFFER) {
                continue; // Leave non-OFFER applications open for a manual terminal.
            }
            RecruitmentPosition position = RecruitmentPosition.findById(application.getPositionUuid());
            application.markHired();
            eventRecorder.record(applicationEvent(
                    RecruitmentEventType.APPLICATION_STAGE_CHANGED, application, position)
                    .actorUser(actor.toString())
                    .payload("from", RecruitmentStage.OFFER.name())
                    .payload("to", RecruitmentStage.HIRED.name())
                    .payload("direction", RecruitmentApplication.MoveDirection.FORWARD.name())
                    .payload("skipped_stages", false));
            if (hiredApplication == null) {
                hiredApplication = application;
                hiredPosition = position;
            }
        }

        RecruitmentEventBuilder hired = RecruitmentEventBuilder
                .event(RecruitmentEventType.CANDIDATE_HIRED)
                .candidate(candidate.getUuid())
                .actorUser(actor.toString())
                .payload("user_uuid", userUuid)
                .payload("team_uuid", teamUuid)
                .payload("planned_start_date",
                        plannedStartDate != null ? plannedStartDate.toString() : null);
        if (hiredApplication != null) {
            hired.application(hiredApplication.getUuid())
                    .position(hiredApplication.getPositionUuid());
            if (hiredPosition != null
                    && hiredPosition.getHiringTrack() == RecruitmentHiringTrack.PARTNER) {
                hired.visibility(RecruitmentEventVisibility.CIRCLE);
            }
        }
        eventRecorder.record(hired);

        log.infof("CANDIDATE_HIRED appended for candidate=%s -> user=%s (offer applications hired=%d) by actor=%s",
                candidate.getUuid(), userUuid, hiredApplication != null ? 1 : 0, actor);
    }

    // ---- Helpers ---------------------------------------------------------------

    /** Newest dossier of the candidate, if any (in practice at most one row). */
    private static Optional<CandidateDossier> newestDossierOf(String candidateUuid) {
        return CandidateDossier
                .<CandidateDossier>find("candidateUuid = ?1 order by createdAt desc", candidateUuid)
                .firstResultOptional();
    }

    /**
     * Event skeleton with all three subjects and {@code visibility=CIRCLE}
     * on partner track — the {@code applicationEvent} idiom shared with
     * {@code RecruitmentApplicationService} (P4 carry-over). The actor is
     * stamped by the caller.
     */
    private static RecruitmentEventBuilder applicationEvent(RecruitmentEventType type,
                                                            RecruitmentApplication application,
                                                            RecruitmentPosition position) {
        RecruitmentEventBuilder builder = RecruitmentEventBuilder.event(type)
                .candidate(application.getCandidateUuid())
                .application(application.getUuid())
                .position(application.getPositionUuid());
        if (position != null
                && position.getHiringTrack() == RecruitmentHiringTrack.PARTNER) {
            builder.visibility(RecruitmentEventVisibility.CIRCLE);
        }
        return builder;
    }
}
