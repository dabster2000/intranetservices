package dk.trustworks.intranet.cultureservice.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.LocalDate;
import java.util.List;

import static javax.persistence.CascadeType.ALL;
import static javax.persistence.FetchType.EAGER;

@Data
@NoArgsConstructor
@Entity
@Table(name = "lessons")
public class Lesson extends PanacheEntityBase {

    @Id
    private String uuid;
    private String useruuid;
    private String projectuuid;
    @ManyToOne(fetch = EAGER)
    @JoinColumn(name = "roleuuid")
    private LessonRole role;
    private String note;
    @JsonSerialize(using = LocalDateSerializer.class)
    @JsonDeserialize(using = LocalDateDeserializer.class)
    private LocalDate startdate;
    @JsonSerialize(using = LocalDateSerializer.class)
    @JsonDeserialize(using = LocalDateDeserializer.class)
    private LocalDate enddate;
    @JsonSerialize(using = LocalDateSerializer.class)
    @JsonDeserialize(using = LocalDateDeserializer.class)
    private LocalDate registered;
    @OneToMany(cascade = ALL, fetch = EAGER)
    @JoinColumn(name = "lr_uuid")
    @JsonProperty("performanceResults")
    private List<PerformanceResult> performanceResults;

}
