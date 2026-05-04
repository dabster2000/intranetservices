package dk.trustworks.intranet.recruitmentservice.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.recruitmentservice.dto.CandidateListResponse;
import dk.trustworks.intranet.recruitmentservice.dto.CandidateRequest;
import dk.trustworks.intranet.recruitmentservice.dto.CandidateResponse;
import dk.trustworks.intranet.recruitmentservice.dto.CandidateSummary;
import dk.trustworks.intranet.recruitmentservice.dto.RevisionSummary;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossier;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossierRevision;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.DossierStatus;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import lombok.extern.jbosslog.JBossLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

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

    /**
     * Create a candidate together with the initial dossier for the supplied
     * template. Mirrors the "+ New candidate" modal which captures both the
     * candidate metadata and the template choice in one step (spec §6.2).
     *
     * @return the freshly persisted candidate hydrated through
     *         {@link #toResponse}
     */
    @Transactional
    public CandidateResponse createCandidate(CandidateRequest req, UUID actor) {
        Objects.requireNonNull(req, "req must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        if (req.templateUuid() == null || req.templateUuid().isBlank()) {
            throw new IllegalArgumentException("templateUuid is required when creating a candidate");
        }

        RecruitmentCandidate candidate = new RecruitmentCandidate();
        candidate.setFirstName(req.firstName());
        candidate.setLastName(req.lastName());
        candidate.setEmail(req.email());
        candidate.setPhone(req.phone());
        candidate.setTargetCompanyUuid(req.targetCompanyUuid());
        candidate.setTargetStartDate(req.targetStartDate());
        candidate.setNotes(req.notes());
        candidate.setStatus(CandidateStatus.ACTIVE);
        candidate.setCreatedByUseruuid(actor.toString());
        RecruitmentCandidate.persist(candidate);

        CandidateDossier dossier = new CandidateDossier();
        dossier.setCandidateUuid(candidate.getUuid());
        dossier.setTemplateUuid(req.templateUuid());
        dossier.setStatus(DossierStatus.OPEN);
        CandidateDossier.persist(dossier);

        log.infof("Created candidate uuid=%s template=%s by actor=%s",
                candidate.getUuid(), req.templateUuid(), actor);

        return toResponse(candidate, Optional.empty());
    }

    public Optional<CandidateResponse> findById(UUID candidateUuid) {
        return Optional.ofNullable(RecruitmentCandidate.<RecruitmentCandidate>findById(candidateUuid.toString()))
                .map(c -> toResponse(c, latestRevision(c.getUuid())));
    }

    /**
     * List candidates with optional status filter and free-text search across
     * first name, last name and email. Returns a paged envelope.
     *
     * @param statusFilter nullable; one of the {@link CandidateStatus} names
     *                     (case-insensitive) or {@code null}/blank for "all"
     * @param search       nullable; if present, applied as a case-insensitive
     *                     {@code LIKE} match on name/email
     */
    public CandidateListResponse list(String statusFilter, String search, int page, int size) {
        StringBuilder where = new StringBuilder("1 = 1");
        Map<String, Object> params = new java.util.HashMap<>();

        if (statusFilter != null && !statusFilter.isBlank()) {
            CandidateStatus parsed;
            try {
                parsed = CandidateStatus.valueOf(statusFilter.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new jakarta.ws.rs.WebApplicationException(
                        "Invalid status filter: " + statusFilter,
                        jakarta.ws.rs.core.Response.Status.BAD_REQUEST);
            }
            where.append(" AND status = :status");
            params.put("status", parsed);
        }
        if (search != null && !search.isBlank()) {
            where.append(" AND (LOWER(firstName) LIKE :q OR LOWER(lastName) LIKE :q OR LOWER(email) LIKE :q)");
            params.put("q", "%" + search.toLowerCase() + "%");
        }

        long totalCount = RecruitmentCandidate.count(where.toString(), params);
        List<RecruitmentCandidate> rows = RecruitmentCandidate
                .<RecruitmentCandidate>find(where.toString(), Sort.descending("createdAt"), params)
                .page(Page.of(page, size))
                .list();

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
                    latest.map(CandidateDossierRevision::getKind).orElse(null),
                    latest.map(CandidateDossierRevision::getCreatedAt).orElse(null)
            ));
        }
        return new CandidateListResponse(data, totalCount);
    }

    /**
     * Apply autosave updates to a candidate's editable metadata. The status
     * field is intentionally NOT mutated through this method — terminal
     * transitions go through {@link #decline} / {@link #withdraw} so the
     * domain guard runs.
     */
    @Transactional
    public CandidateResponse update(UUID candidateUuid, CandidateRequest req, UUID actor) {
        Objects.requireNonNull(req, "req must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        RecruitmentCandidate candidate = requireCandidate(candidateUuid);
        if (req.firstName() != null) candidate.setFirstName(req.firstName());
        if (req.lastName() != null) candidate.setLastName(req.lastName());
        if (req.email() != null) candidate.setEmail(req.email());
        if (req.phone() != null) candidate.setPhone(req.phone());
        if (req.targetCompanyUuid() != null) candidate.setTargetCompanyUuid(req.targetCompanyUuid());
        if (req.targetStartDate() != null) candidate.setTargetStartDate(req.targetStartDate());
        if (req.notes() != null) candidate.setNotes(req.notes());
        return toResponse(candidate, latestRevision(candidate.getUuid()));
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
                c.getTargetCompanyUuid(),
                c.getTargetStartDate(),
                c.getNotes(),
                c.getStatus(),
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
}
