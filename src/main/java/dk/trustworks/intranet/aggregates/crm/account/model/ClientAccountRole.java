package dk.trustworks.intranet.aggregates.crm.account.model;

import dk.trustworks.intranet.aggregates.crm.account.model.enums.AccountRoleType;
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
 * One person's role on one account (CRM spec §3.1): Responsible, or Supported by.
 *
 * <p>The unique key is {@code (client_uuid, user_uuid, role)}, so the same person can be
 * both the Responsible and a supporter without the write path having to think about it —
 * though {@code AccountService} does not create that combination.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "client_account_role")
public class ClientAccountRole extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", length = 36)
    private String uuid;

    @Column(name = "client_uuid", length = 36, nullable = false)
    private String clientUuid;

    @Column(name = "user_uuid", length = 36, nullable = false)
    private String userUuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 20, nullable = false)
    private AccountRoleType role;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 36, nullable = false)
    private String createdBy;
}
