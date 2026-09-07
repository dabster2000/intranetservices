package dk.trustworks.intranet.agreementservice.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.agreementservice.dto.AgreementDTO;
import dk.trustworks.intranet.agreementservice.model.AgreementType;
import dk.trustworks.intranet.agreementservice.model.EmployeeAgreement;
import dk.trustworks.intranet.agreementservice.model.enums.AgreementSource;
import dk.trustworks.intranet.agreementservice.model.enums.AgreementStatus;
import dk.trustworks.intranet.aggregates.users.services.StatusService;
import dk.trustworks.intranet.documentservice.model.TemplateClauseEntity;
import dk.trustworks.intranet.documentservice.model.TemplateClauseVersionEntity;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import lombok.extern.jbosslog.JBossLog;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry reads, manual entry and HR edits (template-clauses spec §8).
 * Every mutation is audit-logged with the acting user; the resource has
 * already enforced HR/ADMIN via {@code AgreementAccessPolicy}.
 */
@JBossLog
@ApplicationScoped
public class AgreementService {

    @Inject
    ObjectMapper objectMapper;

    @Inject
    StatusService statusService;


    // ── Requests ───────────────────────────────────────────────────────────

    /** Manual entry (source=MANUAL). {@code supersedesUuid} marks a prior row SUPERSEDED. */
    public record CreateAgreementRequest(String userUuid, String agreementType, String title,
                                         String summary, BigDecimal amount, String currency,
                                         LocalDate validFrom, LocalDate validTo,
                                         LocalDate effectiveDate, String documentUrl,
                                         String supersedesUuid) {
    }

    /**
     * HR edit — a full-state update of the editable fields: summary,
     * amount, currency and the three dates are replaced with whatever is
     * sent (null clears). agreementType/title/status only change when a
     * non-blank value arrives; documentUrl only when the field is present
     * (blank clears it).
     */
    public record UpdateAgreementRequest(String agreementType, String title, String summary,
                                         BigDecimal amount, String currency, LocalDate validFrom,
                                         LocalDate validTo, LocalDate effectiveDate,
                                         String documentUrl, String status) {
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    public List<AgreementType> findTypes() {
        return AgreementType.findAllOrdered();
    }

    /**
     * Filtered registry list. All filters optional; {@code expiringBefore}
     * selects rows whose {@code valid_to} falls on or before the date
     * (and is set at all). Company filtering happens client-side on the
     * enriched DTO — the registry has no company column by design.
     */
    public List<AgreementDTO> find(String agreementType, String status, String userUuid,
                                   String candidateUuid, LocalDate expiringBefore) {
        StringBuilder query = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();
        if (agreementType != null && !agreementType.isBlank()) {
            query.append(" AND agreementType = :agreementType");
            params.put("agreementType", agreementType);
        }
        if (status != null && !status.isBlank()) {
            query.append(" AND status = :status");
            params.put("status", parseStatus(status).name());
        }
        if (userUuid != null && !userUuid.isBlank()) {
            query.append(" AND userUuid = :userUuid");
            params.put("userUuid", userUuid);
        }
        if (candidateUuid != null && !candidateUuid.isBlank()) {
            query.append(" AND candidateUuid = :candidateUuid");
            params.put("candidateUuid", candidateUuid);
        }
        if (expiringBefore != null) {
            query.append(" AND validTo IS NOT NULL AND validTo <= :expiringBefore");
            params.put("expiringBefore", expiringBefore);
        }
        query.append(" ORDER BY createdAt DESC");
        List<EmployeeAgreement> rows = EmployeeAgreement.list(query.toString(), params);
        EnrichmentCache cache = new EnrichmentCache();
        return rows.stream().map(row -> toDTO(row, cache)).toList();
    }

    public AgreementDTO findByUuid(String uuid) {
        EmployeeAgreement row = requireRow(uuid);
        return toDTO(row, new EnrichmentCache());
    }

    /** Both subject shapes for the wizard/dossier context panel. */
    public List<AgreementDTO> findForSubject(String userUuid, String candidateUuid) {
        List<EmployeeAgreement> rows = userUuid != null && !userUuid.isBlank()
                ? EmployeeAgreement.findByUser(userUuid)
                : EmployeeAgreement.findByCandidate(candidateUuid);
        EnrichmentCache cache = new EnrichmentCache();
        return rows.stream().map(row -> toDTO(row, cache)).toList();
    }

    // ── Mutations (audit-logged) ───────────────────────────────────────────

    @Transactional
    public AgreementDTO create(CreateAgreementRequest request, String actor) {
        if (request.userUuid() == null || request.userUuid().isBlank()) {
            throw new WebApplicationException("userUuid is required for manual entry", 400);
        }
        if (User.findByIdOptional(request.userUuid()).isEmpty()) {
            throw new WebApplicationException("Unknown user: " + request.userUuid(), 400);
        }
        requireType(request.agreementType());
        if (request.title() == null || request.title().isBlank()) {
            throw new WebApplicationException("title is required", 400);
        }
        validateDates(request.validFrom(), request.validTo());

        EmployeeAgreement row = new EmployeeAgreement();
        row.setUserUuid(request.userUuid());
        row.setAgreementType(request.agreementType());
        row.setTitle(request.title().trim());
        row.setSummary(request.summary());
        row.setAmount(request.amount());
        row.setCurrency(normalizeCurrency(request.currency()));
        row.setValidFrom(request.validFrom());
        row.setValidTo(request.validTo());
        row.setEffectiveDate(request.effectiveDate());
        row.setDocumentUrl(validateDocumentUrl(request.documentUrl()));
        row.setSource(AgreementSource.MANUAL.name());
        row.setStatus(AgreementStatus.ACTIVE.name());
        row.setCreatedBy(actor);
        row.persist();

        if (request.supersedesUuid() != null && !request.supersedesUuid().isBlank()) {
            supersede(request.supersedesUuid(), row.getUuid(), actor);
        }

        log.infof("AUDIT: agreement created uuid=%s user=%s type=%s by=%s",
                row.getUuid(), row.getUserUuid(), row.getAgreementType(), actor);
        return toDTO(row, new EnrichmentCache());
    }

    @Transactional
    public AgreementDTO update(String uuid, UpdateAgreementRequest request, String actor) {
        EmployeeAgreement row = requireRow(uuid);
        if (request.agreementType() != null && !request.agreementType().isBlank()) {
            requireType(request.agreementType());
            row.setAgreementType(request.agreementType());
        }
        if (request.title() != null && !request.title().isBlank()) {
            row.setTitle(request.title().trim());
        }
        row.setSummary(request.summary());
        row.setAmount(request.amount());
        row.setCurrency(normalizeCurrency(request.currency()));
        validateDates(request.validFrom(), request.validTo());
        row.setValidFrom(request.validFrom());
        row.setValidTo(request.validTo());
        row.setEffectiveDate(request.effectiveDate());
        if (request.documentUrl() != null) {
            row.setDocumentUrl(request.documentUrl().isBlank()
                    ? null
                    : validateDocumentUrl(request.documentUrl()));
        }
        if (request.status() != null && !request.status().isBlank()) {
            row.setStatus(parseStatus(request.status()).name());
        }
        row.setModifiedBy(actor);

        log.infof("AUDIT: agreement updated uuid=%s status=%s by=%s", uuid, row.getStatus(), actor);
        return toDTO(row, new EnrichmentCache());
    }

    /** Only MANUAL rows may be deleted — signed/backfilled history is kept. */
    @Transactional
    public void delete(String uuid, String actor) {
        EmployeeAgreement row = requireRow(uuid);
        if (!AgreementSource.MANUAL.name().equals(row.getSource())) {
            throw new WebApplicationException(
                    "Only manually entered agreements can be deleted; end this one by setting its status", 409);
        }
        row.delete();
        log.infof("AUDIT: agreement deleted uuid=%s user=%s type=%s by=%s",
                uuid, row.getUserUuid(), row.getAgreementType(), actor);
    }

    private void supersede(String supersededUuid, String successorUuid, String actor) {
        EmployeeAgreement superseded = EmployeeAgreement.findById(supersededUuid);
        if (superseded == null) {
            throw new WebApplicationException("Superseded agreement not found: " + supersededUuid, 400);
        }
        superseded.setStatus(AgreementStatus.SUPERSEDED.name());
        superseded.setModifiedBy(actor);
        log.infof("AUDIT: agreement %s marked SUPERSEDED by new agreement %s (actor=%s)",
                supersededUuid, successorUuid, actor);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private EmployeeAgreement requireRow(String uuid) {
        EmployeeAgreement row = EmployeeAgreement.findById(uuid);
        if (row == null) {
            throw new WebApplicationException("Agreement not found: " + uuid, 404);
        }
        return row;
    }

    private void requireType(String typeKey) {
        if (typeKey == null || typeKey.isBlank() || AgreementType.findById(typeKey) == null) {
            throw new WebApplicationException("Unknown agreement type: " + typeKey, 400);
        }
    }

    private static void validateDates(LocalDate validFrom, LocalDate validTo) {
        if (validFrom != null && validTo != null && validTo.isBefore(validFrom)) {
            throw new WebApplicationException("valid_to cannot be before valid_from", 400);
        }
    }

    private static AgreementStatus parseStatus(String raw) {
        try {
            return AgreementStatus.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException("Unknown agreement status: " + raw, 400);
        }
    }

    /**
     * The document link is rendered as an anchor in the HR UI — only
     * web URLs are stored, never javascript:/data: schemes.
     */
    static String validateDocumentUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String trimmed = url.trim();
        String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) {
            throw new WebApplicationException("documentUrl must be an http(s) link", 400);
        }
        if (trimmed.length() > 1000) {
            throw new WebApplicationException("documentUrl exceeds 1000 characters", 400);
        }
        return trimmed;
    }

    private static String normalizeCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            return null;
        }
        String cleaned = currency.trim().toUpperCase(java.util.Locale.ROOT);
        if (cleaned.length() != 3) {
            throw new WebApplicationException("currency must be a 3-letter code", 400);
        }
        return cleaned;
    }

    // ── Enrichment ─────────────────────────────────────────────────────────

    /**
     * Per-call memo so a list render resolves each user/company/type/candidate/clause
     * key exactly once — <em>including keys that resolve to nothing</em>.
     *
     * <p>Caching the misses is the whole point of the {@code containsKey}/{@code put}
     * shape below. {@code Map.computeIfAbsent} cannot express it: it treats a null
     * <em>value</em> as an absent <em>key</em>, so a mapping function returning null
     * leaves no entry and runs again for the next row. On the degraded rows that is
     * exactly where the memo is needed — an {@code agreement_type} missing from
     * {@code agreement_types}, a {@code clause_uuid} pointing at a deleted clause, a
     * {@code user_uuid} whose user is gone, a purged candidate — and it turned the
     * memo into an N+1: one query per referencing row instead of one per key.</p>
     *
     * <p>A cached null is a resolved negative, not an empty slot. {@link AgreementService#toDTO}
     * already handles those (an unknown type falls back to the raw type key), so the
     * memo only changes how many queries that costs, never what renders.</p>
     *
     * <p>Package-private, with {@link AgreementService#toDTO}, so the DB-free tier can
     * render a populated DTO by seeding these maps — every lookup below is an inherited
     * Panache static and so unreachable without a database. Seeding a null seeds a
     * resolved negative, which the memo now honours.</p>
     */
    class EnrichmentCache {
        final Map<String, String> userNames = new HashMap<>();
        final Map<String, Company> userCompanies = new HashMap<>();
        final Map<String, RecruitmentCandidate> candidates = new HashMap<>();
        final Map<String, AgreementType> types = new HashMap<>();
        final Map<String, TemplateClauseEntity> clauses = new HashMap<>();

        String userName(String uuid) {
            if (userNames.containsKey(uuid)) {
                return userNames.get(uuid);
            }
            String resolved = User.<User>findByIdOptional(uuid).map(User::getFullname).orElse(null);
            userNames.put(uuid, resolved);
            return resolved;
        }

        Company userCompany(String uuid) {
            if (userCompanies.containsKey(uuid)) {
                return userCompanies.get(uuid);
            }
            UserStatus status = statusService.getLatestEmploymentStatus(uuid);
            Company resolved = status != null ? status.getCompany() : null;
            userCompanies.put(uuid, resolved);
            return resolved;
        }

        RecruitmentCandidate candidate(String uuid) {
            if (candidates.containsKey(uuid)) {
                return candidates.get(uuid);
            }
            // Direct static call, not RecruitmentCandidate::findById — a method reference binds to
            // the un-enhanced PanacheEntityBase stub and throws at runtime (only direct call sites
            // are rewritten by Quarkus build-time enhancement). Same for the two lookups below.
            RecruitmentCandidate resolved = RecruitmentCandidate.findById(uuid);
            candidates.put(uuid, resolved);
            return resolved;
        }

        AgreementType type(String key) {
            if (types.containsKey(key)) {
                return types.get(key);
            }
            AgreementType resolved = AgreementType.findById(key);
            types.put(key, resolved);
            return resolved;
        }

        TemplateClauseEntity clause(String uuid) {
            if (clauses.containsKey(uuid)) {
                return clauses.get(uuid);
            }
            TemplateClauseEntity resolved = TemplateClauseEntity.findById(uuid);
            clauses.put(uuid, resolved);
            return resolved;
        }
    }

    AgreementDTO toDTO(EmployeeAgreement row, EnrichmentCache cache) {
        AgreementDTO.AgreementDTOBuilder builder = AgreementDTO.builder()
                .uuid(row.getUuid())
                .userUuid(row.getUserUuid())
                .candidateUuid(row.getCandidateUuid())
                .agreementType(row.getAgreementType())
                .title(row.getTitle())
                .summary(row.getSummary())
                .amount(row.getAmount())
                .currency(row.getCurrency())
                .validFrom(row.getValidFrom())
                .validTo(row.getValidTo())
                .effectiveDate(row.getEffectiveDate())
                .parameters(parseParameters(row.getParametersJson()))
                .clauseUuid(row.getClauseUuid())
                .clauseVersionUuid(row.getClauseVersionUuid())
                .source(row.getSource())
                .signingCaseKey(row.getSigningCaseKey())
                .status(row.getStatus())
                .createdAt(row.getCreatedAt())
                .createdBy(row.getCreatedBy())
                .modifiedBy(row.getModifiedBy());

        AgreementType type = cache.type(row.getAgreementType());
        builder.agreementTypeName(type != null ? type.getName() : row.getAgreementType());

        if (row.getUserUuid() != null) {
            builder.subjectType("USER").subjectName(cache.userName(row.getUserUuid()));
            Company company = cache.userCompany(row.getUserUuid());
            if (company != null) {
                builder.companyUuid(company.getUuid()).companyName(company.getName());
            }
        } else if (row.getCandidateUuid() != null) {
            builder.subjectType("CANDIDATE");
            RecruitmentCandidate candidate = cache.candidate(row.getCandidateUuid());
            if (candidate != null) {
                builder.subjectName((candidate.getFirstName() + " " + candidate.getLastName()).trim());
                if (candidate.getTargetCompanyUuid() != null) {
                    Company company = Company.findById(candidate.getTargetCompanyUuid());
                    if (company != null) {
                        builder.companyUuid(company.getUuid()).companyName(company.getName());
                    }
                }
            }
        }

        if (row.getClauseUuid() != null) {
            TemplateClauseEntity clause = cache.clause(row.getClauseUuid());
            if (clause != null) {
                builder.clauseName(clause.getName());
            }
        }
        if (row.getClauseVersionUuid() != null) {
            TemplateClauseVersionEntity version = TemplateClauseVersionEntity.findById(row.getClauseVersionUuid());
            if (version != null) {
                builder.clauseVersionNumber(version.getVersionNumber());
            }
        }

        builder.documentUrl(resolveDocumentUrl(row));
        return builder.build();
    }

    /** The row's own URL, when manual entry supplied one; null otherwise. */
    private String resolveDocumentUrl(EmployeeAgreement row) {
        if (row.getDocumentUrl() != null && !row.getDocumentUrl().isBlank()) {
            return row.getDocumentUrl();
        }
        return null;
    }

    private Map<String, String> parseParameters(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {
            });
        } catch (Exception e) {
            log.warnf("Unparseable parameters_json on agreement row: %s", e.getMessage());
            return Map.of();
        }
    }
}
