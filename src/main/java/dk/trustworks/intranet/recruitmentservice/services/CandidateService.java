package dk.trustworks.intranet.recruitmentservice.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.documentservice.model.TemplateDefaultSignerEntity;
import dk.trustworks.intranet.recruitmentservice.dto.CandidateApplicationInfo;
import dk.trustworks.intranet.recruitmentservice.dto.CandidateListResponse;
import dk.trustworks.intranet.recruitmentservice.dto.CandidateRequest;
import dk.trustworks.intranet.recruitmentservice.dto.CandidateResponse;
import dk.trustworks.intranet.recruitmentservice.dto.CandidateSummary;
import dk.trustworks.intranet.recruitmentservice.dto.NoteRequest;
import dk.trustworks.intranet.recruitmentservice.dto.RevisionSummary;
import dk.trustworks.intranet.recruitmentservice.dto.SignerConfigDto;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventBuilder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventRecorder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossier;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossierRevision;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateEducationLevel;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateExperienceLevel;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateLawfulBasis;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidatePoolStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateSecurityClearance;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateSource;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.DossierStatus;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Application service that orchestrates {@link RecruitmentCandidate}
 * lifecycle operations and the matching {@code CandidateDossier} creation.
 * <p>
 * Business rules live on the aggregate roots (state machine in
 * {@link RecruitmentCandidate#decline}, {@link RecruitmentCandidate#withdraw},
 * {@link RecruitmentCandidate#markHired}; OPEN/CLOSED guards in
 * {@link CandidateDossier}). This service composes those rules — never
 * inlines them — and is responsible for repository access, JSON
 * marshalling, and cross-aggregate persistence.
 */
@JBossLog
@ApplicationScoped
public class CandidateService {

    @Inject
    ObjectMapper objectMapper;

    @Inject
    RecruitmentEventRecorder eventRecorder;

    @Inject
    RecruitmentApplicationService applicationService;

    @Inject
    jakarta.persistence.EntityManager em;

    /**
     * Create a candidate — either the original dossier flow or a standalone
     * ATS/talent-pool entry (plan §P3). The two paths share one endpoint:
     * <ul>
     *   <li><b>Dossier path</b> ({@code templateUuid} present): also opens
     *       the initial dossier; {@code email} and {@code targetCompanyUuid}
     *       are required (the send actions need a recipient).</li>
     *   <li><b>ATS path</b> ({@code templateUuid} absent): {@code source}
     *       is mandatory — where a candidate came from drives GDPR Art. 14
     *       bookkeeping, referral flows and reporting.</li>
     * </ul>
     * System-maintained GDPR bookkeeping: {@code lawful_basis} starts as
     * LEGITIMATE_INTEREST; indirect sources (referred/sourced — see
     * {@link CandidateSource#requiresArt14Notice()}) set
     * {@code art14_required} and a 30-day {@code art14_deadline} (data only;
     * the clock reactor is P19). Appends {@code CANDIDATE_CREATED} in the
     * same transaction.
     *
     * @return the freshly persisted candidate hydrated through
     *         {@link #toResponse}
     */
    @Transactional
    public CandidateResponse createCandidate(CandidateRequest req, UUID actor) {
        Objects.requireNonNull(req, "req must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        boolean dossierPath = req.templateUuid() != null && !req.templateUuid().isBlank();
        if (dossierPath) {
            requirePresent(req.email(),
                    "email is required when creating a candidate with an offer dossier");
            requirePresent(req.targetCompanyUuid(),
                    "targetCompanyUuid is required when creating a candidate with an offer dossier");
        } else if (req.source() == null) {
            throw badRequest("source is required — record how the candidate entered the funnel");
        }
        if (req.source() == CandidateSource.PARTNER_REFERRAL
                && isBlank(req.sponsoringPartnerUuid())) {
            throw badRequest("sponsoringPartnerUuid is required for PARTNER_REFERRAL — "
                    + "the partner mandate drives the rejection rules later in the process");
        }

        RecruitmentCandidate candidate = new RecruitmentCandidate();
        candidate.setFirstName(req.firstName());
        candidate.setLastName(req.lastName());
        candidate.setEmail(trimToNull(req.email()));
        candidate.setPhone(trimToNull(req.phone()));
        candidate.setLinkedinUrl(trimToNull(req.linkedinUrl()));
        candidate.setTargetCompanyUuid(trimToNull(req.targetCompanyUuid()));
        candidate.setTargetStartDate(req.targetStartDate());
        candidate.setNotes(req.notes());
        candidate.setStatus(CandidateStatus.ACTIVE);
        candidate.setCreatedByUseruuid(actor.toString());
        candidate.setSource(req.source());
        candidate.setSourceDetail(req.sourceDetail());
        candidate.setReferredByUserUuid(trimToNull(req.referredByUserUuid()));
        candidate.setExternalReferrerName(trimToNull(req.externalReferrerName()));
        candidate.setSponsoringPartnerUuid(trimToNull(req.sponsoringPartnerUuid()));
        candidate.setRelevantTeamleadUuid(trimToNull(req.relevantTeamleadUuid()));
        candidate.setTags(normalizeStrings(req.tags()));
        candidate.setEducationLevel(req.educationLevel());
        candidate.setEducationOther(trimToNull(req.educationOther()));
        candidate.setExperienceLevel(req.experienceLevel());
        candidate.setSpecializations(normalizeStrings(req.specializations()));
        candidate.setSecurityClearance(req.securityClearance());
        candidate.setSecurityRelevant(req.securityRelevant());
        candidate.setLawfulBasis(CandidateLawfulBasis.LEGITIMATE_INTEREST);
        if (req.source() != null && req.source().requiresArt14Notice()) {
            candidate.setArt14Required(Boolean.TRUE);
            candidate.setArt14Deadline(LocalDateTime.now(ZoneOffset.UTC).plusDays(30));
        }
        RecruitmentCandidate.persist(candidate);

        if (dossierPath) {
            CandidateDossier dossier = new CandidateDossier();
            dossier.setCandidateUuid(candidate.getUuid());
            dossier.setTemplateUuid(req.templateUuid());
            dossier.setStatus(DossierStatus.OPEN);
            dossier.setSignersConfigJson(seedSignersFromTemplate(req.templateUuid()));
            CandidateDossier.persist(dossier);
        }

        RecruitmentEventBuilder event = candidateEvent(RecruitmentEventType.CANDIDATE_CREATED, candidate, actor)
                .payload("source", candidate.getSource() != null ? candidate.getSource().name() : null)
                .payload("dossier_opened", dossierPath)
                .payload("referred_by_user_uuid", candidate.getReferredByUserUuid())
                .payload("sponsoring_partner_uuid", candidate.getSponsoringPartnerUuid())
                .payload("relevant_teamlead_uuid", candidate.getRelevantTeamleadUuid())
                .payload("tags", candidate.getTags())
                .payload("education_level", name(candidate.getEducationLevel()))
                .payload("experience_level", name(candidate.getExperienceLevel()))
                .payload("specializations", candidate.getSpecializations())
                .payload("security_clearance", name(candidate.getSecurityClearance()))
                .payload("security_relevant", candidate.getSecurityRelevant())
                .payload("lawful_basis", candidate.getLawfulBasis().name())
                .payload("art14_required", candidate.getArt14Required())
                .pii("first_name", candidate.getFirstName())
                .pii("last_name", candidate.getLastName());
        piiIfPresent(event, "email", candidate.getEmail());
        piiIfPresent(event, "phone", candidate.getPhone());
        piiIfPresent(event, "linkedin_url", candidate.getLinkedinUrl());
        piiIfPresent(event, "external_referrer_name", candidate.getExternalReferrerName());
        if (candidate.getSourceDetail() != null && !candidate.getSourceDetail().isEmpty()) {
            // The adaptive follow-up may carry reference names — the whole
            // blob is treated as personal data (spec §4.1).
            event.pii("source_detail", candidate.getSourceDetail());
        }
        eventRecorder.record(event);

        log.infof("Created candidate uuid=%s source=%s dossier=%s by actor=%s",
                candidate.getUuid(), candidate.getSource(), dossierPath, actor);

        return toResponse(candidate, Optional.empty());
    }

    public Optional<CandidateResponse> findById(UUID candidateUuid) {
        return Optional.ofNullable(RecruitmentCandidate.<RecruitmentCandidate>findById(candidateUuid.toString()))
                .map(c -> toResponse(c, latestRevision(c.getUuid())));
    }

    /**
     * List candidates with optional filters and free-text search across
     * first name, last name and email. Returns a paged envelope.
     * <p>
     * The P3 qualification filters (tag, education, experience,
     * specialization, security clearance) make pool rediscovery possible
     * from the API on day one; the saved-view UI arrives with the P8
     * database grid.
     *
     * @param statusFilter    nullable; a {@link CandidateStatus} name
     *                        (case-insensitive) or {@code null}/blank for "all"
     * @param search          nullable; case-insensitive {@code LIKE} on name/email
     * @param tag             nullable; exact tag membership
     * @param education       nullable; a {@link CandidateEducationLevel} name
     * @param experience      nullable; a {@link CandidateExperienceLevel} name
     * @param specialization  nullable; exact specialization membership
     * @param clearance       nullable; a {@link CandidateSecurityClearance} name
     * @param viewerUuid      nullable; the {@code X-Requested-By} user — when
     *                        present, each row carries its open applications
     *                        (visibility-filtered: partner-track applications
     *                        are absent for non-circle viewers, P4). When
     *                        absent (legacy callers without the header) the
     *                        rows carry empty application lists.
     */
    public CandidateListResponse list(String statusFilter, String search, String tag,
                                      String education, String experience,
                                      String specialization, String clearance,
                                      int page, int size, String viewerUuid) {
        StringBuilder where = new StringBuilder("1 = 1");
        Map<String, Object> params = new java.util.HashMap<>();

        if (statusFilter != null && !statusFilter.isBlank()) {
            where.append(" AND status = :status");
            params.put("status", parseEnum(CandidateStatus.class, statusFilter, "status"));
        }
        if (search != null && !search.isBlank()) {
            where.append(" AND (LOWER(firstName) LIKE :q OR LOWER(lastName) LIKE :q OR LOWER(email) LIKE :q)");
            params.put("q", "%" + search.toLowerCase() + "%");
        }
        if ((tag != null && !tag.isBlank()) || (specialization != null && !specialization.isBlank())) {
            // tags/specializations are JSON string arrays behind an
            // AttributeConverter, so HQL cannot LIKE them directly — resolve
            // matching uuids natively first (a LIKE on the quoted element is
            // exact-membership at this scale; values are length-capped,
            // quotes rejected in jsonElementLike).
            List<String> matching = uuidsMatchingJsonFilters(tag, specialization);
            if (matching.isEmpty()) {
                return new CandidateListResponse(List.of(), 0);
            }
            where.append(" AND uuid IN :jsonFilterUuids");
            params.put("jsonFilterUuids", matching);
        }
        if (education != null && !education.isBlank()) {
            where.append(" AND educationLevel = :education");
            params.put("education", parseEnum(CandidateEducationLevel.class, education, "education"));
        }
        if (experience != null && !experience.isBlank()) {
            where.append(" AND experienceLevel = :experience");
            params.put("experience", parseEnum(CandidateExperienceLevel.class, experience, "experience"));
        }
        if (clearance != null && !clearance.isBlank()) {
            where.append(" AND securityClearance = :clearance");
            params.put("clearance", parseEnum(CandidateSecurityClearance.class, clearance, "clearance"));
        }

        long totalCount = RecruitmentCandidate.count(where.toString(), params);
        List<RecruitmentCandidate> rows = RecruitmentCandidate
                .<RecruitmentCandidate>find(where.toString(), Sort.descending("createdAt"), params)
                .page(Page.of(page, size))
                .list();

        // Open-application facts per row (P4) — two batched queries for the
        // whole page, visibility-filtered per viewer. Legacy callers without
        // an X-Requested-By header get empty lists.
        Map<String, List<CandidateApplicationInfo>> applicationInfo =
                (viewerUuid == null || viewerUuid.isBlank())
                        ? Map.of()
                        : applicationService.openApplicationInfoByCandidate(
                                viewerUuid,
                                rows.stream().map(RecruitmentCandidate::getUuid).toList());

        List<CandidateSummary> data = new ArrayList<>(rows.size());
        for (RecruitmentCandidate c : rows) {
            Optional<CandidateDossier> dossier = findOpenOrLatestDossier(c.getUuid());
            Optional<CandidateDossierRevision> latest = latestRevisionEntity(c.getUuid());
            data.add(new CandidateSummary(
                    c.getUuid(),
                    (c.getFirstName() + " " + c.getLastName()).trim(),
                    c.getEmail(),
                    c.getTargetCompanyUuid(),
                    dossier.map(CandidateDossier::getTemplateUuid).orElse(null),
                    c.getStatus(),
                    c.getPoolStatus(),
                    c.getSource(),
                    c.getTags(),
                    latest.map(CandidateDossierRevision::getKind).orElse(null),
                    latest.map(CandidateDossierRevision::getCreatedAt).orElse(null),
                    applicationInfo.getOrDefault(c.getUuid(), List.of())
            ));
        }
        return new CandidateListResponse(data, totalCount);
    }

    /**
     * Apply autosave updates to a candidate's editable metadata (null fields
     * unchanged). The status field is intentionally NOT mutated through this
     * method — terminal transitions go through {@link #decline} /
     * {@link #withdraw}, pool moves through {@link #pool} / {@link #unpool},
     * so the domain guards run.
     * <p>
     * Appends {@code CANDIDATE_UPDATED} when anything actually changed:
     * structural facts (before/after) in payload, personal fields
     * (before/after) in pii — spec §3.4. A no-op update appends nothing.
     */
    @Transactional
    public CandidateResponse update(UUID candidateUuid, CandidateRequest req, UUID actor) {
        Objects.requireNonNull(req, "req must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        RecruitmentCandidate candidate = requireCandidate(candidateUuid);

        Map<String, Object> structuralChanges = new LinkedHashMap<>();
        Map<String, Object> personalChanges = new LinkedHashMap<>();

        applyPersonal(personalChanges, "first_name", candidate.getFirstName(), req.firstName(), candidate::setFirstName);
        applyPersonal(personalChanges, "last_name", candidate.getLastName(), req.lastName(), candidate::setLastName);
        applyPersonal(personalChanges, "email", candidate.getEmail(), trimToNull(req.email()), candidate::setEmail);
        applyPersonal(personalChanges, "phone", candidate.getPhone(), trimToNull(req.phone()), candidate::setPhone);
        applyPersonal(personalChanges, "linkedin_url", candidate.getLinkedinUrl(), trimToNull(req.linkedinUrl()), candidate::setLinkedinUrl);
        applyPersonal(personalChanges, "notes", candidate.getNotes(), req.notes(), candidate::setNotes);
        applyPersonal(personalChanges, "external_referrer_name", candidate.getExternalReferrerName(),
                trimToNull(req.externalReferrerName()), candidate::setExternalReferrerName);
        if (req.sourceDetail() != null && !Objects.equals(candidate.getSourceDetail(), req.sourceDetail())) {
            personalChanges.put("source_detail", beforeAfter(candidate.getSourceDetail(), req.sourceDetail()));
            candidate.setSourceDetail(req.sourceDetail());
        }

        applyStructural(structuralChanges, "target_company_uuid", candidate.getTargetCompanyUuid(),
                trimToNull(req.targetCompanyUuid()), candidate::setTargetCompanyUuid);
        if (req.targetStartDate() != null && !Objects.equals(candidate.getTargetStartDate(), req.targetStartDate())) {
            structuralChanges.put("target_start_date",
                    beforeAfter(String.valueOf(candidate.getTargetStartDate()), req.targetStartDate().toString()));
            candidate.setTargetStartDate(req.targetStartDate());
        }
        if (req.source() != null && candidate.getSource() != req.source()) {
            structuralChanges.put("source", beforeAfter(name(candidate.getSource()), req.source().name()));
            candidate.setSource(req.source());
        }
        applyStructural(structuralChanges, "referred_by_user_uuid", candidate.getReferredByUserUuid(),
                trimToNull(req.referredByUserUuid()), candidate::setReferredByUserUuid);
        applyStructural(structuralChanges, "sponsoring_partner_uuid", candidate.getSponsoringPartnerUuid(),
                trimToNull(req.sponsoringPartnerUuid()), candidate::setSponsoringPartnerUuid);
        applyStructural(structuralChanges, "relevant_teamlead_uuid", candidate.getRelevantTeamleadUuid(),
                trimToNull(req.relevantTeamleadUuid()), candidate::setRelevantTeamleadUuid);
        if (req.tags() != null) {
            List<String> normalized = normalizeStrings(req.tags());
            if (!Objects.equals(nullSafeList(candidate.getTags()), nullSafeList(normalized))) {
                structuralChanges.put("tags", beforeAfter(candidate.getTags(), normalized));
                candidate.setTags(normalized);
            }
        }
        if (req.educationLevel() != null && candidate.getEducationLevel() != req.educationLevel()) {
            structuralChanges.put("education_level",
                    beforeAfter(name(candidate.getEducationLevel()), req.educationLevel().name()));
            candidate.setEducationLevel(req.educationLevel());
        }
        applyStructural(structuralChanges, "education_other", candidate.getEducationOther(),
                trimToNull(req.educationOther()), candidate::setEducationOther);
        if (req.experienceLevel() != null && candidate.getExperienceLevel() != req.experienceLevel()) {
            structuralChanges.put("experience_level",
                    beforeAfter(name(candidate.getExperienceLevel()), req.experienceLevel().name()));
            candidate.setExperienceLevel(req.experienceLevel());
        }
        if (req.specializations() != null) {
            List<String> normalized = normalizeStrings(req.specializations());
            if (!Objects.equals(nullSafeList(candidate.getSpecializations()), nullSafeList(normalized))) {
                structuralChanges.put("specializations", beforeAfter(candidate.getSpecializations(), normalized));
                candidate.setSpecializations(normalized);
            }
        }
        if (req.securityClearance() != null && candidate.getSecurityClearance() != req.securityClearance()) {
            structuralChanges.put("security_clearance",
                    beforeAfter(name(candidate.getSecurityClearance()), req.securityClearance().name()));
            candidate.setSecurityClearance(req.securityClearance());
        }
        if (req.securityRelevant() != null && !Objects.equals(candidate.getSecurityRelevant(), req.securityRelevant())) {
            structuralChanges.put("security_relevant",
                    beforeAfter(candidate.getSecurityRelevant(), req.securityRelevant()));
            candidate.setSecurityRelevant(req.securityRelevant());
        }

        if (!structuralChanges.isEmpty() || !personalChanges.isEmpty()) {
            List<String> changedFields = new ArrayList<>(structuralChanges.keySet());
            changedFields.addAll(personalChanges.keySet());
            RecruitmentEventBuilder event = candidateEvent(RecruitmentEventType.CANDIDATE_UPDATED, candidate, actor)
                    .payload("changed_fields", changedFields);
            structuralChanges.forEach(event::payload);
            personalChanges.forEach(event::pii);
            eventRecorder.record(event);
        }
        return toResponse(candidate, latestRevision(candidate.getUuid()));
    }

    /**
     * Move a candidate into the talent pool (or re-bucket a pooled one).
     * Appends {@code CANDIDATE_POOLED} — the P19 GdprClock keys retention
     * decisions on pool membership.
     */
    @Transactional
    public CandidateResponse pool(UUID candidateUuid, CandidatePoolStatus poolStatus, UUID actor) {
        Objects.requireNonNull(actor, "actor must not be null");
        RecruitmentCandidate candidate = requireCandidate(candidateUuid);
        candidate.pool(poolStatus, actor);
        eventRecorder.record(candidateEvent(RecruitmentEventType.CANDIDATE_POOLED, candidate, actor)
                .payload("pool_status", candidate.getPoolStatus().name()));
        log.infof("Pooled candidate uuid=%s bucket=%s by actor=%s",
                candidate.getUuid(), candidate.getPoolStatus(), actor);
        return toResponse(candidate, latestRevision(candidate.getUuid()));
    }

    /** Bring a pooled candidate back to ACTIVE. Appends {@code CANDIDATE_UNPOOLED}. */
    @Transactional
    public CandidateResponse unpool(UUID candidateUuid, UUID actor) {
        Objects.requireNonNull(actor, "actor must not be null");
        RecruitmentCandidate candidate = requireCandidate(candidateUuid);
        candidate.unpool(actor);
        eventRecorder.record(candidateEvent(RecruitmentEventType.CANDIDATE_UNPOOLED, candidate, actor));
        log.infof("Unpooled candidate uuid=%s by actor=%s", candidate.getUuid(), actor);
        return toResponse(candidate, latestRevision(candidate.getUuid()));
    }

    /**
     * Replace the candidate's tag set. A no-op replacement appends nothing;
     * a real change appends {@code CANDIDATE_UPDATED} (tags are structural —
     * there is no dedicated tag event in the spec §3.4 catalog).
     */
    @Transactional
    public CandidateResponse updateTags(UUID candidateUuid, List<String> tags, UUID actor) {
        Objects.requireNonNull(actor, "actor must not be null");
        RecruitmentCandidate candidate = requireCandidate(candidateUuid);
        List<String> normalized = normalizeStrings(tags);
        if (!Objects.equals(nullSafeList(candidate.getTags()), nullSafeList(normalized))) {
            RecruitmentEventBuilder event = candidateEvent(RecruitmentEventType.CANDIDATE_UPDATED, candidate, actor)
                    .payload("changed_fields", List.of("tags"))
                    .payload("tags", beforeAfter(candidate.getTags(), normalized));
            candidate.setTags(normalized);
            eventRecorder.record(event);
        }
        return toResponse(candidate, latestRevision(candidate.getUuid()));
    }

    /**
     * Record a note as a {@code NOTE_ADDED} event — notes have no state
     * table; the text lives exclusively in the event's pii block
     * (spec §4.1). {@code payload.private} drives the P8 timeline's
     * author+recruiter+admin gate; {@code payload.field} marks structured
     * notes (only {@code SALARY_EXPECTATION} is defined in P3 — its scope
     * gate lives in the resource layer).
     *
     * @return the appended event (callers surface seq/occurredAt)
     */
    @Transactional
    public RecruitmentEvent addNote(UUID candidateUuid, NoteRequest note, UUID actor) {
        Objects.requireNonNull(note, "note must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        RecruitmentCandidate candidate = requireCandidate(candidateUuid);
        if (note.field() != null && !NoteRequest.FIELD_SALARY_EXPECTATION.equals(note.field())) {
            throw badRequest("Unknown note field: only " + NoteRequest.FIELD_SALARY_EXPECTATION
                    + " is supported");
        }
        return eventRecorder.record(
                candidateEvent(RecruitmentEventType.NOTE_ADDED, candidate, actor)
                        .payload("private", Boolean.TRUE.equals(note.isPrivate()))
                        .payload("field", note.field())
                        .pii("text", note.text()));
    }

    /**
     * Decline a candidate. Delegates the state transition to
     * {@link RecruitmentCandidate#decline(String, UUID)} (which throws on
     * non-ACTIVE status) and cascades a {@code closeOnTerminal()} call to
     * every dossier owned by the candidate.
     */
    @Transactional
    public CandidateResponse decline(UUID candidateUuid, String reason, UUID actor) {
        Objects.requireNonNull(actor, "actor must not be null");
        RecruitmentCandidate candidate = requireCandidate(candidateUuid);
        candidate.decline(reason, actor);
        closeAllDossiers(candidate.getUuid());
        log.infof("Declined candidate uuid=%s by actor=%s", candidate.getUuid(), actor);
        return toResponse(candidate, latestRevision(candidate.getUuid()));
    }

    /**
     * Mark a candidate as having withdrawn (the candidate themselves backed
     * out). Same cascade mechanics as {@link #decline}.
     */
    @Transactional
    public CandidateResponse withdraw(UUID candidateUuid, String reason, UUID actor) {
        Objects.requireNonNull(actor, "actor must not be null");
        RecruitmentCandidate candidate = requireCandidate(candidateUuid);
        candidate.withdraw(reason, actor);
        closeAllDossiers(candidate.getUuid());
        log.infof("Withdrew candidate uuid=%s by actor=%s", candidate.getUuid(), actor);
        return toResponse(candidate, latestRevision(candidate.getUuid()));
    }

    // ---- helpers ---------------------------------------------------------------

    private RecruitmentCandidate requireCandidate(UUID candidateUuid) {
        RecruitmentCandidate c = RecruitmentCandidate.findById(candidateUuid.toString());
        if (c == null) {
            throw new NotFoundException("Candidate not found: " + candidateUuid);
        }
        return c;
    }

    private void closeAllDossiers(String candidateUuid) {
        List<CandidateDossier> dossiers = CandidateDossier
                .<CandidateDossier>find("candidateUuid = ?1 AND status = ?2",
                        candidateUuid, DossierStatus.OPEN)
                .list();
        for (CandidateDossier d : dossiers) {
            d.closeOnTerminal();
        }
    }

    private Optional<RevisionSummary> latestRevision(String candidateUuid) {
        return latestRevisionEntity(candidateUuid).map(r -> new RevisionSummary(
                r.getUuid(), r.getVersionNumber(), r.getKind(), r.getCreatedAt()));
    }

    private Optional<CandidateDossierRevision> latestRevisionEntity(String candidateUuid) {
        // JPQL: find most recent revision across any dossier belonging to the candidate
        return CandidateDossierRevision
                .<CandidateDossierRevision>find(
                        "dossierUuid IN (SELECT d.uuid FROM CandidateDossier d WHERE d.candidateUuid = ?1)",
                        Sort.descending("createdAt"),
                        candidateUuid)
                .firstResultOptional();
    }

    private Optional<CandidateDossier> findOpenOrLatestDossier(String candidateUuid) {
        // Prefer an OPEN dossier; otherwise the most recent CLOSED.
        return CandidateDossier
                .<CandidateDossier>find(
                        "candidateUuid = ?1",
                        Sort.descending("status").and("createdAt", Sort.Direction.Descending),
                        candidateUuid)
                .firstResultOptional();
    }

    /**
     * Build the candidate read DTO. Deliberately a private mapper rather than
     * a public utility — the wire shape is owned by this service and should
     * not be referenced from elsewhere.
     */
    private CandidateResponse toResponse(RecruitmentCandidate c, Optional<RevisionSummary> latest) {
        return new CandidateResponse(
                c.getUuid(),
                c.getFirstName(),
                c.getLastName(),
                c.getEmail(),
                c.getPhone(),
                c.getLinkedinUrl(),
                c.getTargetCompanyUuid(),
                c.getTargetStartDate(),
                c.getNotes(),
                c.getStatus(),
                c.getPoolStatus(),
                c.getSource(),
                c.getSourceDetail(),
                c.getReferredByUserUuid(),
                c.getExternalReferrerName(),
                c.getSponsoringPartnerUuid(),
                c.getRelevantTeamleadUuid(),
                c.getTags(),
                c.getEducationLevel(),
                c.getEducationOther(),
                c.getExperienceLevel(),
                c.getSpecializations(),
                c.getSecurityClearance(),
                c.getSecurityRelevant(),
                c.getLawfulBasis(),
                c.getArt14Required(),
                c.getArt14Deadline(),
                c.getDeclineReason(),
                c.getConvertedUserUuid(),
                c.getSharepointFolderPath(),
                c.getSharepointMoveStatus(),
                c.getCreatedByUseruuid(),
                c.getCreatedAt(),
                c.getUpdatedAt(),
                latest.orElse(null)
        );
    }

    // ---- P3 helpers -------------------------------------------------------------

    /**
     * Event skeleton for this candidate: subject + acting user. Candidate
     * events are NORMAL visibility in P3 — CIRCLE visibility appears when
     * candidates join partner-track applications (P4).
     */
    private static RecruitmentEventBuilder candidateEvent(RecruitmentEventType type,
                                                          RecruitmentCandidate candidate, UUID actor) {
        return RecruitmentEventBuilder.event(type)
                .candidate(candidate.getUuid())
                .actorUser(actor.toString());
    }

    /** Track a personal-field change (before/after into pii) and apply it. */
    private static void applyPersonal(Map<String, Object> changes, String field,
                                      String oldValue, String newValue, Consumer<String> setter) {
        if (newValue != null && !Objects.equals(oldValue, newValue)) {
            changes.put(field, beforeAfter(oldValue, newValue));
            setter.accept(newValue);
        }
    }

    /** Track a structural-field change (before/after into payload) and apply it. */
    private static void applyStructural(Map<String, Object> changes, String field,
                                        String oldValue, String newValue, Consumer<String> setter) {
        if (newValue != null && !Objects.equals(oldValue, newValue)) {
            changes.put(field, beforeAfter(oldValue, newValue));
            setter.accept(newValue);
        }
    }

    private static Map<String, Object> beforeAfter(Object before, Object after) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("before", before);
        change.put("after", after);
        return change;
    }

    /** Trim entries, drop blanks, dedupe (insertion order kept). Null in → null out. */
    private static List<String> normalizeStrings(List<String> values) {
        if (values == null) {
            return null;
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String trimmed = value.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.indexOf('"') >= 0 || trimmed.indexOf('\\') >= 0) {
                throw badRequest("Tags and specializations must not contain quotes or backslashes");
            }
            normalized.add(trimmed);
        }
        return new ArrayList<>(normalized);
    }

    private static List<String> nullSafeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    /** Candidate uuids whose tags/specializations JSON contains the filter element(s). */
    @SuppressWarnings("unchecked")
    private List<String> uuidsMatchingJsonFilters(String tag, String specialization) {
        StringBuilder sql = new StringBuilder("SELECT uuid FROM recruitment_candidates WHERE 1 = 1");
        boolean byTag = tag != null && !tag.isBlank();
        boolean bySpecialization = specialization != null && !specialization.isBlank();
        if (byTag) {
            sql.append(" AND tags LIKE :tagLike");
        }
        if (bySpecialization) {
            sql.append(" AND specializations LIKE :specializationLike");
        }
        var query = em.createNativeQuery(sql.toString());
        if (byTag) {
            query.setParameter("tagLike", jsonElementLike(tag, "tag"));
        }
        if (bySpecialization) {
            query.setParameter("specializationLike", jsonElementLike(specialization, "specialization"));
        }
        return (List<String>) query.getResultList();
    }

    /**
     * Exact-membership LIKE pattern for a JSON string-array column: the
     * quoted element. LIKE wildcards in the user's filter value are escaped
     * so they match literally.
     */
    private static String jsonElementLike(String element, String label) {
        String trimmed = element.trim();
        if (trimmed.indexOf('"') >= 0 || trimmed.indexOf('\\') >= 0) {
            throw badRequest("Invalid " + label + " filter: quotes and backslashes are not allowed");
        }
        String escaped = trimmed.replace("%", "\\%").replace("_", "\\_");
        return "%\"" + escaped + "\"%";
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String label) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw badRequest("Invalid " + label + " filter: " + value);
        }
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static void piiIfPresent(RecruitmentEventBuilder event, String key, String value) {
        if (value != null && !value.isBlank()) {
            event.pii(key, value);
        }
    }

    private static void requirePresent(String value, String message) {
        if (isBlank(value)) {
            throw badRequest(message);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static WebApplicationException badRequest(String message) {
        return new WebApplicationException(message, Response.Status.BAD_REQUEST);
    }

    /**
     * Returns the configured Jackson {@link ObjectMapper} so callers in the
     * same package (e.g. {@link DossierRevisionService}) can share it without
     * each having their own injection.
     */
    ObjectMapper objectMapper() {
        return objectMapper;
    }

    static IllegalStateException jsonError(String operation, JsonProcessingException cause) {
        return new IllegalStateException("JSON " + operation + " failed: " + cause.getOriginalMessage(), cause);
    }

    /**
     * Seed the dossier's signers_config from the template's default signers,
     * sorted by signer group then display order. ${PLACEHOLDER} tokens in
     * name/email are preserved as-is; revision snapshots resolve them at
     * send time. Returns "[]" when the template defines no defaults so the
     * not-null DB column always carries a valid JSON array.
     */
    private String seedSignersFromTemplate(String templateUuid) {
        List<TemplateDefaultSignerEntity> defaults = TemplateDefaultSignerEntity
                .find("template.uuid = ?1 ORDER BY signerGroup, displayOrder", templateUuid)
                .list();

        List<SignerConfigDto> signers = new ArrayList<>(defaults.size());
        for (TemplateDefaultSignerEntity s : defaults) {
            signers.add(new SignerConfigDto(
                    String.valueOf(s.getSignerGroup()),
                    s.getName(),
                    s.getEmail(),
                    s.isSigning(),
                    s.isNeedsCpr(),
                    s.getRole(),
                    null
            ));
        }

        try {
            return objectMapper.writeValueAsString(signers);
        } catch (JsonProcessingException e) {
            throw jsonError("write", e);
        }
    }
}
