package dk.trustworks.intranet.config.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Response DTO for the migration registry endpoint.
 *
 * Contains the list of all page migrations along with metadata
 * about the registry itself.
 */
public record MigrationRegistryResponse(
        List<PageMigrationDto> pages,
        String version,
        LocalDateTime generatedAt
) {
    /**
     * Create a new response with current timestamp.
     *
     * @param pages   the list of page migrations
     * @param version a version identifier (can be used for cache busting)
     * @return the response
     */
    public static MigrationRegistryResponse of(List<PageMigrationDto> pages, String version) {
        return new MigrationRegistryResponse(pages, version, LocalDateTime.now());
    }
}
