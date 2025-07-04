package dk.trustworks.intranet.appplatform.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Entity(name = "app")
public class App extends PanacheEntityBase {

    @Id
    private String uuid;

    private String name;

    @Column(name = "created")
    private LocalDateTime created;
}
