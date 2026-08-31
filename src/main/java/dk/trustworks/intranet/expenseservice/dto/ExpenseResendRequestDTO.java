package dk.trustworks.intranet.expenseservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Selected expenses to re-send to / pre-check against e-conomic.
 *
 * @param confirmDuplicates the caller has seen the pre-check's duplicate warning and accepts a
 *                          second voucher for rows whose voucher is still in e-conomic. Absent or
 *                          null means NOT confirmed — the safe default, so an old client, a script
 *                          or a hand-rolled call can never duplicate a booked voucher by omission.
 *                          Ignored by the pre-check endpoint, which never writes.
 */
public record ExpenseResendRequestDTO(
        @NotEmpty @Size(max = 500) List<@NotBlank String> uuids,
        Boolean confirmDuplicates) {

    /** Pre-check callers (and tests) that have no confirmation to give. */
    public ExpenseResendRequestDTO(List<String> uuids) {
        this(uuids, null);
    }

    public boolean duplicatesConfirmed() {
        return Boolean.TRUE.equals(confirmDuplicates);
    }
}
