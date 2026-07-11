package dk.trustworks.intranet.contracts.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Response DTO for one framework agreement audit entry.
 *
 * <p>Shape is pinned by the cross-repo contract (C4) consumed by the BFF passthrough at
 * {@code GET /api/framework-agreements/[code]/audit}:
 * {@code {"id":number,"entityType":"AGREEMENT"|"PRICING_RULE"|"VALIDATION_RULE",
 * "ruleId":string|null,"operation":"CREATE"|"UPDATE"|"DELETE"|"RESTORE",
 * "changedBy":string|null,"changedByName":string|null,"changedAt":"<ISO-8601>",
 * "summary":string|null}}
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ContractTypeAuditEntryDTO {

    private Long id;

    /** AGREEMENT | PRICING_RULE | VALIDATION_RULE */
    private String entityType;

    /** Rule id for rule-level entries; null for AGREEMENT entries. */
    private String ruleId;

    /** CREATE | UPDATE | DELETE | RESTORE */
    private String operation;

    /** User uuid from X-Requested-By (or a system:&lt;client&gt; principal); null when unattributed. */
    private String changedBy;

    /** Full name resolved from the user table when changedBy is a known user uuid; else null. */
    private String changedByName;

    /** When the change happened (ISO-8601). */
    private LocalDateTime changedAt;

    /** Short human-readable field diff, e.g. "active: true -&gt; false"; null when unavailable. */
    private String summary;
}
