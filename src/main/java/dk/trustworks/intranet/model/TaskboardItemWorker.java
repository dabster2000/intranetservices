package dk.trustworks.intranet.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import dk.trustworks.intranet.domain.user.entity.User;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import jakarta.persistence.*;

@Data
@NoArgsConstructor
@RequiredArgsConstructor
@Entity
@Table(name = "taskboard_item_workers")
public class TaskboardItemWorker extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy=GenerationType.IDENTITY)
    int id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "itemuuid")
    @JsonIgnore
    @NonNull
    TaskboardItem item;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "useruuid")
    @NonNull
    User user;
}
