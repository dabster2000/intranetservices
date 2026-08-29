package dk.trustworks.intranet.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import dk.trustworks.intranet.model.enums.ItExpenseStatus;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Entity
@Table(name = "itbudget")
public class ItExpenseItem extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    private int id;

    private String useruuid;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name="category_id")
    private ItExpenseCategory category;

    private String description;

    private int price;

    @Column(name = "status")
    @Enumerated(EnumType.STRING)
    private ItExpenseStatus status;

    @JsonSerialize(using = LocalDateSerializer.class)
    @JsonDeserialize(using = LocalDateDeserializer.class)
    private LocalDate invoicedate;

    /**
     * Written by the column default, never by us. {@code @Generated} makes
     * Hibernate read the value back after the insert — without it the row the
     * POST returns carries a null createdAt, and the BFF's 48-hour edit window
     * is evaluated against it.
     */
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

}
