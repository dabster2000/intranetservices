package dk.trustworks.intranet.aggregates.accounting.dto;

import java.util.ArrayList;
import java.util.List;

public class ExpenseDistributionResult {
    public int year;
    public int month;
    public List<CompanySummary> companies = new ArrayList<>();
    public List<AccountDistribution> accounts = new ArrayList<>();
    public List<CategoryAggregate> categories = new ArrayList<>();
    public List<IntercompanyOwe> owesByAccount = new ArrayList<>();
    public List<IntercompanyOweCategory> owesByCategory = new ArrayList<>();
    public ExpenseDistributionResult(int year, int month) {
        this.year = year; this.month = month;
    }
}




