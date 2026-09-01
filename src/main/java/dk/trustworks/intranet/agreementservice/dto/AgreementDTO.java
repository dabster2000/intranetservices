package dk.trustworks.intranet.agreementservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * One registry row (template-clauses spec §4.7) enriched for the HR
 * surfaces: subject name + company resolved from the user/candidate,
 * clause wording version resolved from the library, and the signed-PDF
 * link resolved from the signing case when the row itself carries none.
 *
 * <p>Served exclusively by {@code AgreementResource} under
 * {@code agreements:*} — never on User responses (spec §9).</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgreementDTO {

    private String uuid;

    /** USER or CANDIDATE — which side of the XOR the row sits on. */
    private String subjectType;
    private String userUuid;
    private String candidateUuid;
    /** Full name of the employee or candidate. */
    private String subjectName;
    /** The subject's company (employee: active status; candidate: target). */
    private String companyUuid;
    private String companyName;

    private String agreementType;
    /** Danish display name from the vocabulary. */
    private String agreementTypeName;
    private String title;
    private String summary;

    private BigDecimal amount;
    private String currency;
    private LocalDate validFrom;
    private LocalDate validTo;
    private LocalDate effectiveDate;

    /** Parameters not mapped to first-class columns. */
    private Map<String, String> parameters;

    private String clauseUuid;
    private String clauseName;
    private String clauseVersionUuid;
    /** Wording version signed (D7); null for manual/custom rows. */
    private Integer clauseVersionNumber;

    /** SIGNED_CASE / BACKFILL / MANUAL. */
    private String source;
    private String signingCaseKey;
    /** Signed PDF link — the row's own URL, else the case's archived URL. */
    private String documentUrl;

    /** ACTIVE / EXPIRED / SUPERSEDED / TERMINATED. */
    private String status;

    private LocalDateTime createdAt;
    private String createdBy;
    private String modifiedBy;
}
