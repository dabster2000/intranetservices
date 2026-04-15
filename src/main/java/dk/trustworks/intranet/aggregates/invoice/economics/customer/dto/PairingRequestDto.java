package dk.trustworks.intranet.aggregates.invoice.economics.customer.dto;

import dk.trustworks.intranet.aggregates.invoice.economics.customer.PairingSource;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

/**
 * Request body for {@code POST /economics/customers/pair}.
 *
 * <p>{@code pairingSource} is supplied by the caller so the admin UI can
 * distinguish a {@code MANUAL} pairing from a {@code CREATED} one (where
 * the admin first created a new e-conomic customer and then paired it).
 */
@Getter @Setter
public class PairingRequestDto {
    @NotBlank private String clientUuid;
    @NotBlank private String companyUuid;
    @NotNull  @Positive private Integer economicsCustomerNumber;
    @NotNull  private PairingSource pairingSource;
}
