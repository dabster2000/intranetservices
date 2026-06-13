package dk.trustworks.intranet.domain.user.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Single-row monotonic high-water counter for suggested Danløn numbers
 * (spec §4.2). Read/incremented with SELECT … FOR UPDATE by
 * {@code DanlonNumberSequenceService}. NEVER recomputed from
 * user_danlon_history, so it can never hand back a freed number.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "danlon_number_sequence")
public class DanlonNumberSequence extends PanacheEntityBase {

    public static final String DANLON = "danlon";

    @Id
    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Column(name = "next_value", nullable = false)
    private long nextValue;
}
