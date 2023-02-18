package dk.trustworks.intranet.userservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Entity
@Table(name = "passwordchanges")
public class PasswordChange extends PanacheEntityBase {

    @Id
    private String uuid;
    private String useruuid;
    private String source;
    private String password;
    private LocalDateTime created;

    public PasswordChange(String uuid, String useruuid, String password, String source) {
        this.uuid = uuid;
        this.useruuid = useruuid;
        this.password = password;
        this.source = source;
        created = LocalDateTime.now();
    }
}
