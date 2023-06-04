package dk.trustworks.intranet.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import dk.trustworks.intranet.model.enums.ItExpenseStatus;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.LocalDate;

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

}
