package dk.trustworks.intranet.aggregates.finance.dto;

import java.util.List;

/**
 * Registered vs invoiced for the team's junior members (JK Team 2.0 WP6 §4.6.2 row "Registered
 * vs. invoiced hours"): the ClientStatusService expected-vs-invoiced computation restricted to
 * the roster. Caveat, stated on the surface: it measures what reached the intranet — junior
 * hours logged under another consultant's line are invisible to it by construction.
 *
 * @param months trailing twelve month keys, oldest first
 */
public record TeamRegisteredVsInvoicedDTO(
        List<String> months,
        List<ClientRow> clients,
        double totalExpected,
        double totalInvoiced
) {
    public record ClientRow(
            String clientUuid,
            String clientName,
            List<MonthCell> months,
            double expected,
            double invoiced,
            /** expected − invoiced; positive = registered work not yet billed */
            double gap
    ) {}

    public record MonthCell(String monthKey, double expected, double invoiced) {}
}
