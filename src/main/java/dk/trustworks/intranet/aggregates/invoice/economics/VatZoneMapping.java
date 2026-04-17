package dk.trustworks.intranet.aggregates.invoice.economics;

import dk.trustworks.intranet.model.Company;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Maps a currency to the e-conomic `vatZoneNumber` (Domestic / EU / Abroad).
 * Per-company overrides supported; NULL company means global default.
 *
 * SPEC-INV-001 §5.4.
 */
@Getter
@Setter
@Entity
@Table(name = "vat_zone_mapping")
public class VatZoneMapping extends PanacheEntityBase {

    @Id
    private String uuid;

    @NotNull
    @Column(name = "currency", length = 10, nullable = false)
    private String currency;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "company_uuid")
    private Company company;

    @NotNull
    @Column(name = "economics_vat_zone_number", nullable = false)
    private Integer economicsVatZoneNumber;

    @Column(name = "economics_vat_zone_name", length = 50)
    private String economicsVatZoneName;

    @NotNull
    @Column(name = "vat_rate_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal vatRatePercent;
}
