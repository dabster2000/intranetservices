package dk.trustworks.intranet.knowledgeservice.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import dk.trustworks.intranet.knowledgeservice.model.enums.CKOExpenseStatus;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "cko_expense")
public class CKOExpense extends PanacheEntityBase {

    @Id
    private String uuid;

    @JsonSerialize(using = LocalDateSerializer.class)
    @JsonDeserialize(using = LocalDateDeserializer.class)
    private LocalDate eventdate;

    private String useruuid;

    private String description;

    private int price;

    private String comment;

    //private double days;

    //@Enumerated(EnumType.STRING)
    //private CKOExpenseType type;

    @Column(name = "status")
    @Enumerated(EnumType.STRING)
    private CKOExpenseStatus status;

    //@Column(name = "purpose")
    //@Enumerated(EnumType.STRING)
    //private CKOExpensePurpose purpose;

    //private double rating;

    //private String rating_comment;

    //private int certification;
    //private int certified;

}

