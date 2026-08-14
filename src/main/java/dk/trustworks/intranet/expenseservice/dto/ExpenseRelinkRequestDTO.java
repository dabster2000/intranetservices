package dk.trustworks.intranet.expenseservice.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Manual re-link of expenses to their moved/renumbered e-conomic vouchers (2026-08 fiscal-year
 * reshuffle: pre-marker vouchers have no durable identity, so the nightly sync cannot re-link
 * them and a human supplies the new triple per row).
 */
public record ExpenseRelinkRequestDTO(
        @NotEmpty @Size(max = 500) List<@NotNull @Valid Row> rows,
        boolean dryRun) {

    /** Where the target voucher lives: an unbooked draft in a journal, or the booked ledger. */
    public enum Target { DRAFT, BOOKED }

    /**
     * One re-link: expense {@code uuid} gets pointed at the given voucher. {@code journalnumber}
     * is required for {@code DRAFT} targets (draft vouchers are addressed per journal) and
     * ignored for {@code BOOKED} ones (the booked ledger is addressed by year + voucher only;
     * the expense keeps its stored journal number, mirroring the sync's booked-marker heal).
     */
    public record Row(
            @NotBlank String uuid,
            Integer journalnumber,
            @Min(1) int vouchernumber,
            @NotBlank String accountingyear,
            @NotNull Target target) {}
}
