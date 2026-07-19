package dk.trustworks.intranet.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import dk.trustworks.intranet.userservice.utils.LocalDateDeserializer;
import dk.trustworks.intranet.userservice.utils.LocalDateSerializer;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.LocalDate;

/**
 * A temporal practice-lead assignment (V418). Mirrors the {@code teamroles}
 * idiom: multiple concurrent leads possible, history preserved via
 * {@code enddate = null} meaning "current". Dates serialize as ISO yyyy-MM-dd
 * to match the team-membership editing contract the frontend already uses.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "practice_lead")
public class PracticeLead extends PanacheEntityBase {

    @Id
    private String uuid;

    @Column(name = "practice_code")
    private String practiceCode;

    private String useruuid;

    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    private LocalDate startdate;

    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    private LocalDate enddate;

    public PracticeLead(String uuid, String practiceCode, String useruuid, LocalDate startdate, LocalDate enddate) {
        this.uuid = uuid;
        this.practiceCode = practiceCode;
        this.useruuid = useruuid;
        this.startdate = startdate;
        this.enddate = enddate;
    }
}
