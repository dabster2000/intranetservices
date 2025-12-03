package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Client Detail Table DTO - Wrapper for Table E.
 * Contains the full list of clients with detailed metrics and a total count.
 *
 * Used for:
 * - Populating the Client Portfolio Details grid
 * - CSV export functionality
 * - Pagination/filtering support (if needed in future)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClientDetailTableDTO {
    /**
     * List of all clients with TTM metrics.
     * Ordered by TTM revenue descending by default.
     */
    private List<ClientDetailDTO> clients;

    /**
     * Total number of clients in the result set.
     * Useful for pagination or summary statistics.
     */
    private int totalCount;
}
