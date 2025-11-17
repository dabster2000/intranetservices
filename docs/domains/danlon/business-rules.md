# Danløn Number Generation - Business Rules

## Overview

This document describes the comprehensive business rules for automatic Danløn (payroll) number generation in the Trustworks system. Danløn numbers are employee identifiers used for payroll integration with external systems.

## Core Principles

1. **Temporal Tracking**: All Danløn number changes are tracked historically with effective dates normalized to the 1st of the month
2. **Automatic Generation**: System automatically generates new Danløn numbers when specific business conditions are met
3. **Audit Trail**: All changes include `created_by` marker to indicate the triggering rule or user
4. **Unique Constraint**: Only one Danløn number per user per month (enforced at database level)
5. **Rule Precedence**: Company transition takes precedence over salary type changes when both occur in same month

## Business Rules

### Rule 1: New Employee Hire

**Trigger**: User is hired into a company for the first time.

**Conditions**:
- User has new `UserStatus` record
- Status type is NOT `TERMINATED` or `PREBOARDING`
- User has valid company association

**Action**: Initial Danløn number is manually assigned by HR during onboarding process.

**Marker**: User-created (typically admin email address)

---

### Rule 2: Salary Changes

#### Rule 2a: Salary Amount Changes

**Trigger**: User's monthly salary amount changes.

**Conditions**:
- New `Salary` record created with different `salary` field value
- Effective from 1st of month

**Action**: No Danløn number change. Salary adjustments do not trigger new Danløn numbers.

**Detection**: Reported in `DanlonResource.findChangedUsers()` with message like "Lønforhøjelse: 35.000,00 kr."

#### Rule 2b: Salary Type Change (HOURLY → NORMAL)

**Trigger**: User transitions from hourly (HOURLY) to monthly (NORMAL) salary type.

**Conditions** (ALL must be true):
1. Previous month's `Salary` record has `type = HOURLY`
2. Current month's `Salary` record has `type = NORMAL`
3. User has `UserStatus` in same month with status NOT `TERMINATED` or `PREBOARDING`
4. No Danløn history exists for this month with `created_by = 'system-salary-type-change'`
5. No company transition occurred this month (Rule 3 takes precedence)

**Action**: Generate new Danløn number (format: `T####` where #### is auto-incremented)

**Marker**: `created_by = 'system-salary-type-change'`

**Detection Location**:
- `SalaryService.create()` → `handleSalaryTypeChange()` (lines 127-168)
- `StatusService.create()` → `checkForPendingSalaryTypeChange()` (lines 166-231) [reciprocal check]

**Implementation Details**:
- **Order-Independent**: Works regardless of whether salary is changed first or status is created first
- **Bidirectional Checking**: Both `SalaryService` and `StatusService` check for the complementary change
- **Duplicate Prevention**: Unique constraint on `(useruuid, active_date)` prevents duplicate history records

**Example Scenario**:
```
User: John Doe
Previous Month (Oct 2025): Salary = 25,000 kr, Type = HOURLY
Current Month (Nov 2025): Salary = 30,000 kr, Type = NORMAL, Status = ACTIVE
Result: New Danløn number T1234 generated with marker "system-salary-type-change"
```

#### Rule 2c: Company Transition Detection in Reporting

**Trigger**: Company transition detected when generating change reports.

**Conditions**:
- User has `UserDanlonHistory` record with `created_by = 'system-company-transition'` for current month

**Action**: Report change in `DanlonResource.findChangedUsers()` with message "Skiftet firma. Nyt Danløn-nummer: T####."

**Detection Location**: `DanlonResource.findChangedUsers()` → `hasCompanyTransitionThisMonth()` (lines 239-253)

---

### Rule 3: Company Transition (NEW - Priority Rule)

**Trigger**: User is terminated from one company and becomes active in a different company on the same date.

**Conditions** (ALL must be true):
1. User has `UserStatus` with `status = TERMINATED` in Company A on date D
2. User has `UserStatus` with `status = ACTIVE` (or other qualifying status) in Company B on the SAME date D
3. Company A UUID ≠ Company B UUID (different companies)
4. Target status is NOT `TERMINATED` or `PREBOARDING`
5. No Danløn history exists for this month with `created_by = 'system-company-transition'`

**Action**: Generate new Danløn number (format: `T####`)

**Marker**: `created_by = 'system-company-transition'`

**Detection Location**: `StatusService.create()` → `checkForCompanyTransition()` (lines 259-332)

**Precedence**: This rule takes **absolute precedence** over Rule 2b (salary type change). If both company transition AND salary type change occur in the same month, only the company transition generates a Danløn number.

**Implementation Details**:
- **Exact Date Matching**: Termination and activation must be on the exact same date
- **Company Filtering**: Query uses `company.uuid != ?` to ensure different companies
- **Month Normalization**: Danløn history active_date is set to 1st of month
- **Error Handling**: Gracefully handles race conditions with try-catch for `IllegalArgumentException`

**Example Scenarios**:

**✅ Valid Company Transition**:
```
Date: 2025-11-01
Company A: User TERMINATED on 2025-11-01
Company B: User ACTIVE on 2025-11-01
Result: New Danløn number T5678 generated with marker "system-company-transition"
```

**❌ Invalid - Different Dates**:
```
Date: 2025-11-01 (Company A termination)
Date: 2025-11-15 (Company B activation)
Result: No Danløn number generated (different dates)
```

**❌ Invalid - Same Company**:
```
Company A: User TERMINATED on 2025-11-01
Company A: User ACTIVE on 2025-11-01 (rehired same company)
Result: No Danløn number generated (same company)
```

**❌ Invalid - PREBOARDING in Target Company**:
```
Company A: User TERMINATED on 2025-11-01
Company B: User PREBOARDING on 2025-11-01
Result: No Danløn number generated (PREBOARDING excluded)
```

---

### Rule 4: Re-Employment (NEW - Third Priority)

**Trigger**: User was previously TERMINATED and becomes ACTIVE again (either same company or different company).

**Conditions** (ALL must be true):
1. User has current `UserStatus` with `status = ACTIVE` (or qualifying status, not TERMINATED/PREBOARDING)
2. User has previous `UserStatus` with `status = TERMINATED` (any company, any earlier date)
3. PREBOARDING statuses are ignored (treated as transition states)
4. No Danløn history already exists for current month (any marker)

**Action**: Generate new Danløn number (format: `T####`)

**Marker**: `created_by = 'system-re-employment'`

**Detection Location**: `StatusService.create()` → `checkForReEmployment()` (lines 363-433)

**Precedence**: This rule runs AFTER company transition (Rule 3) and salary type change (Rule 2b). If either of those rules already generated a Danløn number this month, re-employment check is skipped.

**Cleanup**: When ACTIVE status is deleted, the corresponding re-employment Danløn history is automatically deleted. Other Danløn markers (company transition, salary type change, manual entries) are preserved.

**Implementation Details**:
- **Query Pattern**: Finds ANY previous TERMINATED status (ignoring PREBOARDING) using `statusdate < currentStatusDate`
- **Company Scope**: Applies to both SAME company and DIFFERENT companies
- **Time Gap**: No minimum gap required (same date allowed)
- **Error Handling**: Gracefully handles race conditions with try-catch for `IllegalArgumentException`
- **Cleanup Location**: `StatusService.delete()` (lines 137-182)

**Example Scenarios**:

**✅ Valid Re-Employment (Same Company)**:
```
User: John Doe
Company A: TERMINATED on 2025-10-15
Company A: ACTIVE on 2025-11-01 (re-hired)
Result: New Danløn number T6789 generated with marker "system-re-employment"
```

**✅ Valid Re-Employment (Different Companies)**:
```
User: Jane Smith
Company A: TERMINATED on 2025-10-15
Company B: ACTIVE on 2025-11-01 (different company, different month)
Result: New Danløn number T6790 generated with marker "system-re-employment"
```

**❌ Invalid - Company Transition Takes Precedence**:
```
Company A: TERMINATED on 2025-11-01
Company B: ACTIVE on 2025-11-01 (different companies, SAME date)
Result: Company transition marker used instead (precedence)
```

**✅ Valid - PREBOARDING Ignored**:
```
Company A: TERMINATED on 2025-10-01
Company A: PREBOARDING on 2025-10-25
Company A: ACTIVE on 2025-11-01
Result: Re-employment detected (PREBOARDING ignored as transition state)
```

**🧹 Cleanup Example**:
```
# Create re-employment Danløn
statusService.create(activeStatus) → Danløn T6789 created

# Delete ACTIVE status
statusService.delete(activeStatus.uuid) → Danløn T6789 automatically deleted
```

---

### Rule 5: Termination

**Trigger**: User's employment is terminated.

**Conditions**:
- `UserStatus` with `status = TERMINATED` created

**Action**: No Danløn number change. Terminated employees retain their last Danløn number.

**Detection**: Reported in `DanlonResource.findChangedUsers()` with message "Sidste løn, medarbejder opsagt."

---

## Rule Precedence Order

When multiple rules could apply in the same month, the following precedence order is enforced:

1. **Rule 3: Company Transition** (Highest Priority)
   - Checked FIRST in `StatusService.create()`
   - If company transition detected, salary type change and re-employment checks are skipped
   - Only one Danløn history record created with marker `system-company-transition`

2. **Rule 2b: Salary Type Change**
   - Checked SECOND (only if Rule 3 did not apply)
   - Conditional check: `!hasDanlonChangedInMonthBy(useruuid, month, "system-company-transition")`
   - Creates Danløn history record with marker `system-salary-type-change`

3. **Rule 4: Re-Employment** (Third Priority)
   - Checked THIRD (only if Rules 3 and 2b did not apply)
   - Conditional check: `!hasDanlonChangedInMonth(useruuid, month)` (checks ANY marker)
   - Creates Danløn history record with marker `system-re-employment`
   - **Cleanup**: Automatically deleted when ACTIVE status is deleted

4. **Rule 1, 2a, 5**: Other Rules
   - Do not generate new Danløn numbers
   - Reported in change detection endpoints

## Implementation Architecture

### Key Components

**Entities**:
- `UserDanlonHistory` - Temporal storage of Danløn numbers with effective dates
- `UserStatus` - Employment status with company association
- `Salary` - Salary records (no company association)

**Services**:
- `UserDanlonHistoryService` - CRUD operations, temporal queries, helper methods
- `StatusService` - Status management, company transition detection (Rule 3)
- `SalaryService` - Salary management, salary type change detection (Rule 2b)

**Resources**:
- `DanlonResource` - REST API for change detection and reporting

### Bidirectional Checking Pattern

Both `SalaryService` and `StatusService` implement reciprocal checking to handle order-independent changes:

**Scenario 1: Salary changes first, then Status created**:
1. Admin changes salary type HOURLY → NORMAL
2. `SalaryService.handleSalaryTypeChange()` runs → finds no UserStatus → no action
3. Admin creates new ACTIVE UserStatus
4. `StatusService.checkForPendingSalaryTypeChange()` runs → detects salary was changed → generates Danløn

**Scenario 2: Status created first, then Salary changed**:
1. Admin creates new ACTIVE UserStatus
2. `StatusService.checkForPendingSalaryTypeChange()` runs → salary still HOURLY → no action
3. Admin changes salary type HOURLY → NORMAL
4. `SalaryService.handleSalaryTypeChange()` runs → finds UserStatus → generates Danløn

### Duplicate Prevention

Multiple mechanisms prevent duplicate Danløn history records:

1. **Database Constraint**: Unique constraint on `(useruuid, active_date)`
2. **Marker Checks**: `hasDanlonChangedInMonthBy(useruuid, month, marker)` checks before generation
3. **Exception Handling**: `IllegalArgumentException` caught and logged (duplicate prevented)

## Helper Methods

### `UserDanlonHistoryService`

**`hasDanlonChangedInMonth(String useruuid, LocalDate month)`**:
- Returns `true` if user has ANY Danløn history record for the month
- Used for general change detection

**`hasDanlonChangedInMonthBy(String useruuid, LocalDate month, String createdBy)`**:
- Returns `true` if user has Danløn history record with specific `created_by` marker
- Used for precedence checking (e.g., skip salary type change if company transition exists)

**`generateNextDanlonNumber()`**:
- Auto-increments from last Danløn number in system
- Format: `T####` (e.g., T1000, T1001, T1002)
- Thread-safe via database query

**`addDanlonHistory(String useruuid, LocalDate activeDate, String danlon, String createdBy)`**:
- Creates new `UserDanlonHistory` record
- Normalizes `activeDate` to 1st of month
- Throws `IllegalArgumentException` if duplicate exists

### `DanlonResource`

**`hasDanlonNumberChangedThisMonth(User user, LocalDate month)`**:
- Returns `true` if user has Danløn change from EITHER salary type change OR company transition
- Query: `created_by = 'system-salary-type-change' OR created_by = 'system-company-transition'`
- Used in `findChangedUsers()` for automatic inclusion in reports

**`hasCompanyTransitionThisMonth(User user, LocalDate month)`**:
- Returns `true` if user has company transition this month
- Query: `created_by = 'system-company-transition'`
- Used for generating transition-specific messages in reports

**`hasReEmploymentThisMonth(User user, LocalDate month)`**:
- Returns `true` if user was re-employed this month
- Query: `created_by = 'system-re-employment'`
- Used for generating re-employment-specific messages in reports

## Testing

Comprehensive integration tests verify all rules and edge cases:

**`SalaryTypeChangeIntegrationTest.java`** (11 test cases):
- Happy path: HOURLY → NORMAL with ACTIVE status
- Edge cases: No status, TERMINATED status, PREBOARDING status
- Reverse direction: NORMAL → HOURLY (should not generate)
- Duplicate prevention: Multiple changes in same month
- Order independence: Salary first vs Status first
- Helper method validation

**`CompanyTransitionIntegrationTest.java`** (8 test cases):
- Happy path: TERMINATED in Company A + ACTIVE in Company B on same date
- Edge cases: Different dates, same company, PREBOARDING in target
- Duplicate prevention: Multiple transitions in same month
- Precedence: Company transition overrides salary type change
- Helper method validation

**`ReEmploymentIntegrationTest.java`** (11 test cases):
- Happy path: TERMINATED → ACTIVE (same company, different months)
- Same company, same date (re-employment, not company transition)
- Different companies, same date (company transition takes precedence)
- Different companies, different dates (re-employment)
- TERMINATED → PREBOARDING → ACTIVE (PREBOARDING ignored)
- Cleanup: Delete ACTIVE status, verify re-employment Danløn deleted
- Cleanup: Preserve company transition Danløn when deleting ACTIVE
- Cleanup: No deletion when deleting TERMINATED status
- Multiple terminations/re-employments (one Danløn per month)
- No previous termination (new hire, no Danløn generated)
- Helper method validation

## Data Migration

Historical data migration followed this pattern:

1. **V109**: Created `user_danlon_history` table with unique constraint
2. **V110**: Migrated existing `user_ext_account.danlon` values to history table
   - Set `active_date` to user's hire date (1st of month)
   - Set `created_by = 'system-migration'`
3. **V111** (FUTURE): Will remove denormalized `user_ext_account.danlon` field after validation period

## API Endpoints

**GET** `/company/{companyuuid}/danlon/employees/changed?month=YYYY-MM`
- Returns list of users with changes in specified month
- Includes Danløn number changes, salary changes, terminations
- Messages reflect business rules (e.g., "Skiftet firma. Nyt Danløn-nummer: T1234.")

**POST** `/company/{companyuuid}/danlon/employees/export`
- Generates CSV export of employees for payroll integration
- Includes current Danløn numbers as of specified date

## References

- **Entity**: `dk.trustworks.intranet.domain.user.entity.UserDanlonHistory`
- **Service**: `dk.trustworks.intranet.domain.user.service.UserDanlonHistoryService`
- **Status Service**: `dk.trustworks.intranet.aggregates.users.services.StatusService`
- **Salary Service**: `dk.trustworks.intranet.aggregates.users.services.SalaryService`
- **Resource**: `dk.trustworks.intranet.aggregates.accounting.resources.DanlonResource`
- **Tests**: `src/test/java/dk/trustworks/intranet/aggregates/users/services/`
- **Migrations**: `src/main/resources/db/migration/V109__Create_user_danlon_history.sql`

## Revision History

| Date | Version | Author | Changes |
|------|---------|--------|---------|
| 2025-11-17 | 1.0 | System | Initial documentation of Danløn generation rules |
| 2025-11-17 | 1.1 | System | Added Rule 3 (Company Transition) with precedence logic |
| 2025-11-17 | 1.2 | System | Added Rule 4 (Re-Employment) with cleanup logic and precedence ordering |
