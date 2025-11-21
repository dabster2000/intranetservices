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

## e-conomic API Integration (Critical Patterns)

**CRITICAL - Accounting Year Format for Path Parameters** (Fixed 2025-11-19):

The e-conomic REST API requires **different accounting year formats** depending on usage:

### Path Parameters (URL segments) - Use Underscore Format
**✅ CORRECT Pattern:**
```java
// For /accounting-years/{accountingYear}/entries endpoint
String yearId = DateUtils.toEconomicsUrlYear(year);  // Returns "2025_6_2026"
Response response = api.getYearEntries(yearId, filter, pagesize);
```

**❌ INCORRECT Pattern (causes HTTP 404):**
```java
// DON'T double-convert - produces slash format
String yearId = DateUtils.toEconomicsApiYear(
    DateUtils.toEconomicsUrlYear(year)  // Returns "2025/2026" ❌
);
Response response = api.getYearEntries(yearId, filter, pagesize);  // HTTP 404!
```

**Why This Matters:**
- Slash format `"2025/2026"` creates URL: `/accounting-years/2025/2026/entries`
- Server interprets this as THREE path segments, not matching route pattern
- Results in HTTP 404 Not Found error
- Underscore format `"2025_6_2026"` creates correct URL: `/accounting-years/2025_6_2026/entries`

### Request Bodies (JSON payloads) - Use Slash Format
```java
// For JSON request bodies, use slash format
String accountingYear = DateUtils.toEconomicsApiYear(year);  // Returns "2025/2026"
// Include in POST/PUT request body
```

### Conversion Methods (DateUtils)
- `toEconomicsUrlYear(String year)` → Returns `"2025_6_2026"` (for URL path parameters)
- `toEconomicsApiYear(String year)` → Returns `"2025/2026"` (for JSON request bodies)

**Working Reference Implementation:**
- `EconomicsInvoiceStatusService.toAccountingYearId()` (lines 36-47) - Correct underscore conversion
- `ExpenseSyncBatchlet.syncExpense()` (line 95) - Fixed 2025-11-19

**Test Pattern:**
```java
// Test that path parameters work correctly
@Test
void testAccountingYearPathParameter() {
    String year = "2025/2026";  // Input from database
    String yearId = DateUtils.toEconomicsUrlYear(year);  // "2025_6_2026"

    Response response = api.getYearEntries(yearId, filter, 1000);
    assertEquals(200, response.getStatus());  // Should succeed, not 404
}
```

### Accountant Notes Synchronization (Added 2025-11-19)

The expense-sync batch job extracts and stores voucher text/notes from e-conomic that accountants add during the verification/booking process.

**Field Mapping**:
- **e-conomic API**: `entry.text` (from both journal entries and accounting year entries)
- **Database**: `expenses.accountant_notes` (TEXT column)
- **Entity**: `Expense.accountantNotes` (separate from user `description`)

**Data Flow**:
```
Journal Entries (VERIFIED_UNBOOKED)  → entry.text → expense.accountantNotes
Accounting Year Entries (VERIFIED_BOOKED) → entry.text → expense.accountantNotes
User-entered description → expense.description (unchanged, preserved separately)
```

**Sync Behavior**:
- Runs every 24 hours as part of `expense-sync` batch job
- Updates accountant notes for both VERIFIED_UNBOOKED and VERIFIED_BOOKED states
- Only updates if text has changed (avoids unnecessary database writes)
- Empty or null text from e-conomic is ignored (no update)
- User descriptions remain untouched in separate `description` field

**Implementation Details**:
- **Location**: `ExpenseSyncBatchlet.java`
  - Line 172: `extractFirstText(String body)` - Extracts text field from API response
  - Line 88-96: Journal entries sync - Updates accountant notes for VERIFIED_UNBOOKED
  - Line 127-135: Accounting year sync - Updates accountant notes for VERIFIED_BOOKED
- **Migration**: `V112__Add_accountant_notes_to_expenses.sql`
- **Entity Field**: `Expense.accountantNotes` (Lombok generates getters/setters)

**Example Scenario**:
```
1. User creates expense: description="Taxi to client meeting"
2. Expense uploaded to e-conomic
3. Accountant reviews and adds note in e-conomic: "Approved - business expense, client XYZ"
4. expense-sync job runs (within 24 hours)
5. Database updated:
   - expense.description = "Taxi to client meeting" (unchanged)
   - expense.accountant_notes = "Approved - business expense, client XYZ" (new)
```

**API Response Structure**:
```json
{
  "collection": [
    {
      "entryNumber": 123,
      "voucherNumber": 456,
      "account": {
        "accountNumber": "5810"
      },
      "text": "Approved - business expense, client XYZ",
      "amount": 1200.00
    }
  ]
}
```

**Logging**:
```
INFO  Expense uuid-123: updating accountant notes (journal): Approved - business expense, client...
INFO  Expense uuid-456: updating accountant notes (booked): Verified and booked - OK
```

**References**:
- Official e-conomic API docs: https://restdocs.e-conomic.com/
- Journal entries endpoint: https://apis.e-conomic.com/journalsapi/redoc.html
- Field name: `text` (standard field in all entry responses)

### Expense Receipt Validation with Home Address Proximity Check (Added 2025-11-20)

**Overview:**
The `ExpenseService.validateExpenseReceipt()` method validates expense receipts using OpenAI Vision API with enhanced fraud prevention through home address proximity checking.

**Key Features:**
- **AI-powered receipt validation**: Verifies image readability, amount clarity, and completeness
- **Web search enabled**: GPT-5 searches the internet for actual store locations and addresses
- **Precise distance calculation**: AI calculates real distances between home and store locations (~2 km threshold)
- **Context-aware analysis**: AI evaluates whether expenses near home are legitimate (e.g., work-from-home, client meetings)
- **Graceful fallback**: Continues validation even if home address is unavailable

**Implementation Details:**
- **Location**: `ExpenseService.java`, lines 406-509
- **Method**: `validateExpenseReceipt(String expenseUuid)`
- **Returns**: String (max 160 characters) - validation message or warning
- **Data Sources**:
  - `Expense` entity - expense details and user reference
  - `UserContactinfo` entity - employee home address (street, postal code, city)
  - `ExpenseFile` - receipt image from S3 storage (base64-encoded)

**Validation Flow**:
```
1. Load expense by UUID
2. Retrieve employee home address from UserContactinfo
3. Load receipt image from S3
4. Send to OpenAI Vision API with:
   - System prompt: fraud prevention assistant
   - User prompt: validation criteria + home address context
   - Receipt image (base64 JPEG)
5. AI evaluates:
   - Image readability
   - Amount clarity
   - Store location proximity to home (~2 km)
   - Missing information
   - Work-related context (if near home)
6. Return validation message (max 160 chars)
```

**Proximity Validation Behavior**:
- **Distance Threshold**: ~2 km (walking distance in Copenhagen)
- **AI Decision**: Context-aware, not hard rejection
  - ⚠️ **Flag suspicious**: "Receipt near home address - verify work purpose"
  - ✅ **Allow legitimate**: "Work-from-home expense approved" (clear work context)
  - ✅ **Allow with context**: Home office supplies, client meeting nearby

**Example Validation Messages** (with web search):
```
✅ "Readable: Yes; Total: 76 DKK; Info complete for reimbursement."
⚠️ "Readable: Yes; Total: 76 DKK; Proximity: ~3 km from home to Nyropsgade 30 – FLAG unless explicit work context"
✅ "Receipt readable, work-from-home context clear. Amount: 450 DKK. Approved."
❌ "Image unreadable - please upload clearer photo."
```

**Note**: The AI performs real web searches to find store addresses (e.g., "Netto Nyropsgade 30 København") and calculates actual distances from the employee's home address.

**Address Data Retrieval**:
```java
// Fetch user contact info
UserContactinfo contactInfo = UserContactinfo.findByUseruuid(expense.getUseruuid());

// Build formatted address
String homeAddress = String.format("%s, %s %s",
    contactInfo.getStreetname(),
    contactInfo.getPostalcode() != null ? contactInfo.getPostalcode() : "",
    contactInfo.getCity() != null ? contactInfo.getCity() : ""
).trim();
// Example: "Rosenvængets Allé 10, 2100 København Ø"
```

**OpenAI Prompt Structure** (when home address available):
```
System: You are a receipt validation assistant for expense fraud prevention.
        Provide concise validation feedback with context-aware analysis.

User:   The attached image is a receipt for employee expense reimbursement.
        Employee home address: {homeAddress}

        Validate the following:
        1) Image is readable and clear
        2) Total amount is readable
        3) Store location proximity to home (~2 km threshold) - FLAG if suspicious unless clear work-related context
        4) No missing information required for reimbursement

        Return validation result (max 160 characters). Include proximity warning if applicable.
```

**Fallback Mode** (no home address):
- Validation continues without proximity check
- AI validates only: readability, amount, missing info
- No false positives for users without address data

**Error Handling**:
```java
// Address lookup failure → graceful fallback (no proximity check)
try {
    UserContactinfo contactInfo = UserContactinfo.findByUseruuid(expense.getUseruuid());
    // ... build address
} catch (Exception e) {
    log.warnf(e, "Failed to retrieve home address - continuing without proximity check");
}

// OpenAI API failure → user-friendly error message
try {
    validationResult = openAIService.askSimpleQuestionWithImage(...);
} catch (Exception e) {
    return "Validation error: Unable to process receipt image";
}
```

**Logging**:
```
INFO  Validating expense receipt for uuid=abc-123
INFO  Retrieved home address for user xyz-456: Rosenvængets Allé 10, 2100 København Ø
INFO  Validation complete for uuid=abc-123, result length=87 chars

WARN  No home address found for user xyz-789 - proximity check will be skipped
WARN  Failed to retrieve home address for user xyz-999 - continuing without proximity check
ERROR OpenAI validation failed for uuid=abc-123
```

**Integration Points**:
- **Frontend**: Vaadin expense upload views call this method for real-time validation feedback
- **OpenAI Service**: `askSimpleQuestionWithImageAndWebSearch()` method with GPT-5 web search enabled
- **Web Search**: AI actively searches the internet for store locations in Denmark (user_location: "DK")
- **S3 Storage**: Receipt images stored as base64-encoded JPEG files
- **User Contact Info**: Home address from `user_contactinfo` table

**Performance Considerations**:
- **OpenAI API latency**: ~3-6 seconds per validation (includes vision + web search + reasoning)
- **Web search overhead**: ~1-2 seconds additional latency for real-time store location lookups
- **Address lookup**: Fast Panache query (indexed by useruuid)
- **S3 fetch**: Receipt already cached in `ExpenseFile` object
- **Character limit**: 160 chars enforced client-side (truncated with "..." if exceeded)

**Security & Privacy**:
- Home addresses are sensitive PII - already stored in database
- Receipt images may contain personal data - stored in S3 with access control
- OpenAI API logs may include addresses - ensure vendor compliance
- Validation messages visible to employee and managers

**Future Enhancements** (not implemented):
- Configurable distance threshold (currently hardcoded ~2 km in prompt via configuration)
- Hard rejection option (currently warning-only, allows manager override)
- Batch validation for multiple expenses
- Caching of store location lookups to reduce web search API calls
- Multi-country support (currently optimized for Denmark)

**See Also**:
- Comprehensive expense processing: `docs/expense-processing.md` (in main docs/)
- OpenAI integration: `OpenAIService.java`, `apis/openai/`
- Advanced validation: `ExpenseAIValidationService.java` (async, comprehensive)

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

**Cleanup Behavior** (Updated 2025-11-18):
- When EITHER TERMINATED or ACTIVE status is deleted, the corresponding company transition Danløn is automatically deleted
- Company transition requires BOTH statuses (TERMINATED in one company + ACTIVE in another on same date)
- Deleting EITHER status invalidates the transition → Danløn must be removed
- Cleanup location: `StatusService.delete()` (lines 143-179)

**Cleanup Rationale**:
```java
// Delete ACTIVE status → Company transition Danløn deleted
// User is just TERMINATED, not transitioning

// Delete TERMINATED status → Company transition Danløn deleted
// User is just becoming ACTIVE (new hire or re-employment), not transitioning
```

**Difference from Re-Employment Cleanup**:
- Company transition: Delete when EITHER TERMINATED or ACTIVE is deleted (requires both statuses)
- Re-employment: Delete only when ACTIVE is deleted (defined only by ACTIVE status)

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
- Cleanup location: `StatusService.delete()` (lines 181-215)

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