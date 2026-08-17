package dk.trustworks.intranet.expenseservice.dto;

import dk.trustworks.intranet.expenseservice.model.Expense;
import java.util.List;

public record ExpenseReviewListItemDTO(
    Expense expense,
    String employeeName, String employeePhotoUrl,
    String employeeJustification,
    String aiRuleId, List<String> aiRuleIds,
    int daysWaiting,
    /** True when the expense already has an e-conomic voucher (voucher fields are
     *  @JsonIgnore on the entity) — drives which pipeline actions the UI offers. */
    boolean hasVoucher) {}
