package dk.trustworks.intranet.config.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.intranet.config.model.PageMigration;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * DTO for page migration data returned to frontend applications.
 *
 * This record represents a page's migration status and is consumed by
 * both Vaadin and React frontends to render navigation menus.
 */
public record PageMigrationDto(
        String pageKey,
        String pageLabel,
        boolean migrated,
        String reactRoute,
        String vaadinRoute,
        String vaadinViewClass,
        List<String> requiredRoles,
        int displayOrder,
        String section,
        String iconName,
        @JsonProperty("isExternal") boolean isExternal,
        String externalUrl,
        LocalDateTime migratedAt
) {
    /**
     * Create DTO from entity.
     *
     * @param entity the page migration entity
     * @return the DTO
     */
    public static PageMigrationDto fromEntity(PageMigration entity) {
        List<String> roles = entity.getRequiredRoles() != null
                ? Arrays.asList(entity.getRequiredRoles().split(","))
                : List.of("USER");

        return new PageMigrationDto(
                entity.getPageKey(),
                entity.getPageLabel(),
                entity.isMigrated(),
                entity.getReactRoute(),
                entity.getVaadinRoute(),
                entity.getVaadinViewClass(),
                roles,
                entity.getDisplayOrder(),
                entity.getSection(),
                entity.getIconName(),
                entity.isExternal(),
                entity.getExternalUrl(),
                entity.getMigratedAt()
        );
    }
}
