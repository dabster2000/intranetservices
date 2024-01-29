package dk.trustworks.intranet.knowledgeservice.model;

import dk.trustworks.intranet.userservice.model.User;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

@Data
@Entity
@Table(name = "cko_course_participants")
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
public class CkoCourseParticipant extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    private String uuid;
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "courseuuid")
    private CkoCourse ckoCourse;
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "useruuid")
    private User user;
    private String status;
    @Column(name = "application_date")
    private LocalDate applicationDate;

}
