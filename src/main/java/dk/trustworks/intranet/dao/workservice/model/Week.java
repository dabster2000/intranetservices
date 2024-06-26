package dk.trustworks.intranet.dao.workservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Transient;
import lombok.Data;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Created by hans on 28/06/2017.
 */

@Data
@Entity(name = "week")
@Table(name = "week")
public class Week extends PanacheEntityBase {
    @Id
    private String uuid;
    private String taskuuid;
    private String useruuid;
    private int weeknumber;
    private int year;
    private int sorting;
    private String workas;
    @Transient
    private boolean isCommentable;
}
