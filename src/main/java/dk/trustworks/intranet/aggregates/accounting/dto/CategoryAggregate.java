package dk.trustworks.intranet.aggregates.accounting.dto;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

public class CategoryAggregate {
    public String categoryCode;
    public String categoryName;
    /** Allocation per paying company: companyUuid -> amount */
    public Map<String, BigDecimal> allocations = new LinkedHashMap<>();
}