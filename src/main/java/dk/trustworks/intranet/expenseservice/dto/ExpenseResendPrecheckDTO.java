package dk.trustworks.intranet.expenseservice.dto;

/** Per-expense pre-check: whether it can be re-sent and whether its voucher still exists. */
public record ExpenseResendPrecheckDTO(
        String uuid,
        boolean eligible,
        String reason,        // null when eligible; else the skip reason
        boolean voucherExists,
        String location) {}   // "DRAFT" | "BOOKED" | "MISSING"
