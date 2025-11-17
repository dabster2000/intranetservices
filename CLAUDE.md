# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Development Commands

### Build and Run
- `./mvnw compile quarkus:dev` - Run in development mode with live reload
- `./mvnw package` - Build the application (JAR in target/quarkus-app/)
- `./mvnw package -Dquarkus.package.type=uber-jar` - Build uber-jar
- `./mvnw package -Pnative` - Build native executable
- `java -jar target/quarkus-app/quarkus-run.jar` - Run packaged application

### Environment Variables


### Docker
- `docker-compose.yaml` - Main docker compose file
- `config/docker-compose.yaml` - Additional configuration
- `docker-build-native-images.sh` - Build native Docker images
- Docker environment files in `config/` directory for each service

## Architecture Overview

This is a Quarkus-based Java 21 modular monolith for Trustworks intranet services. While organized as logical microservices, it's deployed as a single application with shared database and configuration:

### Core Services
- **API Gateway** (`apigateway/`) - Central routing and authentication
- **User Service** (`userservice/`) - User management, authentication, roles
- **Finance Service** (`financeservice/`) - Financial data, invoicing
- **Expense Service** (`expenseservice/`) - Expense processing and e-conomic integration
- **Work Service** - Time tracking and project work
- **Communication Service** (`communicationsservice/`) - Email and Slack integration
- **Knowledge Service** (`knowledgeservice/`) - Training, courses, certifications
- **File Service** (`fileservice/`) - S3-based file storage and photo processing

### Aggregation Layer
- **Aggregates** (`aggregates/`) - Cross-service data aggregation and business logic
- **Aggregate Services** (`aggregateservices/`) - Cached aggregate data services

### Data Architecture
- MariaDB database with Flyway migrations (`src/main/resources/db/migration/`)
- Hibernate ORM with Panache for data access
- JPA Streamer for advanced querying
- Event-driven architecture using AWS SNS

### Security
- JWT-based authentication with public/private key pairs
- Role-based access control
- Request logging and API usage tracking (`logging/ApiUsageLog`)

### External Integrations
- **e-conomic** - Accounting system integration
- **Slack** - Team communication
- **AWS S3** - File storage
- **Claid AI** - Image processing
- **OpenAI** - AI services for resume parsing and text improvement

## Configuration

- `src/main/resources/application.yml` - Main configuration
- Environment-specific configs in `config/docker-env-*.env` files
- Database connection defaults to localhost:3306/twservices
- HTTP server runs on port 9093
- Health checks available at `/health`
- OpenAPI/Swagger UI enabled in dev mode

## Database

- Uses Flyway for schema migrations
- Migration files in `src/main/resources/db/migration/`
- Baseline version 1.0 with migrate-at-start enabled
- Connection pooling (5-10 connections)

## Key Features

- Multi-tenant company data aggregation
- Invoice generation and management
- Expense processing with receipt upload
- User availability and budget tracking
- Conference and training management
- Slack integration for notifications
- Photo service with dynamic resizing
- API usage logging and monitoring
- Danløn (payroll) number history tracking with temporal queries

## Development Notes

- Uses Lombok for boilerplate reduction
- Extensive caching with Caffeine
- Reactive programming with Mutiny
- REST endpoints follow JAX-RS standards
- Background jobs using Quarkus Scheduler
- Comprehensive documentation in `docs/` directory for specific features

## Testing

- `./mvnw test` - Run unit tests
- `./mvnw verify` - Run all tests including integration tests
- Dev UI available at http://localhost:8080/q/dev/ in dev mode

## Feature Documentation

Key features have detailed documentation in the `docs/` directory:
- Expense Processing: `docs/expense-processing.md`
- Photo Service: `docs/photo-service.md`
- API Usage Logging: `docs/api-usage-logging.md`
- Draft Invoice Creation: `docs/draft-invoice-creation.md`
- Guest Registration: `docs/guest-registration.md`
- Slack User Sync: `docs/slack-user-sync.md`
- Danløn (Payroll) Integration: See comprehensive documentation in `docs/domains/danlon/`
  - Overview: `docs/domains/danlon/README.md`
  - Business Rules: `docs/domains/danlon/business-rules.md`
  - Architecture: `docs/domains/danlon/architecture.md`
  - Integration Guide: `docs/domains/danlon/integration-guide.md`

## Danløn History Tracking (Added 2025-11-17)

**Overview:**
The Danløn number history tracking system stores historical Danløn employee numbers with effective dates, enabling temporal queries for payroll reports and audit trails.

**Key Components:**
- **Entity**: `UserDanlonHistory` (`domain/user/entity/`) - Stores historical Danløn numbers with active_date
- **Service**: `UserDanlonHistoryService` (`domain/user/service/`) - Business logic for temporal queries and CRUD operations
- **REST API**: `UserAccountResource` (`expenseservice/resources/`) - Backward-compatible endpoints
- **Migrations**:
  - V109: Creates `user_danlon_history` table
  - V110: Migrates existing data from `user_ext_account.danlon` to history table
  - V111: (FUTURE - 3-6 months) Removes denormalized field after validation

**Temporal Pattern:**
```java
// Get current Danløn number (as of today)
Optional<String> current = danlonHistoryService.getCurrentDanlon(useruuid);

// Get historical Danløn number (as of specific date)
Optional<String> historical = danlonHistoryService.getDanlonAsOf(useruuid, LocalDate.of(2024, 6, 1));

// Get full history for user
List<UserDanlonHistory> history = danlonHistoryService.getHistory(useruuid);
```

**Backward Compatibility:**
During the transition period (until V111 is deployed):
- The denormalized `user_ext_account.danlon` field is maintained automatically
- Existing code using `UserAccount.getDanlon()` continues to work
- All updates via `UserAccountResource` automatically create history records
- Active date is set to the 1st of the current month for new changes

**Migration Path:**
1. Deploy V109 + V110 migrations (creates table and populates history)
2. Deploy code changes (service, resource updates)
3. Test in production for 3-6 months
4. Gradually migrate code from `UserAccount.getDanlon()` to `UserDanlonHistoryService.getCurrentDanlon()`
5. Deploy V111 migration (removes denormalized field)

**Testing:**
- Unit tests: `UserDanlonHistoryServiceTest` (20+ test cases)
- Integration tests: `UserAccountResourceTest` (14 REST API test cases)

**Important Notes:**
- Active dates are always normalized to the 1st of the month
- Duplicate records for the same user + month are prevented
- History creation errors don't fail account updates (backward compatibility)
- Created by field tracks audit trail ("system", username, or "system-migration")

**See Also:**
- Comprehensive Danløn documentation: `docs/domains/danlon/`
- Database schema: `src/main/resources/db/migration/V109__Create_user_danlon_history.sql`
- Entity finder methods: `UserDanlonHistory.findDanlonAsOf()`, `UserDanlonHistory.findCurrentDanlon()`

## Automatic Danløn Number Generation Rules (Added 2025-11-17)

**CRITICAL**: The system automatically generates new Danløn numbers based on specific business rules with strict precedence ordering.

### Rule Precedence (Highest to Lowest Priority)

When multiple rules could apply in the same month, only ONE Danløn number is generated following this precedence:

#### 1. Company Transition (HIGHEST PRIORITY)
**Marker**: `created_by = 'system-company-transition'`

**Trigger**: User is TERMINATED in one company AND becomes ACTIVE in a different company on the **exact same date**.

**Conditions (ALL must be true)**:
- User has `UserStatus` with `status = TERMINATED` in Company A on date D
- User has `UserStatus` with `status = ACTIVE` (or qualifying status) in Company B on the **SAME** date D
- Company A UUID ≠ Company B UUID (different companies)
- Target status is NOT `TERMINATED` or `PREBOARDING`
- No existing Danløn history for this month with this marker

**Implementation**:
- Detection: `StatusService.create()` → `checkForCompanyTransition()` (lines 259-332)
- Query pattern: `"useruuid = ?1 AND statusdate = ?2 AND status = ?3 AND company.uuid != ?4"`
- Date matching: **Exact same date only** (not approximate, not within same month)
- Precedence check: Runs **FIRST**, before salary type change check

**Example**:
```java
// 2025-11-01: User terminated from Company A and starts at Company B
LocalDate transitionDate = LocalDate.of(2025, 11, 1);

// StatusService.create() automatically generates new Danløn number T1234
// with marker "system-company-transition"
```

#### 2. Salary Type Change (HOURLY → NORMAL)
**Marker**: `created_by = 'system-salary-type-change'`

**Trigger**: User transitions from hourly (HOURLY) to monthly (NORMAL) salary type.

**Conditions (ALL must be true)**:
- Previous month's `Salary` has `type = HOURLY`
- Current month's `Salary` has `type = NORMAL`
- User has `UserStatus` in same month with status NOT `TERMINATED` or `PREBOARDING`
- **No company transition occurred this month** (Rule 1 takes precedence)
- No existing Danløn history for this month with this marker

**Implementation**:
- Detection: Bidirectional (order-independent)
  - `SalaryService.create()` → `handleSalaryTypeChange()` (lines 127-168)
  - `StatusService.create()` → `checkForPendingSalaryTypeChange()` (lines 166-231)
- Precedence check: `!hasDanlonChangedInMonthBy(useruuid, month, "system-company-transition")`

**Order-Independent Pattern**:
```java
// Scenario 1: Salary changes first
admin.changeSalaryType(HOURLY → NORMAL);  // SalaryService: no UserStatus yet → no action
admin.createUserStatus(ACTIVE);            // StatusService: detects pending salary change → generates Danløn

// Scenario 2: Status created first
admin.createUserStatus(ACTIVE);            // StatusService: salary still HOURLY → no action
admin.changeSalaryType(HOURLY → NORMAL);  // SalaryService: finds UserStatus → generates Danløn

// Result: Both scenarios generate exactly ONE Danløn number
```

#### 3. Re-Employment (THIRD PRIORITY)
**Marker**: `created_by = 'system-re-employment'`

**Trigger**: User was previously TERMINATED and becomes ACTIVE again (either same company or different company).

**Conditions (ALL must be true)**:
- User has current `UserStatus` with `status = ACTIVE` (or qualifying status, not TERMINATED/PREBOARDING)
- User has previous `UserStatus` with `status = TERMINATED` (any company, any earlier date)
- PREBOARDING statuses are ignored (treated as transition states)
- No Danløn history already exists for current month (any marker)

**Implementation**:
- Detection: `StatusService.create()` → `checkForReEmployment()` (lines 363-433)
- Query pattern: `"useruuid = ?1 AND status = ?2 AND statusdate < ?3 ORDER BY statusdate DESC"` (finds ANY previous TERMINATED)
- Company scope: Applies to both SAME company and DIFFERENT companies
- Time gap: No minimum gap required (same date allowed)
- Precedence check: Runs **THIRD**, only if Rules 1 and 2 did not generate Danløn

**Cleanup Behavior**:
- When ACTIVE status is deleted, the corresponding re-employment Danløn is automatically deleted
- Other Danløn markers (company transition, salary type change, manual entries) are preserved
- Cleanup location: `StatusService.delete()` (lines 137-182)

**Example**:
```java
// Scenario 1: Same company re-employment
// User was TERMINATED on 2025-10-15, now ACTIVE on 2025-11-01 (same company)
LocalDate terminationDate = LocalDate.of(2025, 10, 15);
LocalDate reEmploymentDate = LocalDate.of(2025, 11, 1);

// StatusService.create() automatically generates new Danløn number T6789
// with marker "system-re-employment"

// Scenario 2: Different companies, different months (NOT company transition)
// User was TERMINATED in Company A on 2025-10-15
// User becomes ACTIVE in Company B on 2025-11-01
// Re-employment rule applies (not company transition because different dates)

// Scenario 3: Cleanup on delete
statusService.delete(activeStatusUuid);
// Re-employment Danløn T6789 is automatically deleted
// Company transition and salary type change Danløn numbers are preserved
```

**CRITICAL - Precedence Over Company Transition**:
```java
// If user is TERMINATED in Company A and ACTIVE in Company B on SAME date:
// → Company transition rule applies (Rule 1), NOT re-employment (Rule 3)

// If user is TERMINATED in Company A on Oct 15 and ACTIVE in Company B on Nov 1:
// → Re-employment rule applies (Rule 3), because different dates
```

### Precedence Enforcement Mechanism

**Critical Code Pattern** (StatusService.create(), lines 73-112):
```java
// BUSINESS RULE 1: Check for company transition (TERMINATED in one company, ACTIVE in another)
// This check takes precedence over salary type change and re-employment
checkForCompanyTransition(status);

// BUSINESS RULE 2: Check for pending salary type change (HOURLY → NORMAL)
// Only check if company transition didn't generate Danløn number
if (!danlonHistoryService.hasDanlonChangedInMonthBy(status.getUseruuid(),
        status.getStatusdate().withDayOfMonth(1), "system-company-transition")) {
    checkForPendingSalaryTypeChange(status);
}

// BUSINESS RULE 3: Check for re-employment (TERMINATED → ACTIVE)
// Only check if no other rule has generated Danløn number this month
if (!danlonHistoryService.hasDanlonChangedInMonth(status.getUseruuid(),
        status.getStatusdate().withDayOfMonth(1))) {
    checkForReEmployment(status);
}
```

**Why This Matters**:
- Prevents duplicate Danløn numbers in same month
- Ensures correct audit trail (marker reflects actual triggering event)
- Company transition is more significant than salary type change (exact same date, different companies)
- Re-employment is less specific than company transition (broader time window, any company)

### Duplicate Prevention and Cleanup

**Duplicate Prevention** - Multiple mechanisms prevent duplicate Danløn history records:

1. **Database Constraint**: Unique constraint on `(useruuid, active_date)` in `user_danlon_history` table
2. **Marker Checks**: `hasDanlonChangedInMonthBy(useruuid, month, marker)` before generation
3. **Exception Handling**: `IllegalArgumentException` caught and logged if duplicate detected
4. **Precedence Logic**: Company transition → salary type change → re-employment (only one rule generates Danløn per month)

**Automatic Cleanup** - Re-employment Danløn numbers are automatically deleted when ACTIVE status is removed:

1. **Cleanup Location**: `StatusService.delete()` (lines 137-182)
2. **Cleanup Scope**: Deletes ONLY re-employment Danløn (marker = `system-re-employment`)
3. **Preservation**: Company transition, salary type change, and manual entries are preserved
4. **Pattern**:
```java
// When deleting ACTIVE status, clean up re-employment Danløn
if (entity.getStatus() == StatusType.ACTIVE) {
    Optional<UserDanlonHistory> reEmploymentDanlon = UserDanlonHistory.find(
        "useruuid = ?1 AND active_date = ?2 AND created_by = ?3",
        useruuid, monthStart, "system-re-employment"
    ).firstResultOptional();

    if (reEmploymentDanlon.isPresent()) {
        danlonHistoryService.deleteDanlonHistory(reEmploymentDanlon.get().getUuid());
    }
}
```

### Helper Methods

**Critical Methods** (UserDanlonHistoryService):

```java
// Check for ANY Danløn change in month (either rule)
boolean hasDanlonChangedInMonth(String useruuid, LocalDate month);

// Check for SPECIFIC Danløn change marker (for precedence)
boolean hasDanlonChangedInMonthBy(String useruuid, LocalDate month, String createdBy);

// Generate next sequential Danløn number (T####)
String generateNextDanlonNumber();

// Create history record (throws IllegalArgumentException if duplicate)
void addDanlonHistory(String useruuid, LocalDate activeDate, String danlon, String createdBy);
```

### Reporting Integration

**DanlonResource.findChangedUsers()** automatically includes users with new Danløn numbers:

```java
// Rule 2c: Company transition detection in reporting (lines 239-253)
if (hasCompanyTransitionThisMonth(user, currentMonth)) {
    Optional<UserDanlonHistory> newDanlon = UserDanlonHistory.find(
            "useruuid = ?1 AND active_date = ?2 AND created_by = ?3",
            user.getUuid(), currentMonth, "system-company-transition"
    ).firstResultOptional();

    if (newDanlon.isPresent()) {
        salaryNote.append("Skiftet firma. Nyt Danløn-nummer: ")
                .append(newDanlon.get().getDanlon()).append(". ");
    }
}

// Rule 2d: Re-employment detection in reporting (lines 255-269)
if (hasReEmploymentThisMonth(user, currentMonth)) {
    Optional<UserDanlonHistory> newDanlon = UserDanlonHistory.find(
            "useruuid = ?1 AND active_date = ?2 AND created_by = ?3",
            user.getUuid(), currentMonth, "system-re-employment"
    ).firstResultOptional();

    if (newDanlon.isPresent()) {
        salaryNote.append("Genansættelse. Nyt Danløn-nummer: ")
                .append(newDanlon.get().getDanlon()).append(". ");
    }
}
```

### Testing

**Integration Tests**:
- `SalaryTypeChangeIntegrationTest.java` (11 test cases) - Rule 2 validation
- `CompanyTransitionIntegrationTest.java` (8 test cases) - Rule 1 validation and precedence
- `ReEmploymentIntegrationTest.java` (11 test cases) - Rule 3 validation and cleanup

**Key Test Scenarios**:

**Company Transition & Salary Type Change**:
- ✅ Company transition + salary type change in same month → Only company transition marker
- ✅ Salary change first, then status created → Generates Danløn (order-independent)
- ✅ Status created first, then salary change → Generates Danløn (order-independent)
- ✅ Multiple transitions/changes in same month → Only one Danløn history record
- ❌ Different dates for TERMINATED and ACTIVE → No Danløn generation (company transition)
- ❌ Same company transition → No Danløn generation
- ❌ PREBOARDING in target company → No Danløn generation

**Re-Employment**:
- ✅ TERMINATED → ACTIVE (same company, different months) → Generates Danløn
- ✅ TERMINATED → ACTIVE (same company, same date) → Generates re-employment Danløn
- ✅ TERMINATED in Company A → ACTIVE in Company B (different dates) → Generates re-employment Danløn
- ✅ TERMINATED → PREBOARDING → ACTIVE (PREBOARDING ignored) → Generates Danløn
- ✅ Delete ACTIVE status → Deletes re-employment Danløn (cleanup)
- ✅ Delete ACTIVE with company transition Danløn → Preserves company transition Danløn
- ✅ Delete TERMINATED status → No Danløn deletion
- ✅ Multiple terminations/re-employments → One Danløn per month
- ❌ TERMINATED in Company A → ACTIVE in Company B (same date) → Company transition takes precedence
- ❌ No previous termination (new hire) → No re-employment Danløn

### Common Pitfalls to Avoid

**❌ WRONG - Checking lifecycle status for queue validation**:
```java
// Don't check company transition by looking for different StatusType values
if (status.getStatus() == StatusType.ACTIVE && otherStatus.getStatus() == StatusType.TERMINATED) {
    // This doesn't guarantee same date or different companies!
}
```

**✅ CORRECT - Using dedicated helper methods**:
```java
// Use the helper method that checks date, company, and marker
if (hasCompanyTransitionThisMonth(user, currentMonth)) {
    // Guaranteed to have correct transition logic
}
```

**❌ WRONG - Generating Danløn without checking precedence**:
```java
// SalaryService generates without checking company transition
if (salaryTypeChanged(HOURLY → NORMAL)) {
    generateDanlonNumber();  // Might create duplicate if company transition also applies!
}
```

**✅ CORRECT - Checking precedence first**:
```java
// Check company transition didn't already generate Danløn
if (!hasDanlonChangedInMonthBy(useruuid, month, "system-company-transition")) {
    if (salaryTypeChanged(HOURLY → NORMAL)) {
        generateDanlonNumber("system-salary-type-change");
    }
}
```

### Documentation References

- **Comprehensive Business Rules**: `docs/domains/danlon/business-rules.md`
- **StatusService Implementation**: `src/main/java/dk/trustworks/intranet/aggregates/users/services/StatusService.java`
- **SalaryService Implementation**: `src/main/java/dk/trustworks/intranet/aggregates/users/services/SalaryService.java`
- **DanlonResource Reporting**: `src/main/java/dk/trustworks/intranet/aggregates/accounting/resources/DanlonResource.java`
- **Integration Tests**: `src/test/java/dk/trustworks/intranet/aggregates/users/services/`