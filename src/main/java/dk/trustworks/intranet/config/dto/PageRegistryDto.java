package dk.trustworks.intranet.config.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.intranet.config.model.PageRegistry;

import java.util.Arrays;
import java.util.List;

public record PageRegistryDto(
        String pageKey,
        String pageLabel,
        boolean visible,
        String reactRoute,
        List<String> requiredRoles,
        int displayOrder,
        String section,
        String iconName,
        @JsonProperty("isExternal") boolean isExternal,
        String externalUrl
) {
    public static PageRegistryDto fromEntity(PageRegistry entity) {
        List<String> roles = entity.getRequiredRoles() != null
                ? Arrays.stream(entity.getRequiredRoles().split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(String::toUpperCase)
                        .toList()
                : List.of("USER");

        return new PageRegistryDto(
                entity.getPageKey(),
                entity.getPageLabel(),
                entity.isVisible(),
                entity.getReactRoute(),
                roles,
                entity.getDisplayOrder(),
                entity.getSection(),
                entity.getIconName(),
                entity.isExternal(),
                entity.getExternalUrl()
        );
    }
}
