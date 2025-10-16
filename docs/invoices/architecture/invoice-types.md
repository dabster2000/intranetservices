# Invoice Types

The system supports 5 distinct invoice types, each with specific business rules and workflows.

## 1. INVOICE (Regular Invoice)

### Purpose
Standard client invoices for services rendered.

### Characteristics
- **Invoice Number**: Assigned sequentially upon creation
- **PDF Generation**: Automatic upon finalization
- **E-conomics Integration**: Full synchronization
- **Bonus Eligibility**: Yes
- **VAT Application**: Yes (for DKK currency)

### Workflow
```
DRAFT → CREATED → SUBMITTED → PAID
```

### Implementation
```java
// Create regular invoice from project work
Invoice invoice = invoiceService.createInvoiceFromProject(
    contractUuid, projectUuid, LocalDate.of(2025, 1, 1));
invoice.setType(InvoiceType.INVOICE);
invoice.setStatus(InvoiceStatus.DRAFT);
```

### Business Rules
- Must have client information
- Requires at least one line item
- Cannot be deleted after CREATED status
- Supports header-level discounts (0-100%)

## 2. CREDIT_NOTE

### Purpose
Refunds, corrections, or adjustments to existing invoices.

### Characteristics
- **Invoice Number**: New sequential number
- **Reference**: Links to original invoice via `creditnoteForUuid`
- **Amounts**: Always negative
- **E-conomics Integration**: Yes
- **Bonus Eligibility**: No (negative bonus possible)

### Workflow
```
Original Invoice (CREATED/SUBMITTED/PAID)
          ↓
    Create Credit Note
          ↓
    CREDIT_NOTE status
```

### Implementation
```java
// Create credit note from existing invoice
Invoice creditNote = invoiceService.createCreditNote(originalInvoice);
creditNote.setType(InvoiceType.CREDIT_NOTE);
creditNote.setStatus(InvoiceStatus.CREDIT_NOTE);
creditNote.setCreditnoteForUuid(originalInvoice.getUuid());

// Negate all amounts
creditNote.getInvoiceitems().forEach(item -> 
    item.setHours(-Math.abs(item.getHours())));
```

### Business Rules
- Cannot create credit note for DRAFT invoices
- Original invoice must exist and be finalized
- Amounts automatically negated
- Shows visual relationship in UI
- Cannot have credit note of credit note

### UI Features
- Watermark display on credit notes
- Side-by-side comparison with original
- Badge showing credit note count on original invoice

## 3. PHANTOM

### Purpose
Placeholder invoices for expected revenue without actual delivery.

### Characteristics
- **Invoice Number**: Not assigned
- **Status**: Immediately CREATED
- **E-conomics Integration**: No
- **Bonus Eligibility**: Limited
- **Purpose**: Revenue forecasting, planning

### Workflow
```
DRAFT → Create Phantom → CREATED (no invoice number)
```

### Implementation
```java
// Create phantom invoice
Invoice phantom = invoiceService.createPhantom(draftInvoice);
phantom.setType(InvoiceType.PHANTOM);
phantom.setStatus(InvoiceStatus.CREATED);
// No invoice number assigned
```

### Business Rules
- No e-conomics synchronization
- Cannot be sent to clients
- Used for internal reporting only
- Can be converted to regular invoice later

## 4. INTERNAL

### Purpose
Inter-company invoices within Trustworks group.

### Characteristics
- **Access Control**: ADMIN or TECHPARTNER role required
- **Company Selection**: Target company must be specified
- **Invoice Number**: Assigned normally
- **E-conomics Integration**: Separate ledger
- **Bonus Eligibility**: No

### Workflow
```
Regular Invoice (CREATED)
          ↓
    Select Target Company
          ↓
    Create Internal Invoice
```

### Implementation
```java
// InvoiceView.java:998-1020
if (AuthenticatedUser.hasRole(ADMIN) || AuthenticatedUser.hasRole(TECHPARTNER)) {
    // Show internal invoice creation UI
    Select<Company> companySelect = new Select<>();
    companySelect.setItems(Company.values());
    
    Button createInternal = new Button("Create Internal Invoice", e -> {
        Invoice internal = invoiceService.createInternalInvoice(
            originalInvoice, selectedCompany);
        internal.setType(InvoiceType.INTERNAL);
    });
}
```

### Business Rules
- Original invoice must be finalized
- Requires special permissions
- Maintains reference to source invoice
- Separate accounting treatment
- No bonus assignments allowed

## 5. INTERNAL_SERVICE

### Purpose
Service invoices between Trustworks companies.

### Characteristics
- **Creation**: Through InternalInvoiceListView
- **Access Control**: TECHPARTNER or ADMIN only
- **Custom Fields**: Service-specific descriptions
- **E-conomics Integration**: Internal ledger only

### Workflow
```
InternalInvoiceListView
          ↓
    Enter Service Details
          ↓
    Create Internal Service Invoice
```

### Implementation
```java
// InternalInvoiceListView.java:75-104
@Route(value = "internal-invoices", layout = MainLayout.class)
@RolesAllowed({"TECHPARTNER", "ADMIN"})
public class InternalInvoiceListView {
    
    private void createServiceInvoice() {
        Invoice serviceInvoice = new Invoice();
        serviceInvoice.setType(InvoiceType.INTERNAL_SERVICE);
        serviceInvoice.setCompany(selectedCompany);
        // Service-specific setup
    }
}
```

### Business Rules
- No client association required
- Manual entry of all details
- Internal approval workflow
- No external visibility

## Type-Specific Features Matrix

| Feature | INVOICE | CREDIT_NOTE | PHANTOM | INTERNAL | INTERNAL_SERVICE |
|---------|---------|-------------|---------|----------|-----------------|
| Invoice Number | ✅ | ✅ | ❌ | ✅ | ✅ |
| PDF Generation | ✅ | ✅ | ❌ | ✅ | ✅ |
| E-conomics Sync | ✅ | ✅ | ❌ | Partial | ❌ |
| Bonus Eligible | ✅ | Negative | ❌ | ❌ | ❌ |
| Client Required | ✅ | ✅ | ✅ | ❌ | ❌ |
| VAT Application | ✅ | ✅ | ✅ | ✅ | ❌ |
| Editable Draft | ✅ | ❌ | ✅ | ✅ | ✅ |
| Role Required | SALES | SALES | SALES | ADMIN | TECHPARTNER |

## Type Determination Logic

```java
public InvoiceType determineInvoiceType(Invoice invoice) {
    // Credit note check
    if (invoice.getCreditnoteForUuid() != null) {
        return InvoiceType.CREDIT_NOTE;
    }
    
    // Internal check
    if (invoice.getCompany() != null && 
        invoice.getCompany() != Company.TRUSTWORKS) {
        return InvoiceType.INTERNAL;
    }
    
    // Phantom check (no invoice number after creation)
    if (invoice.getStatus() == InvoiceStatus.CREATED && 
        invoice.getInvoicenumber() == 0) {
        return InvoiceType.PHANTOM;
    }
    
    // Internal service (special creation flow)
    if (invoice.getSource() == InvoiceSource.INTERNAL_SERVICE) {
        return InvoiceType.INTERNAL_SERVICE;
    }
    
    // Default to regular invoice
    return InvoiceType.INVOICE;
}
```

## UI Type Indicators

The system provides visual indicators for invoice types:

```java
// InvoiceView type badges
private Badge getTypeBadge(Invoice invoice) {
    Badge badge = new Badge(invoice.getType().name());
    
    switch(invoice.getType()) {
        case CREDIT_NOTE:
            badge.addThemeVariants(BadgeVariant.ERROR);
            break;
        case PHANTOM:
            badge.addThemeVariants(BadgeVariant.CONTRAST);
            break;
        case INTERNAL:
        case INTERNAL_SERVICE:
            badge.addThemeVariants(BadgeVariant.WARNING);
            break;
        default:
            badge.addThemeVariants(BadgeVariant.SUCCESS);
    }
    
    return badge;
}
```

## Future Invoice Types (Planned)

### RECURRING
- Automated monthly/quarterly invoicing
- Template-based generation
- Subscription management

### PROFORMA
- Quote-to-invoice conversion
- Approval workflow before invoicing
- Validity period tracking

### CONSOLIDATED
- Multiple projects in single invoice
- Cross-contract billing
- Period-based aggregation
