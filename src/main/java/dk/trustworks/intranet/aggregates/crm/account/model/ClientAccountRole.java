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
 * One person's role on one account: Supported by, or a member of the account team.
 *
 * <p>Since V598 this is the only table that answers "who is on this account". The owner
 * is {@code client.accountmanager} and is never written here; the {@code ACCOUNT_TEAM}
 * bubbles' membership was migrated into {@code MEMBER} rows and those bubbles stopped
 * being a store.
 *
 * <p>The unique key is {@code (client_uuid, user_uuid, role)}, but
 * {@code AccountService.replaceRoles} refuses the same person in both lists and refuses
 * the account's owner in either — a person appears on an account once.
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
