package dk.trustworks.intranet.dao.workservice.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * Created by hans on 28/06/2017.
 */

@EqualsAndHashCode(callSuper = false)
@Data
@Entity
@Table(name = "work")
public class Work extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    private String uuid;

    @JsonSerialize(using = LocalDateSerializer.class)
    @JsonDeserialize(using = LocalDateDeserializer.class)
    private LocalDate registered;
    private double workduration;
    private String clientuuid;
    private String projectuuid;
    private String taskuuid;
    private String contractuuid;
    private String useruuid;
    private String workas;
    private double rate;
    private String comments;
    @JsonIgnore
    private boolean billable;
    private boolean paidout;

}

