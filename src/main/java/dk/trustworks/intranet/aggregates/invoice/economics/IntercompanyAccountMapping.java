package dk.trustworks.intranet.aggregates.invoice.economics;

import dk.trustworks.intranet.model.Company;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * Maps a (debtor company x issuer company) pair to the e-conomic cost account in
 * the DEBTOR's chart of accounts for an inter-company purchase (the debtor-side
 * SupplierInvoice voucher). E.g. debtor A/S + issuer Technology -> 3050,
 * debtor A/S + issuer Cyber -> 3055.
 *
 * Spec: 2026-06-15-internal-invoice-debtor-cost-account-mapping-design.md §4.2.
 */
@Getter
@Setter
@Entity
@Table(name = "intercompany_account_mapping")
public class IntercompanyAccountMapping extends PanacheEntityBase {

    @Id
    private String uuid;

    @NotNull
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "debtor_company_uuid", nullable = false)
    private Company debtorCompany;

    @NotNull
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "issuer_company_uuid", nullable = false)
    private Company issuerCompany;

    @NotNull
    @Column(name = "economics_cost_account_number", nullable = false)
    private Integer economicsCostAccountNumber;

    @Column(name = "economics_cost_account_name", length = 100)
    private String economicsCostAccountName;
}
