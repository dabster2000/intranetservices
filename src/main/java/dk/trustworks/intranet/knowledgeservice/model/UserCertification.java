package dk.trustworks.intranet.knowledgeservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Transient;
import java.util.UUID;

@Data
@NoArgsConstructor
@Entity
@Table(name = "user_certifications")
public class UserCertification extends PanacheEntityBase {

    @Id
    private String uuid;
    private String useruuid;
    private String certificationuuid;
    @Transient private String certification;

    public UserCertification(String useruuid, String certificationuuid) {
        this.uuid = UUID.randomUUID().toString();
        this.useruuid = useruuid;
        this.certificationuuid = certificationuuid;
    }
}
