package dk.trustworks.intranet.dao.crm.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

/**
 * JPA Entity for the client activity log.
 * Tracks field-level changes to client-related entities (clients, contracts,
 * clientdata, contract consultants, contract projects).
 *
 * <p>This entity is INSERT-ONLY from application code. No updates or deletes
 * should be performed on activity log entries.
 */
@Entity
@Table(name = "client_activity_log")
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
public class ClientActivityLog extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "client_uuid", nullable = false, length = 36)
    private String clientUuid;

    @Column(name = "entity_type", nullable = false, length = 50)
    private String entityType;

    @Column(name = "entity_uuid", nullable = false, length = 36)
    private String entityUuid;

    @Column(name = "entity_name", length = 255)
    private String entityName;

    @Column(nullable = false, length = 20)
    private String action;

    @Column(name = "field_name", length = 100)
    private String fieldName;

    @Column(name = "old_value", columnDefinition = "TEXT")
    private String oldValue;

    @Column(name = "new_value", columnDefinition = "TEXT")
    private String newValue;

    @Column(name = "modified_by", nullable = false, length = 36)
    private String modifiedBy;

    @Column(name = "modified_at", nullable = false)
    private LocalDateTime modifiedAt;

    // --- Entity type constants ---
    public static final String TYPE_CLIENT = "CLIENT";
    public static final String TYPE_CONTRACT = "CONTRACT";
    public static final String TYPE_CLIENTDATA = "CLIENTDATA";
    public static final String TYPE_CONTRACT_CONSULTANT = "CONTRACT_CONSULTANT";
    public static final String TYPE_CONTRACT_PROJECT = "CONTRACT_PROJECT";

    // --- Action constants ---
    public static final String ACTION_CREATED = "CREATED";
    public static final String ACTION_MODIFIED = "MODIFIED";
    public static final String ACTION_DELETED = "DELETED";

    // --- Panache finder methods ---

    public static List<ClientActivityLog> findByClientUuid(String clientUuid, int limit) {
        return find("clientUuid = ?1 ORDER BY modifiedAt DESC", clientUuid)
                .page(0, limit)
                .list();
    }

    public static List<ClientActivityLog> findByEntityTypeAndUuid(String entityType, String entityUuid, int limit) {
        return find("entityType = ?1 AND entityUuid = ?2 ORDER BY modifiedAt DESC", entityType, entityUuid)
                .page(0, limit)
                .list();
    }
}
