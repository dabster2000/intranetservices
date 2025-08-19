package dk.trustworks.intranet.aggregates.accounting.dto;

import java.math.BigDecimal;

public class IntercompanyOweCategory {
    public String fromCompanyUuid;
    public String toCompanyUuid;
    public String categoryCode;
    public String categoryName;
    public BigDecimal amount;
}