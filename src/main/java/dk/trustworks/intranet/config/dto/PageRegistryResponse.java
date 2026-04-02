package dk.trustworks.intranet.config.dto;

import java.time.LocalDateTime;
import java.util.List;

public record PageRegistryResponse(
        List<PageRegistryDto> pages,
        String version,
        LocalDateTime generatedAt
) {
    public static PageRegistryResponse of(List<PageRegistryDto> pages, String version) {
        return new PageRegistryResponse(pages, version, LocalDateTime.now());
    }
}
