package dk.trustworks.intranet.aggregates.invoice.economics.customer.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * Summary counts returned by {@code POST /economics/customers/pair/auto-run}.
 *
 * @see PairingRowDto
 */
@Getter @AllArgsConstructor
public class AutoRunResultDto {
    /** Number of clients newly paired (CVR or name). */
    private int paired;
    /** Number of clients already paired where no change was needed. */
    private int unchanged;
    /** Number of clients where multiple e-conomic candidates matched. */
    private int ambiguous;
    /** Number of clients with no matching e-conomic customer. */
    private int unmatched;
    /** Client UUIDs that failed during auto-pair (exceptions or persistence errors). */
    private List<String> errors;
}
