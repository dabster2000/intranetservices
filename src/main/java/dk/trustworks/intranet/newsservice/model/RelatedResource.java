package dk.trustworks.intranet.newsservice.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;

import javax.persistence.*;

@Data
@Entity(name = "related_resource")
public class RelatedResource extends PanacheEntityBase {

    @Id
    private String uuid;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "news_uuid")
    private News news;

    @Column(name = "related_resource_uuid")
    private String relatedResourceUuid;
}
