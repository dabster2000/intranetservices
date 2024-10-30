package dk.trustworks.intranet.fileservice.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.*;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.LocalDate;


@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Data
@NoArgsConstructor
@RequiredArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "files")
public class File extends PanacheEntityBase {

    @Id
    @NonNull
    @EqualsAndHashCode.Include
    private String uuid;

    @NonNull
    private String relateduuid;

    @NonNull
    private String type;

    private String name;

    private String filename;

    @JsonSerialize(using = LocalDateSerializer.class)
    @JsonDeserialize(using = LocalDateDeserializer.class)
    private LocalDate uploaddate;

    @Transient
    private byte[] file;

    @Override
    public String toString() {
        return "File{" +
                "uuid='" + uuid + '\'' +
                ", relateduuid='" + relateduuid + '\'' +
                ", type='" + type + '\'' +
                ", name='" + name + '\'' +
                ", filename='" + filename + '\'' +
                ", uploaddate=" + uploaddate +
                '}';
    }
}
