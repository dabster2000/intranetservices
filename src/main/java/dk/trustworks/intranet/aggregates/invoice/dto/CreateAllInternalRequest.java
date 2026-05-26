package dk.trustworks.intranet.aggregates.invoice.dto;

import java.util.List;

/**
 * Request body for {@code POST /invoices/{uuid}/create-all-internal}.
 *
 * @param issuerCompanyUuids       optional subset of issuer companies to materialize.
 *                                 {@code null} or empty means "all detected issuers
 *                                 from the source invoice's current attribution state".
 * @param queue                    when {@code true}, newly-created DRAFT internal
 *                                 invoices are transitioned to {@code QUEUED} in the
 *                                 same transaction. Default {@code false}.
 *                                 The {@code /accounting/internal-invoices} page sends
 *                                 {@code true}; the {@code /invoices} page sends {@code false}.
 * @param excludedAttributionUuids optional attribution UUIDs to skip during line
 *                                 generation, matching BASE rows the user deselected
 *                                 in the preview modal. CALCULATED rows are
 *                                 re-derived from the surviving BASE set. The
 *                                 attribution table is not modified. {@code null}
 *                                 or empty means "include all attributions".
 */
public record CreateAllInternalRequest(
        List<String> issuerCompanyUuids,
        boolean queue,
        List<String> excludedAttributionUuids
) {
    public CreateAllInternalRequest(List<String> issuerCompanyUuids, boolean queue) {
        this(issuerCompanyUuids, queue, null);
    }
}
