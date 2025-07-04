package dk.trustworks.intranet.appplatform.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@Entity(name = "app_user_role")
public class AppUserRole extends PanacheEntityBase {

    @Id
    private String uuid;

    @Column(name = "appuuid")
    private String appUuid;

    @Column(name = "useruuid")
    private String userUuid;

    @Enumerated(EnumType.STRING)
    private AppRole role;

    public static java.util.List<AppUserRole> findByUser(String userUuid) {
        return list("userUuid", userUuid);
    }
}
