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
  "active": true
}
```

#### Update Contract Type
```
PUT /api/contract-types/{code}
Content-Type: application/json

{
  "name": "SKI Framework Agreement 2026 - Updated",
  "description": "New description",
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

## Summary

✅ **No breaking changes for existing REST clients using legacy contract types**
✅ **New capability to create contract types dynamically via API**
✅ **Backward compatible with all existing integrations**
✅ **Comprehensive validation ensures data integrity**

The system gracefully supports both old and new approaches, allowing you to migrate at your own pace.
