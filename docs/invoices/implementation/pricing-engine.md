# Pricing Engine

## Overview

The Pricing Engine is a server-side calculation system that provides transparent, consistent pricing calculations for invoices. It handles discounts, VAT, and provides detailed breakdowns of all calculations.

## API Endpoint

### Preview Pricing
```
GET /api/pricing/preview/{invoiceUuid}
```

Returns calculated invoice totals with detailed breakdown:

```json
{
  "uuid": "invoice-123",
  "sumBeforeDiscounts": 100000.00,
  "sumAfterDiscounts": 90000.00,
  "vatAmount": 22500.00,
  "grandTotal": 112500.00,
  "calculationBreakdown": [
    {
      "lineNumber": 1,
      "description": "Consulting Services",
      "amount": 80000.00,
      "calculation": "100 hours × 800 kr"
    },
    {
      "lineNumber": 2,
      "description": "Development",
      "amount": 20000.00,
      "calculation": "25 hours × 800 kr"
    },
    {
      "lineNumber": "discount",
      "description": "Header Discount (10%)",
      "amount": -10000.00,
      "calculation": "100000 × 0.10"
    },
    {
      "lineNumber": "vat",
      "description": "VAT (25%)",
      "amount": 22500.00,
      "calculation": "90000 × 0.25"
    }
  ]
}
```

## Calculation Rules

### 1. Base Calculation
Sum all line items (BASE origin):
```java
double sumBeforeDiscounts = invoice.getInvoiceitems().stream()
    .filter(item -> item.getOrigin() == InvoiceItemOrigin.BASE)
    .mapToDouble(item -> item.getRate() * item.getHours())
    .sum();
```

### 2. Discount Application
Apply header-level discount to base sum:
```java
double discountAmount = sumBeforeDiscounts * (invoice.getDiscount() / 100.0);
double sumAfterDiscounts = sumBeforeDiscounts - discountAmount;
```

### 3. VAT Calculation
VAT only applies to DKK invoices:
```java
double vatAmount = 0;
if ("DKK".equals(invoice.getCurrency()) && invoice.getVat() > 0) {
    vatAmount = sumAfterDiscounts * (invoice.getVat() / 100.0);
}
```

### 4. Grand Total
```java
double grandTotal = sumAfterDiscounts + vatAmount;
```

## Calculation Types

### Standard Invoice Calculation
```java
public class StandardCalculation {
    public Invoice calculate(Invoice invoice) {
        // Step 1: Sum line items
        double base = sumLineItems(invoice);
        
        // Step 2: Apply discount
        double discounted = applyDiscount(base, invoice.getDiscount());
        
        // Step 3: Calculate VAT
        double vat = calculateVAT(discounted, invoice);
        
        // Step 4: Total
        double total = discounted + vat;
        
        // Set transient fields
        invoice.setSumBeforeDiscounts(base);
        invoice.setSumAfterDiscounts(discounted);
        invoice.setVatAmount(vat);
        invoice.setGrandTotal(total);
        
        return invoice;
    }
}
```

### Credit Note Calculation
```java
public class CreditNoteCalculation {
    public Invoice calculate(Invoice creditNote) {
        // All amounts negative
        double base = sumLineItems(creditNote); // Already negative
        double discounted = base * (1 - creditNote.getDiscount() / 100.0);
        double vat = calculateVAT(discounted, creditNote);
        double total = discounted + vat;
        
        // Ensure all amounts are negative
        creditNote.setSumBeforeDiscounts(-Math.abs(base));
        creditNote.setSumAfterDiscounts(-Math.abs(discounted));
        creditNote.setVatAmount(-Math.abs(vat));
        creditNote.setGrandTotal(-Math.abs(total));
        
        return creditNote;
    }
}
```

## Line Item Types

### BASE Items
User-created line items:
```java
InvoiceItem baseItem = new InvoiceItem();
baseItem.setOrigin(InvoiceItemOrigin.BASE);
baseItem.setRate(1500.00);
baseItem.setHours(40);
baseItem.setItemname("Consulting");
// Total: 1500 × 40 = 60,000
```

### CALCULATED Items
System-generated items from pricing rules:
```java
InvoiceItem calculatedItem = new InvoiceItem();
calculatedItem.setOrigin(InvoiceItemOrigin.CALCULATED);
calculatedItem.setCalculationRef("TRAVEL_EXPENSE");
calculatedItem.setRuleId("RULE_001");
calculatedItem.setRate(5000.00);
calculatedItem.setHours(1); // Quantity
// Auto-calculated based on rules
```

## Discount Types

### Header Discount
Applied to entire invoice:
```java
invoice.setDiscount(10.0); // 10% discount on all items
```

### SKI Discount (Special)
Government/framework agreement discount:
```java
// Currently stored in specificdescription field
// Applied as additional header discount
double skiDiscount = invoice.getSkiDiscount(); // Future field
double totalDiscount = invoice.getDiscount() + skiDiscount;
```

## VAT Rules

### Currency-Based VAT
```java
public double calculateVAT(Invoice invoice, double baseAmount) {
    // VAT only for DKK
    if (!"DKK".equals(invoice.getCurrency())) {
        return 0;
    }
    
    // Standard rate is 25%
    double vatRate = invoice.getVat() > 0 ? invoice.getVat() : 25.0;
    
    return baseAmount * (vatRate / 100.0);
}
```

### VAT Exemptions
- Foreign currency invoices: 0% VAT
- Export services: 0% VAT (future)
- Reverse charge: 0% VAT (EU business)

## Rounding Rules

### Standard Rounding
```java
public double roundAmount(double amount) {
    // Round to 2 decimal places
    return Math.round(amount * 100.0) / 100.0;
}
```

### Currency-Specific Rounding
```java
public double roundByCurrency(double amount, String currency) {
    switch(currency) {
        case "DKK":
        case "EUR":
        case "USD":
        case "GBP":
            // Round to 2 decimals
            return Math.round(amount * 100.0) / 100.0;
        case "SEK":
            // Round to nearest whole number
            return Math.round(amount);
        default:
            return amount;
    }
}
```

## Integration with UI

### Preview Button Implementation
```java
// InvoiceView.java:932-945
private void showPricingPreview(Invoice invoice) {
    if (invoice.getStatus() != InvoiceStatus.DRAFT) {
        // Use cached pricing for non-drafts
        Invoice priced = invoiceService.preview(invoice.getUuid());
        updatePricingDisplay(priced);
    } else {
        // Real-time calculation for drafts
        saveDraft(invoice);
        Invoice priced = invoiceService.calculatePricing(invoice);
        updatePricingDisplay(priced);
    }
}
```

### Real-time Updates
```java
// Update totals as user edits
invoiceItemGrid.addItemPropertyChangedListener(e -> {
    // Recalculate on rate/hours change
    if ("rate".equals(e.getProperty()) || "hours".equals(e.getProperty())) {
        updateTotalsDisplay();
    }
});
```

## Calculation Breakdown

### Detailed Breakdown Structure
```java
public class CalculationBreakdownLine {
    private String lineNumber;    // "1", "2", "discount", "vat"
    private String description;   // Human-readable description
    private double amount;        // Calculated amount
    private String calculation;   // Formula used
    
    // Example breakdown
    public static CalculationBreakdownLine forLineItem(InvoiceItem item, int position) {
        return CalculationBreakdownLine.builder()
            .lineNumber(String.valueOf(position))
            .description(item.getItemname())
            .amount(item.getRate() * item.getHours())
            .calculation(String.format("%.2f hours × %.2f kr", 
                item.getHours(), item.getRate()))
            .build();
    }
}
```

## Performance Optimization

### Caching Strategy
```java
@CacheResult(cacheName = "pricing-preview")
public Invoice preview(String invoiceUuid) {
    // Cache for 30 minutes
    return calculatePricing(findInvoice(invoiceUuid));
}

@CacheEvict(cacheName = "pricing-preview")
public void invalidatePricing(String invoiceUuid) {
    // Clear cache on invoice update
}
```

### Batch Calculation
```java
public List<Invoice> calculateBatch(List<Invoice> invoices) {
    return invoices.parallelStream()
        .map(this::calculatePricing)
        .collect(Collectors.toList());
}
```

## Validation Rules

### Pre-Calculation Validation
```java
public void validateForPricing(Invoice invoice) {
    // Must have items
    if (invoice.getInvoiceitems().isEmpty()) {
        throw new ValidationException("Invoice must have at least one item");
    }
    
    // Valid discount range
    if (invoice.getDiscount() < 0 || invoice.getDiscount() > 100) {
        throw new ValidationException("Discount must be between 0 and 100");
    }
    
    // Valid VAT range
    if (invoice.getVat() < 0 || invoice.getVat() > 100) {
        throw new ValidationException("VAT must be between 0 and 100");
    }
}
```

## Error Handling

### Calculation Errors
```java
public Invoice safeCalculate(Invoice invoice) {
    try {
        return calculatePricing(invoice);
    } catch (Exception e) {
        log.error("Pricing calculation failed for invoice: {}", 
            invoice.getUuid(), e);
        
        // Return invoice with zero amounts
        invoice.setSumBeforeDiscounts(0.0);
        invoice.setSumAfterDiscounts(0.0);
        invoice.setVatAmount(0.0);
        invoice.setGrandTotal(0.0);
        
        // Add error to breakdown
        invoice.setCalculationBreakdown(List.of(
            CalculationBreakdownLine.builder()
                .description("Calculation Error")
                .calculation(e.getMessage())
                .build()
        ));
        
        return invoice;
    }
}
```

## Future Enhancements

### Planned Features
1. **Line-Item Discounts**: Individual item discounts
2. **Tiered Pricing**: Volume-based discounts
3. **Multi-Currency Conversion**: Real-time exchange rates
4. **Tax Rules Engine**: Complex tax scenarios
5. **Pricing Templates**: Reusable pricing rules
6. **Subscription Pricing**: Recurring invoice calculations

### API Extensions
```java
// Future endpoints
POST /api/pricing/simulate    // What-if scenarios
GET  /api/pricing/rules        // Available pricing rules
POST /api/pricing/validate    // Validate pricing without saving
```
