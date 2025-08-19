package dk.trustworks.intranet.aggregates.accounting.dto;

import java.math.BigDecimal;

public class IntercompanyOwe {
    /** payer */
    public String fromCompanyUuid;
    /** receiver (origin) */
    public String toCompanyUuid;
    public int accountCode;
    public BigDecimal amount;
}