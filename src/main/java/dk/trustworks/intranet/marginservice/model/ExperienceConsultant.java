package dk.trustworks.intranet.marginservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Created by hans on 23/06/2017.
 */

@Entity
@Table(name = "experience_consultants")
public class ExperienceConsultant extends PanacheEntityBase {

    @Id
    private int id;
    private String useruuid;
    @Column(name = "seniority_year")
    private int seniority;

    public ExperienceConsultant() {
    }

    public ExperienceConsultant(String useruuid, int seniority) {
        this.useruuid = useruuid;
        this.seniority = seniority;
    }

    public int getId() {
        return id;
    }

    public String getUseruuid() {
        return useruuid;
    }

    public void setUseruuid(String useruuid) {
        this.useruuid = useruuid;
    }

    public int getSeniority() {
        return seniority;
    }

    public void setSeniority(int seniority) {
        this.seniority = seniority;
    }
}
