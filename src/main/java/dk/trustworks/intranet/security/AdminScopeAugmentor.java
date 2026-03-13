package dk.trustworks.intranet.security;

import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Set;

/**
 * Augments identities that carry the {@code admin:*} scope by granting
 * every defined scope in the system.
 * <p>
 * This ensures that admin-level API clients (like the BFF) pass all
 * {@code @RolesAllowed} checks without needing to enumerate every scope
 * in their JWT claims. The augmentor runs once per request during
 * Quarkus security identity resolution.
 * <p>
 * The scope list must be kept in sync with the full scope catalogue
 * defined in the API client management system.
 */
@ApplicationScoped
public class AdminScopeAugmentor implements SecurityIdentityAugmentor {

    static final String ADMIN_WILDCARD = "admin:*";

    /**
     * All 66 scopes in the system. When a client holds {@code admin:*},
     * every scope below is added to its identity so that fine-grained
     * {@code @RolesAllowed} annotations pass without modification.
     */
    static final Set<String> ALL_SCOPES = Set.of(
            // Users & HR
            "users:read", "users:write",
            "userstatus:read", "userstatus:write",
            "salaries:read", "salaries:write",

            // CRM
            "crm:read", "crm:write",

            // Contracts
            "contracts:read", "contracts:write",

            // Time registration
            "timeregistration:read", "timeregistration:write",

            // Invoicing
            "invoices:read", "invoices:write",

            // Expenses
            "expenses:read", "expenses:write",

            // Revenue & utilization
            "revenue:read",
            "utilization:read",
            "availability:read",

            // Budgets
            "budgets:read", "budgets:write",

            // Accounting
            "accounting:read", "accounting:write",

            // Bonuses
            "bonus:read", "bonus:write",
            "partnerbonus:read", "partnerbonus:write",
            "teamleadbonus:read", "teamleadbonus:write",

            // Signing & documents
            "signing:read", "signing:write",
            "dashboard:read",
            "documents:read", "documents:write",

            // Knowledge
            "knowledge:read", "knowledge:write",

            // Teams
            "teams:read", "teams:write",

            // Career level
            "careerlevel:read", "careerlevel:write",

            // Vacation
            "vacation:read", "vacation:write",

            // Companies
            "companies:read",

            // Capacity
            "capacity:read",

            // Notifications
            "notifications:write",

            // Consultant
            "consultant:read", "consultant:write",

            // Conference
            "conference:read", "conference:write",

            // Lunch
            "lunch:read", "lunch:write",

            // News
            "news:read", "news:write",

            // Taskboard
            "taskboard:read", "taskboard:write",

            // Devices
            "devices:read", "devices:write",

            // Transportation
            "transportation:read", "transportation:write",

            // DST statistics
            "dststatistics:read",

            // System
            "system:read", "system:write",

            // Public
            "public:read",

            // Guest
            "guest:read", "guest:write",

            // Admin wildcard (included so admin:* is always present)
            ADMIN_WILDCARD
    );

    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity,
                                         AuthenticationRequestContext context) {
        if (identity.isAnonymous()) {
            return Uni.createFrom().item(identity);
        }

        if (!identity.getRoles().contains(ADMIN_WILDCARD)) {
            return Uni.createFrom().item(identity);
        }

        // Identity already has admin:* — expand to all scopes
        QuarkusSecurityIdentity augmented = QuarkusSecurityIdentity.builder(identity)
                .addRoles(ALL_SCOPES)
                .build();

        return Uni.createFrom().item(augmented);
    }
}
