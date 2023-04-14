package dk.trustworks.intranet.knowledgeservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Lob;
import javax.persistence.Table;
import java.util.UUID;

@Data
@Entity
@Table(name = "faq")
public class Faq extends PanacheEntityBase {

    @Id
    private String uuid;
    private String faqgroup;
    private String title;
    @Lob
    private String content;

    public Faq() {
        this.uuid = UUID.randomUUID().toString();
    }

    public Faq(String faqgroup, String title, String content) {
        this();
        this.faqgroup = faqgroup;
        this.title = title;
        this.content = content;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public String getFaqgroup() {
        return faqgroup;
    }

    public void setFaqgroup(String faqgroup) {
        this.faqgroup = faqgroup;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    @Override
    public String toString() {
        return "faq{" +
                "uuid='" + uuid + '\'' +
                ", faqgroup='" + faqgroup + '\'' +
                ", title='" + title + '\'' +
                ", content='" + content + '\'' +
                '}';
    }
}
