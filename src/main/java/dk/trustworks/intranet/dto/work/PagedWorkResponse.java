package dk.trustworks.intranet.dto.work;

import dk.trustworks.intranet.dao.workservice.model.WorkFull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/**
 * Response DTO for paginated work data queries.
 * Provides work records along with pagination metadata.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Paginated response containing work records and pagination metadata")
public class PagedWorkResponse {

    @Schema(description = "List of work records for the current page", required = true)
    private List<WorkFull> content;

    @Schema(description = "Current page number (0-based)", example = "0", required = true)
    private int page;

    @Schema(description = "Number of records per page", example = "100", required = true)
    private int size;

    @Schema(description = "Total number of work records across all pages", example = "1534", required = true)
    private long totalElements;

    @Schema(description = "Total number of pages available", example = "16", required = true)
    private int totalPages;

    @Schema(description = "Indicates if this is the first page", example = "true")
    private boolean first;

    @Schema(description = "Indicates if this is the last page", example = "false")
    private boolean last;

    @Schema(description = "Indicates if there is a next page available", example = "true")
    private boolean hasNext;

    @Schema(description = "Indicates if there is a previous page available", example = "false")
    private boolean hasPrevious;

    /**
     * Factory method to create a PagedWorkResponse from work data and pagination info.
     */
    public static PagedWorkResponse of(List<WorkFull> content, int page, int size, long totalElements) {
        int totalPages = (int) Math.ceil((double) totalElements / size);
        return PagedWorkResponse.builder()
                .content(content)
                .page(page)
                .size(size)
                .totalElements(totalElements)
                .totalPages(totalPages)
                .first(page == 0)
                .last(page >= totalPages - 1)
                .hasNext(page < totalPages - 1)
                .hasPrevious(page > 0)
                .build();
    }
}