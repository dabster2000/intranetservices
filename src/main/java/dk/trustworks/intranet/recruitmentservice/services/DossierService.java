package dk.trustworks.intranet.recruitmentservice.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.documentservice.model.DocumentTemplateEntity;
import dk.trustworks.intranet.recruitmentservice.dto.AppendixDto;
import dk.trustworks.intranet.recruitmentservice.dto.DossierRequest;
import dk.trustworks.intranet.recruitmentservice.dto.DossierResponse;
import dk.trustworks.intranet.recruitmentservice.dto.SignerConfigDto;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventBuilder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventRecorder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventVisibility;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossier;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossierAppendix;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossierRevision;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.DossierStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentHiringTrack;
import dk.trustworks.intranet.recruitmentservice.model.exception.BusinessRuleViolation;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Application service for the {@link CandidateDossier} aggregate. Handles
 * creation (and reopening) of the dossier, autosave of the JSON draft state
 * (placeholders + signers + appendices), appendix CRUD with filename
 * sanitisation, and read-side projection to the {@link DossierResponse} DTO.
 * <p>
 * Send actions live on {@link DossierRevisionService}, not here, so this
 * service stays focused on draft-state mutation only.
 */
@JBossLog
@ApplicationScoped
public class DossierService {

    /** Maximum allowed appendix display index — bound matches what fits comfortably on the dossier UI. */
    private static final int MAX_APPENDIX_ORDER = 99;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    DossierTemplateResolver templateResolver;

    @Inject
    RecruitmentEventRecorder eventRecorder;

    /**
     * Look up the open dossier for the given (candidate, template) pair, if
     * one exists. Returns {@link Optional#empty()} for a candidate that has
     * no dossier on that template — the caller decides whether to 404.
     */
    public Optional<DossierResponse> findByCandidateAndTemplate(UUID candidateUuid, UUID templateUuid) {
        return CandidateDossier
                .<CandidateDossier>find("candidateUuid = ?1 AND templateUuid = ?2",
                        candidateUuid.toString(), templateUuid.toString())
                .firstResultOptional()
                .map(this::toResponse);
    }

    /**
     * Load the (unique) dossier for a candidate. Per spec there is one
     * dossier per candidate per template, and each candidate is created with
     * exactly one dossier — so in practice this returns at most one row.
     */
    public Optional<DossierResponse> loadForCandidate(UUID candidateUuid) {
        return CandidateDossier
                .<CandidateDossier>find("candidateUuid = ?1", Sort.descending("createdAt"),
                        candidateUuid.toString())
                .firstResultOptional()
                .map(this::toResponse);
    }

    /**
     * Open the offer dossier for an existing candidate — the manual
     * recruiter step {@code RecruitmentOfferBridge.onOfferEntered} has always
     * deferred to ("dossier creation stays a manual recruiter step on the
     * profile's Offer &amp; Contract tab") but which had no endpoint until
     * now. Until this shipped, a candidate who reached OFFER without having
     * been created through the dossier path had no way to ever get one: the
     * only {@code new CandidateDossier()} in {@code src/main} lived inside
     * {@link CandidateService#createCandidate}.
     *
     * <h3>Guards, in the contract's order</h3>
     * Authorization (the dossier flag and the ADMIN/HR write gate) is the
     * resource's job and runs first. Everything below is domain state and
     * lives here, in {@link #resolveReopenTarget}: templateUuid present (400
     * {@code TEMPLATE_REQUIRED}), template resolves and is active (400
     * {@code TEMPLATE_NOT_FOUND} / {@code TEMPLATE_INACTIVE}), candidate
     * ACTIVE (409 {@code CANDIDATE_NOT_ACTIVE}), no OPEN dossier (409
     * {@code DOSSIER_EXISTS}), a CLOSED dossier on the same template is
     * reopened, a CLOSED dossier on a different template refuses (409).
     *
     * <h3>Why the whole command is one transaction</h3>
     * {@code RecruitmentAtomicCreateStructureTest} forbids a class-level
     * {@code @Transactional} on {@code RecruitmentResource}, so composing
     * the insert and the event append up there would commit a dossier and
     * then possibly fail the append — a contract dossier the timeline never
     * mentions. Both halves therefore live in this one method, at the
     * default REQUIRED propagation, exactly like the atomic candidate
     * create.
     *
     * @param candidateUuid the candidate to open the dossier for; already
     *                      resolved and authorized by the resource
     * @param templateUuid  the caller's chosen {@code document_templates.uuid}
     * @param actor         the acting user (from {@code X-Requested-By})
     * @return the dossier as {@code GET} would return it
     * @throws NotFoundException       if the candidate does not exist
     * @throws WebApplicationException 400/409 with a {@code {error, message}}
     *                                 body the dialog renders verbatim
     */
    @Transactional
    public DossierResponse createForCandidate(UUID candidateUuid, String templateUuid, UUID actor) {
        Objects.requireNonNull(actor, "actor must not be null");

        String trimmedTemplateUuid = templateUuid == null ? null : templateUuid.trim();
        // Read, do not validate: resolveReopenTarget owns the whole guard
        // chain so its order — which the contract pins — is decided in one
        // place the DB-free tier can exercise end to end.
        DocumentTemplateEntity template = trimmedTemplateUuid == null || trimmedTemplateUuid.isEmpty()
                ? null
                : DocumentTemplateEntity.findById(trimmedTemplateUuid);
        RecruitmentCandidate candidate = requireCandidate(candidateUuid);
        List<CandidateDossier> existing = CandidateDossier
                .<CandidateDossier>list("candidateUuid = ?1", candidate.getUuid());

        CandidateDossier reopenTarget = resolveReopenTarget(
                trimmedTemplateUuid, template, candidate, existing);
        boolean reopened = reopenTarget != null;

        CandidateDossier dossier;
        if (reopened) {
            dossier = reopenTarget;
            dossier.reopen();
        } else {
            dossier = new CandidateDossier();
            dossier.setCandidateUuid(candidate.getUuid());
            // The template's OWN uuid, never the request string: the row then
            // always points at a template that exists.
            dossier.setTemplateUuid(template.getUuid());
            dossier.setStatus(DossierStatus.OPEN);
            dossier.setSignersConfigJson(templateResolver.seedSignersFromTemplate(template.getUuid(), candidate.getTargetCompanyUuid()));
            CandidateDossier.persist(dossier);
        }

        // The open application (if any) is context for the timeline, and on a
        // PARTNER-track position it is also what decides the event's secrecy.
        RecruitmentApplication application =
                RecruitmentApplicationService.openApplicationOf(candidate.getUuid());
        RecruitmentPosition position = application == null
                ? null
                : RecruitmentPosition.findById(application.getPositionUuid());
        eventRecorder.record(dossierCreatedEvent(
                candidate, dossier, reopened, application, position, actor));

        log.infof("DOSSIER_CREATED candidate=%s dossier=%s template=%s reopened=%s by actor=%s",
                candidate.getUuid(), dossier.getUuid(), dossier.getTemplateUuid(), reopened, actor);

        return toResponse(dossier);
    }

    /**
     * The create-dossier guard chain, pure so every branch is reachable on
     * the DB-free tier (which is the deploy gate).
     *
     * <h3>Why a CLOSED dossier reopens rather than re-inserting</h3>
     * {@code uk_dossier_candidate_template UNIQUE (candidate_uuid,
     * template_uuid)} would turn a naive re-insert on the same template into
     * a duplicate-key 500, so that case reopens instead. A CLOSED dossier on
     * a <em>different</em> template refuses rather than adding a second one:
     * {@code S3EmployeePromotionService} groups revisions by dossier and
     * promotes per dossier, so a second dossier multiplies what lands in the
     * employee record at hire. No candidate in production has ever had more
     * than one — this keeps it that way.
     *
     * @param templateUuid the (trimmed) template reference the caller supplied
     * @param template     what it resolved to, or {@code null}
     * @param candidate    the candidate the dossier is for
     * @param existing     every dossier that candidate already has
     * @return the CLOSED dossier to reopen, or {@code null} to insert a new one
     */
    static CandidateDossier resolveReopenTarget(String templateUuid,
                                                DocumentTemplateEntity template,
                                                RecruitmentCandidate candidate,
                                                List<CandidateDossier> existing) {
        DocumentTemplateEntity usable = DossierTemplateResolver.requireUsable(templateUuid, template);

        if (candidate.getStatus() != CandidateStatus.ACTIVE) {
            throw dossierConflict("CANDIDATE_NOT_ACTIVE",
                    "This candidate is " + candidate.getStatus()
                            + " — reactivate them before opening a contract dossier.");
        }

        // Rule 6 scans ALL existing dossiers before rules 7/8 look at any one
        // of them: an OPEN dossier is an answer on its own, whichever
        // template it is on.
        for (CandidateDossier dossier : existing) {
            if (dossier.getStatus() == DossierStatus.OPEN) {
                throw dossierConflict("DOSSIER_EXISTS",
                        "This candidate already has an open dossier — open it instead of creating another.");
            }
        }
        for (CandidateDossier dossier : existing) {
            if (usable.getUuid().equals(dossier.getTemplateUuid())) {
                return dossier;
            }
        }
        if (!existing.isEmpty()) {
            throw dossierConflict("DOSSIER_EXISTS",
                    "This candidate already has a dossier on template "
                            + existing.get(0).getTemplateUuid()
                            + " — reopen that one rather than starting a second.");
        }
        return null;
    }

    /**
     * The {@code DOSSIER_CREATED} event. Payload is structural only — no
     * salary, no names, no email (the PII fixture forbids them, spec §3.3),
     * and this command has no personal data to record in the first place.
     *
     * @param position the open application's position, or {@code null}
     */
    static RecruitmentEventBuilder dossierCreatedEvent(RecruitmentCandidate candidate,
                                                       CandidateDossier dossier,
                                                       boolean reopened,
                                                       RecruitmentApplication application,
                                                       RecruitmentPosition position,
                                                       UUID actor) {
        RecruitmentEventBuilder event = RecruitmentEventBuilder
                .event(RecruitmentEventType.DOSSIER_CREATED)
                .candidate(candidate.getUuid())
                .actorUser(actor.toString())
                .payload("template_uuid", dossier.getTemplateUuid())
                .payload("dossier_uuid", dossier.getUuid())
                .payload("reopened", reopened);
        if (application != null) {
            event.application(application.getUuid())
                    .position(application.getPositionUuid())
                    .payload("application_uuid", application.getUuid())
                    .payload("stage", application.getStage() == null
                            ? null : application.getStage().name());
        }
        if (position != null && position.getHiringTrack() == RecruitmentHiringTrack.PARTNER) {
            // BOTH stamps are load-bearing: the readers of a CIRCLE event
            // (RecruitmentTimelineService.isVisible, RecruitmentLandingService)
            // resolve the circle FROM the event's position, so a CIRCLE event
            // with a null position fails closed for everyone — including the
            // HR user who just created the dossier. Same pairing as
            // CandidateService.createCandidate and
            // RecruitmentApplicationService.applicationEvent.
            event.position(position.getUuid())
                    .visibility(RecruitmentEventVisibility.CIRCLE);
        }
        return event;
    }

    /**
     * Swap the template of an OPEN dossier that has never been sent — the
     * misclick escape hatch: the create dialog's template picker has no
     * default and a wrong pick previously locked the candidate onto the
     * wrong contract forever ({@code DOSSIER_EXISTS} blocks a second
     * dossier, and reopen only matches the SAME template).
     *
     * <p>Only allowed while the dossier has ZERO revisions: the first send
     * snapshots the template into a revision, and after that the honest
     * correction is branching. What happens on a change:</p>
     * <ul>
     *   <li>signers re-seed from the new template's defaults (the old
     *       config referenced the old template's {@code ${...}} signer
     *       fields);</li>
     *   <li>clause selections clear (offers are per-template links);</li>
     *   <li>placeholder values and appendices are KEPT — shared keys
     *       (employee name, dates) carry over, template-specific keys the
     *       new template does not declare are simply never rendered.</li>
     * </ul>
     *
     * @throws BusinessRuleViolation   if the dossier is CLOSED
     * @throws WebApplicationException 400/409 per
     *                                 {@link #resolveTemplateChange}
     */
    @Transactional
    public DossierResponse changeTemplate(UUID candidateUuid, String templateUuid, UUID actor) {
        Objects.requireNonNull(actor, "actor must not be null");

        String trimmed = templateUuid == null ? null : templateUuid.trim();
        DocumentTemplateEntity template = trimmed == null || trimmed.isEmpty()
                ? null
                : DocumentTemplateEntity.findById(trimmed);
        RecruitmentCandidate candidate = requireCandidate(candidateUuid);
        CandidateDossier dossier = CandidateDossier
                .<CandidateDossier>find("candidateUuid = ?1", candidate.getUuid())
                .firstResultOptional()
                .orElseThrow(() -> new NotFoundException(
                        "Dossier not found for candidate: " + candidateUuid));
        guardOpen(dossier, "change template of");

        long revisionCount = CandidateDossierRevision.count("dossierUuid", dossier.getUuid());
        // uk_dossier_candidate_template would turn a swap onto a template a
        // CLOSED dossier already sits on into a duplicate-key 500.
        boolean targetTaken = template != null && CandidateDossier.count(
                "candidateUuid = ?1 AND templateUuid = ?2 AND uuid <> ?3",
                candidate.getUuid(), template.getUuid(), dossier.getUuid()) > 0;

        DocumentTemplateEntity usable =
                resolveTemplateChange(trimmed, template, revisionCount, targetTaken);
        if (usable.getUuid().equals(dossier.getTemplateUuid())) {
            // Idempotent no-op: picking the template the dossier is already on
            // changes nothing and records nothing.
            return toResponse(dossier);
        }

        String oldTemplateUuid = dossier.getTemplateUuid();
        dossier.setTemplateUuid(usable.getUuid());
        dossier.setSignersConfigJson(templateResolver.seedSignersFromTemplate(
                usable.getUuid(), candidate.getTargetCompanyUuid()));
        dossier.setClausesJson(null);

        RecruitmentApplication application =
                RecruitmentApplicationService.openApplicationOf(candidate.getUuid());
        RecruitmentPosition position = application == null
                ? null
                : RecruitmentPosition.findById(application.getPositionUuid());
        eventRecorder.record(dossierTemplateChangedEvent(
                candidate, dossier, oldTemplateUuid, application, position, actor));

        log.infof("DOSSIER_TEMPLATE_CHANGED candidate=%s dossier=%s template=%s->%s by actor=%s",
                candidate.getUuid(), dossier.getUuid(), oldTemplateUuid, usable.getUuid(), actor);
        return toResponse(dossier);
    }

    /**
     * The change-template guard chain, pure so every branch is reachable on
     * the DB-free tier (the {@link #resolveReopenTarget} pattern). Ordering:
     * template validity first (a broken reference is a 400 whatever the
     * dossier's history), then the revision lock, then the unique-pair
     * collision.
     *
     * @param templateUuid  the (trimmed) template reference the caller supplied
     * @param template      what it resolved to, or {@code null}
     * @param revisionCount how many revisions the dossier already has
     * @param targetTaken   whether ANOTHER dossier of this candidate already
     *                      sits on the target template
     * @return the resolved, active target template
     */
    static DocumentTemplateEntity resolveTemplateChange(String templateUuid,
                                                        DocumentTemplateEntity template,
                                                        long revisionCount,
                                                        boolean targetTaken) {
        DocumentTemplateEntity usable = DossierTemplateResolver.requireUsable(templateUuid, template);
        if (revisionCount > 0) {
            throw dossierConflict("DOSSIER_HAS_REVISIONS",
                    "This dossier has already been sent — the template is locked into its "
                            + "revision history. Branch from a revision instead.");
        }
        if (targetTaken) {
            throw dossierConflict("DOSSIER_EXISTS",
                    "This candidate already has a closed dossier on that template — "
                            + "reopen that one instead of re-pointing this dossier at it.");
        }
        return usable;
    }

    /**
     * The {@code DOSSIER_TEMPLATE_CHANGED} event. Structural payload only —
     * old and new template + dossier — with the same application context and
     * PARTNER-track CIRCLE pairing as {@link #dossierCreatedEvent}: both
     * stamps stay load-bearing for CIRCLE readers, and the secrecy of a
     * partner search must not depend on which dossier command ran last.
     */
    static RecruitmentEventBuilder dossierTemplateChangedEvent(RecruitmentCandidate candidate,
                                                               CandidateDossier dossier,
                                                               String oldTemplateUuid,
                                                               RecruitmentApplication application,
                                                               RecruitmentPosition position,
                                                               UUID actor) {
        RecruitmentEventBuilder event = RecruitmentEventBuilder
                .event(RecruitmentEventType.DOSSIER_TEMPLATE_CHANGED)
                .candidate(candidate.getUuid())
                .actorUser(actor.toString())
                .payload("dossier_uuid", dossier.getUuid())
                .payload("old_template_uuid", oldTemplateUuid)
                .payload("template_uuid", dossier.getTemplateUuid());
        if (application != null) {
            event.application(application.getUuid())
                    .position(application.getPositionUuid())
                    .payload("application_uuid", application.getUuid());
        }
        if (position != null && position.getHiringTrack() == RecruitmentHiringTrack.PARTNER) {
            event.position(position.getUuid())
                    .visibility(RecruitmentEventVisibility.CIRCLE);
        }
        return event;
    }

    /**
     * Apply autosave updates to the dossier's JSON draft state. The request
     * payload is partial — only non-null fields on {@code req} are written;
     * the others are left as-is on the entity.
     *
     * @throws BusinessRuleViolation if the dossier is CLOSED — autosave on a
     *                               closed dossier indicates a stale UI and
     *                               we surface 409 to force a reload.
     */
    @Transactional
    public DossierResponse update(UUID dossierUuid, DossierRequest req, UUID actor) {
        Objects.requireNonNull(req, "req must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        CandidateDossier dossier = requireDossier(dossierUuid);
        guardOpen(dossier, "update");

        if (req.placeholderValues() != null) {
            dossier.setPlaceholderValuesJson(writeJson(req.placeholderValues()));
        }
        if (req.signersConfig() != null) {
            dossier.setSignersConfigJson(writeJson(req.signersConfig()));
        }
        if (req.clauses() != null) {
            dossier.setClausesJson(writeJson(req.clauses()));
        }
        log.debugf("Autosaved dossier uuid=%s by actor=%s", dossier.getUuid(), actor);
        return toResponse(dossier);
    }

    /**
     * Add an appendix to a dossier. The {@code originalFilename} is sanitised
     * via {@link Path#getFileName()} and rejected if it contains path-traversal
     * sequences or non-printable characters. The S3 file UUID is the caller's
     * responsibility — the file must already be uploaded by the time this
     * method is called.
     */
    @Transactional
    public AppendixDto addAppendix(UUID dossierUuid, String originalFilename, String fileUuid,
                                   boolean signObligated, UUID actor) {
        Objects.requireNonNull(originalFilename, "originalFilename must not be null");
        Objects.requireNonNull(fileUuid, "fileUuid must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        CandidateDossier dossier = requireDossier(dossierUuid);
        guardOpen(dossier, "addAppendix");

        String sanitised = sanitiseFilename(originalFilename);

        int nextOrder = nextDisplayOrder(dossier.getUuid());

        CandidateDossierAppendix appendix = new CandidateDossierAppendix();
        appendix.setDossierUuid(dossier.getUuid());
        appendix.setFileUuid(fileUuid);
        appendix.setOriginalFilename(sanitised);
        appendix.setDisplayOrder(nextOrder);
        appendix.setSignObligated(signObligated);
        appendix.setUploadedByUseruuid(actor.toString());
        CandidateDossierAppendix.persist(appendix);

        log.infof("Added appendix uuid=%s file=%s signObligated=%s to dossier uuid=%s",
                appendix.getUuid(), fileUuid, signObligated, dossier.getUuid());

        return new AppendixDto(appendix.getUuid(), fileUuid, sanitised, nextOrder, signObligated);
    }

    /**
     * Remove an appendix from a dossier by its S3 {@code fileUuid}. Idempotent
     * if the file is not present on the dossier (no rows deleted).
     */
    @Transactional
    public void removeAppendix(UUID dossierUuid, String fileUuid, UUID actor) {
        Objects.requireNonNull(fileUuid, "fileUuid must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        CandidateDossier dossier = requireDossier(dossierUuid);
        guardOpen(dossier, "removeAppendix");
        long deleted = CandidateDossierAppendix.delete(
                "dossierUuid = ?1 AND fileUuid = ?2", dossier.getUuid(), fileUuid);
        if (deleted > 0) {
            log.infof("Removed appendix file=%s from dossier uuid=%s", fileUuid, dossier.getUuid());
        }
    }

    /**
     * Replace the open dossier's draft state with a frozen revision snapshot
     * (placeholders, signers, appendices). Past revisions stay immutable —
     * only the current draft is modified.
     *
     * <p>Appendices are stored in a separate child table; this method
     * deletes all current appendix rows and re-inserts new rows derived from
     * the revision's {@code appendicesSnapshot}. The snapshot's S3 fileUuids
     * are preserved on the new rows; if any of those S3 objects have been
     * reaped, a subsequent Send action will fail with a clear error.
     *
     * @return refreshed {@link DossierResponse}
     * @throws NotFoundException if revision doesn't belong to this candidate
     * @throws WebApplicationException 409 if dossier is CLOSED or candidate
     *         is in a terminal state
     */
    @Transactional
    public DossierResponse branchFromRevision(UUID candidateUuid, UUID revisionUuid, UUID actor) {
        Objects.requireNonNull(actor, "actor must not be null");

        RecruitmentCandidate candidate = requireCandidate(candidateUuid);
        if (candidate.getStatus() == CandidateStatus.HIRED
                || candidate.getStatus() == CandidateStatus.DECLINED
                || candidate.getStatus() == CandidateStatus.WITHDRAWN) {
            throw new WebApplicationException(
                    "Cannot branch — candidate is in terminal state " + candidate.getStatus(),
                    Response.Status.CONFLICT);
        }

        CandidateDossier dossier = CandidateDossier
                .<CandidateDossier>find("candidateUuid = ?1 AND status = ?2",
                        candidate.getUuid(), DossierStatus.OPEN)
                .firstResult();
        if (dossier == null) {
            throw new WebApplicationException(
                    "Cannot branch — no OPEN dossier on candidate " + candidateUuid,
                    Response.Status.CONFLICT);
        }

        CandidateDossierRevision rev = CandidateDossierRevision.findById(revisionUuid.toString());
        if (rev == null || !rev.getDossierUuid().equals(dossier.getUuid())) {
            throw new NotFoundException(
                    "Revision " + revisionUuid + " does not belong to candidate " + candidateUuid);
        }

        // 1) Overwrite placeholder + signer + clause drafts.
        dossier.setPlaceholderValuesJson(rev.getPlaceholderValuesSnapshot());
        dossier.setSignersConfigJson(rev.getSignersConfigSnapshot());
        dossier.setClausesJson(rev.getClausesSnapshot());

        // 2) Replace appendix rows with what the snapshot recorded.
        CandidateDossierAppendix.delete("dossierUuid", dossier.getUuid());
        List<AppendixDto> snapshotAppendices = readJson(
                rev.getAppendicesSnapshot(), new TypeReference<>() {});
        if (snapshotAppendices != null) {
            for (AppendixDto snap : snapshotAppendices) {
                CandidateDossierAppendix appendix = new CandidateDossierAppendix();
                appendix.setDossierUuid(dossier.getUuid());
                appendix.setFileUuid(snap.fileUuid());
                appendix.setOriginalFilename(snap.originalFilename());
                appendix.setDisplayOrder(snap.displayOrder());
                appendix.setSignObligated(snap.signObligated());
                appendix.setUploadedByUseruuid(actor.toString());
                CandidateDossierAppendix.persist(appendix);
            }
        }

        log.infof("BRANCHED_FROM_REVISION candidate=%s revision=%s versionNumber=%d actor=%s",
                candidate.getUuid(), rev.getUuid(), rev.getVersionNumber(), actor);

        return toResponse(dossier);
    }

    // ---- helpers ---------------------------------------------------------------

    private static RecruitmentCandidate requireCandidate(UUID candidateUuid) {
        RecruitmentCandidate candidate = RecruitmentCandidate.findById(candidateUuid.toString());
        if (candidate == null) {
            throw new NotFoundException("Candidate not found: " + candidateUuid);
        }
        return candidate;
    }

    /**
     * 409 with the contract's {@code {error, message}} body — the create
     * dialog renders both fields verbatim, so the message is written for the
     * recruiter rather than for a log. Same shape as
     * {@code RecruitmentOfferBridge.assertSignatureSendAllowed} and
     * {@code RecruitmentCandidateHardDeleteService.requireDeletable}.
     */
    private static WebApplicationException dossierConflict(String code, String message) {
        return new WebApplicationException(Response.status(Response.Status.CONFLICT)
                .entity(Map.of("error", code, "message", message))
                .build());
    }

    private CandidateDossier requireDossier(UUID dossierUuid) {
        CandidateDossier d = CandidateDossier.findById(dossierUuid.toString());
        if (d == null) {
            throw new NotFoundException("Dossier not found: " + dossierUuid);
        }
        return d;
    }

    private void guardOpen(CandidateDossier dossier, String operation) {
        if (dossier.getStatus() != DossierStatus.OPEN) {
            throw new BusinessRuleViolation(
                    "Cannot %s dossier %s: status is CLOSED".formatted(operation, dossier.getUuid()));
        }
    }

    /**
     * Sanitise an appendix filename: strip path components (defensive against
     * upload payloads that include directory prefixes), reject {@code ..},
     * leading slashes, and any non-printable / control characters before the
     * value can land in a storage key or URL.
     *
     * @throws IllegalArgumentException if the name contains forbidden patterns
     */
    static String sanitiseFilename(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("originalFilename must not be empty");
        }
        if (trimmed.startsWith("/") || trimmed.startsWith("\\")) {
            throw new IllegalArgumentException("originalFilename must not start with a path separator");
        }
        if (trimmed.contains("..")) {
            throw new IllegalArgumentException("originalFilename must not contain '..'");
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            // Reject ASCII control chars (0x00-0x1F, 0x7F) and the bidi/format chars
            // most commonly used to bypass filename filters.
            if (c < 0x20 || c == 0x7F) {
                throw new IllegalArgumentException("originalFilename must not contain control characters");
            }
        }
        try {
            // Path.getFileName() drops any directory prefix the client sneaked in.
            Path leaf = Paths.get(trimmed).getFileName();
            if (leaf == null) {
                throw new IllegalArgumentException("originalFilename has no leaf component");
            }
            String name = leaf.toString();
            if (name.equals("..") || name.equals(".")) {
                throw new IllegalArgumentException("originalFilename must not be a relative path component");
            }
            return name;
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("originalFilename is not a valid path: " + e.getMessage());
        }
    }

    private int nextDisplayOrder(String dossierUuid) {
        // Use MAX+1 — display_order is unique per dossier, and a count-based
        // value would collide after delete-then-add.
        Integer max = CandidateDossierAppendix.<CandidateDossierAppendix>find(
                        "dossierUuid", Sort.descending("displayOrder"), dossierUuid)
                .firstResultOptional()
                .map(CandidateDossierAppendix::getDisplayOrder)
                .orElse(0);
        int next = Math.min(max + 1, MAX_APPENDIX_ORDER);
        return Math.max(next, 1);
    }

    private DossierResponse toResponse(CandidateDossier dossier) {
        Map<String, String> placeholderValues = readJson(
                dossier.getPlaceholderValuesJson(),
                new TypeReference<>() {
                });
        List<SignerConfigDto> signersConfig = readJson(
                dossier.getSignersConfigJson(),
                new TypeReference<>() {
                });
        List<AppendixDto> appendices = CandidateDossierAppendix
                .<CandidateDossierAppendix>find("dossierUuid", Sort.ascending("displayOrder"), dossier.getUuid())
                .stream()
                .map(a -> new AppendixDto(a.getUuid(), a.getFileUuid(), a.getOriginalFilename(),
                        a.getDisplayOrder(), a.isSignObligated()))
                .toList();
        List<dk.trustworks.intranet.utils.dto.signing.SelectedClauseDTO> clauses = readJson(
                dossier.getClausesJson(),
                new TypeReference<>() {
                });
        return new DossierResponse(
                dossier.getUuid(),
                dossier.getCandidateUuid(),
                dossier.getTemplateUuid(),
                placeholderValues != null ? placeholderValues : Map.of(),
                signersConfig != null ? signersConfig : List.of(),
                dossier.getStatus(),
                appendices,
                clauses != null ? clauses : List.of(),
                dossier.getCreatedAt(),
                dossier.getUpdatedAt()
        );
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw CandidateService.jsonError("write", e);
        }
    }

    private <T> T readJson(String raw, TypeReference<T> ref) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, ref);
        } catch (JsonProcessingException e) {
            // Re-throw: silent fallback to empty would let the autosave PUT
            // overwrite the (still-valid) DB JSON with empty maps.
            throw CandidateService.jsonError("read", e);
        }
    }

    /**
     * Used by sister services and the recruitment resource to pull a dossier
     * together with its draft snapshots in one place. Deliberately not on the
     * entity — entity stays raw-JSON pure.
     */
    public Map<String, String> currentPlaceholderValues(CandidateDossier dossier) {
        Map<String, String> v = readJson(dossier.getPlaceholderValuesJson(), new TypeReference<>() {
        });
        if (v == null || v.isEmpty()) {
            log.warnf("Dossier %s has no persisted placeholder values — PDFs will render with blank substitutions",
                    dossier.getUuid());
            return Collections.emptyMap();
        }
        return new LinkedHashMap<>(v);
    }

    public List<SignerConfigDto> currentSignersConfig(CandidateDossier dossier) {
        List<SignerConfigDto> v = readJson(dossier.getSignersConfigJson(), new TypeReference<>() {
        });
        return v == null ? List.of() : v;
    }

    /** The draft's clause selection (template-clauses Phase 2); empty when none. */
    public List<dk.trustworks.intranet.utils.dto.signing.SelectedClauseDTO> currentClauses(CandidateDossier dossier) {
        List<dk.trustworks.intranet.utils.dto.signing.SelectedClauseDTO> v =
                readJson(dossier.getClausesJson(), new TypeReference<>() {
                });
        return v == null ? List.of() : v;
    }

    /**
     * Read-side helper used by the revision service and resource to produce
     * the appendix snapshot for a Send action. Returns the appendices in
     * display-order.
     */
    public List<AppendixDto> currentAppendices(String dossierUuid) {
        return CandidateDossierAppendix
                .<CandidateDossierAppendix>find("dossierUuid", Sort.ascending("displayOrder"), dossierUuid)
                .stream()
                .map(a -> new AppendixDto(a.getUuid(), a.getFileUuid(), a.getOriginalFilename(),
                        a.getDisplayOrder(), a.isSignObligated()))
                .toList();
    }

    /**
     * Resolve the candidate's email — the locked review-email recipient. The
     * candidate is loaded fresh because the dossier knows only the candidate
     * UUID.
     */
    String requireCandidateEmail(String candidateUuid) {
        RecruitmentCandidate c = RecruitmentCandidate.findById(candidateUuid);
        if (c == null) {
            throw new NotFoundException("Candidate not found: " + candidateUuid);
        }
        return c.getEmail();
    }
}
