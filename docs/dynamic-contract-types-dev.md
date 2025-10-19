# Dynamic Contract Types API - Developer Guide

**Version:** 1.0
**Base URL:** `http://localhost:9093` (development) / `https://api.trustworks.dk` (production)
**Authentication:** JWT Bearer Token
**Required Role:** `SYSTEM` (admin only)

---

## Table of Contents

1. [Overview](#overview)
2. [Authentication](#authentication)
3. [Common Workflows](#common-workflows)
4. [API Endpoints Reference](#api-endpoints-reference)
5. [Data Models](#data-models)
6. [Error Handling](#error-handling)
7. [Code Examples](#code-examples)
8. [Best Practices](#best-practices)

---

## Overview

The Dynamic Contract Types API allows you to programmatically manage contract types and their pricing rules without requiring backend code changes or deployments. This enables rapid response to new contract requirements and pricing changes.

### Key Concepts

**Contract Type**
- Represents a category of contract (e.g., "SKI0217_2026")
- Has a unique code, name, and description
- Can be activated/deactivated (soft delete)

**Pricing Rule**
- Defines how invoice pricing is calculated for a contract type
- Executes in priority order (10, 20, 30...)
- Can be percentage-based, fixed amount, or dynamic
- Supports date-based activation (validFrom/validTo)

**Rule Types**
- `PERCENT_DISCOUNT_ON_SUM` - Volume/step discount
- `ADMIN_FEE_PERCENT` - Administrative fee
- `FIXED_DEDUCTION` - Fixed amount (e.g., invoice fee)
- `GENERAL_DISCOUNT_PERCENT` - General discount from invoice
- `ROUNDING` - Rounding adjustment

**Step Base**
- `SUM_BEFORE_DISCOUNTS` - Calculate on original amount
- `CURRENT_SUM` - Calculate on running total after previous rules

---

## Authentication

All API endpoints require JWT authentication with `SYSTEM` role.

### Request Headers

```http
Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...
Content-Type: application/json
Accept: application/json
```

### Example: Getting JWT Token

```javascript
// Authenticate first to get JWT token
const response = await fetch('http://localhost:9093/login', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    username: 'admin',
    password: 'password'
  })
});

const { token } = await response.json();

// Use token in subsequent requests
const headers = {
  'Authorization': `Bearer ${token}`,
  'Content-Type': 'application/json'
};
```

---

## Common Workflows

### Workflow 1: Creating a New Contract Type with Rules

**Use Case:** A new SKI framework agreement (SKI0217_2026) is released with updated pricing rules.

**Steps:**

```
1. Create Contract Type
   POST /api/contract-types
   ↓
2. Add Pricing Rules (Bulk)
   POST /api/contract-types/{code}/rules/bulk
   ↓
3. Verify Configuration
   GET /api/contract-types/{code}/with-rules
   ↓
4. (UI) Display in contract type dropdown
   Contract type is now available for use
```

**Detailed Flow:**

```javascript
// Step 1: Create contract type
const contractType = await fetch('/api/contract-types', {
  method: 'POST',
  headers,
  body: JSON.stringify({
    code: 'SKI0217_2026',
    name: 'SKI Framework Agreement 2026',
    description: 'Updated framework with 5% admin fee',
    active: true
  })
});

// Step 2: Add pricing rules
const rules = await fetch('/api/contract-types/SKI0217_2026/rules/bulk', {
  method: 'POST',
  headers,
  body: JSON.stringify({
    rules: [
      {
        ruleId: 'ski21726-key',
        label: 'SKI trapperabat',
        ruleStepType: 'PERCENT_DISCOUNT_ON_SUM',
        stepBase: 'SUM_BEFORE_DISCOUNTS',
        paramKey: 'trapperabat',
        priority: 10
      },
      {
        ruleId: 'ski21726-admin',
        label: '5% SKI administrationsgebyr',
        ruleStepType: 'ADMIN_FEE_PERCENT',
        stepBase: 'CURRENT_SUM',
        percent: 5.0,
        priority: 20
      },
      {
        ruleId: 'ski21726-general',
        label: 'Generel rabat',
        ruleStepType: 'GENERAL_DISCOUNT_PERCENT',
        stepBase: 'CURRENT_SUM',
        priority: 40
      }
    ]
  })
});

// Step 3: Verify
const verification = await fetch('/api/contract-types/SKI0217_2026/with-rules', {
  headers
});

const result = await verification.json();
console.log(`Created ${result.contractType.name} with ${result.activeRules} rules`);
```

---

### Workflow 2: Updating Admin Fee Percentage

**Use Case:** Admin fee changes from 4% to 5% on January 1, 2026.

**Steps:**

```
1. Find Current Rule
   GET /api/contract-types/{code}/rules
   ↓
2. Update Rule with New Percentage
   PUT /api/contract-types/{code}/rules/{ruleId}
   ↓
3. Verify Change
   GET /api/contract-types/{code}/rules/{ruleId}
   ↓
4. (Optional) Add Date-Based Rule for Transition
   POST /api/contract-types/{code}/rules
```

**Detailed Flow:**

```javascript
// Option A: Simple update (applies immediately)
await fetch('/api/contract-types/SKI0217_2026/rules/ski21726-admin', {
  method: 'PUT',
  headers,
  body: JSON.stringify({
    label: '5% SKI administrationsgebyr',
    ruleStepType: 'ADMIN_FEE_PERCENT',
    stepBase: 'CURRENT_SUM',
    percent: 5.0,
    validFrom: null,
    validTo: null,
    priority: 20,
    active: true
  })
});

// Option B: Date-based transition (better for planned changes)
// Keep old rule but add end date
await fetch('/api/contract-types/SKI0217_2026/rules/ski21726-admin', {
  method: 'PUT',
  headers,
  body: JSON.stringify({
    label: '4% SKI administrationsgebyr',
    ruleStepType: 'ADMIN_FEE_PERCENT',
    stepBase: 'CURRENT_SUM',
    percent: 4.0,
    validFrom: null,
    validTo: '2026-01-01',  // Expires Dec 31, 2025
    priority: 20,
    active: true
  })
});

// Create new rule for 2026
await fetch('/api/contract-types/SKI0217_2026/rules', {
  method: 'POST',
  headers,
  body: JSON.stringify({
    ruleId: 'ski21726-admin-2026',
    label: '5% SKI administrationsgebyr',
    ruleStepType: 'ADMIN_FEE_PERCENT',
    stepBase: 'CURRENT_SUM',
    percent: 5.0,
    validFrom: '2026-01-01',  // Starts Jan 1, 2026
    validTo: null,
    priority: 20
  })
});
```

---

### Workflow 3: Viewing Contract Types and Rules (Admin Panel)

**Use Case:** Display contract types management screen.

**Steps:**

```
1. Load All Contract Types
   GET /api/contract-types?includeInactive=false
   ↓
2. (User clicks on a contract type)
   ↓
3. Load Rules for Selected Type
   GET /api/contract-types/{code}/with-rules
   ↓
4. Display Rules Table
   - Show priority, label, type, percentage/amount
   - Allow inline editing
   - Show active/inactive status
```

**Detailed Flow:**

```javascript
// Component: ContractTypesList.jsx
const ContractTypesList = () => {
  const [contractTypes, setContractTypes] = useState([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    loadContractTypes();
  }, []);

  const loadContractTypes = async () => {
    const response = await fetch('/api/contract-types', { headers });
    const data = await response.json();
    setContractTypes(data);
    setLoading(false);
  };

  return (
    <table>
      <thead>
        <tr>
          <th>Code</th>
          <th>Name</th>
          <th>Active</th>
          <th>Actions</th>
        </tr>
      </thead>
      <tbody>
        {contractTypes.map(ct => (
          <tr key={ct.code}>
            <td>{ct.code}</td>
            <td>{ct.name}</td>
            <td>{ct.active ? '✓' : '✗'}</td>
            <td>
              <button onClick={() => viewRules(ct.code)}>View Rules</button>
              <button onClick={() => editContractType(ct.code)}>Edit</button>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
};

// Component: RulesList.jsx
const RulesList = ({ contractTypeCode }) => {
  const [data, setData] = useState(null);

  useEffect(() => {
    loadRulesWithContractType();
  }, [contractTypeCode]);

  const loadRulesWithContractType = async () => {
    const response = await fetch(
      `/api/contract-types/${contractTypeCode}/with-rules`,
      { headers }
    );
    const result = await response.json();
    setData(result);
  };

  return (
    <div>
      <h2>{data?.contractType.name}</h2>
      <p>{data?.contractType.description}</p>
      <table>
        <thead>
          <tr>
            <th>Priority</th>
            <th>Label</th>
            <th>Type</th>
            <th>Percent</th>
            <th>Amount</th>
            <th>Valid From</th>
            <th>Valid To</th>
            <th>Active</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody>
          {data?.rules.map(rule => (
            <tr key={rule.ruleId}>
              <td>{rule.priority}</td>
              <td>{rule.label}</td>
              <td>{rule.ruleStepType}</td>
              <td>{rule.percent ?? '-'}</td>
              <td>{rule.amount ?? '-'}</td>
              <td>{rule.validFrom ?? 'Always'}</td>
              <td>{rule.validTo ?? 'Never'}</td>
              <td>{rule.active ? '✓' : '✗'}</td>
              <td>
                <button onClick={() => editRule(rule)}>Edit</button>
                <button onClick={() => deleteRule(rule)}>Delete</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
};
```

---

### Workflow 4: Deactivating a Contract Type

**Use Case:** Contract type is no longer used and should be hidden from dropdown.

**Steps:**

```
1. Check if Type Has Active Rules
   GET /api/contract-types/{code}/with-rules
   ↓
2. (If has rules) Deactivate All Rules First
   PUT /api/contract-types/{code}/rules/{ruleId} (set active=false)
   ↓
3. Deactivate Contract Type
   DELETE /api/contract-types/{code}
   ↓
4. Verify (should not appear in active list)
   GET /api/contract-types?includeInactive=false
```

**Detailed Flow:**

```javascript
const deactivateContractType = async (code) => {
  // Step 1: Load rules
  const response = await fetch(`/api/contract-types/${code}/with-rules`, { headers });
  const { rules } = await response.json();

  // Step 2: Deactivate all active rules
  for (const rule of rules.filter(r => r.active)) {
    await fetch(`/api/contract-types/${code}/rules/${rule.ruleId}`, {
      method: 'PUT',
      headers,
      body: JSON.stringify({
        ...rule,
        active: false
      })
    });
  }

  // Step 3: Deactivate contract type
  await fetch(`/api/contract-types/${code}`, {
    method: 'DELETE',
    headers
  });

  // Step 4: Refresh list
  await loadContractTypes();
};
```

---

## API Endpoints Reference

### Contract Types

---

#### **GET** `/api/contract-types`

List all contract types.

**Parameters:**

| Name | Type | Location | Required | Description |
|------|------|----------|----------|-------------|
| includeInactive | boolean | query | No | Include inactive types (default: false) |

**Response:** `200 OK`

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
  },
  {
    "id": 2,
    "code": "PERIOD",
    "name": "Standard Time & Materials",
    "description": "Standard time and materials contract",
    "active": true,
    "createdAt": "2025-01-15T09:00:00",
    "updatedAt": "2025-01-15T09:00:00"
  }
]
```

**cURL Example:**

```bash
curl -X GET "http://localhost:9093/api/contract-types?includeInactive=false" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "Accept: application/json"
```

**JavaScript Example:**

```javascript
const contractTypes = await fetch('/api/contract-types?includeInactive=false', {
  headers: {
    'Authorization': `Bearer ${token}`,
    'Accept': 'application/json'
  }
}).then(res => res.json());
```

---

#### **GET** `/api/contract-types/{code}`

Get a specific contract type by code.

**Parameters:**

| Name | Type | Location | Required | Description |
|------|------|----------|----------|-------------|
| code | string | path | Yes | Contract type code |

**Response:** `200 OK`

```json
{
  "id": 1,
  "code": "SKI0217_2026",
  "name": "SKI Framework Agreement 2026",
  "description": "Updated framework with 5% admin fee",
  "active": true,
  "createdAt": "2025-01-15T10:00:00",
  "updatedAt": "2025-01-15T10:00:00"
}
```

**Error Responses:**

- `404 Not Found` - Contract type not found

```json
{
  "error": "Contract type with code 'SKI0217_2026' not found"
}
```

**cURL Example:**

```bash
curl -X GET "http://localhost:9093/api/contract-types/SKI0217_2026" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "Accept: application/json"
```

---

#### **GET** `/api/contract-types/{code}/with-rules`

Get a contract type with all its pricing rules.

**Parameters:**

| Name | Type | Location | Required | Description |
|------|------|----------|----------|-------------|
| code | string | path | Yes | Contract type code |

**Response:** `200 OK`

```json
{
  "contractType": {
    "id": 1,
    "code": "SKI0217_2026",
    "name": "SKI Framework Agreement 2026",
    "description": "Updated framework with 5% admin fee",
    "active": true,
    "createdAt": "2025-01-15T10:00:00",
    "updatedAt": "2025-01-15T10:00:00"
  },
  "rules": [
    {
      "id": 1,
      "contractTypeCode": "SKI0217_2026",
      "ruleId": "ski21726-key",
      "label": "SKI trapperabat",
      "ruleStepType": "PERCENT_DISCOUNT_ON_SUM",
      "stepBase": "SUM_BEFORE_DISCOUNTS",
      "percent": null,
      "amount": null,
      "paramKey": "trapperabat",
      "validFrom": null,
      "validTo": null,
      "priority": 10,
      "active": true,
      "createdAt": "2025-01-15T10:05:00",
      "updatedAt": "2025-01-15T10:05:00"
    },
    {
      "id": 2,
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
      "createdAt": "2025-01-15T10:05:00",
      "updatedAt": "2025-01-15T10:05:00"
    },
    {
      "id": 3,
      "contractTypeCode": "SKI0217_2026",
      "ruleId": "ski21726-general",
      "label": "Generel rabat",
      "ruleStepType": "GENERAL_DISCOUNT_PERCENT",
      "stepBase": "CURRENT_SUM",
      "percent": null,
      "amount": null,
      "paramKey": null,
      "validFrom": null,
      "validTo": null,
      "priority": 40,
      "active": true,
      "createdAt": "2025-01-15T10:05:00",
      "updatedAt": "2025-01-15T10:05:00"
    }
  ],
  "totalRules": 3,
  "activeRules": 3
}
```

**cURL Example:**

```bash
curl -X GET "http://localhost:9093/api/contract-types/SKI0217_2026/with-rules" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "Accept: application/json"
```

---

#### **POST** `/api/contract-types`

Create a new contract type.

**Request Body:**

```json
{
  "code": "SKI0217_2026",
  "name": "SKI Framework Agreement 2026",
  "description": "Updated framework with 5% admin fee",
  "active": true
}
```

**Field Validation:**

| Field | Type | Required | Constraints |
|-------|------|----------|-------------|
| code | string | Yes | 3-50 chars, uppercase alphanumeric + underscores, unique |
| name | string | Yes | Max 255 chars |
| description | string | No | No limit |
| active | boolean | No | Defaults to true |

**Response:** `201 Created`

```json
{
  "id": 1,
  "code": "SKI0217_2026",
  "name": "SKI Framework Agreement 2026",
  "description": "Updated framework with 5% admin fee",
  "active": true,
  "createdAt": "2025-01-15T10:00:00",
  "updatedAt": "2025-01-15T10:00:00"
}
```

**Error Responses:**

- `400 Bad Request` - Validation error or duplicate code

```json
{
  "error": "Contract type with code 'SKI0217_2026' already exists"
}
```

```json
{
  "errors": [
    {
      "field": "code",
      "message": "Code must contain only uppercase letters, numbers, and underscores"
    }
  ]
}
```

**cURL Example:**

```bash
curl -X POST "http://localhost:9093/api/contract-types" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "code": "SKI0217_2026",
    "name": "SKI Framework Agreement 2026",
    "description": "Updated framework with 5% admin fee",
    "active": true
  }'
```

---

#### **PUT** `/api/contract-types/{code}`

Update an existing contract type.

**Parameters:**

| Name | Type | Location | Required | Description |
|------|------|----------|----------|-------------|
| code | string | path | Yes | Contract type code (cannot be changed) |

**Request Body:**

```json
{
  "name": "SKI Framework Agreement 2026 - Updated",
  "description": "Updated description",
  "active": true
}
```

**Field Validation:**

| Field | Type | Required | Constraints |
|-------|------|----------|-------------|
| name | string | Yes | Max 255 chars |
| description | string | No | No limit |
| active | boolean | Yes | true or false |

**Response:** `200 OK`

```json
{
  "id": 1,
  "code": "SKI0217_2026",
  "name": "SKI Framework Agreement 2026 - Updated",
  "description": "Updated description",
  "active": true,
  "createdAt": "2025-01-15T10:00:00",
  "updatedAt": "2025-01-15T11:30:00"
}
```

**Error Responses:**

- `404 Not Found` - Contract type not found
- `400 Bad Request` - Validation error

**cURL Example:**

```bash
curl -X PUT "http://localhost:9093/api/contract-types/SKI0217_2026" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "SKI Framework Agreement 2026 - Updated",
    "description": "Updated description",
    "active": true
  }'
```

---

#### **DELETE** `/api/contract-types/{code}`

Soft delete a contract type (sets active=false).

**Parameters:**

| Name | Type | Location | Required | Description |
|------|------|----------|----------|-------------|
| code | string | path | Yes | Contract type code |

**Response:** `204 No Content`

No response body.

**Error Responses:**

- `404 Not Found` - Contract type not found
- `400 Bad Request` - Contract type has active rules

```json
{
  "error": "Cannot delete contract type with active pricing rules. Please deactivate or delete all rules first."
}
```

**cURL Example:**

```bash
curl -X DELETE "http://localhost:9093/api/contract-types/SKI0217_2026" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"
```

---

#### **POST** `/api/contract-types/{code}/activate`

Reactivate a soft-deleted contract type.

**Parameters:**

| Name | Type | Location | Required | Description |
|------|------|----------|----------|-------------|
| code | string | path | Yes | Contract type code |

**Response:** `204 No Content`

No response body.

**Error Responses:**

- `404 Not Found` - Contract type not found

**cURL Example:**

```bash
curl -X POST "http://localhost:9093/api/contract-types/SKI0217_2026/activate" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"
```

---

### Pricing Rules

---

#### **GET** `/api/contract-types/{contractTypeCode}/rules`

List all pricing rules for a contract type.

**Parameters:**

| Name | Type | Location | Required | Description |
|------|------|----------|----------|-------------|
| contractTypeCode | string | path | Yes | Contract type code |
| includeInactive | boolean | query | No | Include inactive rules (default: false) |

**Response:** `200 OK`

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
    "createdAt": "2025-01-15T10:05:00",
    "updatedAt": "2025-01-15T10:05:00"
  }
]
```

**cURL Example:**

```bash
curl -X GET "http://localhost:9093/api/contract-types/SKI0217_2026/rules?includeInactive=false" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "Accept: application/json"
```

---

#### **GET** `/api/contract-types/{contractTypeCode}/rules/{ruleId}`

Get a specific pricing rule.

**Parameters:**

| Name | Type | Location | Required | Description |
|------|------|----------|----------|-------------|
| contractTypeCode | string | path | Yes | Contract type code |
| ruleId | string | path | Yes | Rule ID |

**Response:** `200 OK`

```json
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
  "createdAt": "2025-01-15T10:05:00",
  "updatedAt": "2025-01-15T10:05:00"
}
```

**Error Responses:**

- `404 Not Found` - Rule not found

**cURL Example:**

```bash
curl -X GET "http://localhost:9093/api/contract-types/SKI0217_2026/rules/ski21726-admin" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "Accept: application/json"
```

---

#### **POST** `/api/contract-types/{contractTypeCode}/rules`

Create a new pricing rule.

**Parameters:**

| Name | Type | Location | Required | Description |
|------|------|----------|----------|-------------|
| contractTypeCode | string | path | Yes | Contract type code |

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

**Field Validation:**

| Field | Type | Required | Constraints |
|-------|------|----------|-------------|
| ruleId | string | Yes | Lowercase alphanumeric + hyphens, unique per contract type |
| label | string | Yes | Max 255 chars |
| ruleStepType | enum | Yes | See [Rule Types](#rule-types) |
| stepBase | enum | Yes | `SUM_BEFORE_DISCOUNTS` or `CURRENT_SUM` |
| percent | number | Conditional | 0-100, required for percent-based rules |
| amount | number | Conditional | Required for `FIXED_DEDUCTION` |
| paramKey | string | No | References contract_type_items |
| validFrom | date | No | ISO 8601 date (YYYY-MM-DD) |
| validTo | date | No | ISO 8601 date (YYYY-MM-DD) |
| priority | integer | No | Positive integer, auto-incremented if not provided |

**Rule Type Requirements:**

| Rule Type | Required Fields |
|-----------|-----------------|
| PERCENT_DISCOUNT_ON_SUM | `percent` OR `paramKey` |
| ADMIN_FEE_PERCENT | `percent` |
| FIXED_DEDUCTION | `amount` |
| GENERAL_DISCOUNT_PERCENT | None (uses invoice.discount) |
| ROUNDING | None |

**Response:** `201 Created`

```json
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
  "createdAt": "2025-01-15T10:05:00",
  "updatedAt": "2025-01-15T10:05:00"
}
```

**Error Responses:**

- `400 Bad Request` - Validation error or duplicate ruleId
- `404 Not Found` - Contract type not found

```json
{
  "error": "Rule with ID 'ski21726-admin' already exists for contract type 'SKI0217_2026'"
}
```

```json
{
  "error": "ADMIN_FEE_PERCENT rules must have 'percent' set"
}
```

**cURL Example:**

```bash
curl -X POST "http://localhost:9093/api/contract-types/SKI0217_2026/rules" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "ruleId": "ski21726-admin",
    "label": "5% SKI administrationsgebyr",
    "ruleStepType": "ADMIN_FEE_PERCENT",
    "stepBase": "CURRENT_SUM",
    "percent": 5.0,
    "priority": 20
  }'
```

---

#### **POST** `/api/contract-types/{contractTypeCode}/rules/bulk`

Create multiple pricing rules at once.

**Parameters:**

| Name | Type | Location | Required | Description |
|------|------|----------|----------|-------------|
| contractTypeCode | string | path | Yes | Contract type code |

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

**Field Validation:**

| Field | Type | Required | Constraints |
|-------|------|----------|-------------|
| rules | array | Yes | At least 1 rule, each rule validated as per single create |

**Response:** `201 Created`

Returns array of created rules:

```json
[
  {
    "id": 1,
    "contractTypeCode": "SKI0217_2026",
    "ruleId": "ski21726-key",
    "label": "SKI trapperabat",
    ...
  },
  {
    "id": 2,
    "contractTypeCode": "SKI0217_2026",
    "ruleId": "ski21726-admin",
    "label": "5% SKI administrationsgebyr",
    ...
  },
  {
    "id": 3,
    "contractTypeCode": "SKI0217_2026",
    "ruleId": "ski21726-general",
    "label": "Generel rabat",
    ...
  }
]
```

**Error Responses:**

- `400 Bad Request` - Validation error
- `404 Not Found` - Contract type not found

**cURL Example:**

```bash
curl -X POST "http://localhost:9093/api/contract-types/SKI0217_2026/rules/bulk" \
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
      }
    ]
  }'
```

---

#### **PUT** `/api/contract-types/{contractTypeCode}/rules/{ruleId}`

Update an existing pricing rule.

**Parameters:**

| Name | Type | Location | Required | Description |
|------|------|----------|----------|-------------|
| contractTypeCode | string | path | Yes | Contract type code |
| ruleId | string | path | Yes | Rule ID (cannot be changed) |

**Request Body:**

Same fields as create, all fields required (full replace).

```json
{
  "label": "5% SKI administrationsgebyr - Updated",
  "ruleStepType": "ADMIN_FEE_PERCENT",
  "stepBase": "CURRENT_SUM",
  "percent": 5.0,
  "amount": null,
  "paramKey": null,
  "validFrom": "2026-01-01",
  "validTo": null,
  "priority": 20,
  "active": true
}
```

**Response:** `200 OK`

```json
{
  "id": 1,
  "contractTypeCode": "SKI0217_2026",
  "ruleId": "ski21726-admin",
  "label": "5% SKI administrationsgebyr - Updated",
  "ruleStepType": "ADMIN_FEE_PERCENT",
  "stepBase": "CURRENT_SUM",
  "percent": 5.0,
  "amount": null,
  "paramKey": null,
  "validFrom": "2026-01-01",
  "validTo": null,
  "priority": 20,
  "active": true,
  "createdAt": "2025-01-15T10:05:00",
  "updatedAt": "2025-01-15T14:30:00"
}
```

**Error Responses:**

- `404 Not Found` - Rule not found
- `400 Bad Request` - Validation error

**cURL Example:**

```bash
curl -X PUT "http://localhost:9093/api/contract-types/SKI0217_2026/rules/ski21726-admin" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "label": "5% SKI administrationsgebyr - Updated",
    "ruleStepType": "ADMIN_FEE_PERCENT",
    "stepBase": "CURRENT_SUM",
    "percent": 5.0,
    "priority": 20,
    "active": true
  }'
```

---

#### **DELETE** `/api/contract-types/{contractTypeCode}/rules/{ruleId}`

Soft delete a pricing rule (sets active=false).

**Parameters:**

| Name | Type | Location | Required | Description |
|------|------|----------|----------|-------------|
| contractTypeCode | string | path | Yes | Contract type code |
| ruleId | string | path | Yes | Rule ID |

**Response:** `204 No Content`

No response body.

**Error Responses:**

- `404 Not Found` - Rule not found

**cURL Example:**

```bash
curl -X DELETE "http://localhost:9093/api/contract-types/SKI0217_2026/rules/ski21726-admin" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"
```

---

## Data Models

### ContractTypeDefinition

```typescript
interface ContractTypeDefinition {
  id: number;
  code: string;                 // Unique, uppercase alphanumeric + underscores
  name: string;                 // Display name
  description?: string;         // Optional description
  active: boolean;              // Soft delete flag
  createdAt: string;            // ISO 8601 timestamp
  updatedAt: string;            // ISO 8601 timestamp
}
```

### PricingRuleStep

```typescript
interface PricingRuleStep {
  id: number;
  contractTypeCode: string;     // FK to contract type
  ruleId: string;               // Unique per contract type
  label: string;                // Display label
  ruleStepType: RuleStepType;   // Rule type enum
  stepBase: StepBase;           // Calculation base enum
  percent?: number;             // 0-100 for percentage rules
  amount?: number;              // For fixed amount rules
  paramKey?: string;            // References contract_type_items
  validFrom?: string;           // ISO 8601 date (nullable)
  validTo?: string;             // ISO 8601 date (nullable)
  priority: number;             // Execution order
  active: boolean;              // Soft delete flag
  createdAt: string;            // ISO 8601 timestamp
  updatedAt: string;            // ISO 8601 timestamp
}
```

### Enums

```typescript
enum RuleStepType {
  PERCENT_DISCOUNT_ON_SUM = 'PERCENT_DISCOUNT_ON_SUM',
  ADMIN_FEE_PERCENT = 'ADMIN_FEE_PERCENT',
  FIXED_DEDUCTION = 'FIXED_DEDUCTION',
  GENERAL_DISCOUNT_PERCENT = 'GENERAL_DISCOUNT_PERCENT',
  ROUNDING = 'ROUNDING'
}

enum StepBase {
  SUM_BEFORE_DISCOUNTS = 'SUM_BEFORE_DISCOUNTS',
  CURRENT_SUM = 'CURRENT_SUM'
}
```

---

## Error Handling

### HTTP Status Codes

| Status | Meaning | When Used |
|--------|---------|-----------|
| 200 | OK | Successful GET or PUT |
| 201 | Created | Successful POST |
| 204 | No Content | Successful DELETE or action with no response |
| 400 | Bad Request | Validation error, business rule violation |
| 401 | Unauthorized | Missing or invalid JWT token |
| 403 | Forbidden | User doesn't have SYSTEM role |
| 404 | Not Found | Resource not found |
| 500 | Internal Server Error | Server error |

### Error Response Format

**Validation Errors:**

```json
{
  "errors": [
    {
      "field": "code",
      "message": "Code must contain only uppercase letters, numbers, and underscores"
    },
    {
      "field": "percent",
      "message": "Percent must be non-negative"
    }
  ]
}
```

**Business Rule Errors:**

```json
{
  "error": "Contract type with code 'SKI0217_2026' already exists"
}
```

**Generic Errors:**

```json
{
  "error": "Internal server error",
  "message": "An unexpected error occurred"
}
```

### Error Handling in JavaScript

```javascript
const createContractType = async (data) => {
  try {
    const response = await fetch('/api/contract-types', {
      method: 'POST',
      headers,
      body: JSON.stringify(data)
    });

    if (!response.ok) {
      if (response.status === 400) {
        const error = await response.json();
        if (error.errors) {
          // Validation errors
          error.errors.forEach(err => {
            console.error(`${err.field}: ${err.message}`);
          });
        } else {
          // Business rule error
          console.error(error.error);
        }
      } else if (response.status === 404) {
        console.error('Resource not found');
      } else if (response.status === 401) {
        console.error('Unauthorized - please login again');
      } else {
        console.error('Unexpected error:', response.status);
      }
      return null;
    }

    return await response.json();
  } catch (err) {
    console.error('Network error:', err);
    return null;
  }
};
```

---

## Code Examples

### React Hook for Contract Types

```typescript
// hooks/useContractTypes.ts
import { useState, useEffect } from 'react';

interface UseContractTypesOptions {
  includeInactive?: boolean;
  autoLoad?: boolean;
}

export const useContractTypes = (options: UseContractTypesOptions = {}) => {
  const [contractTypes, setContractTypes] = useState<ContractTypeDefinition[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadContractTypes = async () => {
    setLoading(true);
    setError(null);

    try {
      const params = new URLSearchParams();
      if (options.includeInactive) {
        params.append('includeInactive', 'true');
      }

      const response = await fetch(`/api/contract-types?${params}`, {
        headers: {
          'Authorization': `Bearer ${getToken()}`,
          'Accept': 'application/json'
        }
      });

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }

      const data = await response.json();
      setContractTypes(data);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  };

  const createContractType = async (data: CreateContractTypeRequest) => {
    setLoading(true);
    setError(null);

    try {
      const response = await fetch('/api/contract-types', {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${getToken()}`,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify(data)
      });

      if (!response.ok) {
        const error = await response.json();
        throw new Error(error.error || 'Failed to create contract type');
      }

      const created = await response.json();
      setContractTypes(prev => [...prev, created]);
      return created;
    } catch (err) {
      setError(err.message);
      return null;
    } finally {
      setLoading(false);
    }
  };

  const updateContractType = async (code: string, data: UpdateContractTypeRequest) => {
    setLoading(true);
    setError(null);

    try {
      const response = await fetch(`/api/contract-types/${code}`, {
        method: 'PUT',
        headers: {
          'Authorization': `Bearer ${getToken()}`,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify(data)
      });

      if (!response.ok) {
        const error = await response.json();
        throw new Error(error.error || 'Failed to update contract type');
      }

      const updated = await response.json();
      setContractTypes(prev => prev.map(ct => ct.code === code ? updated : ct));
      return updated;
    } catch (err) {
      setError(err.message);
      return null;
    } finally {
      setLoading(false);
    }
  };

  const deleteContractType = async (code: string) => {
    setLoading(true);
    setError(null);

    try {
      const response = await fetch(`/api/contract-types/${code}`, {
        method: 'DELETE',
        headers: {
          'Authorization': `Bearer ${getToken()}`
        }
      });

      if (!response.ok) {
        const error = await response.json();
        throw new Error(error.error || 'Failed to delete contract type');
      }

      setContractTypes(prev => prev.filter(ct => ct.code !== code));
      return true;
    } catch (err) {
      setError(err.message);
      return false;
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (options.autoLoad !== false) {
      loadContractTypes();
    }
  }, [options.includeInactive]);

  return {
    contractTypes,
    loading,
    error,
    loadContractTypes,
    createContractType,
    updateContractType,
    deleteContractType
  };
};
```

### React Component Example

```typescript
// components/ContractTypeManager.tsx
import React, { useState } from 'react';
import { useContractTypes } from '../hooks/useContractTypes';

export const ContractTypeManager: React.FC = () => {
  const {
    contractTypes,
    loading,
    error,
    createContractType,
    updateContractType,
    deleteContractType
  } = useContractTypes();

  const [showForm, setShowForm] = useState(false);
  const [formData, setFormData] = useState({
    code: '',
    name: '',
    description: ''
  });

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    const result = await createContractType(formData);
    if (result) {
      setShowForm(false);
      setFormData({ code: '', name: '', description: '' });
    }
  };

  const handleDelete = async (code: string) => {
    if (window.confirm(`Delete contract type ${code}?`)) {
      await deleteContractType(code);
    }
  };

  if (loading) return <div>Loading...</div>;
  if (error) return <div>Error: {error}</div>;

  return (
    <div>
      <h1>Contract Types</h1>

      <button onClick={() => setShowForm(true)}>Create New</button>

      {showForm && (
        <form onSubmit={handleSubmit}>
          <input
            placeholder="Code (e.g., SKI0217_2026)"
            value={formData.code}
            onChange={e => setFormData({ ...formData, code: e.target.value })}
            required
          />
          <input
            placeholder="Name"
            value={formData.name}
            onChange={e => setFormData({ ...formData, name: e.target.value })}
            required
          />
          <textarea
            placeholder="Description"
            value={formData.description}
            onChange={e => setFormData({ ...formData, description: e.target.value })}
          />
          <button type="submit">Create</button>
          <button type="button" onClick={() => setShowForm(false)}>Cancel</button>
        </form>
      )}

      <table>
        <thead>
          <tr>
            <th>Code</th>
            <th>Name</th>
            <th>Active</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody>
          {contractTypes.map(ct => (
            <tr key={ct.code}>
              <td>{ct.code}</td>
              <td>{ct.name}</td>
              <td>{ct.active ? '✓' : '✗'}</td>
              <td>
                <button onClick={() => handleDelete(ct.code)}>Delete</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
};
```

---

## Best Practices

### 1. Caching and Performance

**Cache Considerations:**
- Pricing rules are cached for 1 hour
- Changes may take up to 1 hour to apply (or until app restart)
- For immediate effect, restart the application after rule changes

**Recommendation:**
```javascript
// After creating/updating rules, inform users about cache delay
const createRule = async (data) => {
  await fetch('/api/contract-types/SKI0217_2026/rules', {
    method: 'POST',
    headers,
    body: JSON.stringify(data)
  });

  // Show message
  alert('Rule created. Changes will take effect within 1 hour or after application restart.');
};
```

### 2. Priority Management

**Best Practice:** Use increments of 10 for priorities
```javascript
const rules = [
  { priority: 10, ... },  // Step discount
  { priority: 20, ... },  // Admin fee
  { priority: 30, ... },  // Fixed deduction
  { priority: 40, ... }   // General discount
];
```

**Why?** Allows inserting rules between existing ones without reordering:
```javascript
// Can insert priority 15 between 10 and 20
{ priority: 15, label: 'Special discount', ... }
```

### 3. Date-Based Rule Transitions

**Best Practice:** Use `validFrom`/`validTo` for planned changes

```javascript
// Instead of updating existing rule immediately
// Create overlapping rules with date ranges

// Old rule (ends Dec 31, 2025)
{
  ruleId: 'ski21726-admin-2025',
  percent: 4.0,
  validFrom: '2025-01-01',
  validTo: '2026-01-01',  // Exclusive
  ...
}

// New rule (starts Jan 1, 2026)
{
  ruleId: 'ski21726-admin-2026',
  percent: 5.0,
  validFrom: '2026-01-01',
  validTo: null,
  ...
}
```

### 4. Validation Before Submission

**Best Practice:** Validate locally before API call

```javascript
const validateContractType = (data) => {
  const errors = [];

  if (!data.code || data.code.length < 3 || data.code.length > 50) {
    errors.push('Code must be 3-50 characters');
  }

  if (!/^[A-Z0-9_]+$/.test(data.code)) {
    errors.push('Code must be uppercase alphanumeric with underscores');
  }

  if (!data.name || data.name.length > 255) {
    errors.push('Name is required and must not exceed 255 characters');
  }

  return errors;
};

// Before API call
const errors = validateContractType(formData);
if (errors.length > 0) {
  setValidationErrors(errors);
  return;
}
```

### 5. Error Handling

**Best Practice:** Handle all error scenarios

```javascript
const handleApiCall = async (apiCall) => {
  try {
    const response = await apiCall();

    if (!response.ok) {
      switch (response.status) {
        case 400:
          const validation = await response.json();
          if (validation.errors) {
            // Field validation errors
            return { success: false, errors: validation.errors };
          } else {
            // Business rule error
            return { success: false, error: validation.error };
          }
        case 404:
          return { success: false, error: 'Resource not found' };
        case 401:
          // Redirect to login
          window.location.href = '/login';
          return { success: false, error: 'Unauthorized' };
        default:
          return { success: false, error: 'Unexpected error occurred' };
      }
    }

    return { success: true, data: await response.json() };
  } catch (err) {
    return { success: false, error: 'Network error: ' + err.message };
  }
};
```

### 6. Loading States

**Best Practice:** Show loading states during API calls

```javascript
const [loading, setLoading] = useState({
  contractTypes: false,
  rules: false,
  saving: false
});

// Update specific loading state
setLoading(prev => ({ ...prev, contractTypes: true }));

// UI
{loading.contractTypes && <Spinner />}
{loading.saving && <div>Saving...</div>}
```

### 7. Optimistic Updates

**Best Practice:** Update UI immediately, revert on error

```javascript
const deleteContractType = async (code) => {
  // Optimistic update
  const previous = contractTypes;
  setContractTypes(prev => prev.filter(ct => ct.code !== code));

  try {
    const response = await fetch(`/api/contract-types/${code}`, {
      method: 'DELETE',
      headers
    });

    if (!response.ok) {
      throw new Error('Delete failed');
    }
  } catch (err) {
    // Revert on error
    setContractTypes(previous);
    alert('Failed to delete: ' + err.message);
  }
};
```

### 8. Bulk Operations

**Best Practice:** Use bulk endpoints for multiple operations

```javascript
// Good: Single API call for multiple rules
await fetch('/api/contract-types/SKI0217_2026/rules/bulk', {
  method: 'POST',
  headers,
  body: JSON.stringify({
    rules: [rule1, rule2, rule3]
  })
});

// Bad: Multiple API calls
for (const rule of rules) {
  await fetch('/api/contract-types/SKI0217_2026/rules', {
    method: 'POST',
    headers,
    body: JSON.stringify(rule)
  });
}
```

---

## Testing Checklist

### Unit Tests

- [ ] Validate form inputs match API constraints
- [ ] Test error handling for all status codes
- [ ] Test date formatting (ISO 8601)
- [ ] Test enum value validation

### Integration Tests

- [ ] Create contract type successfully
- [ ] Create contract type with duplicate code (should fail)
- [ ] Update contract type
- [ ] Delete contract type with active rules (should fail)
- [ ] Create pricing rule successfully
- [ ] Create rule with invalid ruleStepType (should fail)
- [ ] Bulk create multiple rules
- [ ] Update pricing rule
- [ ] Delete pricing rule
- [ ] Load contract type with rules

### E2E Tests

- [ ] Complete workflow: create contract type → add rules → verify
- [ ] Update admin fee scenario
- [ ] Deactivate contract type with cleanup
- [ ] Date-based rule transition
- [ ] Cache behavior (create rule, verify it applies after cache clear)

---

## Support and Troubleshooting

### Common Issues

**Issue:** Rules not applying to invoices
- **Solution:** Wait 1 hour for cache to expire or restart application

**Issue:** Cannot delete contract type
- **Solution:** Deactivate or delete all pricing rules first

**Issue:** Priority conflict
- **Solution:** Ensure all rules have unique priorities

**Issue:** 401 Unauthorized
- **Solution:** Check JWT token is valid and user has SYSTEM role

**Issue:** Validation error on code field
- **Solution:** Use only uppercase letters, numbers, and underscores

### Getting Help

- Review full documentation: `docs/dynamic-contract-types.md`
- Check existing contract types for examples: `GET /api/contract-types`
- Review pricing engine docs: `docs/invoices/implementation/pricing-engine.md`

---

**Document Version:** 1.0
**Last Updated:** January 2025
**API Version:** 1.0
