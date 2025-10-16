# Invoice REST API

## Base URL
```
{APIGATEWAY_URL}/api/invoices
```

## Authentication
All endpoints require JWT authentication token in header:
```
Authorization: Bearer {token}
```

## Core Invoice Endpoints

### List Invoices
```http
GET /api/invoices
```

**Query Parameters:**
- `page` (int): Page number (0-based)
- `size` (int): Page size (default: 25)
- `sort` (string): Sort field and direction (e.g., "invoicedate,desc")
- `year` (int): Filter by year
- `month` (int): Filter by month
- `status` (string): Filter by status
- `type` (string): Filter by type

**Response:**
```json
{
  "content": [
    {
      "uuid": "inv-123",
      "invoicenumber": 10001,
      "clientname": "Acme Corp",
      "invoicedate": "2025-01-15",
      "status": "CREATED",
      "type": "INVOICE",
      "sumAfterDiscounts": 100000.00
    }
  ],
  "totalElements": 150,
  "totalPages": 6,
  "number": 0,
  "size": 25
}
```

### Get Single Invoice
```http
GET /api/invoices/{uuid}
```

**Response:**
```json
{
  "uuid": "inv-123",
  "invoicenumber": 10001,
  "contractuuid": "con-456",
  "projectuuid": "proj-789",
  "clientname": "Acme Corp",
  "clientaddress": "123 Main St",
  "attention": "John Doe",
  "cvr": "12345678",
  "ean": "5790001234567",
  "invoicedate": "2025-01-15",
  "duedate": "2025-02-15",
  "discount": 10.0,
  "vat": 25.0,
  "currency": "DKK",
  "status": "CREATED",
  "type": "INVOICE",
  "invoiceitems": [
    {
      "uuid": "item-001",
      "itemname": "Consulting Services",
      "description": "Senior consultant work",
      "consultantuuid": "user-123",
      "rate": 1500.00,
      "hours": 40.0,
      "position": 1,
      "origin": "BASE"
    }
  ],
  "sumBeforeDiscounts": 60000.00,
  "sumAfterDiscounts": 54000.00,
  "vatAmount": 13500.00,
  "grandTotal": 67500.00
}
```

### Create Draft Invoice
```http
POST /api/invoices/drafts
```

**Request Body:**
```json
{
  "contractuuid": "con-456",
  "projectuuid": "proj-789",
  "clientname": "Acme Corp",
  "clientaddress": "123 Main St",
  "invoicedate": "2025-01-15",
  "duedate": "2025-02-15",
  "currency": "DKK",
  "vat": 25.0,
  "discount": 0.0,
  "invoiceitems": [
    {
      "itemname": "Development",
      "rate": 1200.00,
      "hours": 80.0
    }
  ]
}
```

**Response:** Created invoice object with status DRAFT

### Update Invoice
```http
PUT /api/invoices/{uuid}
```

**Request Body:** Complete invoice object

**Response:** Updated invoice

**Note:** Only DRAFT invoices can be updated

### Delete Draft Invoice
```http
DELETE /api/invoices/drafts/{uuid}
```

**Response:** 204 No Content

**Note:** Only DRAFT invoices can be deleted

### Create Invoice from Draft
```http
POST /api/invoices
```

**Request Body:**
```json
{
  "draftuuid": "inv-draft-123"
}
```

**Response:** Finalized invoice with invoice number and CREATED status

### Create Phantom Invoice
```http
POST /api/invoices/phantoms
```

**Request Body:**
```json
{
  "draftuuid": "inv-draft-123"
}
```

**Response:** Phantom invoice (no invoice number)

### Create Credit Note
```http
POST /api/invoices/creditnotes
```

**Request Body:**
```json
{
  "originalInvoiceUuid": "inv-123",
  "reason": "Overcharge correction"
}
```

**Response:** Credit note with negative amounts

### Create Internal Invoice
```http
POST /api/invoices/internal
```

**Request Body:**
```json
{
  "originalInvoiceUuid": "inv-123",
  "targetCompany": "TRUSTWORKS_TECHNOLOGY",
  "description": "Inter-company services"
}
```

**Response:** Internal invoice

**Required Role:** ADMIN or TECHPARTNER

## Project Invoice Endpoints

### Load Project Summary
```http
GET /api/invoices/project-summary
```

**Query Parameters:**
- `year` (int): Year
- `month` (int): Month
- `contractuuid` (string): Contract filter

**Response:**
```json
[
  {
    "contractuuid": "con-456",
    "contractname": "Annual Support",
    "projectuuid": "proj-789",
    "projectname": "Development Phase 1",
    "clientname": "Acme Corp",
    "registeredAmount": 150000.00,
    "invoicedAmount": 100000.00,
    "remainingAmount": 50000.00,
    "workItems": [
      {
        "consultantName": "John Smith",
        "hours": 40,
        "rate": 1500.00
      }
    ]
  }
]
```

### Create Invoice from Project
```http
POST /api/invoices/from-project
```

**Request Body:**
```json
{
  "contractuuid": "con-456",
  "projectuuid": "proj-789",
  "year": 2025,
  "month": 1
}
```

**Response:** Draft invoice with work items

## Search and Filter Endpoints

### Search Invoices
```http
GET /api/invoices/search
```

**Query Parameters:**
- `q` (string): Search query
- `field` (string): Search field (clientname, invoicenumber, etc.)

### Financial Year Summary
```http
GET /api/invoices/financial-year/{year}
```

**Response:**
```json
{
  "fiscalYear": 2024,
  "totalRevenue": 5000000.00,
  "invoiceCount": 245,
  "paidAmount": 4500000.00,
  "unpaidAmount": 500000.00,
  "creditNoteAmount": 125000.00
}
```

## Bulk Operations

### Batch Status Update
```http
PUT /api/invoices/batch/status
```

**Request Body:**
```json
{
  "invoiceUuids": ["inv-1", "inv-2", "inv-3"],
  "status": "SUBMITTED"
}
```

**Required Role:** ADMIN

## Error Responses

### 400 Bad Request
```json
{
  "error": "VALIDATION_ERROR",
  "message": "Invoice must have at least one line item",
  "field": "invoiceitems",
  "timestamp": "2025-01-15T10:30:00Z"
}
```

### 401 Unauthorized
```json
{
  "error": "UNAUTHORIZED",
  "message": "Invalid or expired token"
}
```

### 403 Forbidden
```json
{
  "error": "FORBIDDEN",
  "message": "Insufficient permissions for this operation"
}
```

### 404 Not Found
```json
{
  "error": "NOT_FOUND",
  "message": "Invoice not found: inv-123"
}
```

### 409 Conflict
```json
{
  "error": "CONFLICT",
  "message": "Invoice already finalized and cannot be edited"
}
```

## Rate Limiting

- Default: 100 requests per minute per user
- Bulk operations: 10 requests per minute
- Search: 30 requests per minute

## Versioning

API version specified in header:
```
X-API-Version: 1.0
```

## WebSocket Events (Future)

```javascript
// Subscribe to invoice updates
ws.subscribe('/topic/invoices', (event) => {
  console.log('Invoice updated:', event);
});
```

## SDK Examples

### Java
```java
InvoiceRestService service = new InvoiceRestService(apiUrl, token);
List<Invoice> invoices = service.findAll();
Invoice invoice = service.findOne(uuid);
```

### JavaScript
```javascript
const api = new InvoiceAPI(apiUrl, token);
const invoices = await api.getInvoices({ page: 0, size: 25 });
const invoice = await api.getInvoice(uuid);
```

## Testing

### Test Environment
```
https://test-api.trustworks.dk/api/invoices
```

### Sample Test Data
- Test invoice UUID: `test-inv-001`
- Test client: "Test Client AB"
- Test user token: Available in test environment

## Migration Notes

### From v0.9 to v1.0
- `bonusConsultant` field deprecated, use `InvoiceBonus` entities
- `economicsStatus` renamed from `economics_status`
- New required field: `currency` (defaults to "DKK")
