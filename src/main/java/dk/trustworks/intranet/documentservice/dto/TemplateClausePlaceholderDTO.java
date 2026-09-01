package dk.trustworks.intranet.documentservice.dto;

import dk.trustworks.intranet.documentservice.model.enums.DataSource;
import dk.trustworks.intranet.documentservice.model.enums.FieldType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One typed parameter on a clause (template-clauses spec §4.3). Mirrors
 * {@link TemplatePlaceholderDTO} plus {@code registryField} — the Phase 3
 * registry column the value maps into.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TemplateClausePlaceholderDTO {

    private String uuid;
    private String placeholderKey;
    private String label;
    private FieldType fieldType;
    private boolean required;
    private int displayOrder;
    private String defaultValue;
    private String helpText;
    private DataSource source;
    private String sourceField;
    /** AMOUNT / CURRENCY / VALID_FROM / VALID_TO / EFFECTIVE_DATE; null = JSON only. */
    private String registryField;
    private String fieldGroup;
    private String validationRules;
    private String selectOptions;
}
