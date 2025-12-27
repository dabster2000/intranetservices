package dk.trustworks.intranet.config.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Entity representing a page migration status for React/Vaadin coexistence.
 *
 * This table tracks which pages have been migrated from Vaadin to React,
 * allowing dynamic routing without app rebuilds. Both frontends fetch
 * this registry to determine navigation links.
 */
@Entity
@Table(name = "page_migration")
public class PageMigration extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "page_key", unique = true, nullable = false, length = 50)
    private String pageKey;

    @Column(name = "page_label", nullable = false, length = 100)
    private String pageLabel;

    @Column(name = "is_migrated", nullable = false)
    private boolean migrated;

    @Column(name = "react_route", nullable = false, length = 100)
    private String reactRoute;

    @Column(name = "vaadin_route", nullable = false, length = 100)
    private String vaadinRoute;

    @Column(name = "vaadin_view_class", length = 150)
    private String vaadinViewClass;

    @Column(name = "required_roles", nullable = false, length = 255)
    private String requiredRoles = "USER";

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

    @Column(name = "migrated_at")
    private LocalDateTime migratedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    // Default constructor for JPA
    public PageMigration() {
    }

    // Getters and Setters

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getPageKey() {
        return pageKey;
    }

    public void setPageKey(String pageKey) {
        this.pageKey = pageKey;
    }

    public String getPageLabel() {
        return pageLabel;
    }

    public void setPageLabel(String pageLabel) {
        this.pageLabel = pageLabel;
    }

    public boolean isMigrated() {
        return migrated;
    }

    public void setMigrated(boolean migrated) {
        this.migrated = migrated;
    }

    public String getReactRoute() {
        return reactRoute;
    }

    public void setReactRoute(String reactRoute) {
        this.reactRoute = reactRoute;
    }

    public String getVaadinRoute() {
        return vaadinRoute;
    }

    public void setVaadinRoute(String vaadinRoute) {
        this.vaadinRoute = vaadinRoute;
    }

    public String getVaadinViewClass() {
        return vaadinViewClass;
    }

    public void setVaadinViewClass(String vaadinViewClass) {
        this.vaadinViewClass = vaadinViewClass;
    }

    public String getRequiredRoles() {
        return requiredRoles;
    }

    public void setRequiredRoles(String requiredRoles) {
        this.requiredRoles = requiredRoles;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    public void setDisplayOrder(int displayOrder) {
        this.displayOrder = displayOrder;
    }

    public String getSection() {
        return section;
    }

    public void setSection(String section) {
        this.section = section;
    }

    public String getIconName() {
        return iconName;
    }

    public void setIconName(String iconName) {
        this.iconName = iconName;
    }

    public boolean isExternal() {
        return isExternal;
    }

    public void setExternal(boolean external) {
        isExternal = external;
    }

    public String getExternalUrl() {
        return externalUrl;
    }

    public void setExternalUrl(String externalUrl) {
        this.externalUrl = externalUrl;
    }

    public LocalDateTime getMigratedAt() {
        return migratedAt;
    }

    public void setMigratedAt(LocalDateTime migratedAt) {
        this.migratedAt = migratedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public String toString() {
        return "PageMigration{" +
                "id=" + id +
                ", pageKey='" + pageKey + '\'' +
                ", pageLabel='" + pageLabel + '\'' +
                ", migrated=" + migrated +
                ", reactRoute='" + reactRoute + '\'' +
                ", vaadinRoute='" + vaadinRoute + '\'' +
                ", section='" + section + '\'' +
                ", displayOrder=" + displayOrder +
                '}';
    }
}
