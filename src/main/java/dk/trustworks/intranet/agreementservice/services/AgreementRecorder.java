package dk.trustworks.intranet.agreementservice.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.agreementservice.model.AgreementType;
import dk.trustworks.intranet.agreementservice.model.EmployeeAgreement;
import dk.trustworks.intranet.agreementservice.model.enums.AgreementSource;
import dk.trustworks.intranet.agreementservice.model.enums.AgreementStatus;
import dk.trustworks.intranet.documentservice.model.TemplateClauseEntity;
import dk.trustworks.intranet.documentservice.model.TemplateClausePlaceholderEntity;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossier;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossierRevision;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.signing.domain.SigningCase;
import dk.trustworks.intranet.signing.domain.SigningCaseClause;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import lombok.extern.jbosslog.JBossLog;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes {@code employee_agreements} rows when a signing case with
 * clauses reaches COMPLETED (template-clauses spec §8, Phase 3).
 *
 * <p>Called from {@code NextSignStatusSyncBatchlet} inside the same
 * transaction as the completion update. Idempotent on
 * {@code (signing_case_key, clause_uuid | custom_title)} so the inline
 * call and the catch-up sweep can both run safely.</p>
 *
 * <p><b>Subject derivation</b> mirrors {@code EmployeeSigningArchivalService}:
 * a case referenced by {@code candidate_dossier_revisions.signing_case_key}
 * is recruitment-flow — its subject is the dossier's CANDIDATE (the case's
 * {@code user_uuid} is the sending HR user). If that candidate has already
 * converted, the row is written against the converted user directly.
 * Every other case is employee-flow and the subject is the case's
 * {@code user_uuid}.</p>
 *
 * <p>Failures are logged and swallowed — the case is already completed in
 * NextSign and the status update must not be rolled back over a registry
 * write; the catch-up sweep retries on the next pass.</p>
 */
@JBossLog
@ApplicationScoped
public class AgreementRecorder {

    /** Bound per pass — mirrors the batchlet's archival sweep bound. */
    private static final int CATCHUP_SWEEP_LIMIT = 25;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    EntityManager em;

    /**
     * Record registry rows for one completed case. No-op when the case
     * has no clause snapshot rows. Never throws.
     *
     * @return number of registry rows written this call
     */
    public int recordCompletedCase(SigningCase signingCase) {
        String caseKey = signingCase.getCaseKey();
        try {
            List<SigningCaseClause> clauses = SigningCaseClause.findByCase(signingCase.getId());
            if (clauses.isEmpty()) {
                return 0;
            }

            Subject subject = deriveSubject(signingCase);
            if (subject == null) {
                log.errorf("Cannot record %d agreement rows for case %s: no subject resolvable"
                        + " (no user_uuid and no dossier linkage)", clauses.size(), caseKey);
                return 0;
            }

            int written = 0;
            for (SigningCaseClause clause : clauses) {
                if (alreadyRecorded(caseKey, clause)) {
                    continue;
                }
                EmployeeAgreement row = buildRow(caseKey, clause, subject);
                if (row != null) {
                    row.persist();
                    written++;
                }
            }
            if (written > 0) {
                log.infof("Recorded %d agreement registry rows for case %s (subject %s)",
                        written, caseKey, subject);
            }
            return written;
        } catch (Exception e) {
            log.errorf(e, "Failed to record agreement registry rows for case %s — the catch-up"
                    + " sweep retries on the next pass", caseKey);
            return 0;
        }
    }

    /**
     * Catch-up: completed cases holding clause snapshot rows but no
     * registry rows — covers cases that completed while Phase 3 was not
     * yet deployed, and any inline recording that failed. Bounded per
     * pass; idempotent per row. Never throws.
     *
     * @return registry rows written this pass
     */
    public int runCatchupSweep() {
        try {
            @SuppressWarnings("unchecked")
            List<SigningCase> missed = em.createNativeQuery("""
                    SELECT DISTINCT sc.* FROM signing_cases sc
                    JOIN signing_case_clauses scc ON scc.signing_case_id = sc.id
                    WHERE LOWER(sc.status) = 'completed'
                      AND NOT EXISTS (SELECT 1 FROM employee_agreements ea
                                      WHERE ea.signing_case_key = sc.case_key)
                    ORDER BY sc.id
                    LIMIT %d""".formatted(CATCHUP_SWEEP_LIMIT), SigningCase.class)
                    .getResultList();
            int written = 0;
            for (SigningCase signingCase : missed) {
                written += recordCompletedCase(signingCase);
            }
            if (!missed.isEmpty()) {
                log.infof("Agreement catch-up sweep: %d rows written across %d cases",
                        written, missed.size());
            }
            return written;
        } catch (Exception e) {
            log.errorf(e, "Agreement catch-up sweep failed (status fetch pass unaffected)");
            return 0;
        }
    }

    // ── Row building ───────────────────────────────────────────────────────

    private boolean alreadyRecorded(String caseKey, SigningCaseClause clause) {
        if (clause.getClauseUuid() != null) {
            return EmployeeAgreement.existsForCaseClause(caseKey, clause.getClauseUuid());
        }
        return EmployeeAgreement.existsForCaseCustom(caseKey, customTitleOf(clause));
    }

    private EmployeeAgreement buildRow(String caseKey, SigningCaseClause clause, Subject subject) {
        EmployeeAgreement row = new EmployeeAgreement();
        row.setUserUuid(subject.userUuid());
        row.setCandidateUuid(subject.candidateUuid());
        row.setSource(AgreementSource.SIGNED_CASE.name());
        row.setStatus(AgreementStatus.ACTIVE.name());
        row.setSigningCaseKey(caseKey);
        row.setClauseUuid(clause.getClauseUuid());
        row.setClauseVersionUuid(clause.getClauseVersionUuid());
        row.setParametersJson(clause.getParameterValuesJson());

        if (clause.getClauseUuid() == null) {
            // Free-text Individuel aftale (D6).
            row.setAgreementType(AgreementType.INDIVIDUEL);
            row.setTitle(customTitleOf(clause));
            row.setSummary(clause.getCustomText());
            return row;
        }

        TemplateClauseEntity libraryClause = TemplateClauseEntity.findById(clause.getClauseUuid());
        String typeKey = libraryClause != null ? libraryClause.getAgreementType() : null;
        if (typeKey == null || typeKey.isBlank() || AgreementType.findById(typeKey) == null) {
            if (typeKey != null && !typeKey.isBlank()) {
                log.warnf("Clause %s maps to unknown agreement type '%s' — recording as %s",
                        clause.getClauseUuid(), typeKey, AgreementType.INDIVIDUEL);
            }
            typeKey = AgreementType.INDIVIDUEL;
        }
        row.setAgreementType(typeKey);
        row.setTitle(libraryClause != null && libraryClause.getName() != null
                ? libraryClause.getName()
                : typeKey);
        row.setSummary(libraryClause != null ? libraryClause.getDescription() : null);

        Map<String, String> values = parseValues(clause.getParameterValuesJson());
        RegistryFields fields = mapRegistryFields(values,
                TemplateClausePlaceholderEntity.findByClause(clause.getClauseUuid()));
        row.setAmount(fields.amount());
        row.setCurrency(fields.currency());
        row.setValidFrom(fields.validFrom());
        row.setValidTo(fields.validTo());
        row.setEffectiveDate(fields.effectiveDate());
        return row;
    }

    private String customTitleOf(SigningCaseClause clause) {
        String title = clause.getCustomTitle();
        return (title == null || title.isBlank()) ? "Individuel aftale" : title;
    }

    private Map<String, String> parseValues(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {
            });
        } catch (Exception e) {
            log.warnf("Unparseable parameter_values_json — registry fields left empty: %s",
                    e.getMessage());
            return Map.of();
        }
    }

    // ── Subject derivation ─────────────────────────────────────────────────

    /** Exactly one of the two is non-null. */
    record Subject(String userUuid, String candidateUuid) {
        @Override
        public String toString() {
            return userUuid != null ? "user=" + userUuid : "candidate=" + candidateUuid;
        }
    }

    private Subject deriveSubject(SigningCase signingCase) {
        CandidateDossierRevision revision = CandidateDossierRevision
                .find("signingCaseKey = ?1 ORDER BY createdAt DESC", signingCase.getCaseKey())
                .firstResult();
        if (revision != null) {
            CandidateDossier dossier = CandidateDossier.findById(revision.getDossierUuid());
            if (dossier == null || dossier.getCandidateUuid() == null) {
                return null;
            }
            RecruitmentCandidate candidate = RecruitmentCandidate.findById(dossier.getCandidateUuid());
            if (candidate != null && candidate.getConvertedUserUuid() != null
                    && !candidate.getConvertedUserUuid().isBlank()) {
                // Completion arrived after HIRED conversion — write against
                // the user directly instead of a row the re-key already ran for.
                return new Subject(candidate.getConvertedUserUuid(), null);
            }
            return new Subject(null, dossier.getCandidateUuid());
        }
        String userUuid = signingCase.getUserUuid();
        if (userUuid == null || userUuid.isBlank()) {
            return null;
        }
        return new Subject(userUuid, null);
    }

    // ── registry_field mapping (pure — unit tested) ────────────────────────

    /** First-class column values extracted from a clause's parameters. */
    public record RegistryFields(BigDecimal amount, String currency, LocalDate validFrom,
                                 LocalDate validTo, LocalDate effectiveDate) {
        static final RegistryFields EMPTY = new RegistryFields(null, null, null, null, null);
    }

    /**
     * Map parameter values into first-class registry columns via each
     * placeholder's {@code registry_field} (spec §4.3): AMOUNT, CURRENCY,
     * VALID_FROM, VALID_TO, EFFECTIVE_DATE. Unparseable values are
     * dropped (the raw value is still in {@code parameters_json}).
     */
    public static RegistryFields mapRegistryFields(Map<String, String> values,
                                                   List<TemplateClausePlaceholderEntity> placeholders) {
        if (values.isEmpty() || placeholders == null || placeholders.isEmpty()) {
            return RegistryFields.EMPTY;
        }
        BigDecimal amount = null;
        String currency = null;
        LocalDate validFrom = null;
        LocalDate validTo = null;
        LocalDate effectiveDate = null;
        for (TemplateClausePlaceholderEntity placeholder : placeholders) {
            String field = placeholder.getRegistryField();
            if (field == null || field.isBlank()) {
                continue;
            }
            String raw = values.get(placeholder.getPlaceholderKey());
            if (raw == null || raw.isBlank()) {
                continue;
            }
            switch (field.toUpperCase(Locale.ROOT)) {
                case "AMOUNT" -> amount = parseAmount(raw);
                case "CURRENCY" -> currency = normalizeCurrency(raw);
                case "VALID_FROM" -> validFrom = parseDate(raw);
                case "VALID_TO" -> validTo = parseDate(raw);
                case "EFFECTIVE_DATE" -> effectiveDate = parseDate(raw);
                default -> { /* unknown mapping — value stays in parameters_json */ }
            }
        }
        return new RegistryFields(amount, currency, validFrom, validTo, effectiveDate);
    }

    /**
     * Parse a human-entered Danish/ISO amount: "60.000", "60.000,50",
     * "60000.50", "60 000 kr." all resolve; ambiguity between Danish
     * thousand-dot and ISO decimal-dot is settled by the digit count
     * after the final separator (two digits = decimals).
     */
    public static BigDecimal parseAmount(String raw) {
        String cleaned = raw.replaceAll("[^0-9,.\\-]", "");
        if (cleaned.isEmpty() || cleaned.equals("-")) {
            return null;
        }
        boolean hasComma = cleaned.contains(",");
        boolean hasDot = cleaned.contains(".");
        String normalized;
        if (hasComma) {
            // Danish: dots are grouping, the comma is the decimal mark.
            normalized = cleaned.replace(".", "").replace(',', '.');
        } else if (hasDot) {
            int lastDot = cleaned.lastIndexOf('.');
            int digitsAfter = cleaned.length() - lastDot - 1;
            boolean singleDot = cleaned.indexOf('.') == lastDot;
            if (singleDot && (digitsAfter == 1 || digitsAfter == 2)) {
                normalized = cleaned; // ISO decimal ("60000.50")
            } else {
                normalized = cleaned.replace(".", ""); // grouping ("60.000")
            }
        } else {
            normalized = cleaned;
        }
        try {
            return new BigDecimal(normalized);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd-MM-uuuu"),
            DateTimeFormatter.ofPattern("dd.MM.uuuu"),
            DateTimeFormatter.ofPattern("dd/MM/uuuu"),
            DateTimeFormatter.ofPattern("dd-MM-uu"));

    /** ISO first, then the Danish day-first shapes. Null when nothing fits. */
    public static LocalDate parseDate(String raw) {
        String trimmed = raw.trim();
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(trimmed, format);
            } catch (DateTimeParseException ignored) {
                // try the next shape
            }
        }
        return null;
    }

    /** "kr", "kr.", "dkk" → DKK; any other 3-letter code passes upper-cased. */
    public static String normalizeCurrency(String raw) {
        String cleaned = raw.trim().toUpperCase(Locale.ROOT).replace(".", "");
        if (cleaned.equals("KR") || cleaned.equals("DKK")) {
            return "DKK";
        }
        return cleaned.length() == 3 && cleaned.chars().allMatch(Character::isLetter)
                ? cleaned
                : null;
    }
}
