package dk.trustworks.intranet.cultureservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

import jakarta.persistence.*;

/**
 * Created by hans on 23/06/2017.
 */
@Entity
@Table(name = "keypurpose")
public class KeyPurpose extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    private int id;

    private String useruuid;

    private int num;

    private String description;

    @Column(name = "meeting_notes")
    private String meetingNotes;

    public KeyPurpose() {
    }

    public KeyPurpose(String useruuid, int num, String description) {
        this.useruuid = useruuid;
        this.num = num;
        this.description = description;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getNum() {
        return num;
    }

    public void setNum(int num) {
        this.num = num;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getMeetingNotes() {
        return meetingNotes;
    }

    public void setMeetingNotes(String meetingNotes) {
        this.meetingNotes = meetingNotes;
    }

    @Override
    public String toString() {
        return "KeyPurpose{" +
                "id=" + id +
                ", user=" + useruuid +
                ", num=" + num +
                ", description='" + description + '\'' +
                '}';
    }

    public String getUseruuid() {
        return useruuid;
    }

    public void setUseruuid(String useruuid) {
        this.useruuid = useruuid;
    }
}
