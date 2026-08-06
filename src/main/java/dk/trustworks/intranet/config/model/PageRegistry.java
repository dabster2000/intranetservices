package dk.trustworks.intranet.config.model;

import dk.trustworks.intranet.model.Auditable;
import dk.trustworks.intranet.security.AuditEntityListener;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "page_registry")
@EntityListeners(AuditEntityListener.class)
public class PageRegistry extends PanacheEntityBase implements Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "page_key", unique = true, nullable = false, length = 50)
    private String pageKey;

    @Column(name = "page_label", nullable = false, length = 100)
    private String pageLabel;

    @Column(name = "is_visible", nullable = false)
    private boolean visible;

    @Column(name = "react_route", nullable = false, length = 100)
    private String reactRoute;

    @Column(name = "required_roles", nullable = false, length = 255)
    private String requiredRoles = "USER";

    /**
     * Permission gating page entry (Phase 6). NULL means "fall back to requiredRoles" —
     * deliberately so for universal USER pages and the dark ATS rows; requiredRoles is
     * dual-read until Phase 14.
     */
    @Column(name = "required_permission", length = 64)
    private String requiredPermission;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "section", length = 50)
    private String section;

    @Column(name = "icon_name", length = 50)
    private String iconName;

    @Column(name = "is_external", nullable = false)
    private boolean isExternal = false;

    @Column(name = "external_url", length = 500)
    private String externalUrl;

    // Timestamps stay DB-managed (insertable/updatable = false): the columns have
    // defaults and existing rows rely on them. The Auditable setters below satisfy
    // the interface but only touch the in-memory value — the listener's real
    // contribution on this entity is the actor columns (V469, Phase 7 task 7.1).
    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    @Column(name = "created_by", updatable = false)
    private String createdBy;

    @Column(name = "modified_by")
    private String modifiedBy;

    public PageRegistry() {}

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public String getPageKey() { return pageKey; }
    public void setPageKey(String pageKey) { this.pageKey = pageKey; }

    public String getPageLabel() { return pageLabel; }
    public void setPageLabel(String pageLabel) { this.pageLabel = pageLabel; }

    public boolean isVisible() { return visible; }
    public void setVisible(boolean visible) { this.visible = visible; }

    public String getReactRoute() { return reactRoute; }
    public void setReactRoute(String reactRoute) { this.reactRoute = reactRoute; }

    public String getRequiredRoles() { return requiredRoles; }
    public void setRequiredRoles(String requiredRoles) { this.requiredRoles = requiredRoles; }

    public String getRequiredPermission() { return requiredPermission; }
    public void setRequiredPermission(String requiredPermission) { this.requiredPermission = requiredPermission; }

    public int getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(int displayOrder) { this.displayOrder = displayOrder; }

    public String getSection() { return section; }
    public void setSection(String section) { this.section = section; }

    public String getIconName() { return iconName; }
    public void setIconName(String iconName) { this.iconName = iconName; }

    public boolean isExternal() { return isExternal; }
    public void setExternal(boolean external) { isExternal = external; }

    public String getExternalUrl() { return externalUrl; }
    public void setExternalUrl(String externalUrl) { this.externalUrl = externalUrl; }

    @Override
    public LocalDateTime getCreatedAt() { return createdAt; }
    @Override
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    @Override
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    @Override
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    @Override
    public String getCreatedBy() { return createdBy; }
    @Override
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    @Override
    public String getModifiedBy() { return modifiedBy; }
    @Override
    public void setModifiedBy(String modifiedBy) { this.modifiedBy = modifiedBy; }

    @Override
    public String toString() {
        return "PageRegistry{" +
                "id=" + id +
                ", pageKey='" + pageKey + '\'' +
                ", pageLabel='" + pageLabel + '\'' +
                ", visible=" + visible +
                ", reactRoute='" + reactRoute + '\'' +
                ", section='" + section + '\'' +
                ", displayOrder=" + displayOrder +
                '}';
    }
}
