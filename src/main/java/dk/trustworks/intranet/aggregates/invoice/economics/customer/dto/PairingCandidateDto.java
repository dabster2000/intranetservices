package dk.trustworks.intranet.aggregates.invoice.economics.customer.dto;

/**
 * A single e-conomic customer candidate returned alongside an AMBIGUOUS
 * pairing row, carrying enough context for the admin to pick the right one.
 *
 * @param customerNumber e-conomic customer number
 * @param name           customer name as stored in e-conomic
 * @param cvrNo          CVR number (may be null if the e-conomic record has none)
 * @param matchReason    how this candidate was matched (e.g. {@code CVR}, {@code NAME})
 */
public record PairingCandidateDto(
        int customerNumber,
        String name,
        String cvrNo,
        String matchReason
) {
}
