package dk.trustworks.intranet.aggregates.crm.trustlink.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * One page of {@code POST /api/connections/search}.
 *
 * <p>The envelope fields are not decoration — they are the only evidence the caller has
 * that the request it sent was the request the server answered. TrustLink ignores request
 * fields it does not recognise instead of rejecting them, so a misspelt filter comes back
 * as a perfectly well-formed page of the wrong data. {@code TrustLinkClient} refuses any
 * page whose {@code page} and {@code pageSize} are not the ones it asked for; see the
 * class Javadoc there for why that check is the most important line in this feature.
 *
 * @param items      the people on this page; never null once the client has accepted it
 * @param totalCount how many people match across all pages
 * @param page       1-based, echoed back from the request
 * @param pageSize   echoed back from the request
 * @param totalPages paging stops when {@code page >= totalPages}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TrustLinkSearchResponse(
        List<TrustLinkConnectionDTO> items,
        int totalCount,
        int page,
        int pageSize,
        int totalPages) {

    /** Never null, so a caller can page without a guard. */
    public List<TrustLinkConnectionDTO> items() {
        return items == null ? List.of() : items;
    }

    /** True when another page exists. Paging is 1-based and stops at {@code totalPages}. */
    public boolean hasMorePages() {
        return page < totalPages;
    }
}
