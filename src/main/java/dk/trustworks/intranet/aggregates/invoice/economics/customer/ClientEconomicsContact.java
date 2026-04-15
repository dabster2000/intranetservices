package dk.trustworks.intranet.aggregates.invoice.economics.customer;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Maps a Trustworks client contact to an e-conomic customer contact number, per Trustworks
 * company. Schema from V286__Invoice_api_migration_schema_foundation.sql (client_economics_contacts).
 * SPEC-INV-001 §5.2.1, §7.1 Phase G2.
 */
@Entity
@Table(
        name = "client_economics_contacts",
        uniqueConstraints = @UniqueConstraint(columnNames = {"client_uuid", "company_uuid", "contact_name"})
)
@Getter @Setter
public class ClientEconomicsContact {

    @Id
    @Column(name = "uuid", length = 36)
    private String uuid;

    @Column(name = "client_uuid", length = 36, nullable = false)
    private String clientUuid;

    @Column(name = "company_uuid", length = 36, nullable = false)
    private String companyUuid;

    @Column(name = "contact_name", length = 150, nullable = false)
    private String contactName;

    @Column(name = "customer_contact_number", nullable = false)
    private int customerContactNumber;

    @Column(name = "object_version", length = 36)
    private String objectVersion;

    @Column(name = "receive_einvoices", nullable = false)
    private boolean receiveEInvoices;

    @Column(name = "einvoice_id", length = 50)
    private String einvoiceId;

    @Column(name = "synced_at", nullable = false)
    private LocalDateTime syncedAt;
}
