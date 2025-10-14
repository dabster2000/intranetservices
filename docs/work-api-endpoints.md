# Work API Performance-Optimized Endpoints

## Overview

This document describes the new performance-optimized REST endpoints added to the Work API for handling large datasets efficiently. These endpoints complement the existing work endpoints with specialized features for pagination, lightweight queries, and aggregation.

## Base URL

```
/work/search/
```

All endpoints require JWT authentication with the `SYSTEM` role.

## Endpoints

### 1. Paginated Work Data

**GET** `/work/search/findByPeriodPaged`

Retrieves work entries with pagination support. Optimized for large datasets to avoid memory issues.

#### Request Parameters

| Parameter | Type | Required | Description | Example |
|-----------|------|----------|-------------|---------|
| fromdate | string | Yes | Start date (inclusive) in YYYY-MM-DD format | 2024-01-01 |
| todate | string | Yes | End date (exclusive) in YYYY-MM-DD format | 2024-02-01 |
| page | integer | No | Page number (0-based), defaults to 0 | 0 |
| size | integer | No | Records per page (max 1000), defaults to 100 | 100 |

#### Response

```json
{
  "content": [
    {
      "uuid": "550e8400-e29b-41d4-a716-446655440000",
      "useruuid": "123e4567-e89b-12d3-a456-426614174000",
      "registered": "2024-01-15",
      "workduration": 7.5,
      "taskuuid": "987e6543-e21b-12d3-a456-426614174000",
      "billable": true,
      "rate": 1200.00,
      // ... other WorkFull fields
    }
  ],
  "page": 0,
  "size": 100,
  "totalElements": 1534,
  "totalPages": 16,
  "first": true,
  "last": false,
  "hasNext": true,
  "hasPrevious": false
}
```

#### Use Cases
- Loading work data in UI tables with pagination
- Exporting large datasets in chunks
- Processing yearly data without memory issues

### 2. Lightweight Work Data

**GET** `/work/search/findByPeriodLightweight`

Returns only essential work fields for optimal performance, without full entity relationships.

#### Request Parameters

| Parameter | Type | Required | Description | Example |
|-----------|------|----------|-------------|---------|
| fromdate | string | Yes | Start date (inclusive) in YYYY-MM-DD format | 2024-01-01 |
| todate | string | Yes | End date (exclusive) in YYYY-MM-DD format | 2024-02-01 |

#### Response

```json
[
  {
    "uuid": "550e8400-e29b-41d4-a716-446655440000",
    "useruuid": "123e4567-e89b-12d3-a456-426614174000",
    "registered": "2024-01-15",
    "workduration": 7.5,
    "taskuuid": "987e6543-e21b-12d3-a456-426614174000",
    "billable": true,
    "rate": 1200.00,
    "projectuuid": "abc12345-f67g-89h0-ijkl-123456789012"
  },
  // ... more records
]
```

#### Use Cases
- Quick reports where full entity data isn't needed
- Data exports to CSV/Excel
- Performance-critical dashboards
- Mobile applications with limited bandwidth

### 3. Count Work Entries

**GET** `/work/search/countByPeriod`

Returns the total count of work entries for a date range without fetching data.

#### Request Parameters

| Parameter | Type | Required | Description | Example |
|-----------|------|----------|-------------|---------|
| fromdate | string | Yes | Start date (inclusive) in YYYY-MM-DD format | 2024-01-01 |
| todate | string | Yes | End date (exclusive) in YYYY-MM-DD format | 2024-02-01 |

#### Response

```json
1534
```

#### Use Cases
- Pre-calculating pagination requirements
- Showing total records before fetching
- Quick statistics without data transfer
- Validation before large operations

### 4. Work Data Grouped by User

**GET** `/work/search/findByPeriodGroupedByUser`

Retrieves work entries organized by user for the specified period.

#### Request Parameters

| Parameter | Type | Required | Description | Example |
|-----------|------|----------|-------------|---------|
| fromdate | string | Yes | Start date (inclusive) in YYYY-MM-DD format | 2024-01-01 |
| todate | string | Yes | End date (exclusive) in YYYY-MM-DD format | 2024-02-01 |

#### Response

```json
{
  "workByUser": {
    "123e4567-e89b-12d3-a456-426614174000": [
      {
        "uuid": "550e8400-e29b-41d4-a716-446655440000",
        "registered": "2024-01-15",
        "workduration": 7.5,
        // ... other WorkFull fields
      }
    ],
    "987e6543-e21b-12d3-a456-426614174001": [
      // ... work entries for this user
    ]
  },
  "fromDate": "2024-01-01",
  "toDate": "2024-02-01",
  "userCount": 42,
  "totalEntries": 1892
}
```

#### Use Cases
- User-specific timesheet reports
- Individual performance analytics
- Payroll processing
- User productivity tracking

### 5. Work Summary Statistics

**GET** `/work/search/summaryByPeriod`

Returns aggregated statistics without individual work records.

#### Request Parameters

| Parameter | Type | Required | Description | Example |
|-----------|------|----------|-------------|---------|
| fromdate | string | Yes | Start date (inclusive) in YYYY-MM-DD format | 2024-01-01 |
| todate | string | Yes | End date (exclusive) in YYYY-MM-DD format | 2024-02-01 |

#### Response

```json
{
  "uniqueUsers": 42,
  "uniqueTasks": 156,
  "uniqueProjects": 23,
  "totalHours": 3567.5,
  "totalRevenue": 4280400.00,
  "totalEntries": 1892,
  "fromDate": "2024-01-01",
  "toDate": "2024-02-01",
  "averageHoursPerUser": 84.94,
  "averageRevenuePerUser": 101914.29,
  "averageHourlyRate": 1200.00,
  "periodDays": 31
}
```

#### Use Cases
- Executive dashboards
- High-level reporting
- Quick period comparisons
- KPI monitoring

## Performance Comparison

| Endpoint | Data Size | Response Time | Memory Usage |
|----------|-----------|---------------|--------------|
| Original `/work/search/findByPeriod` | 1 year | 30-45s | High |
| `/work/search/findByPeriodPaged` | 1 year (page) | <2s | Low |
| `/work/search/findByPeriodLightweight` | 1 year | 5-10s | Medium |
| `/work/search/summaryByPeriod` | 1 year | <1s | Minimal |

## Migration Guide

### From Original to Paginated

**Before:**
```javascript
const works = await fetch('/work/search/findByPeriod?fromdate=2024-01-01&todate=2025-01-01');
// May timeout or consume excessive memory
```

**After:**
```javascript
let page = 0;
let hasMore = true;
const allWorks = [];

while (hasMore) {
  const response = await fetch(
    `/work/search/findByPeriodPaged?fromdate=2024-01-01&todate=2025-01-01&page=${page}&size=500`
  );
  const data = await response.json();
  allWorks.push(...data.content);
  hasMore = data.hasNext;
  page++;
}
```

### From Full to Lightweight

**Before:**
```javascript
const works = await fetch('/work/search/findByPeriod?fromdate=2024-01-01&todate=2024-02-01');
// Returns full entity graph
```

**After:**
```javascript
const works = await fetch('/work/search/findByPeriodLightweight?fromdate=2024-01-01&todate=2024-02-01');
// Returns only essential fields, 5x faster
```

## Error Handling

All endpoints return standard HTTP status codes:

- `200 OK` - Successful response
- `400 Bad Request` - Invalid date format or parameters
- `401 Unauthorized` - Missing or invalid JWT token
- `403 Forbidden` - Insufficient permissions

Error response format:
```json
{
  "error": "Invalid date format",
  "message": "Date must be in YYYY-MM-DD format",
  "status": 400
}
```

## Best Practices

1. **Use pagination** for date ranges > 1 month
2. **Use lightweight endpoint** when full entity data isn't needed
3. **Use summary endpoint** for dashboards and KPIs
4. **Cache count results** when implementing custom pagination
5. **Set reasonable page sizes** (100-500 records)
6. **Use grouped endpoint** for user-centric views

## OpenAPI/Swagger

Full API documentation with interactive testing is available at:
```
/q/swagger-ui/
```

Look for the "time" tag to find all work-related endpoints.