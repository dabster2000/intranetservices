package dk.trustworks.intranet.dao.workservice.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

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
    @Column(name = "paid_out")
    @JsonSerialize(using = LocalDateTimeSerializer.class)
    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    private LocalDateTime paidOut;

    /**
     * Immutable row-creation timestamp (DB-managed: {@code DEFAULT current_timestamp()}, NO
     * {@code ON UPDATE}). Set once at INSERT by the database and never rewritten — unlike
     * {@code updated_at}, which every payout/edit UPDATE clobbers. Mapped read-only so the app
     * never writes it; the DB default is authoritative. NULL for rows created before V387.
     * Used by the time-registration compliance metric. See V387 migration.
     */
    @Column(name = "created_at", insertable = false, updatable = false)
    @JsonSerialize(using = LocalDateTimeSerializer.class)
    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    private LocalDateTime createdAt;

    @JsonIgnore
    public boolean isPaidOut() {
        return paidOut != null;
    }
}

