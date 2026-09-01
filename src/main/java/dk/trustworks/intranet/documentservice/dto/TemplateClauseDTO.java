package dk.trustworks.intranet.documentservice.dto;

import dk.trustworks.intranet.documentservice.model.enums.ClauseRenderMode;
import dk.trustworks.intranet.documentservice.model.enums.ClauseStatus;
import dk.trustworks.intranet.documentservice.model.enums.TemplateCategory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A library clause with its typed parameters and version history
 * (template-clauses spec §4.1–§4.3). Used by the Klausuler admin tab,
 * the template editor's link section and the preparer-facing pickers.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TemplateClauseDTO {

    private String uuid;
    private String clauseKey;
    private String name;
    private String description;
    private String agreementType;
    private ClauseRenderMode renderMode;
    private TemplateCategory category;
    private boolean offerOnCategory;
    private ClauseStatus status;
    private String activeVersionUuid;
    /** Version number of the active version; null when none published. */
    private Integer activeVersionNumber;
    /** Number of sent signing cases referencing this clause. */
    private long usageCount;
    @Builder.Default
    private List<TemplateClausePlaceholderDTO> placeholders = new ArrayList<>();
    @Builder.Default
    private List<ClauseVersionDTO> versions = new ArrayList<>();
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** One wording version (spec §4.2). */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ClauseVersionDTO {
        private String uuid;
        private int versionNumber;
        private String fileUuid;
        private String originalFilename;
        private String changeNote;
        private LocalDateTime publishedAt;
        private String publishedBy;
        /** Immutable: a sent case references this version (D7). */
        private boolean inUse;
        private LocalDateTime createdAt;
    }
}
