# Trustworks CRM System Documentation

## Executive Summary

The Trustworks CRM system is a comprehensive business management platform that handles client relationships, project management, consultant time tracking, and invoicing across three sister companies. This document provides both business logic descriptions and technical deep-dives into critical system operations, particularly the work-to-rate resolution process that determines hourly billing rates.

## Table of Contents
1. [Company Structure](#1-company-structure)
2. [Core Business Entities and Relationships](#2-core-business-entities-and-relationships)
3. [Work Registration and Billing Process](#3-work-registration-and-billing-process)
   - 3.4 [Technical Deep-Dive: Work-to-Rate Resolution](#34-technical-deep-dive-work-to-rate-resolution)
4. [Contract Types and Invoicing Rules](#4-contract-types-and-invoicing-rules)
5. [Data Model Architecture](#5-data-model-architecture)

---

## 1. Company Structure

The Trustworks group consists of three sister companies operating under a unified system:

### 1.1 The Three Companies

1. **Trustworks A/S** (UUID: d8894494-2fb4-4f72-9e05-e6032e6dd691)
   - Abbreviation: TW
   - CVR: 35648941
   - The main holding company providing general IT consulting services
   - Established: March 1, 2014

2. **Trustworks Technology ApS** (UUID: 44592d3b-2be5-4b29-bfaf-4fafc60b0fa3)
   - Abbreviation: TECH
   - CVR: 44232855
   - Specialized technology consulting and development services
   - Established: September 1, 2023

3. **Trustworks Cyber Security ApS** (UUID: e4b0a2a4-0963-4153-b0a2-a409637153a2)
   - Abbreviation: CYB
   - CVR: 45236609
   - Dedicated cybersecurity consulting and services
   - Established: December 1, 2024

### 1.2 Employee Assignment

Employees (users) are assigned to specific companies through the `userstatus` table, which tracks:
- Which company an employee belongs to (`companyuuid`)
- Their employment status (ACTIVE, TERMINATED, various leave types)
- Their allocation (hours per week, typically 37 for full-time in Denmark)
- Their type (CONSULTANT, STAFF, STUDENT, EXTERNAL)
- Bonus eligibility within the company

Contracts are also associated with specific companies, allowing for proper segregation of work and billing between the sister companies.

---

## 2. Core Business Entities and Relationships

### 2.1 Entity Hierarchy

The system follows a hierarchical structure for organizing work:

```
Client (Company we do business with)
  └── Project (Specific engagement for a client)
      └── Task (Phase or component of a project)
          └── Work (Time registration entry)
```

### 2.2 Detailed Entity Descriptions

#### 2.2.1 Client
- **Purpose**: Represents companies that Trustworks provides services to
- **Key Attributes**:
  - Name and contact information
  - Account manager assignment
  - Active/inactive status
  - CRM identifier for integration
- **Business Logic**: Clients are the top-level entity for all business relationships. They own projects and have associated billing information.

#### 2.2.2 Clientdata
- **Purpose**: Stores detailed billing and invoicing address information
- **Key Attributes**:
  - Billing address details (street, city, postal code)
  - Company registration numbers (CVR, EAN)
  - Contact person for invoicing
- **Business Logic**: A client may have multiple clientdata records for different billing destinations. The specific clientdata used for an invoice is determined by the contract's `clientdatauuid` field.

#### 2.2.3 Project
- **Purpose**: Represents specific engagements or work streams for a client
- **Key Attributes**:
  - Name and customer reference
  - Budget allocation
  - Start and end dates
  - Active/locked status
  - Project owner (user)
  - Link to clientdata for billing
- **Business Logic**: Projects group related work together and can be linked to one or more contracts through the contract_project relationship. Projects contain multiple tasks that break down the work.

#### 2.2.4 Task
- **Purpose**: Breaks down projects into manageable components or phases
- **Key Attributes**:
  - Name/description
  - Type (typically "CONSULTANT")
  - Parent project reference
- **Business Logic**: Tasks represent either project phases (e.g., "Analysis", "Implementation", "Testing") or specific competencies (e.g., "Project Management", "Development"). Consultants register their time against specific tasks.

#### 2.2.5 Work
- **Purpose**: Individual time registration entries
- **Key Attributes**:
  - Date of work (`registered`)
  - Duration in hours (`workduration`)
  - Hourly rate for billing (`rate`)
  - Billable flag
  - Comments/description
  - Optional "work as" field (when registering on behalf of another consultant)
  - Paid out timestamp (for tracking invoicing/payment)
- **Business Logic**: Work entries are the atomic units of time tracking. When a consultant registers time, the system:
  1. Links the work to a specific task and user
  2. Determines the applicable contract through the project-contract relationship
  3. Finds the consultant's rate from the contract_consultant record
  4. Stores the rate with the work entry for future invoicing

---

## 3. Work Registration and Billing Process

### 3.1 Contract Structure

#### 3.1.1 Contract
- **Purpose**: Defines the commercial agreement with a client
- **Key Attributes**:
  - Contract type (determines pricing rules)
  - Status (ACTIVE, INACTIVE, etc.)
  - Reference ID
  - Associated company (which Trustworks entity owns the contract)
  - Client and billing information
  - Sales consultant assignment
  - Parent contract (for amendments/extensions)
- **Business Logic**: Contracts are the commercial framework that governs how work is billed. They can have multiple consultants and projects associated with them.

#### 3.1.2 Contract_Consultant
- **Purpose**: Links consultants to contracts with specific rates and periods
- **Key Attributes**:
  - Consultant (user) reference
  - Active period (from/to dates)
  - Hourly billing rate
  - Allocated hours for the period
- **Business Logic**: This is the critical junction that determines billing rates. When work is registered:
  1. The system finds the applicable contract through project → contract_project → contract
  2. It then looks up the contract_consultant record matching the user and work date
  3. The rate from this record becomes the billing rate for the work entry

#### 3.1.3 Contract_Project
- **Purpose**: Links contracts to projects (many-to-many relationship)
- **Business Logic**: Allows flexibility where:
  - One project can be covered by multiple contracts (e.g., different phases)
  - One contract can cover multiple projects (e.g., framework agreements)

### 3.2 Rate Determination Process

When a consultant registers time:

1. **Work Entry Creation**: Consultant selects project, task, and enters hours
2. **Contract Resolution**: System finds applicable contract via project → contract_project
3. **Rate Lookup**: System queries contract_consultant for:
   - Matching user (or "work as" user if specified)
   - Contract from step 2
   - Date falling within contract_consultant's active period
4. **Rate Application**: The hourly rate from contract_consultant is stored with the work entry
5. **Invoice Preparation**: Work entries with rates are aggregated for invoicing

### 3.3 Special Considerations

- **Work As**: Allows one consultant to register time on behalf of another, using the other consultant's rate
- **Billable Flag**: Determines if work should be included in client invoices
- **Paid Out Tracking**: Timestamp indicating when work has been invoiced or paid

### 3.4 Technical Deep-Dive: Work-to-Rate Resolution

This section provides a detailed technical explanation of how the system resolves hourly billing rates for work entries. Understanding this process is crucial for data analysis, troubleshooting billing issues, and system maintenance.

#### 3.4.1 The Work_Full View

The system implements rate resolution through a database view called `work_full`, which consolidates all relevant information from multiple tables into a single queryable structure. This view is the foundation for reporting, invoicing, and revenue analysis.

#### 3.4.2 The Join Path

The rate resolution follows this precise path through the database:

```
work (w)
  └─ LEFT JOIN task (t) ON w.taskuuid = t.uuid
       └─ LEFT JOIN project (p) ON t.projectuuid = p.uuid
            └─ LEFT JOIN contract_context (ccc) ON:
                 • ccc.projectuuid = p.uuid
                 • ccc.useruuid = IF(w.workas IS NOT NULL, w.workas, w.useruuid)
                 • ccc.activefrom <= w.registered
                 • ccc.activeto >= w.registered
```

**Contract Context (ccc)** is a complex subquery that pre-joins:
- `contract_project` → Links projects to contracts
- `contract_consultants` → Contains consultant rates and periods
- `contracts` → Contract metadata and company assignment
- `contract_type_items` → Additional contract parameters (e.g., discounts)

#### 3.4.3 Step-by-Step Rate Resolution

When the system needs to determine the billing rate for a work entry, it follows these steps:

**Step 1: Identify the Task and Project**
```
work.taskuuid → task.uuid → task.projectuuid → project.uuid
```
- The work entry references a task via `taskuuid`
- The task belongs to a project via `projectuuid`
- This establishes the project context for the work

**Step 2: Determine the Effective User**
```
effective_user = IF(work.workas IS NOT NULL, work.workas, work.useruuid)
```
- If `workas` is populated, use that consultant's UUID
- Otherwise, use the actual user who registered the work (`useruuid`)
- This handles scenarios where senior consultants register time on behalf of juniors

**Step 3: Find Applicable Contract-Project Links**
```
contract_project WHERE projectuuid = project.uuid
```
- Multiple contracts may cover the same project
- Each contract_project link represents a potential billing arrangement

**Step 4: Match Contract-Consultant Records**
```
contract_consultants WHERE:
  - contractuuid = contract_project.contractuuid
  - useruuid = effective_user
  - activefrom <= work.registered
  - activeto >= work.registered
```
- Find the contract_consultant record that matches:
  - The contract covering the project
  - The effective user (respecting "work as")
  - The date the work was performed (temporal matching)
- This is the critical junction where the rate is determined

**Step 5: Extract Rate and Metadata**
```
SELECT
  cc.rate,                    -- Hourly billing rate
  cc.name,                    -- Consultant assignment name
  c.uuid AS contractuuid,     -- Which contract applies
  c.companyuuid,              -- Which Trustworks company invoices
  cti.value AS discount       -- Any contract-level discount
FROM contract_consultants cc
JOIN contracts c ON cc.contractuuid = c.uuid
LEFT JOIN contract_type_items cti ON c.uuid = cti.contractuuid
```

**Step 6: Determine Consultant Context**
```sql
(SELECT companyuuid FROM userstatus
 WHERE useruuid = work.useruuid
   AND statusdate <= work.registered
 ORDER BY statusdate DESC LIMIT 1) AS consultant_company_uuid

(SELECT type FROM userstatus
 WHERE useruuid = work.useruuid
   AND statusdate <= work.registered
 ORDER BY statusdate DESC LIMIT 1) AS type
```
- Finds the consultant's employment status on the work date
- Determines which company employed them (may differ from contract company)
- Identifies their type (CONSULTANT, STAFF, STUDENT, EXTERNAL)

#### 3.4.4 Edge Cases and Special Handling

**1. No Matching Contract-Consultant Record**
- If no contract_consultant record exists for the date/user/project combination
- The rate defaults to 0 (`IFNULL(ccc.rate, 0)`)
- This typically indicates:
  - Work registered before contract activation
  - Work registered after contract expiration
  - Missing contract setup
  - Internal/non-billable work

**2. Multiple Contracts Per Project**
- A project may be covered by multiple contracts simultaneously
- The join matches ALL applicable contract_consultant records
- Business rules or additional filters determine which rate to use
- Typically the most recent or highest priority contract wins

**3. Work As Functionality**
- When consultant A registers time as consultant B:
  - `work.useruuid` = A (who registered the time)
  - `work.workas` = B (whose rate should apply)
  - Rate lookup uses B's UUID
  - Employment status still references A's userstatus
- This enables team leads to register time for their team

**4. Temporal Boundary Conditions**
- Contract periods use inclusive date matching:
  - `activefrom <= registered` (inclusive start)
  - `activeto >= registered` (inclusive end)
- Work registered exactly on start/end dates is included
- Gap periods (no active contract) result in rate = 0

**5. Cross-Company Work**
- A consultant employed by Trustworks A/S can work on contracts owned by Trustworks Technology ApS
- `consultant_company_uuid` tracks which company employs the consultant
- `contract_company_uuid` tracks which company owns the contract
- This enables resource sharing across sister companies

#### 3.4.5 Data Validation and Integrity

**Critical Validations:**
1. **Task Existence**: Every work entry must reference a valid task
2. **Project Linkage**: Every task must belong to a project
3. **Contract Coverage**: Projects should have active contracts during work periods
4. **Rate Assignment**: All billable work should have non-zero rates
5. **Temporal Consistency**: Contract_consultant periods should not have gaps

**Common Data Issues:**
- **Rate = 0 on billable work**: Indicates missing contract_consultant record
- **Multiple rate matches**: Indicates overlapping contract periods (needs resolution)
- **Missing project context**: Orphaned tasks not linked to projects
- **Expired contracts with active work**: Work registered outside contract periods

#### 3.4.6 Performance Considerations

The work_full view joins multiple tables and executes subqueries, making it computationally expensive for large datasets. Performance optimizations include:

1. **Date Filtering**: Always filter by `registered >= '2021-07-01'` to limit historical data
2. **Indexed Columns**: Critical indexes on:
   - `work.taskuuid, work.useruuid, work.registered`
   - `contract_consultants.useruuid, contractuuid, activefrom, activeto`
   - `userstatus.useruuid, statusdate`
3. **Materialized Results**: Consider caching work_full results for reporting
4. **Batch Processing**: Process work entries in date-bounded batches

#### 3.4.7 Practical Example

Consider this work entry:
- Consultant: Sandra Warming (useruuid: 979f0032-7ee9-40ad-ba81-b4d8511ba8ce)
- Date: 2026-01-02
- Duration: 7.4 hours
- Task: f585f46f-19c1-4a3a-9ebd-1a4f21007282

**Resolution Process:**
1. Task belongs to Project X
2. Project X is linked to Contract Y via contract_project
3. Contract Y has a contract_consultant record for Sandra:
   - Active from 2025-12-01 to 2026-06-30
   - Rate: 1,325 DKK/hour
4. Work date (2026-01-02) falls within active period
5. **Result**: Rate = 1,325 DKK, Total = 9,805 DKK

---

## 4. Contract Types and Invoicing Rules

### 4.1 Contract Type Overview

The system supports five distinct contract types, each with specific pricing and discount rules:

#### 4.1.1 PERIOD Contracts
- **Description**: Standard time-and-materials contracts
- **Pricing Rules**:
  - Base hourly rates as defined in contract_consultant
  - Optional general discount percentage
  - No automatic fees or deductions
- **Use Case**: Direct commercial agreements with private sector clients

#### 4.1.2 SKI0217_2021 Contracts
- **Description**: Government framework agreement (2021 version)
- **Pricing Sequence**:
  1. **SKI Step Discount** ("trapperabat"): Volume-based discount on total sum
  2. **2% Administrative Fee**: Added to current sum
  3. **Fixed Invoice Fee**: DKK 2,000 added
  4. **General Discount**: Optional percentage discount
- **Use Case**: Danish public sector contracts under the 2021 SKI framework

#### 4.1.3 SKI0217_2025 Contracts
- **Description**: Updated government framework (2025 version)
- **Pricing Sequence**:
  1. **SKI Step Discount** ("trapperabat"): Volume-based discount
  2. **4% Administrative Fee**: Added to current sum (increased from 2021)
  3. **General Discount**: Optional percentage discount
- **Key Difference**: Higher admin fee (4% vs 2%) and no fixed invoice fee
- **Use Case**: Danish public sector contracts under the 2025 SKI 02.17 framework

#### 4.1.4 SKI0215_2025 Contracts
- **Description**: Simplified government framework (2025 version)
- **Pricing Sequence**:
  1. **4% Administrative Fee**: Added to current sum
  2. **General Discount**: Optional percentage discount
- **Key Difference**: No step discount, simplified pricing structure
- **Use Case**: Danish public sector contracts under the 2025 SKI 02.15 framework

#### 4.1.5 SKI0217_2025_V2 Contracts
- **Description**: Alternative SKI framework contract with standard pricing
- **Pricing Rules**:
  - Base hourly rates as defined in contract_consultant
  - Optional general discount percentage
  - No automatic fees or deductions
  - **Behaves identically to PERIOD contracts**
- **Key Difference**: Named after SKI framework but uses standard PERIOD pricing
- **Use Case**: Special case SKI framework contracts that follow standard time-and-materials pricing

### 4.2 Invoice Calculation Process

For each contract type, the invoice amount is calculated as follows:

1. **Base Calculation**: Sum of (hours × rate) for all work entries
2. **Apply Contract-Specific Rules**: Process discounts and fees in priority order
3. **Final Amount**: Result after all adjustments

The priority order ensures consistent calculation regardless of the specific discounts or fees applied.

---

## 5. Data Model Architecture

### 5.1 Relationship Summary

```
Companies (1) ← → (N) Users (via userstatus)
Companies (1) ← → (N) Contracts
Clients (1) ← → (N) Clientdata
Clients (1) ← → (N) Projects
Projects (1) ← → (N) Tasks
Projects (N) ← → (N) Contracts (via contract_project)
Contracts (1) ← → (N) Contract_Consultants
Users (1) ← → (N) Contract_Consultants
Users (1) ← → (N) Work entries
Tasks (1) ← → (N) Work entries
```

### 5.2 Temporal Data Management

#### 5.2.1 User Status Tracking
The `userstatus` table implements a temporal log pattern:
- **Every status change** creates a new record with an effective date
- **Current status** is determined by finding the latest record ≤ today
- **Historical tracking** enables retroactive reporting and audit trails

Example Status Progression:
1. PREBOARDING (before start date)
2. ACTIVE (employment begins)
3. MATERNITY_LEAVE (temporary leave)
4. ACTIVE (return from leave)
5. TERMINATED (employment ends)

#### 5.2.2 Contract Consultant Periods
Contract_consultant records use date ranges (`activefrom`/`activeto`) to:
- Track rate changes over time
- Manage consultant assignments to contracts
- Support overlapping contracts for the same consultant

### 5.3 Key Business Rules

1. **Work Registration Validation**:
   - Must have valid task (which links to project)
   - User must have active status on registration date
   - Contract_consultant record must exist for the period

2. **Rate Determination**:
   - Always uses the rate from contract_consultant, not user-defined
   - Rate is locked at registration time (stored with work entry)
   - "Work as" functionality uses the target consultant's rate

3. **Invoice Generation**:
   - Only includes billable work entries
   - Groups work by consultant for line items
   - Applies contract-type-specific pricing rules
   - Links to specific clientdata for billing address

4. **Multi-Company Operations**:
   - Contracts belong to specific Trustworks companies
   - Invoices are issued from the contract's company
   - Users can work across companies but are employed by one

### 5.4 Data Integrity Constraints

1. **Foreign Key Relationships**: Maintain referential integrity
2. **Temporal Consistency**: Status dates must be logical (no gaps/overlaps)
3. **Rate Immutability**: Work entry rates are fixed at registration
4. **Contract Coverage**: Work must fall within contract consultant active periods

---

## Conclusion

The Trustworks CRM system implements a sophisticated yet flexible model for managing consulting engagements across multiple companies. The hierarchical structure from clients through projects and tasks to individual work entries provides granular tracking while maintaining clear business relationships.

The contract management system, with its support for different pricing models and government frameworks, enables Trustworks to serve both private and public sector clients effectively. The temporal data management ensures accurate historical tracking and supports complex employment scenarios.

This architecture supports the complete consulting lifecycle: from initial client engagement and contract negotiation, through project execution and time tracking, to final invoicing and payment processing.