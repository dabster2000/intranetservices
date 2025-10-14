# Team Lead Bonus System Implementation Guide

## Overview
This document describes how to implement REST services for calculating team lead bonuses based on the model described in the bonus documentation. The system calculates bonuses based on two components: pool-based profit sharing and individual production bonuses.

## Core Calculation Components

### 1. Pool-Based Bonus (Profit Sharing)
- Company profit share: **5%** of total profit
- Distribution based on adjusted team utilization
- Minimum utilization threshold: **65%**
- Pro-rated by months in period

### 2. Production Bonus
- Threshold: **1,100,000 kr** per year (pro-rated)
- Commission: **20%** of amount above threshold
- Based on individual invoicing

## Required Data and Services

### Existing Services to Leverage

#### 1. Team Utilization Data
**Service:** `UtilizationService` and `UtilizationResource`

```java
// Get team utilization for fiscal year
GET /company/{companyuuid}/utilization/actual/teams/{teamuuid}?fiscalYear=YYYY
```
- **Path:** `src/main/java/dk/trustworks/intranet/aggregates/utilization/resources/UtilizationResource.java:173`
- **Returns:** List of `DateValueDTO` with monthly utilization percentages
- **Usage:** Calculate average utilization for the bonus period

#### 2. Team Size and Members
**Service:** `TeamService`

```java
// Get team members for each month to calculate average team size
GET /teams/{teamuuid}/users/search/findByMonth?month=YYYY-MM-DD
```
- **Path:** `src/main/java/dk/trustworks/intranet/apigateway/resources/TeamResource.java:60`
- **Usage:** Count members for each month to get average team size

#### 3. Individual Revenue (Production)
**Service:** `RevenueService`

```java
// Get consultant's registered revenue for period
GET /users/{useruuid}/revenue/registered?periodFrom=YYYY-MM-DD&periodTo=YYYY-MM-DD
```
- **Path:** `src/main/java/dk/trustworks/intranet/aggregates/revenue/resources/EmployeeRevenueResource.java:36`
- **Returns:** Monthly revenue data for calculating production bonus

#### 4. Team Leaders
**Service:** `TeamService`

```java
// Get team leaders for a specific month
GET /teams/{teamuuid}/users/search/findTeamleadersByMonth?month=YYYY-MM-DD
```
- **Path:** `src/main/java/dk/trustworks/intranet/apigateway/resources/TeamResource.java:66`

## Proposed New REST Endpoints

### 1. Team Lead Bonus Calculation Endpoint

```http
GET /bonuses/teamlead/{useruuid}/calculate
```

**Query Parameters:**
- `fiscalYear`: Integer (e.g., 2023 for July 2023 - June 2024)
- `companyProfit`: Double (total company profit)
- `prepaidBonus`: Double (optional, default 0)
- `teamSplit`: Double (optional, default 0)

**Response Structure:**
```json
{
  "teamLeadUuid": "string",
  "teamLeadName": "string",
  "period": {
    "startDate": "2023-07-01",
    "endDate": "2024-06-30",
    "monthsActive": 12
  },
  "teamMetrics": {
    "averageUtilization": 0.8547,
    "averageTeamSize": 9.62,
    "teamFactor": 1.5,
    "adjustedUtilization": 1.53525
  },
  "poolBonus": {
    "companyProfit": 7700431,
    "poolPercentage": 0.05,
    "totalPool": 385021.55,
    "pricePerPoint": 673.03,
    "teamLeadShare": 103326.37,
    "proRatedShare": 103326.37
  },
  "productionBonus": {
    "totalInvoiced": 1121769,
    "annualThreshold": 1100000,
    "proRatedThreshold": 1100000,
    "aboveThreshold": 21769,
    "bonusPercentage": 0.20,
    "bonusAmount": 4353.80
  },
  "adjustments": {
    "teamSplit": 25000,
    "prepaidBonus": 25000
  },
  "totalBonus": 107680.17
}
```

### 2. Batch Calculation for All Team Leads

```http
GET /bonuses/teamlead/calculate-all
```

**Query Parameters:**
- `fiscalYear`: Integer
- `companyProfit`: Double

**Response Structure:**
```json
{
  "fiscalYear": "2023-2024",
  "companyProfit": 7700431,
  "totalPool": 385021.55,
  "teamLeadBonuses": [
    {
      "teamLeadUuid": "string",
      "teamLeadName": "string",
      "teamName": "string",
      "poolBonus": 103326.37,
      "productionBonus": 4353.80,
      "totalBonus": 107680.17
    }
  ],
  "summary": {
    "totalBonusesPaid": 584330.92,
    "totalPoolDistributed": 385021.55,
    "totalProductionBonuses": 199309.37
  }
}
```

## Implementation Service Classes

### BonusCalculationService

```java
package dk.trustworks.intranet.aggregates.bonus.services;

@ApplicationScoped
public class BonusCalculationService {

    @Inject UtilizationService utilizationService;
    @Inject TeamService teamService;
    @Inject RevenueService revenueService;

    public TeamLeadBonus calculateTeamLeadBonus(
        String teamLeadUuid,
        LocalDate startDate,
        LocalDate endDate,
        double companyProfit,
        double prepaidBonus,
        double teamSplit
    ) {
        // 1. Get team utilization
        double avgUtilization = calculateAverageTeamUtilization(teamLeadUuid, startDate, endDate);

        // 2. Get average team size
        double avgTeamSize = calculateAverageTeamSize(teamLeadUuid, startDate, endDate);

        // 3. Calculate team factor
        double teamFactor = getTeamFactor(avgTeamSize);

        // 4. Calculate pool bonus
        double poolBonus = calculatePoolBonus(
            avgUtilization,
            teamFactor,
            companyProfit,
            getMonthsInPeriod(startDate, endDate)
        );

        // 5. Get individual production
        double totalInvoiced = getTeamLeadInvoicing(teamLeadUuid, startDate, endDate);

        // 6. Calculate production bonus
        double productionBonus = calculateProductionBonus(
            totalInvoiced,
            getMonthsInPeriod(startDate, endDate)
        );

        // 7. Calculate total with adjustments
        double totalBonus = poolBonus + productionBonus + teamSplit - prepaidBonus;

        return new TeamLeadBonus(/* ... */);
    }

    private double calculatePoolBonus(
        double utilization,
        double teamFactor,
        double companyProfit,
        int months
    ) {
        final double MIN_UTILIZATION = 0.65;
        final double POOL_PERCENTAGE = 0.05;
        final double PRICE_PER_POINT = 673.03;

        if (utilization < MIN_UTILIZATION) {
            return 0.0;
        }

        // Calculate points
        double adjustedUtil = (utilization - MIN_UTILIZATION) * 5 * teamFactor;

        // Calculate share of pool
        double poolShare = adjustedUtil * 100 * PRICE_PER_POINT;

        // Pro-rate by months
        return (poolShare / 12) * months;
    }

    private double calculateProductionBonus(double totalInvoiced, int months) {
        final double ANNUAL_THRESHOLD = 1_100_000;
        final double COMMISSION_RATE = 0.20;

        double proRatedThreshold = (ANNUAL_THRESHOLD / 12) * months;
        double aboveThreshold = totalInvoiced - proRatedThreshold;

        return Math.max(aboveThreshold * COMMISSION_RATE, 0);
    }

    private double getTeamFactor(double avgTeamSize) {
        if (avgTeamSize <= 6) return 1.0;
        if (avgTeamSize <= 10) return 1.5;
        if (avgTeamSize <= 12) return 2.0;
        return 1.0; // Default
    }
}
```

### BonusResource (REST Controller)

```java
package dk.trustworks.intranet.aggregates.bonus.resources;

@Path("/bonuses/teamlead")
@RequestScoped
@Produces(APPLICATION_JSON)
@RolesAllowed({"ADMIN", "PARTNER"})
public class TeamLeadBonusResource {

    @Inject BonusCalculationService bonusService;

    @GET
    @Path("/{teamleaduuid}/calculate")
    public TeamLeadBonusDTO calculateBonus(
        @PathParam("teamleaduuid") String teamLeadUuid,
        @QueryParam("fiscalYear") int fiscalYear,
        @QueryParam("companyProfit") double companyProfit,
        @QueryParam("prepaidBonus") @DefaultValue("0") double prepaidBonus,
        @QueryParam("teamSplit") @DefaultValue("0") double teamSplit
    ) {
        LocalDate startDate = LocalDate.of(fiscalYear, 7, 1);
        LocalDate endDate = LocalDate.of(fiscalYear + 1, 6, 30);

        TeamLeadBonus bonus = bonusService.calculateTeamLeadBonus(
            teamLeadUuid,
            startDate,
            endDate,
            companyProfit,
            prepaidBonus,
            teamSplit
        );

        return TeamLeadBonusDTO.from(bonus);
    }

    @GET
    @Path("/calculate-all")
    public AllTeamLeadBonusesDTO calculateAllBonuses(
        @QueryParam("fiscalYear") int fiscalYear,
        @QueryParam("companyProfit") double companyProfit
    ) {
        // Implementation to calculate for all team leads
    }
}
```

## Data Models

### TeamLeadBonusDTO

```java
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TeamLeadBonusDTO {
    private String teamLeadUuid;
    private String teamLeadName;
    private PeriodDTO period;
    private TeamMetricsDTO teamMetrics;
    private PoolBonusDTO poolBonus;
    private ProductionBonusDTO productionBonus;
    private AdjustmentsDTO adjustments;
    private double totalBonus;
}

@Data
public class TeamMetricsDTO {
    private double averageUtilization;
    private double averageTeamSize;
    private double teamFactor;
    private double adjustedUtilization;
}

@Data
public class PoolBonusDTO {
    private double companyProfit;
    private double poolPercentage;
    private double totalPool;
    private double pricePerPoint;
    private double teamLeadShare;
    private double proRatedShare;
}

@Data
public class ProductionBonusDTO {
    private double totalInvoiced;
    private double annualThreshold;
    private double proRatedThreshold;
    private double aboveThreshold;
    private double bonusPercentage;
    private double bonusAmount;
}
```

## Configuration Parameters

### Application Configuration

Add to `application.yml`:

```yaml
bonus:
  teamlead:
    pool-percentage: 0.05
    min-utilization: 0.65
    price-per-point: 673.03
    production-threshold: 1100000
    production-commission: 0.20
    team-factors:
      small: 1.0    # 1-6 members
      medium: 1.5   # 7-10 members
      large: 2.0    # 11-12 members
```

## Testing Strategy

### Unit Tests

```java
@QuarkusTest
public class BonusCalculationServiceTest {

    @Inject
    BonusCalculationService service;

    @Test
    public void testPoolBonusCalculation() {
        // Test with utilization above threshold
        double poolBonus = service.calculatePoolBonus(0.8547, 1.5, 7700431, 12);
        assertEquals(103326.37, poolBonus, 0.01);
    }

    @Test
    public void testProductionBonusCalculation() {
        // Test with invoicing above threshold
        double productionBonus = service.calculateProductionBonus(1121769, 12);
        assertEquals(4353.80, productionBonus, 0.01);
    }

    @Test
    public void testBelowMinimumUtilization() {
        // Test that pool bonus is 0 when below 65%
        double poolBonus = service.calculatePoolBonus(0.60, 1.0, 7700431, 12);
        assertEquals(0.0, poolBonus, 0.01);
    }
}
```

## Database Schema (Optional)

If you want to persist bonus calculations:

```sql
CREATE TABLE team_lead_bonus (
    uuid VARCHAR(36) PRIMARY KEY,
    team_lead_uuid VARCHAR(36) NOT NULL,
    fiscal_year INT NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    months_active INT NOT NULL,
    avg_utilization DECIMAL(5,4),
    avg_team_size DECIMAL(4,2),
    team_factor DECIMAL(3,1),
    pool_bonus DECIMAL(10,2),
    production_bonus DECIMAL(10,2),
    team_split DECIMAL(10,2),
    prepaid_bonus DECIMAL(10,2),
    total_bonus DECIMAL(10,2),
    calculated_at TIMESTAMP NOT NULL,
    FOREIGN KEY (team_lead_uuid) REFERENCES user(uuid)
);

CREATE INDEX idx_team_lead_bonus_fiscal ON team_lead_bonus(fiscal_year);
CREATE INDEX idx_team_lead_bonus_user ON team_lead_bonus(team_lead_uuid);
```

## Security Considerations

1. **Access Control**: Endpoints should be restricted to ADMIN/PARTNER roles
2. **Audit Trail**: Log all bonus calculations with timestamps and user who triggered calculation
3. **Data Validation**: Validate all input parameters (fiscal year range, profit amounts)
4. **Rate Limiting**: Implement rate limiting on calculation endpoints to prevent abuse

## Integration Points

### 1. With Existing Salary System
- Bonus calculations could be integrated with `SalarySupplementService`
- Create salary supplements automatically when bonuses are approved

### 2. With Reporting System
- Export bonus calculations to reporting/BI systems
- Generate PDF reports for team leads

### 3. With Notification System
- Send notifications when bonuses are calculated
- Alert team leads when bonuses are approved for payment

## Example API Calls

### Calculate Single Team Lead Bonus

```bash
curl -X GET "http://localhost:8080/bonuses/teamlead/uuid-stephan/calculate?\
fiscalYear=2023&\
companyProfit=7700431&\
prepaidBonus=25000&\
teamSplit=25000" \
-H "Authorization: Bearer $JWT_TOKEN"
```

### Calculate All Team Lead Bonuses

```bash
curl -X GET "http://localhost:8080/bonuses/teamlead/calculate-all?\
fiscalYear=2023&\
companyProfit=7700431" \
-H "Authorization: Bearer $JWT_TOKEN"
```

## Validation Rules

1. **Fiscal Year**: Must be valid year (e.g., 2023 represents July 2023 - June 2024)
2. **Company Profit**: Must be positive number
3. **Utilization**: Automatically capped between 0.0 and 1.0
4. **Team Size**: Must be positive integer
5. **Months Active**: Must be between 1 and 12

## Error Handling

```java
@Provider
public class BonusExceptionMapper implements ExceptionMapper<BonusCalculationException> {
    @Override
    public Response toResponse(BonusCalculationException e) {
        return Response.status(Response.Status.BAD_REQUEST)
            .entity(new ErrorResponse(e.getMessage(), e.getErrorCode()))
            .build();
    }
}
```

## Performance Optimization

1. **Caching**: Cache team utilization and size calculations
2. **Batch Processing**: Calculate all team leads in parallel
3. **Database Indexes**: Add indexes on frequently queried fields
4. **Async Processing**: For large calculations, use async processing with status polling

## Monitoring and Metrics

Add metrics to track:
- Bonus calculation execution time
- Number of calculations per period
- Average bonus amounts
- Errors and failures

```java
@Counted(name = "bonus_calculations", description = "Number of bonus calculations")
@Timed(name = "bonus_calculation_time", description = "Time to calculate bonus")
public TeamLeadBonus calculateBonus(...) {
    // Implementation
}
```