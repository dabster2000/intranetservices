⏺ Contract Validation System - Developer Guide

Overview

The contract validation system prevents billing conflicts by ensuring no consultant can be assigned to the same project during overlapping time periods. This maintains data integrity and prevents ambiguous rate calculations.

Key Validation Rules

1. No Overlapping Assignments: A consultant cannot have overlapping date ranges for the same project across different contracts
2. Valid Date Ranges: activeFrom must be ≤ activeTo
3. Positive Rates: Consultant rates must be > 0
4. Contract Consistency: Active contracts must have valid consultant and project configurations

  ---
When Validation Occurs

Automatic Validation (Enforced)

Validation automatically runs and blocks the operation when:

- Creating a new contract with active/signed/time status
- Updating an existing contract to active/signed/time status
- Adding a consultant to a contract
- Updating a consultant's dates, rate, or assignment
- Linking a project to a contract

If validation fails, a ContractValidationException is thrown with HTTP status 500 and detailed error information.

Preview Validation (Optional)

Call validation endpoints before submitting changes to preview issues:

POST /contracts/validate
POST /contracts/{contractUuid}/consultants/validate
POST /contracts/{contractUuid}/projects/{projectUuid}/validate

These endpoints return a ValidationReport without saving changes.

  ---
API Endpoints Reference

1. Validate Full Contract

Endpoint: POST /contracts/validate

When to use: Before activating a contract or making bulk changes

Request Body:
{
"uuid": "contract-uuid",
"status": "ACTIVE",
"contractConsultants": [
{
"uuid": "cc-uuid",
"useruuid": "user-uuid",
"name": "John Doe",
"activeFrom": "2025-01-01",
"activeTo": "2025-12-31",
"rate": 1200,
"hours": 160
}
],
"contractProjects": [
{
"contractuuid": "contract-uuid",
"projectuuid": "project-uuid"
}
]
}

Response: ValidationReport (see format below)

2. Validate Consultant Assignment

Endpoint: POST /contracts/{contractUuid}/consultants/validate

When to use: Before adding or updating a consultant

Request Body:
{
"uuid": "cc-uuid",
"useruuid": "user-uuid",
"name": "John Doe",
"activeFrom": "2025-01-01",
"activeTo": "2025-12-31",
"rate": 1200,
"hours": 160
}

Response: ValidationReport

3. Validate Project Linkage

Endpoint: POST /contracts/{contractUuid}/projects/{projectUuid}/validate

When to use: Before linking a project to a contract

Request: Empty body (uses path parameters)

Response: ValidationReport

  ---
Response Format - ValidationReport

{
"valid": false,
"validatedAt": "2025-10-04T14:30:00",
"errors": [
{
"field": "consultant",
"message": "Consultant John Doe already assigned to project from 2025-01-01 to 2025-06-30",
"type": "OVERLAP_CONFLICT"
}
],
"warnings": [
{
"field": "rate",
"message": "Rate change affects 45 unbilled work entries worth 54000.00",
"type": "RATE_CHANGE"
}
],
"overlaps": [
{
"consultantName": "John Doe",
"projectName": "Project Alpha",
"existingContractName": "Contract A",
"existingActiveFrom": "2025-01-01",
"existingActiveTo": "2025-06-30",
"existingRate": 1150,
"newActiveFrom": "2025-03-01",
"newActiveTo": "2025-12-31",
"newRate": 1200,
"overlapStart": "2025-03-01",
"overlapEnd": "2025-06-30",
"overlapDays": 122,
"rateConflict": true,
"description": "Consultant John Doe is already assigned..."
}
],
"context": {
"contractUuid": "contract-uuid",
"consultantUuid": "user-uuid",
"consultantName": "John Doe",
"affectedWorkEntries": 45,
"unbilledAmount": 54000.00,
"conflictingContracts": 1
},
"summary": "Validation failed: 1 error(s), 1 overlap(s), 1 warning(s)"
}

Error Types

| Type               | Description                                            |
  |--------------------|--------------------------------------------------------|
| OVERLAP_CONFLICT   | Consultant has overlapping assignment for same project |
| DATE_RANGE_INVALID | activeFrom > activeTo                                  |
| MISSING_REQUIRED   | Required field is null                                 |
| RATE_CONFLICT      | Rate is invalid (≤ 0)                                  |
| CONTRACT_INACTIVE  | Contract is not active                                 |
| WORK_EXISTS        | Work entries exist in affected period                  |
| DUPLICATE_PROJECT  | Project already linked to contract                     |

Warning Types

| Type                 | Description                       |
  |----------------------|-----------------------------------|
| RATE_CHANGE          | Rate change affects unbilled work |
| FUTURE_CONTRACT      | Contract starts in the future     |
| GAP_IN_COVERAGE      | Gap in consultant coverage        |
| RETROACTIVE_CONTRACT | Contract covers past work         |
| HIGH_RATE            | Rate is unusually high            |
| LOW_RATE             | Rate is unusually low             |

  ---
Exception Handling

HTTP Error Response

When automatic validation fails, you'll receive:

Status Code: 500 Internal Server Error

Response Body:
{
"error": "ContractValidationException",
"message": "Contract validation failed with 2 error(s):\n- consultant: Consultant John Doe already assigned...\n- dateRange: Start date must be before end date\n",
"errors": [
{
"field": "consultant",
"message": "Consultant John Doe already assigned...",
"type": "OVERLAP_CONFLICT"
},
{
"field": "dateRange",
"message": "Start date must be before end date",
"type": "DATE_RANGE_INVALID"
}
]
}

Client-Side Handling

JavaScript/TypeScript Example

async function addConsultant(contractUuid: string, consultant: Consultant) {
try {
// Option 1: Preview validation first (recommended)
const validationReport = await fetch(
`/contracts/${contractUuid}/consultants/validate`,
{
method: 'POST',
headers: { 'Content-Type': 'application/json' },
body: JSON.stringify(consultant)
}
).then(r => r.json());

      if (!validationReport.valid) {
        // Show errors to user
        displayValidationErrors(validationReport);
        return;
      }

      // Warnings don't block, but should be confirmed
      if (validationReport.warnings.length > 0) {
        const confirmed = await confirmWarnings(validationReport.warnings);
        if (!confirmed) return;
      }

      // Validation passed, proceed with save
      await fetch(`/contracts/${contractUuid}/consultants/${consultant.uuid}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(consultant)
      });

      showSuccess('Consultant added successfully');

    } catch (error) {
      if (error.response?.status === 500) {
        // Option 2: Handle validation exception after the fact
        const errorData = await error.response.json();
        if (errorData.error === 'ContractValidationException') {
          displayValidationErrors(errorData);
        } else {
          showError('An unexpected error occurred');
        }
      }
    }
}

function displayValidationErrors(report: ValidationReport) {
// Show errors
report.errors.forEach(error => {
alert(`${error.field}: ${error.message}`);
});

    // Show overlaps with details
    report.overlaps.forEach(overlap => {
      alert(overlap.description);
    });

    // Show warnings
    report.warnings.forEach(warning => {
      console.warn(`${warning.field}: ${warning.message}`);
    });
}

Java Client Example

public void addConsultant(String contractUuid, ContractConsultant consultant) {
// Preview validation
ValidationReport report = restClient
.post()
.uri("/contracts/{contractUuid}/consultants/validate", contractUuid)
.body(consultant)
.retrieve()
.body(ValidationReport.class);

      if (!report.isValid()) {
          // Handle errors
          report.getErrors().forEach(error ->
              log.error("Validation error on {}: {}", error.getField(), error.getMessage())
          );
          throw new BusinessException("Validation failed", report);
      }

      // Check warnings
      if (!report.getWarnings().isEmpty()) {
          // Prompt user for confirmation
          if (!confirmWarnings(report.getWarnings())) {
              return;
          }
      }

      // Proceed with save
      try {
          restClient
              .post()
              .uri("/contracts/{contractUuid}/consultants/{uuid}", contractUuid, consultant.getUuid())
              .body(consultant)
              .retrieve()
              .toBodilessEntity();
      } catch (HttpServerErrorException e) {
          if (e.getStatusCode().value() == 500) {
              // Parse validation exception
              handleValidationException(e);
          }
      }
}

Python Client Example

def add_consultant(contract_uuid: str, consultant: dict):
# Preview validation
validation_url = f"/contracts/{contract_uuid}/consultants/validate"
response = requests.post(validation_url, json=consultant)
report = response.json()

      if not report['valid']:
          # Display errors
          for error in report['errors']:
              print(f"Error: {error['message']}")

          for overlap in report['overlaps']:
              print(f"Overlap: {overlap['description']}")

          raise ValidationError("Validation failed", report)

      # Check warnings
      if report['warnings']:
          for warning in report['warnings']:
              print(f"Warning: {warning['message']}")

          if not confirm_warnings():
              return

      # Save
      save_url = f"/contracts/{contract_uuid}/consultants/{consultant['uuid']}"
      try:
          requests.post(save_url, json=consultant)
      except requests.HTTPError as e:
          if e.response.status_code == 500:
              error_data = e.response.json()
              if error_data.get('error') == 'ContractValidationException':
                  handle_validation_exception(error_data)

  ---
Best Practices

1. Always Preview Before Save (Recommended Workflow)

1. User fills form
2. Client calls validation endpoint
3. Show errors/warnings to user
4. User confirms or fixes issues
5. Client calls save endpoint
6. Success or handle unexpected errors

2. Handle Warnings Appropriately

- Warnings don't block saves but should be shown to users
- Consider requiring confirmation for high-impact warnings (rate changes, work conflicts)
- Log warnings for audit purposes

3. Provide Context to Users

Use the context object in ValidationReport:
- Show affected work entries count
- Display unbilled amount impact
- List conflicting contracts

4. Graceful Degradation

If validation service is unavailable:
- Database constraints will still prevent invalid data
- Catch database constraint violations as fallback

5. Batch Operations

For bulk imports:
async function importConsultants(consultants: Consultant[]) {
const validationResults = await Promise.all(
consultants.map(c => validateConsultant(c))
);

    const valid = validationResults.filter(r => r.valid);
    const invalid = validationResults.filter(r => !r.valid);

    // Report issues
    console.log(`Valid: ${valid.length}, Invalid: ${invalid.length}`);

    // Import only valid ones
    await importValid(valid);
}

  ---
SQL Queries to Find Existing Data Issues

1. Find Overlapping Consultant Assignments on Same Projects

-- Find all consultant overlaps with details
SELECT
cc1.uuid AS consultant1_uuid,
cc1.useruuid AS user_uuid,
u.firstname,
u.lastname,
cc1.contractuuid AS contract1_uuid,
c1.name AS contract1_name,
cc1.activefrom AS contract1_from,
cc1.activeto AS contract1_to,
cc1.rate AS contract1_rate,
cc2.uuid AS consultant2_uuid,
cc2.contractuuid AS contract2_uuid,
c2.name AS contract2_name,
cc2.activefrom AS contract2_from,
cc2.activeto AS contract2_to,
cc2.rate AS contract2_rate,
cp1.projectuuid AS project_uuid,
p.name AS project_name,
-- Calculate overlap period
GREATEST(cc1.activefrom, cc2.activefrom) AS overlap_start,
LEAST(cc1.activeto, cc2.activeto) AS overlap_end,
DATEDIFF(
LEAST(cc1.activeto, cc2.activeto),
GREATEST(cc1.activefrom, cc2.activefrom)
) + 1 AS overlap_days,
-- Rate conflict indicator
IF(ABS(cc1.rate - cc2.rate) > 0.01, 'YES', 'NO') AS rate_conflict,
-- Status
c1.status AS contract1_status,
c2.status AS contract2_status
FROM contract_consultants cc1
JOIN contract_consultants cc2
ON cc1.useruuid = cc2.useruuid
AND cc1.uuid < cc2.uuid -- Prevent duplicate pairs
JOIN contract_project cp1 ON cc1.contractuuid = cp1.contractuuid
JOIN contract_project cp2 ON cc2.contractuuid = cp2.contractuuid
AND cp1.projectuuid = cp2.projectuuid -- Same project
JOIN contracts c1 ON c1.uuid = cc1.contractuuid
JOIN contracts c2 ON c2.uuid = cc2.contractuuid
JOIN project p ON p.uuid = cp1.projectuuid
LEFT JOIN user u ON u.uuid = cc1.useruuid
WHERE
-- Check for date overlap
cc1.activeto >= cc2.activefrom
AND cc1.activefrom <= cc2.activeto
-- Only check active contracts
AND c1.status NOT IN ('INACTIVE', 'CLOSED')
AND c2.status NOT IN ('INACTIVE', 'CLOSED')
ORDER BY
u.lastname,
u.firstname,
p.name,
overlap_start;

2. Find Invalid Date Ranges

-- Consultants with activeFrom > activeTo
SELECT
cc.uuid,
cc.contractuuid,
c.name AS contract_name,
cc.useruuid,
u.firstname,
u.lastname,
cc.activefrom,
cc.activeto,
DATEDIFF(cc.activeto, cc.activefrom) AS days_diff,
cc.rate,
c.status
FROM contract_consultants cc
JOIN contracts c ON c.uuid = cc.contractuuid
LEFT JOIN user u ON u.uuid = cc.useruuid
WHERE cc.activefrom > cc.activeto
ORDER BY cc.activefrom DESC;

3. Find Negative or Zero Rates

-- Consultants with invalid rates
SELECT
cc.uuid,
cc.contractuuid,
c.name AS contract_name,
cc.useruuid,
u.firstname,
u.lastname,
cc.rate,
cc.activefrom,
cc.activeto,
c.status,
-- Check for unbilled work
(SELECT COUNT(*)
FROM work w
JOIN task t ON w.taskuuid = t.uuid
JOIN contract_project cp ON t.projectuuid = cp.projectuuid
WHERE w.useruuid = cc.useruuid
AND cp.contractuuid = cc.contractuuid
AND w.registered BETWEEN cc.activefrom AND cc.activeto
AND w.invoice_line_uuid IS NULL
) AS unbilled_work_count
FROM contract_consultants cc
JOIN contracts c ON c.uuid = cc.contractuuid
LEFT JOIN user u ON u.uuid = cc.useruuid
WHERE cc.rate <= 0
ORDER BY c.status, cc.rate;

4. Find Duplicate Contract-Project Links

-- Projects linked multiple times to same contract
SELECT
cp.contractuuid,
c.name AS contract_name,
cp.projectuuid,
p.name AS project_name,
COUNT(*) AS link_count,
GROUP_CONCAT(cp.uuid) AS link_uuids
FROM contract_project cp
JOIN contracts c ON c.uuid = cp.contractuuid
JOIN project p ON p.uuid = cp.projectuuid
GROUP BY cp.contractuuid, cp.projectuuid
HAVING COUNT(*) > 1
ORDER BY link_count DESC;

5. Find Active Contracts Without Consultants or Projects

-- Contracts missing consultants
SELECT
c.uuid,
c.name,
c.status,
c.created,
(SELECT COUNT(*) FROM contract_consultants WHERE contractuuid = c.uuid) AS consultant_count,
(SELECT COUNT(*) FROM contract_project WHERE contractuuid = c.uuid) AS project_count
FROM contracts c
WHERE c.status IN ('ACTIVE', 'SIGNED', 'TIME')
AND (
NOT EXISTS (SELECT 1 FROM contract_consultants WHERE contractuuid = c.uuid)
OR NOT EXISTS (SELECT 1 FROM contract_project WHERE contractuuid = c.uuid)
)
ORDER BY c.created DESC;

6. Find Work Entries Without Valid Contract Coverage

-- Work that doesn't match any contract
SELECT
w.uuid AS work_uuid,
w.registered AS work_date,
w.useruuid,
u.firstname,
u.lastname,
w.taskuuid,
t.name AS task_name,
t.projectuuid,
p.name AS project_name,
w.workduration AS hours,
w.billable,
-- Attempt to find matching contract
(SELECT COUNT(*)
FROM contract_consultants cc
JOIN contract_project cp ON cc.contractuuid = cp.contractuuid
WHERE cc.useruuid = w.useruuid
AND cp.projectuuid = t.projectuuid
AND cc.activefrom <= w.registered
AND cc.activeto >= w.registered
) AS matching_contracts
FROM work w
JOIN task t ON w.taskuuid = t.uuid
JOIN project p ON t.projectuuid = p.uuid
LEFT JOIN user u ON u.uuid = w.useruuid
WHERE w.billable = 1
AND w.registered >= '2021-07-01' -- Adjust date range as needed
HAVING matching_contracts = 0
ORDER BY w.registered DESC
LIMIT 100;

7. Find Consultant Assignments with Unusually High/Low Rates

-- Outlier rates (configurable thresholds)
WITH rate_stats AS (
SELECT
AVG(rate) AS avg_rate,
STDDEV(rate) AS stddev_rate,
MIN(rate) AS min_rate,
MAX(rate) AS max_rate
FROM contract_consultants
WHERE rate > 0
)
SELECT
cc.uuid,
cc.contractuuid,
c.name AS contract_name,
cc.useruuid,
u.firstname,
u.lastname,
cc.rate,
rs.avg_rate,
ROUND((cc.rate - rs.avg_rate) / NULLIF(rs.stddev_rate, 0), 2) AS std_deviations,
CASE
WHEN cc.rate < 500 THEN 'Very Low'
WHEN cc.rate > 2500 THEN 'Very High'
WHEN ABS((cc.rate - rs.avg_rate) / NULLIF(rs.stddev_rate, 0)) > 2 THEN 'Outlier'
ELSE 'Normal'
END AS rate_category,
cc.activefrom,
cc.activeto,
c.status
FROM contract_consultants cc
CROSS JOIN rate_stats rs
JOIN contracts c ON c.uuid = cc.contractuuid
LEFT JOIN user u ON u.uuid = cc.useruuid
WHERE
cc.rate > 0
AND (
cc.rate < 500
OR cc.rate > 2500
OR ABS((cc.rate - rs.avg_rate) / NULLIF(rs.stddev_rate, 0)) > 2
)
ORDER BY ABS((cc.rate - rs.avg_rate) / NULLIF(rs.stddev_rate, 0)) DESC;

8. Comprehensive Data Quality Report

-- Single query to get overview of all issues
SELECT
'Overlapping Consultants' AS issue_type,
COUNT(DISTINCT cc1.uuid) AS issue_count,
'Critical' AS severity
FROM contract_consultants cc1
JOIN contract_consultants cc2 ON cc1.useruuid = cc2.useruuid AND cc1.uuid < cc2.uuid
JOIN contract_project cp1 ON cc1.contractuuid = cp1.contractuuid
JOIN contract_project cp2 ON cc2.contractuuid = cp2.contractuuid AND cp1.projectuuid = cp2.projectuuid
JOIN contracts c1 ON c1.uuid = cc1.contractuuid
JOIN contracts c2 ON c2.uuid = cc2.contractuuid
WHERE cc1.activeto >= cc2.activefrom
AND cc1.activefrom <= cc2.activeto
AND c1.status NOT IN ('INACTIVE', 'CLOSED')
AND c2.status NOT IN ('INACTIVE', 'CLOSED')

UNION ALL

SELECT
'Invalid Date Ranges' AS issue_type,
COUNT(*) AS issue_count,
'Critical' AS severity
FROM contract_consultants
WHERE activefrom > activeto

UNION ALL

SELECT
'Invalid Rates' AS issue_type,
COUNT(*) AS issue_count,
'Critical' AS severity
FROM contract_consultants
WHERE rate <= 0

UNION ALL

SELECT
'Duplicate Project Links' AS issue_type,
COUNT(*) AS issue_count,
'Medium' AS severity
FROM (
SELECT contractuuid, projectuuid
FROM contract_project
GROUP BY contractuuid, projectuuid
HAVING COUNT(*) > 1
) AS duplicates

UNION ALL

SELECT
'Active Contracts Without Consultants' AS issue_type,
COUNT(*) AS issue_count,
'Medium' AS severity
FROM contracts c
WHERE c.status IN ('ACTIVE', 'SIGNED', 'TIME')
AND NOT EXISTS (SELECT 1 FROM contract_consultants WHERE contractuuid = c.uuid)

UNION ALL

SELECT
'Active Contracts Without Projects' AS issue_type,
COUNT(*) AS issue_count,
'Medium' AS severity
FROM contracts c
WHERE c.status IN ('ACTIVE', 'SIGNED', 'TIME')
AND NOT EXISTS (SELECT 1 FROM contract_project WHERE contractuuid = c.uuid)

ORDER BY
FIELD(severity, 'Critical', 'Medium', 'Low'),
issue_count DESC;

  ---
Running the Queries

To Fix Issues Found:

1. Export Results: Run queries and export to CSV for review
2. Categorize: Separate critical vs warning issues
3. Plan Resolution: Decide which overlaps are legitimate vs errors
4. Fix Data: Update or delete problematic records
5. Re-validate: Run queries again to confirm fixes
6. Enable Constraints: Once clean, the validation system will prevent future issues

Example Fix Query:

-- Example: Fix a specific overlap by adjusting dates
UPDATE contract_consultants
SET activeto = '2024-12-31'
WHERE uuid = 'problematic-consultant-uuid'
AND activeto > '2024-12-31';

Use these queries regularly as part of data quality monitoring!
