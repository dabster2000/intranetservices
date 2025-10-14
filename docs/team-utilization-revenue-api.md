# Team, Utilization, Revenue, and Salary API Documentation

## Overview
This document provides answers to common questions about accessing team members, utilization metrics, salary information, and revenue calculations within the Trustworks Intranet Services API.

## a) Getting Members of a Specific Team for a Specific Month

### Service: `TeamService`
**Location:** `dk.trustworks.intranet.userservice.services.TeamService`

#### Primary Method:
```java
public List<User> getUsersByTeam(String teamuuid, LocalDate month)
```
- **Path:** `src/main/java/dk/trustworks/intranet/userservice/services/TeamService.java:52`
- **Description:** Returns all users who are members of a specific team in a specific month
- **Parameters:**
  - `teamuuid`: UUID of the team
  - `month`: The month to query (LocalDate)
- **Returns:** List of User objects who are active team members in the specified month

#### REST Endpoints:
```http
GET /teams/{teamuuid}/users/search/findByMonth?month=YYYY-MM-DD
```
- **Path:** `src/main/java/dk/trustworks/intranet/apigateway/resources/TeamResource.java:60`
- **Description:** Get team members for a specific month
- **Authentication:** JWT with SYSTEM role

```http
GET /teams/{teamuuid}/users
```
- **Path:** `src/main/java/dk/trustworks/intranet/apigateway/resources/TeamResource.java:49`
- **Description:** Get all current team members

```http
GET /teams/{teamuuid}/users/search/findByFiscalYear?fiscalyear=YYYY
```
- **Path:** `src/main/java/dk/trustworks/intranet/apigateway/resources/TeamResource.java:72`
- **Description:** Get all unique team members throughout a fiscal year

#### Alternative Methods:
- `getTeamLeadersByTeam(String teamuuid, LocalDate month)` - Returns team leaders for a specific team and month (line 64)
  - **REST:** `GET /teams/{teamuuid}/users/search/findTeamleadersByMonth?month=YYYY-MM-DD`
- `getUsersByTeamAndFiscalYear(String teamuuid, int intFiscalYear)` - Returns all unique team members throughout a fiscal year (line 145)

## b) Getting Utilization for a Specific User in a Period

### Service: `UtilizationService` and `UserUtilizationResource`
**Location:** `dk.trustworks.intranet.aggregates.utilization`

#### For Actual Utilization:
```java
// REST Endpoint
@GET
@Path("/users/{useruuid}/utilization/actuals")
public List<DateValueDTO> getActualUtilizationPerMonthByConsultant(String useruuid, String fromdate, String todate)
```
- **Path:** `src/main/java/dk/trustworks/intranet/aggregates/utilization/resources/UserUtilizationResource.java:54`
- **REST:** GET `/users/{useruuid}/utilization/actuals?fromdate=YYYY-MM-DD&todate=YYYY-MM-DD`
- **Returns:** List of DateValueDTO with utilization percentages per month

#### For Budget/Contract Utilization:
```java
// REST Endpoint
@GET
@Path("/users/{useruuid}/utilization/budgets")
public List<DateValueDTO> getBudgetUtilizationPerMonthByConsultant(String useruuid, String fromdate, String todate)
```
- **Path:** `src/main/java/dk/trustworks/intranet/aggregates/utilization/resources/UserUtilizationResource.java:44`
- **REST:** GET `/users/{useruuid}/utilization/budgets?fromdate=YYYY-MM-DD&todate=YYYY-MM-DD`

#### Service Method:
```java
public List<DateValueDTO> calculateActualUtilizationPerMonthByConsultant(String useruuid, LocalDate fromDate, LocalDate toDate, List<DateValueDTO> workService)
```
- **Path:** `src/main/java/dk/trustworks/intranet/aggregates/utilization/services/UtilizationService.java:45`
- **Description:** Calculates utilization based on work hours vs available hours

## c) Getting Salary Per Month of a User

### Service: `SalaryService`
**Location:** `dk.trustworks.intranet.aggregates.users.services.SalaryService`

#### Primary Method:
```java
public Salary getUserSalaryByMonth(String useruuid, LocalDate month)
```
- **Path:** `src/main/java/dk/trustworks/intranet/aggregates/users/services/SalaryService.java:31`
- **Description:** Returns the salary object for a user at a specific month
- **Parameters:**
  - `useruuid`: UUID of the user
  - `month`: The month to query
- **Returns:** Salary object containing salary amount and other compensation details

#### REST Endpoints:
```http
GET /users/{useruuid}/salaries
```
- **Path:** `src/main/java/dk/trustworks/intranet/aggregates/users/resources/SalaryResource.java:74`
- **Description:** Get all salary entries for a user
- **Authentication:** JWT with SYSTEM role
- **Returns:** List of Salary objects

```http
GET /users/{useruuid}/salaries/payments/{month}
```
- **Path:** `src/main/java/dk/trustworks/intranet/aggregates/users/resources/SalaryResource.java:80`
- **Description:** Get detailed payment breakdown for a specific month
- **Returns:** List of SalaryPayment objects including base salary, supplements, vacation, expenses, etc.

```http
POST /users/{useruuid}/salaries
```
- **Path:** `src/main/java/dk/trustworks/intranet/aggregates/users/resources/SalaryResource.java:219`
- **Description:** Create or update salary entry

```http
DELETE /users/{useruuid}/salaries/{salaryuuid}
```
- **Path:** `src/main/java/dk/trustworks/intranet/aggregates/users/resources/SalaryResource.java:226`
- **Description:** Delete a salary entry

#### Alternative Methods:
- `listAll(String useruuid)` - Returns all salary entries for a user (line 27)
- `findByUseruuid(String useruuid)` - Returns cached list of all salaries for a user (line 86)

### Accessing Salary Amount:
The `Salary` entity includes:
- `salary`: The base salary amount
- `lunch`: Boolean for lunch benefit
- `phone`: Boolean for phone benefit
- `type`: Salary type (enum)

## d) Calculating Average Utilization of a Team During a Financial Year

### Service: `UtilizationResource`
**Location:** `dk.trustworks.intranet.aggregates.utilization.resources.UtilizationResource`

#### Method for Team Utilization:
```java
@GET
@Path("/budget/teams/{teamuuid}")
public List<DateValueDTO> getBudgetUtilizationPerMonthByTeam(String teamuuid, int fiscalYear)
```
- **Path:** `src/main/java/dk/trustworks/intranet/aggregates/utilization/resources/UtilizationResource.java:91`
- **REST:** GET `/company/{companyuuid}/utilization/budget/teams/{teamuuid}?fiscalYear=YYYY`
- **Returns:** Monthly utilization for the team throughout the fiscal year

#### For Actual Utilization:
```java
@GET
@Path("/actual/teams/{teamuuid}")
public List<DateValueDTO> getActualUtilizationPerMonthByTeam(String teamuuid, int fiscalYear)
```
- **Path:** `src/main/java/dk/trustworks/intranet/aggregates/utilization/resources/UtilizationResource.java:173`
- **REST:** GET `/company/{companyuuid}/utilization/actual/teams/{teamuuid}?fiscalYear=YYYY`

To calculate the average for the fiscal year, sum all monthly values and divide by 12.

## e) Finding Registered Revenue for a User During a Financial Year

### Service: `RevenueService`
**Location:** `dk.trustworks.intranet.aggregates.revenue.services.RevenueService`

#### Primary Methods:

##### Get Revenue for Period:
```java
public List<DateValueDTO> getRegisteredRevenueByPeriodAndSingleConsultant(String useruuid, String periodFrom, String periodTo)
```
- **Path:** `src/main/java/dk/trustworks/intranet/aggregates/revenue/services/RevenueService.java:187`
- **Description:** Returns monthly revenue for a consultant over a period
- **Parameters:**
  - `useruuid`: UUID of the consultant
  - `periodFrom`: Start date (format: "YYYY-MM-DD")
  - `periodTo`: End date (format: "YYYY-MM-DD")
- **Returns:** List of DateValueDTO with monthly revenue amounts

##### Get Revenue for Single Month:
```java
public double getRegisteredRevenueForSingleMonthAndSingleConsultant(String useruuid, LocalDate month)
```
- **Path:** `src/main/java/dk/trustworks/intranet/aggregates/revenue/services/RevenueService.java:146`
- **Description:** Returns total revenue for a consultant in a specific month
- **Returns:** Double value of total revenue

#### REST Endpoints:

```http
GET /users/{useruuid}/revenue/registered?periodFrom=YYYY-MM-DD&periodTo=YYYY-MM-DD
```
- **Path:** `src/main/java/dk/trustworks/intranet/aggregates/revenue/resources/EmployeeRevenueResource.java:36`
- **Description:** Get registered revenue for a consultant over a period
- **Authentication:** JWT with SYSTEM role
- **Returns:** List of DateValueDTO with monthly revenue

```http
GET /users/{useruuid}/revenue/registered/hours?fromdate=YYYY-MM-DD&todate=YYYY-MM-DD
```
- **Path:** `src/main/java/dk/trustworks/intranet/aggregates/revenue/resources/EmployeeRevenueResource.java:42`
- **Description:** Get registered billable hours for a consultant over a period
- **Returns:** List of DateValueDTO with monthly hours

```http
GET /users/{useruuid}/revenue/registered/months/{month}
```
- **Path:** `src/main/java/dk/trustworks/intranet/aggregates/revenue/resources/EmployeeRevenueResource.java:48`
- **Description:** Get revenue amount for a specific month
- **Returns:** GraphKeyValue with revenue amount

```http
GET /users/{useruuid}/revenue/registered/months/{month}/hours
```
- **Path:** `src/main/java/dk/trustworks/intranet/aggregates/revenue/resources/EmployeeRevenueResource.java:54`
- **Description:** Get billable hours for a specific month
- **Returns:** GraphKeyValue with hours

```http
GET /company/{companyuuid}/revenue/profits/consultants/{useruuid}?fromdate=YYYY-MM-DD&todate=YYYY-MM-DD&interval=N
```
- **Path:** `src/main/java/dk/trustworks/intranet/aggregates/revenue/resources/RevenueResource.java:138`
- **Description:** Get profit calculations for a consultant
- **Parameters:** interval = number of months to group

For a fiscal year (July 1st to June 30th), call with:
- `periodFrom`: "YYYY-07-01"
- `periodTo`: "(YYYY+1)-06-30"

## f) Getting Names of Users and Teams

### For User Names:

#### Service: `User` Entity
**Location:** `dk.trustworks.intranet.domain.user.entity.User`

##### Methods:
```java
public String getFullname()
```
- **Path:** `src/main/java/dk/trustworks/intranet/domain/user/entity/User.java:170`
- **Returns:** Full name as "firstname lastname"

```java
public String getUsername()
```
- **Returns:** Username (email-like identifier)

##### Accessing User Properties:
- `firstname`: First name of the user
- `lastname`: Last name of the user
- `username`: Username/email
- `uuid`: Unique identifier

### For Team Names:

#### Service: `Team` Entity
**Location:** `dk.trustworks.intranet.domain.user.entity.Team`

##### Properties:
- `name`: Full team name
- `shortname`: Abbreviated team name
- `description`: Team description
- `uuid`: Unique team identifier

#### Service: `TeamService`
```java
public List<Team> listAll()
```
- **Path:** `src/main/java/dk/trustworks/intranet/userservice/services/TeamService.java:29`
- **Returns:** List of all teams with their names and properties

#### REST Endpoints:

```http
GET /teams
```
- **Path:** `src/main/java/dk/trustworks/intranet/apigateway/resources/TeamResource.java:38`
- **Description:** Get all teams
- **Authentication:** JWT with SYSTEM role
- **Returns:** List of Team objects with names and properties

```http
GET /teams/search/findByRoles?useruuid=UUID&date=YYYY-MM-DD&roles=ROLE1,ROLE2
```
- **Path:** `src/main/java/dk/trustworks/intranet/apigateway/resources/TeamResource.java:43`
- **Description:** Find teams by user roles
- **Returns:** List of Team objects filtered by roles

## Custom Data Types and Return Values

### Common DTOs (Data Transfer Objects)

#### DateValueDTO
**Location:** `dk.trustworks.intranet.dto.DateValueDTO`
```java
{
  "date": "YYYY-MM-DD",   // LocalDate - typically first day of month
  "value": 0.0             // Double - can represent hours, amount, or percentage
}
```
**Usage:** Used for time-series data like monthly utilization, revenue, or hours

#### GraphKeyValue
**Location:** `dk.trustworks.intranet.dto.GraphKeyValue`
```java
{
  "uuid": "string",        // Unique identifier
  "description": "string", // Human-readable description
  "value": 0.0            // Double - aggregated value
}
```
**Usage:** Used for single-value metrics with metadata

#### KeyDateValueListDTO
**Location:** `dk.trustworks.intranet.dto.KeyDateValueListDTO`
```java
{
  "key": "string",                    // Identifier (e.g., user UUID or name)
  "dateValueDTOList": [DateValueDTO]  // List of time-series data
}
```
**Usage:** Used for multi-series data like per-employee utilization over time

#### SalaryPayment
**Location:** `dk.trustworks.intranet.aggregates.users.dto.SalaryPayment`
```java
{
  "month": "YYYY-MM-DD",      // LocalDate of payment month
  "description": "string",     // Payment type description
  "payment": "string"          // Formatted payment amount or info
}
```
**Usage:** Detailed breakdown of salary components and benefits

### Entity Objects

#### User
**Key Properties:**
- `uuid`: Unique identifier
- `username`: Email-like identifier
- `firstname`, `lastname`: Name components
- `active`: Boolean status
- `created`: LocalDate of user creation
- `salaries[]`: List of salary entries
- `statuses[]`: List of status changes
- `teams[]`: List of team memberships

#### Team
**Key Properties:**
- `uuid`: Unique team identifier
- `name`: Full team name
- `shortname`: Abbreviated name
- `description`: Team description (AI-generated)
- `teamleadbonus`: Boolean for bonus eligibility
- `teambonus`: Boolean for team bonus

#### Salary
**Key Properties:**
- `uuid`: Unique identifier
- `salary`: Base amount (integer for NORMAL type, hourly rate for HOURLY type)
- `activefrom`: LocalDate when salary becomes effective
- `type`: Enum (NORMAL, HOURLY)
- `lunch`, `phone`, `prayerDay`: Boolean benefits

## Parameter Formats and Validation

### Date Parameters
- **Format:** `YYYY-MM-DD` (ISO 8601)
- **Examples:** `2024-01-01`, `2024-07-01`
- **Query params:** `fromdate`, `todate`, `month`, `date`
- **Fiscal year:** Integer year where fiscal year starts July 1st
  - Example: `fiscalyear=2024` means July 1, 2024 to June 30, 2025

### UUID Parameters
- **Format:** Standard UUID v4
- **Example:** `f7602dd6-9daa-43cb-8712-e9b1b99dc3a9`
- **Used for:** `useruuid`, `teamuuid`, `companyuuid`, `salaryuuid`

### Utilization Values
- **Format:** Decimal between 0.0 and 1.0+ (can exceed 1.0)
- **Calculation:** `billable_hours / available_hours`
- **Example:** `0.85` represents 85% utilization

### Revenue and Salary Amounts
- **Revenue:** Double values in currency units
- **Salary:** Integer values for monthly salary, double for hourly rates
- **Formatted:** When returned as string, includes currency formatting

### Special Parameters
- **interval:** Integer for grouping periods (e.g., `3` for quarterly)
- **roles:** Comma-separated list (e.g., `MEMBER,LEADER`)
- **statusArray:** Array of status types (`ACTIVE`, `TERMINATED`, etc.)
- **consultantTypesArray:** Array of types (`CONSULTANT`, `STAFF`, etc.)

## Additional Notes

### Authentication
All endpoints require JWT authentication with appropriate roles (typically "SYSTEM" role).

### Date Formats
- Most methods accept dates in "YYYY-MM-DD" format
- Fiscal years run from July 1st to June 30th
- LocalDate objects are used internally

### Caching
Many services use caching for performance:
- User data: `user-cache`
- Employee revenue: `employee-revenue`
- Utilization: `utilization`

### Company Context
Most resources are scoped by company UUID, typically passed as a path parameter:
`/company/{companyuuid}/...`

### Error Handling
- Methods typically return empty lists or zero values when no data is found
- Some methods return default objects with zero values for missing data
- Invalid date formats may result in empty responses or default values