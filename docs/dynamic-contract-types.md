# Dynamic Contract Types and Pricing Rules

This document describes the REST API system for creating and managing contract types and their pricing rules dynamically, without requiring code changes or deployments.

## Overview

The system allows you to:
- Create new contract types via REST API
- Define pricing rules for each contract type
- Update and manage rules without code changes
- Maintain backward compatibility with existing enum-based contract types

## Architecture

### Components

1. **Database Tables**
   - `contract_type_definitions` - Stores contract type metadata
   - `pricing_rule_steps` - Stores pricing rules for each contract type

2. **JPA Entities**
   - `ContractTypeDefinition` - Entity for contract types
   - `PricingRuleStepEntity` - Entity for pricing rules

3. **Services**
   - `ContractTypeDefinitionService` - CRUD operations for contract types
   - `PricingRuleStepService` - CRUD operations with priority management

4. **REST APIs**
   - `ContractTypeResource` - HTTP endpoints for contract types
   - `PricingRuleResource` - HTTP endpoints for pricing rules

5. **Enhanced PricingRuleCatalog**
   - Supports dual loading (database + hardcoded)
   - Caching for performance
   - Transparent backward compatibility

### Loading Strategy

The `PricingRuleCatalog` uses a dual loading strategy:

1. **Try database first**: Load rules from `pricing_rule_steps` table by contract type code
2. **Fallback to hardcoded**: If no database rules exist, use hardcoded enum-based rules
3. **Caching**: Database rules are cached for performance

This ensures:
- Existing enum-based contracts continue working
- New dynamic contract types can be created via API
- No disruption to existing functionality

## REST API Reference

### Contract Type Endpoints

All endpoints require `SYSTEM` role (admin access).

#### List All Contract Types

```http
GET /api/contract-types?includeInactive=false
```

**Response:**
```json
[
  {
    "id": 1,
    "code": "SKI0217_2026",
    "name": "SKI Framework Agreement 2026",
    "description": "Updated framework with 5% admin fee",
    "active": true,
    "createdAt": "2025-01-15T10:00:00",
    "updatedAt": "2025-01-15T10:00:00"
  }
]
```

#### Get Contract Type by Code

```http
GET /api/contract-types/{code}
```

**Example:**
```bash
curl -X GET http://localhost:9093/api/contract-types/SKI0217_2026 \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"
```

#### Get Contract Type with Rules

```http
GET /api/contract-types/{code}/with-rules
```

Returns the contract type and all its pricing rules in one response.

#### Create Contract Type

```http
POST /api/contract-types
Content-Type: application/json
```

**Request Body:**
```json
{
  "code": "SKI0217_2026",
  "name": "SKI Framework Agreement 2026",
  "description": "Updated framework with 5% admin fee",
  "active": true
}
```

**Validation Rules:**
- `code`: Required, 3-50 characters, uppercase alphanumeric + underscores, unique
- `name`: Required, max 255 characters
- `description`: Optional
- `active`: Optional, defaults to true

#### Update Contract Type

```http
PUT /api/contract-types/{code}
Content-Type: application/json
```

**Request Body:**
```json
{
  "name": "SKI Framework Agreement 2026 - Updated",
  "description": "New description",
  "active": true
}
```

**Note:** The `code` field cannot be changed after creation.

#### Delete Contract Type

```http
DELETE /api/contract-types/{code}
```

Performs a soft delete (sets `active=false`). The record is preserved for historical data.

**Error:** Returns 400 if the contract type has active pricing rules. Delete or deactivate rules first.

#### Activate Contract Type

```http
POST /api/contract-types/{code}/activate
```

Reactivates a soft-deleted contract type.

### Pricing Rule Endpoints

All endpoints require `SYSTEM` role (admin access).

#### List All Rules for Contract Type

```http
GET /api/contract-types/{contractTypeCode}/rules?includeInactive=false
```

**Response:**
```json
[
  {
    "id": 1,
    "contractTypeCode": "SKI0217_2026",
    "ruleId": "ski21726-admin",
    "label": "5% SKI administrationsgebyr",
    "ruleStepType": "ADMIN_FEE_PERCENT",
    "stepBase": "CURRENT_SUM",
    "percent": 5.0,
    "amount": null,
    "paramKey": null,
    "validFrom": null,
    "validTo": null,
    "priority": 20,
    "active": true,
    "createdAt": "2025-01-15T10:00:00",
    "updatedAt": "2025-01-15T10:00:00"
  }
]
```

#### Get Rule by ID

```http
GET /api/contract-types/{contractTypeCode}/rules/{ruleId}
```

#### Create Pricing Rule

```http
POST /api/contract-types/{contractTypeCode}/rules
Content-Type: application/json
```

**Request Body:**
```json
{
  "ruleId": "ski21726-admin",
  "label": "5% SKI administrationsgebyr",
  "ruleStepType": "ADMIN_FEE_PERCENT",
  "stepBase": "CURRENT_SUM",
  "percent": 5.0,
  "amount": null,
  "paramKey": null,
  "validFrom": null,
  "validTo": null,
  "priority": 20
}
```

**Validation Rules:**
- `ruleId`: Required, lowercase alphanumeric + hyphens, unique per contract type
- `label`: Required, max 255 characters
- `ruleStepType`: Required, one of: `PERCENT_DISCOUNT_ON_SUM`, `ADMIN_FEE_PERCENT`, `FIXED_DEDUCTION`, `GENERAL_DISCOUNT_PERCENT`, `ROUNDING`
- `stepBase`: Required, one of: `SUM_BEFORE_DISCOUNTS`, `CURRENT_SUM`
- `percent`: Optional, 0-100 for percentage rules
- `amount`: Optional, for fixed amount rules
- `paramKey`: Optional, references `contract_type_items.key`
- `validFrom`/`validTo`: Optional dates for time-based activation
- `priority`: Optional (auto-incremented if not provided), must be positive

**Auto-Priority:** If `priority` is not provided, it will be auto-incremented from the highest existing priority by +10.

#### Create Rules in Bulk

```http
POST /api/contract-types/{contractTypeCode}/rules/bulk
Content-Type: application/json
```

**Request Body:**
```json
{
  "rules": [
    {
      "ruleId": "ski21726-key",
      "label": "SKI trapperabat",
      "ruleStepType": "PERCENT_DISCOUNT_ON_SUM",
      "stepBase": "SUM_BEFORE_DISCOUNTS",
      "paramKey": "trapperabat",
      "priority": 10
    },
    {
      "ruleId": "ski21726-admin",
      "label": "5% SKI administrationsgebyr",
      "ruleStepType": "ADMIN_FEE_PERCENT",
      "stepBase": "CURRENT_SUM",
      "percent": 5.0,
      "priority": 20
    },
    {
      "ruleId": "ski21726-general",
      "label": "Generel rabat",
      "ruleStepType": "GENERAL_DISCOUNT_PERCENT",
      "stepBase": "CURRENT_SUM",
      "priority": 40
    }
  ]
}
```

Useful for setting up a complete contract type with all its rules in one operation.

#### Update Pricing Rule

```http
PUT /api/contract-types/{contractTypeCode}/rules/{ruleId}
Content-Type: application/json
```

**Request Body:** Same as create, but all fields can be updated.

**Note:** `ruleId` and `contractTypeCode` cannot be changed.

#### Delete Pricing Rule

```http
DELETE /api/contract-types/{contractTypeCode}/rules/{ruleId}
```

Performs a soft delete (sets `active=false`).

## Rule Types

### PERCENT_DISCOUNT_ON_SUM

Volume-based or step discount. Can use a fixed percentage or reference a dynamic value from `contract_type_items`.

**Example - Fixed Percent:**
```json
{
  "ruleStepType": "PERCENT_DISCOUNT_ON_SUM",
  "stepBase": "SUM_BEFORE_DISCOUNTS",
  "percent": 10.0,
  "priority": 10
}
```

**Example - Dynamic Percent (trapperabat):**
```json
{
  "ruleStepType": "PERCENT_DISCOUNT_ON_SUM",
  "stepBase": "SUM_BEFORE_DISCOUNTS",
  "paramKey": "trapperabat",
  "priority": 10
}
```

The `paramKey` references a value stored in `contract_type_items` table, allowing per-contract customization.

### ADMIN_FEE_PERCENT

Administrative fee calculated as a percentage. Reduces the total.

**Example:**
```json
{
  "ruleStepType": "ADMIN_FEE_PERCENT",
  "stepBase": "CURRENT_SUM",
  "percent": 5.0,
  "priority": 20
}
```

### FIXED_DEDUCTION

Fixed amount deduction (e.g., invoice fee).

**Example:**
```json
{
  "ruleStepType": "FIXED_DEDUCTION",
  "stepBase": "CURRENT_SUM",
  "amount": 2000.00,
  "priority": 30
}
```

### GENERAL_DISCOUNT_PERCENT

General discount percentage (pulled from `invoice.discount` field).

**Example:**
```json
{
  "ruleStepType": "GENERAL_DISCOUNT_PERCENT",
  "stepBase": "CURRENT_SUM",
  "priority": 40
}
```

**Note:** This rule type is automatically added as a fallback if not explicitly defined.

### ROUNDING

Rounding adjustment (rarely used).

## Step Base Types

### SUM_BEFORE_DISCOUNTS

Calculate based on the original invoice item sum, before any rules are applied.

**Use case:** Volume discounts that should always be based on the original amount.

### CURRENT_SUM

Calculate based on the running total after previous rules have been applied.

**Use case:** Admin fees calculated on already-discounted prices.

## Priority and Execution Order

Rules execute in **priority order** (lower numbers first):

```
Priority 10: Step discount (trapperabat)
Priority 20: Admin fee (2% or 4%)
Priority 30: Fixed invoice fee (if applicable)
Priority 40: General discount
```

**Best Practices:**
- Use increments of 10 (10, 20, 30...) to allow inserting rules between existing ones
- Lower priority = executes earlier
- General discount should typically have the highest priority number (executes last)

## Date-Based Rule Activation

Rules can have `validFrom` and `validTo` dates for time-based activation:

```json
{
  "ruleId": "ski21726-admin-2025",
  "label": "4% SKI administrationsgebyr",
  "percent": 4.0,
  "validFrom": "2025-01-01",
  "validTo": "2026-01-01",
  "priority": 20
}
```

**Behavior:**
- `validFrom` is inclusive
- `validTo` is exclusive
- `null` means unlimited (always active)

**Use case:** Transition from 2% to 4% admin fee on a specific date:
- Create rule with `validTo: "2025-01-01"` for 2%
- Create rule with `validFrom: "2025-01-01"` for 4%

## Complete Example: Creating SKI0217_2026

### Step 1: Create Contract Type

```bash
curl -X POST http://localhost:9093/api/contract-types \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "code": "SKI0217_2026",
    "name": "SKI Framework Agreement 2026",
    "description": "Updated framework with 5% admin fee"
  }'
```

### Step 2: Create Pricing Rules (Bulk)

```bash
curl -X POST http://localhost:9093/api/contract-types/SKI0217_2026/rules/bulk \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "rules": [
      {
        "ruleId": "ski21726-key",
        "label": "SKI trapperabat",
        "ruleStepType": "PERCENT_DISCOUNT_ON_SUM",
        "stepBase": "SUM_BEFORE_DISCOUNTS",
        "paramKey": "trapperabat",
        "priority": 10
      },
      {
        "ruleId": "ski21726-admin",
        "label": "5% SKI administrationsgebyr",
        "ruleStepType": "ADMIN_FEE_PERCENT",
        "stepBase": "CURRENT_SUM",
        "percent": 5.0,
        "priority": 20
      },
      {
        "ruleId": "ski21726-general",
        "label": "Generel rabat",
        "ruleStepType": "GENERAL_DISCOUNT_PERCENT",
        "stepBase": "CURRENT_SUM",
        "priority": 40
      }
    ]
  }'
```

### Step 3: Verify

```bash
curl -X GET http://localhost:9093/api/contract-types/SKI0217_2026/with-rules \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"
```

### Step 4: Use in Contracts

The new contract type `SKI0217_2026` is now available for use in contracts. The pricing engine will automatically load and apply these rules when pricing invoices.

**Note:** The contract type must be added to the `ContractType` enum for UI integration, but the pricing rules will work immediately via the database.

## Caching

Pricing rules loaded from the database are cached for performance:

- **Cache name:** `pricing-rules`
- **TTL:** 1 hour (configurable)
- **Invalidation:** Automatic on create/update/delete operations
- **Key:** Contract type code + invoice date

Cache is managed by Quarkus Cache with Caffeine.

## Backward Compatibility

The system maintains 100% backward compatibility:

1. **Existing enum-based contracts** continue working with hardcoded rules
2. **New database-based contracts** use dynamic rules
3. **PricingRuleCatalog** transparently supports both
4. **No migration required** - both systems can coexist

## Troubleshooting

### Rule not applying

**Check:**
1. Rule is `active=true`
2. Rule's `validFrom`/`validTo` dates include the invoice date
3. Contract type code matches exactly (case-sensitive)
4. Cache is not stale (wait 1 hour or restart application)

### Cache issues

**Clear cache:**
- Restart the application
- Or wait for cache TTL (1 hour)
- Or update/delete/create a rule (triggers cache invalidation)

### Priority conflicts

Rules with the same priority may execute in undefined order. Ensure all rules have unique priorities.

## Database Schema

### contract_type_definitions

| Column | Type | Description |
|--------|------|-------------|
| id | INT | Primary key |
| code | VARCHAR(50) | Unique contract type code |
| name | VARCHAR(255) | Display name |
| description | TEXT | Detailed description |
| active | BOOLEAN | Soft delete flag |
| created_at | TIMESTAMP | Creation timestamp |
| updated_at | TIMESTAMP | Last update timestamp |

### pricing_rule_steps

| Column | Type | Description |
|--------|------|-------------|
| id | INT | Primary key |
| contract_type_code | VARCHAR(50) | FK to contract_type_definitions.code |
| rule_id | VARCHAR(64) | Stable identifier |
| label | VARCHAR(255) | Display label |
| rule_step_type | VARCHAR(50) | Rule type enum |
| step_base | VARCHAR(50) | Calculation base enum |
| percent | DECIMAL(10,4) | Percentage value |
| amount | DECIMAL(15,2) | Fixed amount |
| param_key | VARCHAR(64) | Reference to contract_type_items |
| valid_from | DATE | Activation date |
| valid_to | DATE | Expiration date |
| priority | INT | Execution order |
| active | BOOLEAN | Soft delete flag |
| created_at | TIMESTAMP | Creation timestamp |
| updated_at | TIMESTAMP | Last update timestamp |

## Security

All endpoints require:
- **Authentication:** Valid JWT token
- **Authorization:** `SYSTEM` role (admin access)

This ensures only authorized administrators can create and modify contract types and pricing rules.

## Performance Considerations

1. **Caching:** Database rules are cached for 1 hour
2. **Index Usage:** Queries use indexes on `code`, `active`, and `priority`
3. **Bulk Operations:** Use bulk endpoints to create multiple rules efficiently
4. **Read-Heavy:** System is optimized for read operations (pricing calculations)

## Future Enhancements

Potential future improvements:

1. **UI Integration:** Admin panel for managing contract types and rules
2. **Rule Validation:** Pre-flight validation before saving rules
3. **Rule Templates:** Predefined rule sets for common contract types
4. **Audit Logging:** Track all changes to contract types and rules
5. **Rule Simulation:** Test pricing calculations before activating rules
6. **Full Migration:** Convert all enum-based contracts to database-based

## Related Documentation

- [Pricing Engine Implementation](invoices/implementation/pricing-engine.md)
- [Draft Invoice Creation](draft-invoice-creation.md)
- [Contract Management](../CLAUDE.md#contracts)
