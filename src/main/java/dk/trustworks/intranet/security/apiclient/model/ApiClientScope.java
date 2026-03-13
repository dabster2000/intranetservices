package dk.trustworks.intranet.security.apiclient.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Represents a single permission scope assigned to an API client.
 * Scopes follow the resource:action convention (e.g., invoices:read)
 * and may also include legacy role names (e.g., SYSTEM) during the
 * migration period.
 *
 * This entity is owned by the {@link ApiClient} aggregate root and
 * must not be accessed directly from outside the aggregate.
 */
@Entity
@Table(name = "api_client_scopes", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"client_uuid", "scope"})
})
@Getter
@Setter
@NoArgsConstructor
public class ApiClientScope {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_uuid", nullable = false, length = 36, insertable = false, updatable = false)
    private String clientUuid;

    @Column(name = "scope", nullable = false, length = 100)
    private String scope;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_uuid", nullable = false)
    private ApiClient client;

    public ApiClientScope(ApiClient client, String scope) {
        this.client = client;
        this.clientUuid = client.getUuid();
        this.scope = scope;
    }
}
