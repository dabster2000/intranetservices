package dk.trustworks.intranet.aggregates.invoice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Maps a recurring phantom clientname (an e-conomic account label) to a real
 * client, or marks it excluded. One row per distinct label. Maintained via the
 * admin review queue; read by PhantomAttributionService during derivation.
 *
 * <p>Active-record Panache entity; mirrors {@link InvoiceItemAttribution}
 * (public fields, Lombok accessors, timestamps set in the constructor).
 */
@Getter
@Setter
@Entity
@Table(name = "phantom_client_map")
public class PhantomClientMap extends PanacheEntityBase {

    @Id
    public String clientname;

    @Column(name = "client_uuid")
    public String clientUuid;

    public boolean excluded;

    public String note;

    @Column(name = "confirmed_by")
    public String confirmedBy;

    @Column(name = "confirmed_at")
    public LocalDateTime confirmedAt;

    @Column(name = "created_at", updatable = false)
    public LocalDateTime createdAt;

    @Column(name = "updated_at")
    public LocalDateTime updatedAt;

    public PhantomClientMap() {
    }

    public PhantomClientMap(String clientname,
                            String clientUuid,
                            boolean excluded,
                            String note,
                            String confirmedBy) {
        LocalDateTime now = LocalDateTime.now();
        this.clientname = clientname;
        this.clientUuid = clientUuid;
        this.excluded = excluded;
        this.note = note;
        this.confirmedBy = confirmedBy;
        this.confirmedAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }
}
