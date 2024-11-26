package dk.trustworks.intranet.userservice.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import dk.trustworks.intranet.userservice.model.enums.VacationType;
import dk.trustworks.intranet.userservice.utils.LocalDateDeserializer;
import dk.trustworks.intranet.userservice.utils.LocalDateSerializer;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "vacation")
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
public class Vacation extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    private String uuid; // UUID as the primary key

    private String useruuid;

    @Enumerated(EnumType.STRING)
    private VacationType vacationType;

    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    private LocalDate date;

    @Column(name = "vacation_earned")
    private double vacationEarned;


}