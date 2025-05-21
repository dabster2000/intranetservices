package dk.trustworks.intranet.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import dk.trustworks.intranet.model.enums.BorrowedDeviceType;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.LocalDate;

@Data
@NoArgsConstructor
@Entity
@Table(name = "borrowed_device")
public class BorrowedDevice extends PanacheEntityBase {

    @Id
    private String uuid;

    private String useruuid;

    @Enumerated(EnumType.STRING)
    private BorrowedDeviceType type;

    private String description;

    private String serial;

    @Column(name = "borrowed_date")
    @JsonSerialize(using = LocalDateSerializer.class)
    @JsonDeserialize(using = LocalDateDeserializer.class)
    private LocalDate borrowedDate;

    @Column(name = "returned_date")
    @JsonSerialize(using = LocalDateSerializer.class)
    @JsonDeserialize(using = LocalDateDeserializer.class)
    private LocalDate returnedDate;

    @JsonIgnore
    public boolean isReturned() {
        return returnedDate != null;
    }
}
