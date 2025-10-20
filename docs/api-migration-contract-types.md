# REST API Migration Guide: Contract Type System Upgrade

## Overview

The contract type system has been upgraded to support both **legacy enum-based contract types** and **dynamically defined contract types** managed via REST API. This allows administrators to create new contract types without code changes or deployments.

**Effective Date**: Version 2.0.0 (TBD)

**Breaking Change**: The `contractType` field in Contract and Invoice entities now accepts String values instead of enum values.

## What's Changed

### Contract Entity (`/contracts`)

**Before:**
```json
{
  "uuid": "abc-123",
  "contractType": "SKI0217_2025",
  "amount": 100000,
  "status": "SIGNED"
}
```

**After:**
```json
{
  "uuid": "abc-123",
  "contractType": "SKI0217_2025",
  "amount": 100000,
  "status": "SIGNED"
}
```

**Note**: The JSON structure remains the same! The `contractType` field is still a string in JSON. However, the backend now validates it against both legacy enum values AND database-defined types.

### Invoice Entity

Same as Contract - the `contractType` field remains a string in JSON but now supports dynamic types.

## Backward Compatibility

✅ **All existing REST clients will continue to work without changes!**

The following legacy contract types are still supported:
- `PERIOD`
- `SKI0217_2021`
- `SKI0217_2025`
- `SKI0215_2025`
- `SKI0217_2025_V2`

These values work exactly as before.

## New Capabilities

### Creating Dynamic Contract Types

Administrators can now create new contract types via the API:

```bash
POST /api/contract-types
Authorization: Bearer <JWT_TOKEN>
Content-Type: application/json

{
  "code": "SKI0217_2026",
  "name": "SKI Framework Agreement 2026",
  "description": "Updated framework with 5% admin fee",
  "active": true
}
```

### Using Dynamic Contract Types in Contracts

Once created, dynamic contract types can be used immediately in contracts:

```bash
POST /contracts
Authorization: Bearer <JWT_TOKEN>
Content-Type: application/json

{
  "contractType": "SKI0217_2026",
  "amount": 150000,
  "status": "DRAFT",
  ...
}
```

### Time-Limited Contract Types (Optional Date Ranges)

Contract types can have optional validity periods to support versioning and phasing out old types:

```bash
POST /api/contract-types
Authorization: Bearer <JWT_TOKEN>
Content-Type: application/json

{
  "code": "SKI0217_2025",
  "name": "SKI Framework Agreement 2025",
  "description": "2025 framework agreement",
  "validFrom": "2025-01-01",
  "validUntil": "2026-01-01",
  "active": true
}
```

**Date Semantics:**
- `validFrom`: **Inclusive** - the contract type becomes valid starting from this date
- `validUntil`: **Exclusive** - the contract type stops being valid before this date
- Both fields are **optional** - null means no date restriction on that end

**Example Timeline:**
```
2024-12-31: Contract type not yet valid (validFrom is 2025-01-01)
2025-01-01: Contract type becomes valid ✓
2025-06-15: Contract type is valid ✓
2025-12-31: Contract type is valid ✓
2026-01-01: Contract type no longer valid (validUntil is 2026-01-01)
```

**Validation Behavior:**
- When creating a contract, the contract type must be valid on the **contract creation date**
- Existing contracts are **grandfathered in** - they continue to work even if their contract type later becomes invalid
- Only new contracts are subject to date-based validation
- Legacy contract types (PERIOD, SKI0217_2021, etc.) have no date restrictions

## Validation Rules

### Valid Contract Type Codes

Contract type codes must:
- **Contain only uppercase letters, numbers, and underscores** (`^[A-Z0-9_]+$`)
- **Be 3-50 characters long**
- **Exist as either**:
  - A legacy enum value (`PERIOD`, `SKI0217_2021`, etc.), OR
  - An active entry in the `contract_type_definitions` table

### Examples

✅ Valid:
- `SKI0217_2025` (legacy enum)
- `SKI0217_2026` (if defined in database)
- `CUSTOM_TYPE_2026`
- `FRAMEWORK_2026`

❌ Invalid:
- `ski-2025` (lowercase, contains hyphen)
- `SKI 2025` (contains space)
- `A` (too short)
- `UNDEFINED_TYPE` (not in enum or database)

## Error Handling

### Invalid Contract Type

**Request:**
```json
POST /contracts
{
  "contractType": "INVALID_TYPE",
  ...
}
```

**Response:**
```
HTTP/1.1 400 Bad Request
Content-Type: application/json

{
  "error": "Invalid contract type 'INVALID_TYPE'. Must be either a valid legacy type (PERIOD, SKI0217_2021, SKI0217_2025, SKI0215_2025, SKI0217_2025_V2) or an active contract type defined via the contract types API."
}
```

## Migration Checklist for REST Clients

### No Changes Required ✅

If your client:
- Only uses legacy enum contract types (`PERIOD`, `SKI0217_2021`, etc.)
- Passes `contractType` as a string in JSON

**You don't need to change anything!** Your client will continue working.

### Optional Enhancements 🔧

If you want to support dynamic contract types:

1. **Add Contract Type Discovery** - Fetch available contract types:
   ```javascript
   // Get all active contract types
   GET /api/contract-types

   // Response:
   [
     {
       "code": "PERIOD",
       "name": "Standard Time & Materials",
       "active": true
     },
     {
       "code": "SKI0217_2026",
       "name": "SKI Framework Agreement 2026",
       "active": true
     }
   ]
   ```

2. **Dynamic Validation** - Validate contract types against the API instead of hardcoded enum values

3. **Admin UI** - Add UI for managing contract types (if your client has admin functionality)

## API Reference

### Contract Type Management Endpoints

All endpoints require `SYSTEM` role (admin access).

#### List All Contract Types
```
GET /api/contract-types?includeInactive=false
```

#### Get Contract Type by Code
```
GET /api/contract-types/{code}
```

#### Create Contract Type
```
POST /api/contract-types
Content-Type: application/json

{
  "code": "SKI0217_2026",
  "name": "SKI Framework Agreement 2026",
  "description": "Updated framework with 5% admin fee",
  "validFrom": "2026-01-01",
  "validUntil": "2027-01-01",
  "active": true
}
```

**Optional Fields:**
- `validFrom` (date, optional): When the contract type becomes valid (inclusive)
- `validUntil` (date, optional): When the contract type stops being valid (exclusive)
- Both can be null (no date restriction)
- `validUntil` must be after `validFrom` if both are provided

#### Update Contract Type
```
PUT /api/contract-types/{code}
Content-Type: application/json

{
  "name": "SKI Framework Agreement 2026 - Updated",
  "description": "New description",
  "validFrom": "2026-01-01",
  "validUntil": "2027-01-01",
  "active": true
}
```

#### Delete Contract Type (Soft Delete)
```
DELETE /api/contract-types/{code}
```

**Note**: Deletion fails if the contract type has active pricing rules.

### Pricing Rule Management

Dynamic contract types support custom pricing rules:

```
GET /api/contract-types/{contractTypeCode}/rules
POST /api/contract-types/{contractTypeCode}/rules
PUT /api/contract-types/{contractTypeCode}/rules/{ruleId}
DELETE /api/contract-types/{contractTypeCode}/rules/{ruleId}
```

See [dynamic-contract-types.md](dynamic-contract-types.md) for detailed pricing rule documentation.

## Testing Strategy

### 1. Verify Backward Compatibility

Test that existing contracts with legacy types still work:

```bash
# Should succeed
POST /contracts
{
  "contractType": "PERIOD",
  ...
}

POST /contracts
{
  "contractType": "SKI0217_2025",
  ...
}
```

### 2. Test Dynamic Types

Create a dynamic type and use it:

```bash
# Create type
POST /api/contract-types
{
  "code": "TEST_TYPE_2026",
  "name": "Test Contract Type",
  "active": true
}

# Use in contract
POST /contracts
{
  "contractType": "TEST_TYPE_2026",
  ...
}
```

### 3. Test Validation

Verify invalid types are rejected:

```bash
# Should fail with 400
POST /contracts
{
  "contractType": "INVALID_TYPE",
  ...
}
```

### 4. Test Date-Based Validity

Create a time-limited contract type and verify date validation:

```bash
# Create a contract type valid only in 2026
POST /api/contract-types
{
  "code": "FUTURE_TYPE_2026",
  "name": "Future Contract Type",
  "validFrom": "2026-01-01",
  "validUntil": "2027-01-01",
  "active": true
}

# Create contract TODAY (2025-10-19) - should FAIL (type not valid yet)
POST /contracts
{
  "contractType": "FUTURE_TYPE_2026",
  "amount": 100000,
  "status": "DRAFT",
  ...
}
# Response: 400 Bad Request - Contract type not valid on 2025-10-19

# Update contract type to remove start date
PUT /api/contract-types/FUTURE_TYPE_2026
{
  "name": "Future Contract Type",
  "validFrom": null,
  "validUntil": "2027-01-01",
  "active": true
}

# Create contract NOW - should SUCCEED (no validFrom restriction)
POST /contracts
{
  "contractType": "FUTURE_TYPE_2026",
  "amount": 100000,
  "status": "DRAFT",
  ...
}
# Response: 201 Created - Contract created successfully

# Set validUntil to the past - existing contract still works (grandfathered)
PUT /api/contract-types/FUTURE_TYPE_2026
{
  "name": "Future Contract Type",
  "validFrom": null,
  "validUntil": "2024-01-01",
  "active": true
}

# Try to create NEW contract with expired type - should FAIL
POST /contracts
{
  "contractType": "FUTURE_TYPE_2026",
  "amount": 100000,
  "status": "DRAFT",
  ...
}
# Response: 400 Bad Request - Contract type not valid on 2025-10-19
```

## Common Issues & Solutions

### Issue: "Invalid contract type" error for existing types

**Cause**: The contract type is not in the legacy enum OR the database.

**Solution**:
1. Check if the type is a typo (case-sensitive!)
2. For new types, create them via `/api/contract-types` first
3. For legacy types, verify they're in the enum: `PERIOD`, `SKI0217_2021`, `SKI0217_2025`, `SKI0215_2025`, `SKI0217_2025_V2`

### Issue: Pricing rules not applying for new contract types

**Cause**: Pricing rules must be defined for new contract types.

**Solution**: Create pricing rules via `/api/contract-types/{code}/rules`. See [dynamic-contract-types.md](dynamic-contract-types.md) for examples.

### Issue: Cannot delete contract type

**Cause**: Contract type has active pricing rules.

**Solution**: Delete or deactivate all pricing rules first, then delete the contract type.

### Issue: "Contract type not valid on [date]" error when creating contract

**Cause**: The contract type has a validity period that does not include the contract creation date.

**Solution**:
1. Check the contract type's `validFrom` and `validUntil` dates
2. Either:
   - Wait until the type's `validFrom` date to create the contract, OR
   - Update the contract type to remove/extend the date restrictions, OR
   - Use a different contract type that is valid on the current date

### Issue: validUntil must be after validFrom error

**Cause**: When creating or updating a contract type, `validUntil` date is not after `validFrom` date.

**Solution**: Ensure that `validUntil` is a date that comes after `validFrom`:
```bash
# ❌ Invalid - validUntil is before validFrom
{
  "validFrom": "2026-01-01",
  "validUntil": "2025-01-01"
}

# ✓ Valid
{
  "validFrom": "2025-01-01",
  "validUntil": "2026-01-01"
}

# ✓ Valid - Only validUntil specified
{
  "validFrom": null,
  "validUntil": "2025-12-31"
}
```

## Timeline

| Date | Milestone |
|------|-----------|
| TBD | Version 2.0.0 released with dynamic contract type support |
| TBD + 30 days | Deprecation notice for hardcoded contract types |
| TBD + 90 days | Begin migrating legacy enum types to database |
| TBD + 180 days | Full migration complete (enum kept for backward compatibility) |

## Support & Feedback

For questions or issues:
- **Documentation**: See `docs/dynamic-contract-types.md` for detailed technical documentation
- **GitHub Issues**: Report bugs at https://github.com/trustworks/intranetservices/issues
- **Slack**: #api-support channel

## Contract Type Rules: Three Separate Systems

The contract type system now supports **three independent rule categories**, each managing a different aspect of contract behavior:

### 1. **Pricing Rules** - Invoice Calculation
Controls how invoices are calculated (discounts, fees, deductions).
- **Endpoint**: `/api/contract-types/{code}/pricing-rules` (via pricing rule steps)
- **Use Case**: "Apply 4% admin fee then 2% discount"
- **Execution**: During invoice generation
- **Documentation**: See existing pricing rule documentation

### 2. **Validation Rules** - Business Constraints ✨ NEW
Enforces business requirements during work registration and operations.
- **Endpoint**: `/api/contract-types/{code}/validation-rules`
- **Use Cases**:
  - Require notes on time registration
  - Set minimum hours per entry
  - Set maximum hours per day
  - Require task selection
- **Execution**: During work entry validation

### 3. **Rate Adjustment Rules** - Time-Based Rate Modifications ✨ NEW
Handles automatic rate increases over time (annual increases, inflation adjustments).
- **Endpoint**: `/api/contract-types/{code}/rate-adjustments`
- **Use Cases**:
  - 3% annual rate increase
  - Quarterly inflation-linked adjustments
  - One-time rate adjustments
- **Execution**: During rate calculation for invoices/contracts

---

## Validation Rules (NEW)

Validation rules enforce business constraints like requiring notes on time entries or setting hour thresholds.

### Create Validation Rule - Notes Required

```bash
POST /api/contract-types/SKI0217_2025/validation-rules
Authorization: Bearer <JWT_TOKEN>
Content-Type: application/json

{
  "ruleId": "ski-notes-required",
  "label": "Notes required for time registration",
  "validationType": "NOTES_REQUIRED",
  "required": true,
  "priority": 10
}
```

### Create Validation Rule - Minimum Hours

```bash
POST /api/contract-types/CONSULTING_2025/validation-rules
{
  "ruleId": "min-hours-threshold",
  "label": "Minimum 0.25 hours per entry",
  "validationType": "MIN_HOURS_PER_ENTRY",
  "thresholdValue": 0.25,
  "priority": 20
}
```

### Validation Types

- `NOTES_REQUIRED` - Time registration must include comments (`required`: boolean)
- `MIN_HOURS_PER_ENTRY` - Minimum work duration (`thresholdValue`: decimal)
- `MAX_HOURS_PER_DAY` - Maximum daily hours (`thresholdValue`: decimal)
- `REQUIRE_TASK_SELECTION` - Task must be selected (`required`: boolean)

### List Validation Rules

```bash
GET /api/contract-types/SKI0217_2025/validation-rules

Response:
[
  {
    "id": 1,
    "ruleId": "ski-notes-required",
    "label": "Notes required for time registration",
    "validationType": "NOTES_REQUIRED",
    "required": true,
    "thresholdValue": null,
    "priority": 10,
    "active": true
  }
]
```

---

## Rate Adjustment Rules (NEW)

Rate adjustment rules handle time-based rate modifications like annual increases.

### Create Annual Rate Increase

```bash
POST /api/contract-types/CONSULTING_2025/rate-adjustments
Authorization: Bearer <JWT_TOKEN>
Content-Type: application/json

{
  "ruleId": "annual-increase-2025",
  "label": "3% annual rate increase",
  "adjustmentType": "ANNUAL_INCREASE",
  "adjustmentPercent": 3.0,
  "frequency": "YEARLY",
  "effectiveDate": "2025-01-01",
  "priority": 10
}
```

### Calculate Adjusted Rate

```bash
GET /api/contract-types/CONSULTING_2025/rate-adjustments/calculate?baseRate=1000&date=2026-01-15

Response:
{
  "baseRate": 1000.00,
  "adjustedRate": 1030.00,
  "effectiveDate": "2026-01-15"
}
```

### Adjustment Types

- `ANNUAL_INCREASE` - Yearly percentage increase
- `INFLATION_LINKED` - Linked to external inflation index
- `STEP_BASED` - Tiered increases based on milestones
- `FIXED_ADJUSTMENT` - One-time rate adjustment

### Adjustment Frequencies

- `YEARLY` - Applied annually on anniversary
- `QUARTERLY` - Every 3 months
- `MONTHLY` - Every month
- `ONE_TIME` - Single application, does not repeat

---

## Get All Rules for a Contract Type

New composite endpoint returns all three rule categories in one call:

```bash
GET /api/contract-types/SKI0217_2025/all-rules

Response:
{
  "contractType": {
    "code": "SKI0217_2025",
    "name": "SKI Framework Agreement 2025",
    ...
  },
  "pricingRules": [
    { "ruleId": "ski-admin", "ruleStepType": "ADMIN_FEE_PERCENT", ... }
  ],
  "validationRules": [
    { "ruleId": "ski-notes-required", "validationType": "NOTES_REQUIRED", ... }
  ],
  "rateAdjustments": [
    { "ruleId": "annual-increase", "adjustmentType": "ANNUAL_INCREASE", ... }
  ]
}
```

---

## Rule Types Comparison

| Feature | Pricing Rules | Validation Rules | Rate Adjustments |
|---------|--------------|------------------|------------------|
| **Purpose** | Calculate invoice amounts | Enforce business constraints | Modify rates over time |
| **Execution** | Invoice generation | Work entry validation | Rate calculation |
| **Examples** | Admin fees, discounts | Required notes, hour limits | Annual increases |
| **Endpoint** | `/pricing-rules` | `/validation-rules` | `/rate-adjustments` |
| **Key Fields** | `percent`, `amount`, `stepBase` | `required`, `thresholdValue` | `adjustmentPercent`, `frequency` |

---

## Summary

✅ **No breaking changes for existing REST clients using legacy contract types**
✅ **New capability to create contract types dynamically via API**
✅ **Optional date-based validity periods for versioning and phasing out types**
✅ **Three independent rule systems for pricing, validation, and rate adjustments**
✅ **Grandfathering of existing contracts ensures no disruption**
✅ **Backward compatible with all existing integrations**
✅ **Comprehensive validation ensures data integrity**

The system gracefully supports both old and new approaches, allowing you to migrate at your own pace. Date-based validity periods enable sophisticated contract type management including versioning strategies and controlled rollouts of new agreement terms. The three rule systems provide clean separation of concerns for different aspects of contract management.
