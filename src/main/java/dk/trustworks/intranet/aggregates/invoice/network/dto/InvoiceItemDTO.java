package dk.trustworks.intranet.aggregates.invoice.network.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Invoice line item DTO (shared between V1 and V2 APIs).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceItemDto {
    private String uuid;
    private String consultantuuid;
    private String itemname;
    private String description;
    private Double rate;
    private Double hours;
    private Integer position;
    private String origin;
    private String calculationRef;
    private String ruleId;
    private String label;

    /**
     * Convenience constructor for basic invoice items.
     */
    public InvoiceItemDto(String itemname, double hours, double rate, String description) {
        this.itemname = itemname;
        this.hours = hours;
        this.rate = rate;
        this.description = description;
    }
}
