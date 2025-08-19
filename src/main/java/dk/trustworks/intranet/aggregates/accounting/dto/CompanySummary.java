package dk.trustworks.intranet.aggregates.accounting.dto;

import java.math.BigDecimal;

public class CompanySummary {
    public String companyUuid;
    public String companyName;
    /** Headcount used for ratio (for transparency) */
    public BigDecimal consultants = BigDecimal.ZERO;
    /** STAFF cost originated in this company (BI * pension multiplier) */
    public BigDecimal staffCostOrigin = BigDecimal.ZERO;
    /** What this company pays for STAFF across all companies after sharing */
    public BigDecimal staffPayable = BigDecimal.ZERO;
}