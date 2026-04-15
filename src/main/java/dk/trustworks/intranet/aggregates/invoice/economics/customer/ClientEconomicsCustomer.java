package dk.trustworks.intranet.aggregates.invoice.economics.customer;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Maps a Trustworks client/partner to an e-conomic customer number, per Trustworks
 * company (one e-conomic agreement per company). Schema from V286__Invoice_api_migration_schema_foundation.sql.
 * SPEC-INV-001 §5.2.
 */
@Entity
@Table(
    name = "client_economics_customer",
    uniqueConstraints = @UniqueConstraint(columnNames = {"client_uuid", "company_uuid"})
)
@Getter @Setter
public class ClientEconomicsCustomer {

    @Id
    @Column(name = "uuid", length = 36)
    private String uuid;

    @Column(name = "client_uuid", length = 36, nullable = false)
    private String clientUuid;

    @Column(name = "company_uuid", length = 36, nullable = false)
    private String companyUuid;

    @Column(name = "customer_number", nullable = false)
    private int customerNumber;

    @Column(name = "object_version", length = 36)
    private String objectVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "pairing_source", length = 20, nullable = false)
    private PairingSource pairingSource;

    @Column(name = "synced_at", nullable = false)
    private LocalDateTime syncedAt;
}
