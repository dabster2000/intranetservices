package dk.trustworks.intranet.contracts.dto;

import dk.trustworks.intranet.contracts.model.PricingModelDefinition;

/** One pricing model as the contract editor's select reads it (JK Team 2.0 WP5). */
public record PricingModelDTO(String code, String name, String description, boolean active) {

    public static PricingModelDTO from(PricingModelDefinition row) {
        return new PricingModelDTO(row.getCode(), row.getName(), row.getDescription(), row.isActive());
    }
}
