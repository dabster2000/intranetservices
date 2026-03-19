package dk.trustworks.intranet.aggregates.bugreport.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Data
@Entity
@Table(name = "bug_report_comments")
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
public class BugReportComment extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "uuid", length = 36)
    private String uuid;

    @Column(name = "report_uuid", nullable = false, length = 36)
    private String reportUuid;

    @Column(name = "author_uuid", nullable = false, length = 36)
    private String authorUuid;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "is_system", nullable = false)
    private boolean system;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    static BugReportComment create(String reportUuid, String authorUuid, String content, boolean isSystem) {
        Objects.requireNonNull(reportUuid);
        Objects.requireNonNull(authorUuid);
        Objects.requireNonNull(content);
        var comment = new BugReportComment();
        comment.uuid = UUID.randomUUID().toString();
        comment.reportUuid = reportUuid;
        comment.authorUuid = authorUuid;
        comment.content = content;
        comment.system = isSystem;
        comment.createdAt = LocalDateTime.now();
        return comment;
    }

    @PrePersist
    private void onPrePersist() {
        if (this.createdAt == null) this.createdAt = LocalDateTime.now();
    }
}
