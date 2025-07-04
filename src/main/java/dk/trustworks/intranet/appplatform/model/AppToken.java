package dk.trustworks.intranet.appplatform.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Entity(name = "app_token")
public class AppToken extends PanacheEntityBase {

    @Id
    private String uuid;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "appuuid")
    private App app;

    @Column(name = "token_hash")
    private String tokenHash;

    private boolean revoked;

    @Column(name = "created")
    private LocalDateTime created;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    public static AppToken findActiveToken(String appUuid) {
        return find("app.uuid = ?1 and revoked = false", appUuid).firstResult();
    }
}
