package dk.trustworks.intranet.aggregates.invoice.economics.book;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter; import lombok.Setter;

/** Subset of GET /self for capability checks (Phase I uses canSendElectronicInvoice). */
@Getter @Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class EconomicsAgreementSelf {
    private String companyVatNumber;
    private Integer agreementNumber;
    private Boolean canSendElectronicInvoice;
}
