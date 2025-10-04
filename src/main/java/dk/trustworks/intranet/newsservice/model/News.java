package dk.trustworks.intranet.newsservice.model;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import dk.trustworks.intranet.newsservice.model.enums.NewsType;
import dk.trustworks.intranet.newsservice.utils.LocalDateTimeSerializer;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.proxy.HibernateProxy;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Getter
@Setter
@ToString
@Entity(name = "news")
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

    public News() {
    }

    public News(LocalDateTime eventDate, NewsType newsType, String createdBy, String text) {
        this.eventDate = eventDate;
        this.newsType = newsType;
        this.createdBy = createdBy;
        this.text = text;
    }

    @Override
    public final boolean equals(Object o) {
        if (this == o) return true;
        if (o == null) return false;
        Class<?> oEffectiveClass = o instanceof HibernateProxy ? ((HibernateProxy) o).getHibernateLazyInitializer().getPersistentClass() : o.getClass();
        Class<?> thisEffectiveClass = this instanceof HibernateProxy ? ((HibernateProxy) this).getHibernateLazyInitializer().getPersistentClass() : this.getClass();
        if (thisEffectiveClass != oEffectiveClass) return false;
        News news = (News) o;
        return getUuid() != null && Objects.equals(getUuid(), news.getUuid());
    }

    @Override
    public final int hashCode() {
        return this instanceof HibernateProxy ? ((HibernateProxy) this).getHibernateLazyInitializer().getPersistentClass().hashCode() : getClass().hashCode();
    }
}
