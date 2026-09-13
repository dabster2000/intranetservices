package dk.trustworks.intranet.aggregates.crm.account.model;

import dk.trustworks.intranet.aggregates.crm.account.model.enums.DomainSource;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * An e-mail domain belonging to a client (CRM spec §3.2) — how a calendar meeting finds
 * its account.
 *
 * <p><b>{@link #domain} is globally unique.</b> If two clients could claim the same
 * domain the same meeting would land on two accounts and every meeting count on both
 * would be wrong in a way nobody would ever notice. The database enforces it; the service
 * turns the resulting constraint violation into a 409 that names the other client.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "client_domain")
public class ClientDomain extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", length = 36)
    private String uuid;

    @Column(name = "client_uuid", length = 36, nullable = false)
    private String clientUuid;

    /** Lower-cased, no {@code @} and no scheme. Unique across every client. */
    @Column(name = "domain", length = 190, nullable = false)
    private String domain;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 10, nullable = false)
    private DomainSource source;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 36, nullable = false)
    private String createdBy;
}
