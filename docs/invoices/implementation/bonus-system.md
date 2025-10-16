# Modern Bonus System

## Overview

The Modern Bonus System provides a comprehensive workflow for consultant bonuses on invoices, featuring self-assignment, approval workflows, line-level allocation, and fiscal year-based eligibility groups.

## Core Components

### Self-Assignment Workflow

#### 1. Eligibility Check
```java
// InvoiceService checks eligibility
public boolean canSelfAssignBonus(String userUuid, Invoice invoice) {
    // Check user eligibility
    BonusEligibility eligibility = findEligibility(userUuid);
    if (eligibility == null || !eligibility.isCanSelfAssign()) {
        return false;
    }
    
    // Check date range
    LocalDate invoiceDate = invoice.getInvoicedate();
    if (!eligibility.isActiveOn(invoiceDate)) {
        return false;
    }
    
    // Check invoice type
    if (invoice.getType() == InvoiceType.CREDIT_NOTE ||
        invoice.getType() == InvoiceType.INTERNAL) {
        return false;
    }
    
    // Check for credit notes
    if (hasAssociatedCreditNote(invoice)) {
        return false;
    }
    
    return true;
}
```

#### 2. Self-Assignment UI
```java
// InvoiceBonusUserPanel.java:66-98
private Component createSelfAssignButton() {
    Button selfAssignBtn = new Button("I'm account manager");
    selfAssignBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
    
    selfAssignBtn.addClickListener(e -> {
        // Create 100% bonus claim
        InvoiceBonus bonus = invoiceService.selfAddBonus(
            invoice.getUuid(),
            currentUser.getUuid(),
            ShareType.PERCENT,
            100.0,
            "Sales commission"
        );
        
        Notification.show("Bonus submitted for approval", 3000,
            Notification.Position.BOTTOM_END)
            .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        
        refreshBonusDisplay();
    });
    
    // Show only if eligible
    selfAssignBtn.setVisible(canSelfAssignBonus());
    
    return selfAssignBtn;
}
```

### Approval Workflow

#### Approval States
```
PENDING → APPROVED (by authorized user)
   ↓
REJECTED (with mandatory reason)
```

#### Approval Implementation
```java
// InvoiceService.java:275-293
@Transactional
public BonusAggregateResponse approveBonus(String invoiceUuid,
                                            String bonusUuid,
                                            String approverUuid) {
    // Validation
    if (!canApprove(approverUuid, bonusUuid)) {
        throw new UnauthorizedException("Cannot approve this bonus");
    }
    
    // Update status
    InvoiceBonus bonus = findBonus(bonusUuid);
    bonus.setStatus(SalesApprovalStatus.APPROVED);
    bonus.setApprovedBy(approverUuid);
    bonus.setApprovedAt(LocalDateTime.now());
    
    // Audit log
    auditService.logApproval(bonus, approverUuid);
    
    // Calculate aggregate status
    return calculateAggregateStatus(invoiceUuid);
}

public BonusAggregateResponse rejectBonus(String invoiceUuid,
                                           String bonusUuid,
                                           String approverUuid,
                                           String reason) {
    // Reason is mandatory
    if (StringUtils.isBlank(reason)) {
        throw new ValidationException("Rejection reason is required");
    }
    
    InvoiceBonus bonus = findBonus(bonusUuid);
    bonus.setStatus(SalesApprovalStatus.REJECTED);
    bonus.setRejectedBy(approverUuid);
    bonus.setRejectedAt(LocalDateTime.now());
    bonus.setRejectionReason(reason);
    
    auditService.logRejection(bonus, approverUuid, reason);
    
    return calculateAggregateStatus(invoiceUuid);
}
```

### Line-Level Allocation

#### Line Selection Rules
- BASE items: User can select 0%, 80%, or 100%
- CALCULATED items: Proportionally distributed based on BASE selection

#### Implementation
```java
// InvoiceRestService.java:333-344
public List<InvoiceBonusLine> getBonusLines(String invoiceUuid, String bonusUuid) {
    List<InvoiceBonusLine> lines = restService.get(
        "/invoices/" + invoiceUuid + "/bonuses/" + bonusUuid + "/lines",
        new TypeReference<List<InvoiceBonusLine>>() {}
    );
    
    // Default selection if no lines exist
    if (lines.isEmpty()) {
        return getDefaultLineSelection(invoiceUuid, bonusUuid);
    }
    
    return lines;
}

public BonusAggregateResponse saveBonusLines(String invoiceUuid,
                                              String bonusUuid,
                                              List<InvoiceBonusLine> lines) {
    // Validate percentages
    for (InvoiceBonusLine line : lines) {
        if (!Arrays.asList(0.0, 80.0, 100.0).contains(line.getPercentage())) {
            throw new ValidationException("Line percentage must be 0%, 80%, or 100%");
        }
    }
    
    // Save and recalculate
    return restService.put(
        "/invoices/" + invoiceUuid + "/bonuses/" + bonusUuid + "/lines",
        lines,
        BonusAggregateResponse.class
    );
}
```

### Bonus Calculation

#### Calculation Formula
```java
public double calculateBonus(Invoice invoice, InvoiceBonus bonus, 
                              List<InvoiceBonusLine> lines) {
    if (bonus.getShareType() == ShareType.AMOUNT) {
        // Fixed amount
        return bonus.getShareValue();
    }
    
    // Percentage-based
    double baseAmount = calculateBaseAmount(invoice, lines);
    double discountFactor = 1 - (invoice.getDiscount() / 100.0);
    double adjustedAmount = baseAmount * discountFactor;
    
    // Apply bonus percentage
    double bonusAmount = adjustedAmount * (bonus.getShareValue() / 100.0);
    
    // Handle credit notes
    if (invoice.getType() == InvoiceType.CREDIT_NOTE) {
        bonusAmount = -Math.abs(bonusAmount);
    }
    
    return Math.round(bonusAmount * 100.0) / 100.0;
}

private double calculateBaseAmount(Invoice invoice, List<InvoiceBonusLine> lines) {
    double baseTotal = 0;
    
    // Sum selected BASE items
    for (InvoiceBonusLine line : lines) {
        InvoiceItem item = findItem(invoice, line.getInvoiceitemuuid());
        if (item.getOrigin() == InvoiceItemOrigin.BASE) {
            double itemTotal = item.getRate() * item.getHours();
            baseTotal += itemTotal * (line.getPercentage() / 100.0);
        }
    }
    
    // Calculate CALCULATED items proportionally
    double calculatedTotal = invoice.getInvoiceitems().stream()
        .filter(item -> item.getOrigin() == InvoiceItemOrigin.CALCULATED)
        .mapToDouble(item -> item.getRate() * item.getHours())
        .sum();
    
    // Determine selection ratio
    double totalBase = invoice.getInvoiceitems().stream()
        .filter(item -> item.getOrigin() == InvoiceItemOrigin.BASE)
        .mapToDouble(item -> item.getRate() * item.getHours())
        .sum();
    
    double selectionRatio = totalBase > 0 ? baseTotal / totalBase : 0;
    
    // Apply ratio to CALCULATED items
    return baseTotal + (calculatedTotal * selectionRatio);
}
```

## Eligibility Management

### Eligibility Groups

#### Group Structure
```java
@Entity
public class BonusEligibilityGroup {
    private String uuid;
    private String name;              // e.g., "Sales Team FY 2024/25"
    private LocalDate periodStart;    // July 1, 2024
    private LocalDate periodEnd;      // June 30, 2025
}
```

#### Group Management UI
```java
// BonusEligibilityEditor.java
public class BonusEligibilityEditor extends Dialog {
    private Grid<BonusEligibilityGroup> groupGrid;
    private Grid<BonusEligibility> memberGrid;
    
    private void createGroup() {
        // Validate fiscal year alignment
        LocalDate start = startDate.getValue();
        LocalDate end = endDate.getValue();
        
        if (start.getMonthValue() != 7 || start.getDayOfMonth() != 1) {
            showError("Group must start on July 1");
            return;
        }
        
        if (end.getMonthValue() != 6 || end.getDayOfMonth() != 30) {
            showError("Group must end on June 30");
            return;
        }
        
        BonusEligibilityGroup group = invoiceService.createEligibilityGroup(
            nameField.getValue(), start, end);
        
        groupGrid.getDataProvider().refreshAll();
    }
    
    private void addMemberToGroup(BonusEligibilityGroup group, User user) {
        BonusEligibility eligibility = new BonusEligibility();
        eligibility.setUseruuid(user.getUuid());
        eligibility.setGroup(group);
        eligibility.setCanSelfAssign(true);
        eligibility.setActiveFrom(group.getPeriodStart());
        eligibility.setActiveTo(group.getPeriodEnd());
        
        invoiceService.upsertEligibility(eligibility);
        memberGrid.getDataProvider().refreshAll();
    }
}
```

### Individual Eligibility

#### Configuration
```java
public class BonusEligibility {
    private String useruuid;        // One per user
    private boolean canSelfAssign;  // Permission to self-assign
    private LocalDate activeFrom;   // Start date
    private LocalDate activeTo;     // End date (often 2999-12-31)
    private BonusEligibilityGroup group; // Optional group membership
    
    public boolean isEligibleOn(LocalDate date) {
        return !date.isBefore(activeFrom) && !date.isAfter(activeTo);
    }
}
```

## UI Components

### Bonus Approval View

#### Split-Pane Layout
```java
// InvoiceBonusApprovalView.java
@Route(value = "invoices/bonus", layout = MainLayout.class)
@RolesAllowed({"ADMIN", "FINANCE", "SALES"})
public class InvoiceBonusApprovalView extends Main {
    
    private void createLayout() {
        SplitLayout splitLayout = new SplitLayout();
        splitLayout.setSplitterPosition(58); // 58% for invoice grid
        
        // Left: Invoice grid with pending bonuses
        Grid<BonusApprovalRowDTO> invoiceGrid = createInvoiceGrid();
        splitLayout.addToPrimary(new RoundedWhiteContainer(invoiceGrid));
        
        // Right: Bonus details for selected invoice
        VerticalLayout detailsPanel = createDetailsPanel();
        splitLayout.addToSecondary(new RoundedWhiteContainer(detailsPanel));
        
        add(splitLayout);
    }
}
```

#### Targeted Row Updates
```java
// Optimized single-row update after approval
private void applyAggregateToRow(BonusAggregateResponse agg) {
    BonusApprovalRowDTO row = rowCache.get(agg.getInvoiceuuid());
    if (row != null) {
        // Update cached instance
        row.setAggregatedStatus(agg.getAggregatedStatus());
        row.setTotalBonusAmount(agg.getTotalBonusAmount());
        
        // Refresh only this row
        invoiceGrid.getDataProvider().refreshItem(row);
    } else {
        // Fallback to full refresh
        filteredProvider.refreshAll();
    }
}
```

### Personal Bonus Dashboard

#### ConsultantInvoiceStatusView
```java
@Route(value = "invoices/my-bonuses", layout = MainLayout.class)
@RolesAllowed({"PARTNER"})
public class ConsultantInvoiceStatusView extends Main {
    
    private void loadFiscalYearData(int fiscalYear) {
        // Load personal bonus summary
        List<MyBonusFySumDTO> summary = invoiceService.myBonusSummary(
            currentUser.getUuid());
        
        // Update KPI cards
        updateKPICards(summary);
        
        // Update charts
        updateApprovedVsPendingChart(summary);
        updateMonthlyTrendChart(summary);
        
        // Load detailed grid
        loadBonusGrid(fiscalYear);
    }
    
    private void updateKPICards(List<MyBonusFySumDTO> summary) {
        double totalApproved = summary.stream()
            .filter(s -> s.getStatus() == APPROVED)
            .mapToDouble(MyBonusFySumDTO::getAmount)
            .sum();
        
        double totalPending = summary.stream()
            .filter(s -> s.getStatus() == PENDING)
            .mapToDouble(MyBonusFySumDTO::getAmount)
            .sum();
        
        approvedCard.setValue(formatCurrency(totalApproved));
        pendingCard.setValue(formatCurrency(totalPending));
    }
}
```

## Security Model

### Role-Based Permissions

| Action | PARTNER | SALES | FINANCE | ADMIN |
|--------|---------|-------|---------|--------|
| View Own Bonuses | ✅ | ✅ | ✅ | ✅ |
| Self-Assign | ✅* | ✅* | ✅* | ✅* |
| View All Bonuses | ❌ | ❌ | ✅ | ✅ |
| Approve Bonuses | ❌ | ❌ | ✅ | ✅ |
| Manage Eligibility | ❌ | ❌ | ❌ | ✅ |
| Edit Line Selection | ❌ | ❌ | ✅ | ✅ |

*If eligible per BonusEligibility configuration

### Permission Checks
```java
// View-level security
@RolesAllowed({"ADMIN", "FINANCE", "SALES"})
public class InvoiceBonusApprovalView

// Method-level security
@PreAuthorize("hasAnyRole('ADMIN', 'FINANCE')")
public BonusAggregateResponse approveBonus(...)

// Data-level security
public List<InvoiceBonus> getUserBonuses(String userUuid) {
    String currentUser = SecurityContext.getCurrentUser();
    if (!currentUser.equals(userUuid) && !hasRole("ADMIN")) {
        throw new AccessDeniedException("Cannot view other user's bonuses");
    }
    return bonusRepository.findByUserUuid(userUuid);
}
```

## API Endpoints

### Bonus Operations
```
GET    /invoices/{id}/bonuses              - List bonuses for invoice
POST   /invoices/{id}/bonuses/self         - Self-assign bonus
POST   /invoices/{id}/bonuses              - Admin create bonus
POST   /invoices/{id}/bonuses/{bid}/approve - Approve bonus
POST   /invoices/{id}/bonuses/{bid}/reject  - Reject bonus
PUT    /invoices/{id}/bonuses/{bid}/lines   - Update line selection
DELETE /invoices/{id}/bonuses/{bid}         - Delete bonus
```

### Eligibility Management
```
GET    /invoices/eligibility               - List all eligibilities
POST   /invoices/eligibility               - Create/update eligibility
GET    /invoices/eligibility-groups         - List groups
POST   /invoices/eligibility-groups         - Create group
GET    /invoices/eligibility-groups/{id}/approved-total - Group totals
```

### Personal Dashboard
```
GET    /invoices/my-bonus?useruuid={id}    - Personal bonus list
GET    /invoices/my-bonus/summary?useruuid={id} - FY summaries
GET    /invoices/bonus-approval            - Approval queue
```

## Performance Optimizations

### Caching Strategy
```java
// User cache for avoiding N+1 queries
private final Map<String, User> userCache = new ConcurrentHashMap<>();

// Row cache for targeted updates
private final Map<String, BonusApprovalRowDTO> rowCache = new ConcurrentHashMap<>();

// FY summary cache
private Map<FiscalYear, YearTotals> totalsByFy = new LinkedHashMap<>();
```

### Lazy Loading
```java
// Server-side pagination
CallbackDataProvider<BonusApprovalRowDTO, Set<SalesApprovalStatus>> provider = 
    new CallbackDataProvider<>(
        query -> {
            int page = query.getOffset() / query.getLimit();
            return invoiceService.findBonusApprovalPage(page, size, statuses).stream();
        },
        query -> invoiceService.countBonusApproval(statuses)
    );
```

## Future Enhancements

1. **Bulk Approval**: Select multiple bonuses for batch approval
2. **Email Notifications**: Automatic alerts on status changes
3. **Advanced Analytics**: Trend analysis and forecasting
4. **Mobile App**: Approve bonuses on the go
5. **Integration with Payroll**: Direct export to payroll system
