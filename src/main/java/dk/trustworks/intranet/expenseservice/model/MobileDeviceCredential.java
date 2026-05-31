package dk.trustworks.intranet.expenseservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "mobile_device_credential")
public class MobileDeviceCredential extends PanacheEntityBase {

    @Id
    public String uuid = UUID.randomUUID().toString();

    @Column(name = "useruuid", nullable = false)
    public String userUuid;

    @Column(name = "credential_id", nullable = false)
    public String credentialId;

    @Column(name = "public_key", nullable = false, columnDefinition = "TEXT")
    public String publicKey;

    @Column(name = "sign_count", nullable = false)
    public long signCount = 0L;

    @Column(name = "device_label")
    public String deviceLabel;

    @Column(name = "transports")
    public String transports;

    @Column(name = "credential_epoch", nullable = false)
    public int credentialEpoch = 0;

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "last_used_at")
    public LocalDateTime lastUsedAt;

    @Column(name = "revoked_at")
    public LocalDateTime revokedAt;
}
