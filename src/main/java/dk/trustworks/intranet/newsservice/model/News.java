package dk.trustworks.intranet.newsservice.model;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import dk.trustworks.intranet.newsservice.model.enums.NewsType;
import dk.trustworks.intranet.newsservice.utils.LocalDateTimeSerializer;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@NoArgsConstructor
@Entity(name = "news")
//@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
//@DiscriminatorColumn(name="news_type", discriminatorType = DiscriminatorType.STRING)
public class News extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    private String uuid;
    @JsonSerialize(using = LocalDateTimeSerializer.class)
    @Column(name = "startdate")
    private LocalDateTime startDate;
    @JsonSerialize(using = LocalDateTimeSerializer.class)
    @Column(name = "enddate")
    private LocalDateTime endDate;
    @JsonSerialize(using = LocalDateTimeSerializer.class)
    @Column(name = "eventdate")
    private LocalDateTime eventDate;
    @Enumerated(EnumType.STRING)
    @Column(name = "newstype")
    private NewsType newsType;

    private String description;
    private String text;
    private String image;
    @Column(name = "createdby")
    private String createdBy;

    @JoinColumn(name = "news_uuid")
    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER, orphanRemoval = true)
    private List<RelatedResource> relatedResources;

    public News(LocalDateTime eventDate, NewsType newsType, String createdBy, String text) {
        this.eventDate = eventDate;
        this.newsType = newsType;
        this.createdBy = createdBy;
        this.text = text;
    }
}
