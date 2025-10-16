# Validation Rules

## Overview

This document details all business rules and validation constraints applied throughout the invoice system to ensure data integrity and business compliance.

## Invoice Creation Rules

### Draft Creation

#### Rule: Client Required
```java
if (contract.getClient() == null || contract.getClient().isEmpty()) {
    throw new ValidationException("Contract must have client information");
}
```
**Applied**: Before draft creation from project
**Location**: `InvoiceView.java:761-764`

#### Rule: Non-Zero Amount
```java
if (invoice.getSumAfterDiscounts() == 0) {
    throw new ValidationException("Invoice amount cannot be zero");
}
```
**Applied**: Before invoice finalization
**Purpose**: Prevent empty invoices

#### Rule: At Least One Line Item
```java
if (invoice.getInvoiceitems().isEmpty()) {
    throw new ValidationException("Invoice must have at least one line item");
}
```
**Applied**: During validation
**Purpose**: Ensure invoice has content

### Date Validation

#### Rule: Due Date After Invoice Date
```java
if (dueDate.isBefore(invoiceDate)) {
    throw new ValidationException("Due date must be after invoice date");
}
```
**Applied**: On save
**Default**: Invoice date + 30 days

#### Rule: Invoice Date Not Future
```java
if (invoiceDate.isAfter(LocalDate.now())) {
    // Warning only, not blocking
    showWarning("Invoice date is in the future");
}
```
**Applied**: During creation
**Type**: Warning (non-blocking)

## Financial Validation

### Discount Rules

#### Rule: Discount Range
```java
if (discount < 0 || discount > 100) {
    throw new ValidationException("Discount must be between 0 and 100%");
}
```
**Applied**: On discount field change
**Range**: 0-100%

#### Rule: SKI Discount Limit
```java
if (skiDiscount > 0 && totalDiscount > 20) {
    showWarning("Total discount exceeds 20% - requires approval");
}
```
**Applied**: When SKI discount present
**Type**: Warning with approval requirement

### VAT Rules

#### Rule: VAT Only for DKK
```java
if (!"DKK".equals(currency) && vat > 0) {
    invoice.setVat(0);
    showInfo("VAT set to 0% for non-DKK currency");
}
```
**Applied**: Automatically on currency change
**Location**: `InvoiceView.java:982-986`

#### Rule: VAT Range
```java
if (vat < 0 || vat > 100) {
    throw new ValidationException("VAT must be between 0 and 100%");
}
```
**Applied**: On VAT field change

### Currency Rules

#### Rule: Valid Currency Codes
```java
private static final Set<String> VALID_CURRENCIES = 
    Set.of("DKK", "EUR", "SEK", "USD", "GBP");

if (!VALID_CURRENCIES.contains(currency)) {
    throw new ValidationException("Invalid currency code: " + currency);
}
```
**Applied**: On currency selection

## Status Transition Rules

### Draft to Created

#### Rule: Only Draft Can Be Finalized
```java
if (invoice.getStatus() != InvoiceStatus.DRAFT) {
    throw new IllegalStateException("Only DRAFT invoices can be finalized");
}
```
**Applied**: Before creating invoice

#### Rule: No Editing After Creation
```java
if (invoice.getStatus() != InvoiceStatus.DRAFT) {
    makeAllFieldsReadOnly();
    throw new IllegalStateException("Cannot edit finalized invoice");
}
```
**Applied**: On edit attempt

### Credit Note Rules

#### Rule: Cannot Credit Draft
```java
if (invoice.getStatus() == InvoiceStatus.DRAFT) {
    throw new ValidationException("Cannot create credit note for draft invoice");
}
```
**Applied**: Before credit note creation
**Location**: `InvoiceView.java:996`

#### Rule: Cannot Credit Phantom
```java
if (invoice.getType() == InvoiceType.PHANTOM) {
    throw new ValidationException("Cannot create credit note for phantom invoice");
}
```
**Applied**: During validation

#### Rule: Single Credit Note Chain
```java
if (invoice.getType() == InvoiceType.CREDIT_NOTE) {
    throw new ValidationException("Cannot create credit note of credit note");
}
```
**Applied**: Prevent chaining

## Bonus System Rules

### Eligibility Rules

#### Rule: Active Eligibility Required
```java
public boolean isEligible(User user, LocalDate invoiceDate) {
    BonusEligibility eligibility = findEligibility(user.getUuid());
    
    return eligibility != null &&
           eligibility.isCanSelfAssign() &&
           !invoiceDate.isBefore(eligibility.getActiveFrom()) &&
           !invoiceDate.isAfter(eligibility.getActiveTo());
}
```
**Applied**: Before self-assignment

#### Rule: Invoice Type Restrictions
```java
if (invoice.getType() == InvoiceType.CREDIT_NOTE ||
    invoice.getType() == InvoiceType.INTERNAL) {
    return false; // Not eligible for bonuses
}
```
**Applied**: During eligibility check

#### Rule: No Bonus if Credit Note Exists
```java
if (hasAssociatedCreditNote(invoice)) {
    throw new ValidationException("Cannot assign bonus - credit note exists");
}
```
**Applied**: Before bonus creation

### Approval Rules

#### Rule: Cannot Approve Own Bonus
```java
if (approverUuid.equals(bonus.getUseruuid())) {
    throw new ValidationException("Cannot approve your own bonus");
}
```
**Applied**: During approval

#### Rule: Rejection Requires Reason
```java
if (StringUtils.isBlank(rejectionReason)) {
    throw new ValidationException("Rejection reason is required");
}
```
**Applied**: During rejection

#### Rule: Only Pending Can Be Approved
```java
if (bonus.getStatus() != SalesApprovalStatus.PENDING) {
    throw new IllegalStateException("Only PENDING bonuses can be approved");
}
```
**Applied**: State validation

### Line Allocation Rules

#### Rule: Valid Percentages Only
```java
private static final Set<Double> VALID_PERCENTAGES = Set.of(0.0, 80.0, 100.0);

if (!VALID_PERCENTAGES.contains(linePercentage)) {
    throw new ValidationException("Line percentage must be 0%, 80%, or 100%");
}
```
**Applied**: During line selection

#### Rule: At Least One Line Selected
```java
boolean hasSelection = lines.stream()
    .anyMatch(line -> line.getPercentage() > 0);
    
if (!hasSelection) {
    throw new ValidationException("Select at least one line item");
}
```
**Applied**: On save

#### Rule: Total Bonus Cannot Exceed Invoice
```java
double totalBonuses = existingBonuses + newBonus.getComputedAmount();

if (totalBonuses > invoice.getSumAfterDiscounts()) {
    throw new ValidationException("Total bonuses exceed invoice amount");
}
```
**Applied**: During calculation

## Line Item Rules

### Item Validation

#### Rule: Required Fields
```java
if (StringUtils.isBlank(item.getItemname())) {
    throw new ValidationException("Item name is required");
}

if (item.getRate() <= 0) {
    throw new ValidationException("Rate must be positive");
}

if (item.getHours() < 0) {
    throw new ValidationException("Hours cannot be negative");
}
```
**Applied**: On line item save

#### Rule: Position Uniqueness
```java
Set<Integer> positions = items.stream()
    .map(InvoiceItem::getPosition)
    .collect(Collectors.toSet());
    
if (positions.size() != items.size()) {
    throw new ValidationException("Duplicate item positions detected");
}
```
**Applied**: After reordering

### Calculated Items

#### Rule: Cannot Edit Calculated Items
```java
if (item.getOrigin() == InvoiceItemOrigin.CALCULATED) {
    makeFieldReadOnly();
    throw new ValidationException("Cannot edit system-calculated items");
}
```
**Applied**: On edit attempt

## Internal Invoice Rules

### Access Control

#### Rule: Role Required for Internal
```java
if (!hasAnyRole("ADMIN", "TECHPARTNER")) {
    throw new AccessDeniedException("Insufficient permissions for internal invoice");
}
```
**Applied**: Before creation
**Location**: `InvoiceView.java:998-1020`

#### Rule: Company Selection Required
```java
if (targetCompany == null || targetCompany == sourceCompany) {
    throw new ValidationException("Select different target company");
}
```
**Applied**: During creation

## Phantom Invoice Rules

#### Rule: No Invoice Number
```java
// Phantom invoices never get invoice numbers
phantom.setInvoicenumber(0);
phantom.setStatus(InvoiceStatus.CREATED);
```
**Applied**: Automatically
**Purpose**: Distinguish from real invoices

#### Rule: No E-conomics Sync
```java
if (invoice.getType() == InvoiceType.PHANTOM) {
    skipEconomicsSync = true;
}
```
**Applied**: During sync process

## Performance Validations

### Bulk Operation Limits

#### Rule: Maximum Items per Invoice
```java
private static final int MAX_LINE_ITEMS = 500;

if (invoice.getInvoiceitems().size() > MAX_LINE_ITEMS) {
    throw new ValidationException("Invoice exceeds maximum of " + MAX_LINE_ITEMS + " items");
}
```
**Applied**: During save

#### Rule: Description Length Limits
```java
private static final int MAX_DESCRIPTION_LENGTH = 1000;

if (description.length() > MAX_DESCRIPTION_LENGTH) {
    throw new ValidationException("Description exceeds " + MAX_DESCRIPTION_LENGTH + " characters");
}
```
**Applied**: On text fields

## Cross-Cutting Rules

### Unicode and Special Characters

#### Rule: Client Name Validation
```java
if (!clientName.matches("^[\\p{L}\\p{N}\\s\\-.,&()]+$")) {
    showWarning("Client name contains special characters");
}
```
**Type**: Warning only

### Decimal Precision

#### Rule: Amount Rounding
```java
// All amounts rounded to 2 decimal places
amount = Math.round(amount * 100.0) / 100.0;
```
**Applied**: All monetary calculations

### Concurrency

#### Rule: Optimistic Locking
```java
@Version
private Long version;

// Throws OptimisticLockingException on concurrent modification
```
**Applied**: Entity updates

## Validation Error Messages

### User-Friendly Messages
```java
public String getUserMessage(ValidationException e) {
    return switch (e.getCode()) {
        case "ZERO_AMOUNT" -> "Invoice must have a value greater than zero";
        case "NO_ITEMS" -> "Please add at least one line item";
        case "INVALID_DATES" -> "Payment due date must be after invoice date";
        case "DISCOUNT_RANGE" -> "Discount must be between 0% and 100%";
        default -> "Validation failed: " + e.getMessage();
    };
}
```

## Custom Validation Implementation

### Validation Service
```java
@Service
public class InvoiceValidationService {
    
    public ValidationResult validate(Invoice invoice) {
        ValidationResult result = new ValidationResult();
        
        // Run all validations
        validateClient(invoice, result);
        validateAmounts(invoice, result);
        validateDates(invoice, result);
        validateLineItems(invoice, result);
        validateBusinessRules(invoice, result);
        
        return result;
    }
    
    private void validateAmounts(Invoice invoice, ValidationResult result) {
        if (invoice.getSumAfterDiscounts() <= 0) {
            result.addError("ZERO_AMOUNT", "Invoice amount must be positive");
        }
        
        if (invoice.getDiscount() < 0 || invoice.getDiscount() > 100) {
            result.addError("INVALID_DISCOUNT", "Invalid discount percentage");
        }
    }
}
```

## Testing Validation Rules

### Unit Tests
```java
@Test
public void testCannotCreateZeroAmountInvoice() {
    Invoice invoice = createTestInvoice();
    invoice.getInvoiceitems().clear();
    
    ValidationResult result = validationService.validate(invoice);
    
    assertTrue(result.hasErrors());
    assertEquals("ZERO_AMOUNT", result.getErrors().get(0).getCode());
}
```

## Future Validation Enhancements

1. **Field-Level Real-Time Validation** - As user types
2. **Custom Business Rules Engine** - Configurable rules
3. **Validation Templates** - Per client or contract type
4. **Machine Learning Validation** - Anomaly detection
5. **Cross-Field Dependencies** - Complex conditional rules
