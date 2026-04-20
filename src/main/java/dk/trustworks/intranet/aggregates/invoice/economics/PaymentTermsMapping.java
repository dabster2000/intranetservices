package dk.trustworks.intranet.aggregates.invoice.economics;

import dk.trustworks.intranet.model.Company;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * Maps a Trustworks payment term configuration (type + optional day count)
 * to the e-conomic `paymentTermsNumber` from the legacy REST API. Per-company
 * overrides are supported; a NULL company means global default.
 *
 * SPEC-INV-001 §5.3.
 */
@Getter
@Setter
@Entity
@Table(name = "payment_terms_mapping")
public class PaymentTermsMapping extends PanacheEntityBase {

    @Id
    private String uuid;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_terms_type", length = 30, nullable = false)
    private PaymentTermsType paymentTermsType;

    @Column(name = "payment_days")
    private Integer paymentDays;

    @NotNull
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "company_uuid", nullable = false)
    private Company company;

    @NotNull
    @Column(name = "economics_payment_terms_number", nullable = false)
    private Integer economicsPaymentTermsNumber;

    @Column(name = "economics_payment_terms_name", length = 50)
    private String economicsPaymentTermsName;
}
