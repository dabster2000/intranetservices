package dk.trustworks.intranet.aggregates.accounting.dto;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

public class AccountDistribution {
    public int accountCode;
    public String accountDescription;
    public String categoryCode;
    public String categoryName;
    public boolean shared;
    public boolean salary;
    /** Company that holds the original GL for this account */
    public String originCompanyUuid;
    /** Allocation per paying company: companyUuid -> amount */
    public Map<String, BigDecimal> allocations = new LinkedHashMap<>();
}