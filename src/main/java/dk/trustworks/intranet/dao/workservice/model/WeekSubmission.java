package dk.trustworks.intranet.dao.workservice.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import dk.trustworks.intranet.dao.workservice.model.enums.WeekSubmissionStatus;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@EqualsAndHashCode(callSuper = false)
@Data
@Entity
@Table(name = "week_submission")
public class WeekSubmission extends PanacheEntityBase {

    @Id
    private String uuid;

    private String useruuid;

    private int year;

    @Column(name = "week_number")
    private int weekNumber;

    @Enumerated(EnumType.STRING)
    private WeekSubmissionStatus status;

    @Column(name = "submitted_at")
    @JsonSerialize(using = LocalDateTimeSerializer.class)
    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    private LocalDateTime submittedAt;

    @Column(name = "unlocked_at")
    @JsonSerialize(using = LocalDateTimeSerializer.class)
    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    private LocalDateTime unlockedAt;

    @Column(name = "unlocked_by")
    private String unlockedBy;

    @Column(name = "unlock_reason")
    private String unlockReason;

    @Column(name = "created_at")
    @JsonSerialize(using = LocalDateTimeSerializer.class)
    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    private LocalDateTime createdAt;
}
