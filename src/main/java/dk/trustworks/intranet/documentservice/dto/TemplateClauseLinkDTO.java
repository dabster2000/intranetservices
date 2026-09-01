package dk.trustworks.intranet.documentservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One clause offered on one template (template-clauses spec §4.4).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TemplateClauseLinkDTO {

    private String uuid;
    private String templateUuid;
    private String clauseUuid;
    private boolean preselected;
    private boolean required;
    private int displayOrder;
}
