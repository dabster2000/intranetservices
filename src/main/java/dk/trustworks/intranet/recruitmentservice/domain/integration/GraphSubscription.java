package dk.trustworks.intranet.recruitmentservice.domain.integration;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "recruitment_graph_subscription")
public class GraphSubscription extends PanacheEntityBase {

    @Id
    @Column(name = "uuid")
    public String uuid;

    @Column(name = "subscription_id", nullable = false, length = 160, unique = true)
    public String subscriptionId;

    @Column(name = "resource", nullable = false, length = 255)
    public String resource;

    @Column(name = "expires_at", nullable = false)
    public LocalDateTime expiresAt;

    @Column(name = "client_state_hmac", nullable = false, length = 64)
    public String clientStateHmac;

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    public static GraphSubscription create(String subscriptionId, String resource,
                                           LocalDateTime expiresAt, String clientStateHmac) {
        GraphSubscription g = new GraphSubscription();
        g.uuid = UUID.randomUUID().toString();
        g.subscriptionId = subscriptionId;
        g.resource = resource;
        g.expiresAt = expiresAt;
        g.clientStateHmac = clientStateHmac;
        g.createdAt = LocalDateTime.now();
        return g;
    }
}
