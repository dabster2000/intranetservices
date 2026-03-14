package dk.trustworks.intranet.security;

import dk.trustworks.intranet.domain.user.entity.User;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import lombok.extern.jbosslog.JBossLog;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

/**
 * JAX-RS response filter that strips sensitive {@code @Transient} fields from
 * {@link User} objects before Jackson serialization, based on the caller's
 * scopes as resolved by {@link ScopeContext}.
 *
 * <p>This filter runs at {@link Priorities#ENTITY_CODER} priority so that it
 * executes after resource methods but before the entity is serialized to JSON.
 *
 * <h3>Scope-to-field mapping</h3>
 * <table>
 *   <tr><th>Missing scope</th><th>Fields cleared</th></tr>
 *   <tr><td>{@code salaries:read}</td><td>{@code salaries}, {@code userBankInfos}</td></tr>
 *   <tr><td>{@code userstatus:read}</td><td>{@code statuses}</td></tr>
 *   <tr><td>{@code teams:read}</td><td>{@code teams}</td></tr>
 *   <tr><td>{@code careerlevel:read}</td><td>{@code careerLevels}</td></tr>
 *   <tr><td>{@code admin:*}</td><td>{@code roleList}</td></tr>
 * </table>
 *
 * <p>Callers holding the {@code admin:*} scope bypass all field stripping —
 * they see the complete User representation.
 *
 * <h3>Entity detection</h3>
 * The filter inspects the response entity for:
 * <ul>
 *   <li>A direct {@link User} instance</li>
 *   <li>A {@link List} of {@link User} instances</li>
 *   <li>Any object with a {@code getUser()} method or {@code user} field
 *       returning a {@link User} (e.g., {@code UserFinanceDocument})</li>
 *   <li>A {@link List} of objects matching the embedded-User pattern above</li>
 * </ul>
 */
@JBossLog
@Provider
@Priority(Priorities.ENTITY_CODER)
public class UserScopeResponseFilter implements ContainerResponseFilter {

    private static final String SCOPE_SALARIES = "salaries:read";
    private static final String SCOPE_USERSTATUS = "userstatus:read";
    private static final String SCOPE_TEAMS = "teams:read";
    private static final String SCOPE_CAREERLEVEL = "careerlevel:read";
    private static final String SCOPE_ADMIN = "admin:*";

    @Inject
    ScopeContext scopeContext;

    @Override
    public void filter(ContainerRequestContext requestContext,
                       ContainerResponseContext responseContext) {
        Object entity = responseContext.getEntity();
        if (entity == null) {
            return;
        }

        // Admin callers see everything — skip all stripping
        if (scopeContext.hasScope(SCOPE_ADMIN)) {
            return;
        }

        processEntity(entity);
    }

    /**
     * Inspects the entity and applies scope-based field stripping to any
     * User objects found within it.
     */
    private void processEntity(Object entity) {
        if (entity instanceof User user) {
            stripFields(user);
        } else if (entity instanceof List<?> list) {
            processList(list);
        } else {
            // Check for embedded User via getUser() method or 'user' field
            User embedded = extractEmbeddedUser(entity);
            if (embedded != null) {
                stripFields(embedded);
            }
        }
    }

    /**
     * Processes a list, stripping fields from any User instances or objects
     * containing embedded Users.
     */
    private void processList(List<?> list) {
        for (Object element : list) {
            if (element instanceof User user) {
                stripFields(user);
            } else if (element != null) {
                User embedded = extractEmbeddedUser(element);
                if (embedded != null) {
                    stripFields(embedded);
                }
            }
        }
    }

    /**
     * Attempts to extract an embedded {@link User} from an object by looking
     * for a {@code getUser()} method first, then falling back to a {@code user}
     * field via reflection.
     *
     * @return the embedded User, or {@code null} if none found
     */
    private User extractEmbeddedUser(Object object) {
        // Try getUser() method first (most common pattern)
        try {
            Method getter = object.getClass().getMethod("getUser");
            if (User.class.isAssignableFrom(getter.getReturnType())) {
                return (User) getter.invoke(object);
            }
        } catch (NoSuchMethodException e) {
            // No getUser() method — fall through to field check
        } catch (Exception e) {
            log.debugf("Failed to invoke getUser() on %s: %s",
                    object.getClass().getSimpleName(), e.getMessage());
        }

        // Try direct 'user' field access
        try {
            Field field = object.getClass().getDeclaredField("user");
            if (User.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                return (User) field.get(object);
            }
        } catch (NoSuchFieldException e) {
            // No 'user' field — this object doesn't embed a User
        } catch (Exception e) {
            log.debugf("Failed to access 'user' field on %s: %s",
                    object.getClass().getSimpleName(), e.getMessage());
        }

        return null;
    }

    /**
     * Clears sensitive transient fields on the given User based on which
     * scopes the caller is missing. Uses {@link Collections#emptyList()} to
     * avoid NPEs in downstream code that calls {@code .stream()} on these
     * lists.
     */
    private void stripFields(User user) {
        if (!scopeContext.hasScope(SCOPE_SALARIES)) {
            user.setSalaries(Collections.emptyList());
            user.setUserBankInfos(Collections.emptyList());
        }

        if (!scopeContext.hasScope(SCOPE_USERSTATUS)) {
            user.setStatuses(Collections.emptyList());
        }

        if (!scopeContext.hasScope(SCOPE_TEAMS)) {
            user.setTeams(Collections.emptyList());
        }

        if (!scopeContext.hasScope(SCOPE_CAREERLEVEL)) {
            user.setCareerLevels(Collections.emptyList());
        }

        // roleList requires admin:* — which we already checked at the top,
        // so if we reach here the caller does NOT have admin:*
        user.setRoleList(Collections.emptyList());
    }
}
