# fact_revenue_budget View Documentation

## Overview

The `fact_revenue_budget` view provides aggregated budget revenue data with both calendar year and fiscal year dimensions for financial reporting and KPI dashboards.

## Purpose

- Provide monthly budget revenue data for CXO dashboards
- Support fiscal year (July-June) and calendar year (Jan-Dec) reporting
- Enable dimensional analysis by service line, sector, and contract type
- Align with `FinanceCharts.createRevenueChart()` fiscal year logic

## Grain

**One row per**: Company × Service Line × Sector × Contract Type × Month

## Schema

### Primary Key
- `revenue_budget_id` (VARCHAR): Composite key `{company}-{service_line}-{sector}-{contract}-{YYYYMM}`

### Dimension Columns
| Column | Type | Description |
|--------|------|-------------|
| `company_id` | VARCHAR(36) | Company UUID |
| `service_line_id` | VARCHAR(10) | Service line: PM, BA, SA, DEV, CYB, UD |
| `sector_id` | VARCHAR(20) | Client sector: PUBLIC, HEALTH, FINANCIAL, ENERGY, EDUCATION, OTHER |
| `contract_type_id` | VARCHAR(30) | Contract type: PERIOD, T&M, FIXED, SKI0217_2021, etc. |

### Calendar Time Dimensions
| Column | Type | Description |
|--------|------|-------------|
| `month_key` | VARCHAR(6) | Calendar month key "YYYYMM" (e.g., "202501") |
| `year` | INT | Calendar year (2024, 2025, etc.) |
| `month_number` | INT | Calendar month (1=January, 12=December) |

### Fiscal Time Dimensions (Added V120)
| Column | Type | Description |
|--------|------|-------------|
| `fiscal_year` | INT | Fiscal year (e.g., 2024 for Jul 2024 - Jun 2025) |
| `fiscal_month_number` | INT | Fiscal month (1=July, 12=June) |
| `fiscal_month_key` | VARCHAR(10) | Fiscal month key "FY2024-01" |

### Metrics
| Column | Type | Description |
|--------|------|-------------|
| `budget_revenue_dkk` | DECIMAL | Sum of budgetHours × rate (DKK) |
| `budget_hours` | DECIMAL | Sum of budget hours |
| `contract_count` | INT | Distinct count of contracts |
| `consultant_count` | INT | Distinct count of consultants |

### Scenario
| Column | Type | Description |
|--------|------|-------------|
| `budget_scenario` | VARCHAR(20) | Budget version: 'ORIGINAL' (future: REVISED, FORECAST) |

## Data Source

**Base Table**: `bi_budget_per_day`
- Filters: `budgetHours > 0`, `document_date IS NOT NULL`, `companyuuid IS NOT NULL`
- Joins:
  - `user` → `primaryskilltype` (service line)
  - `client` → `segment` (sector)
  - `contracts` → `contracttype` (contract type)

## Fiscal Year Logic

Trustworks fiscal year runs **July 1 - June 30**:

| Calendar Month | Fiscal Year | Fiscal Month Number |
|----------------|-------------|---------------------|
| July 2024 | 2024 | 1 |
| August 2024 | 2024 | 2 |
| September 2024 | 2024 | 3 |
| October 2024 | 2024 | 4 |
| November 2024 | 2024 | 5 |
| December 2024 | 2024 | 6 |
| January 2025 | 2024 | 7 |
| February 2025 | 2024 | 8 |
| March 2025 | 2024 | 9 |
| April 2025 | 2024 | 10 |
| May 2025 | 2024 | 11 |
| June 2025 | 2024 | 12 |

**SQL Logic**:
```sql
-- fiscal_year
CASE
    WHEN month_val >= 7 THEN year_val     -- Jul-Dec: same year
    ELSE year_val - 1                     -- Jan-Jun: previous year
END

-- fiscal_month_number
CASE
    WHEN month_val >= 7 THEN month_val - 6  -- Jul=1, Aug=2, ..., Dec=6
    ELSE month_val + 6                      -- Jan=7, Feb=8, ..., Jun=12
END
```

## Usage Examples

See `intranetservices/CLAUDE.md` for comprehensive query examples.

## Performance Considerations

- **Row Count**: ~200-250 rows per fiscal year (for main company)
- **Dimensions**: 3-5 service lines × 4-6 sectors × 2-3 contract types × 12 months
- **Query Performance**: View uses CTEs with aggregation - suitable for monthly/annual queries
- **Index**: No indexes needed (view, not table)

## Related Components

- **Java Service**: `BudgetService.getCompanyBudgetAmountByPeriod()` (alternative approach)
- **REST API**: `CompanyBudgetResource` at `/companies/{uuid}/budgets/amount`
- **Fiscal Year Utility**: `DateUtils.getCurrentFiscalStartDate()`
- **FinanceCharts**: `FinanceCharts.createRevenueChart()` (fiscal year chart)

## Migration History

- **V115**: Created base `fact_revenue_budget` view (calendar year only)
- **V120**: Added fiscal year columns (`fiscal_year`, `fiscal_month_number`, `fiscal_month_key`)

## Notes

- ⚠️ **Not currently used in Java codebase** - Java code queries `bi_budget_per_day` directly
- 📊 **Intended for BI tools** - Provides clean dimensional model for reporting
- 🔄 **Backward Compatible** - Calendar year columns unchanged, existing queries unaffected
