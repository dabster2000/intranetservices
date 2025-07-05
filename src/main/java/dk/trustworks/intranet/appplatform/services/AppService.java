package dk.trustworks.intranet.appplatform.services;

import dk.trustworks.intranet.appplatform.model.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;
import com.password4j.Argon2Function;
import com.password4j.Hash;
import com.password4j.Password;
import com.password4j.types.Argon2;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@JBossLog
@ApplicationScoped
public class AppService {

    @Inject
    EntityManager em;
    
    @Inject
    TokenUtils tokenUtils;

    @Transactional
    public App createApp(String name, String userUuid) {
        App app = new App();
        app.setUuid(UUID.randomUUID().toString());
        app.setName(name);
        app.setCreated(LocalDateTime.now());
        app.persist();
        AppUserRole role = new AppUserRole();
        role.setUuid(UUID.randomUUID().toString());
        role.setAppUuid(app.getUuid());
        role.setUserUuid(userUuid);
        role.setRole(AppRole.APP_ADMIN);
        role.persist();
        log.info("Created app " + app.getUuid());
        return app;
    }

    public List<App> listAppsForUser(String userUuid) {
        log.debug("Fetching apps for user=" + userUuid);
        List<App> apps = em.createQuery("select a from app a join app_user_role r on a.uuid=r.appUuid where r.userUuid=?1", App.class)
                .setParameter(1, userUuid)
                .getResultList();
        log.debug("Found " + apps.size() + " apps for user=" + userUuid);
        return apps;
    }

    @Transactional
    public String createToken(String appUuid, long accessExpiresIn, long refreshExpiresIn) {
        App app = App.findById(appUuid);
        if (app == null) throw new IllegalArgumentException("App not found");
        String rawRefresh = UUID.randomUUID().toString();
        Argon2Function func = Argon2Function.getInstance(3, 65536, 1, 32, Argon2.ID);
        Hash hash = Password.hash(rawRefresh).addRandomSalt().with(func);

        AppToken token = new AppToken();
        token.setUuid(UUID.randomUUID().toString());
        token.setApp(app);
        token.setTokenHash(hash.getResult());
        token.setCreated(LocalDateTime.now());
        token.setExpiresAt(LocalDateTime.now().plusSeconds(refreshExpiresIn));
        token.setRevoked(false);
        token.persist();
        log.debug("Refresh token hash=" + token.getTokenHash());
        log.info("Created refresh token " + token.getUuid() + " for app " + appUuid);
        return rawRefresh;
    }

    @Transactional
    public void revokeToken(String tokenId) {
        AppToken token = AppToken.findById(tokenId);
        if (token != null) {
            token.setRevoked(true);
            log.info("Revoked token " + token.getUuid());
        } else {
            log.warn("Attempted to revoke non-existing token " + tokenId);
        }
    }

    public List<App> listAppsForUser(String userUuid, int page, int size) {
        log.debug("Fetching apps for user=" + userUuid + " page=" + page + " size=" + size);
        List<App> apps = em.createQuery("select a from app a join app_user_role r on a.uuid=r.appUuid where r.userUuid=?1", App.class)
                .setParameter(1, userUuid)
                .setFirstResult(page * size)
                .setMaxResults(size)
                .getResultList();
        log.debug("Found " + apps.size() + " apps for user=" + userUuid);
        return apps;
    }

    public long countAppsForUser(String userUuid) {
        log.debug("Counting apps for user=" + userUuid);
        Long count = em.createQuery("select count(a) from app a join app_user_role r on a.uuid=r.appUuid where r.userUuid=?1", Long.class)
                .setParameter(1, userUuid)
                .getSingleResult();
        log.debug("App count=" + count + " for user=" + userUuid);
        return count;
    }

    public List<AppMember> listMembers(String appUuid) {
        log.debug("Listing members for app=" + appUuid);
        List<AppUserRole> roles = AppUserRole.list("appUuid", appUuid);
        List<AppMember> members = roles.stream().map(r -> new AppMember(r.getUserUuid(), r.getRole())).toList();
        log.debug("Found " + members.size() + " members for app=" + appUuid);
        return members;
    }

    @Transactional
    public void addMember(String appUuid, String userUuid) {
        AppUserRole role = new AppUserRole();
        role.setUuid(UUID.randomUUID().toString());
        role.setAppUuid(appUuid);
        role.setUserUuid(userUuid);
        role.setRole(AppRole.APP_MEMBER);
        role.persist();
        log.info("Added member " + userUuid + " to app " + appUuid);
    }

    @Transactional
    public void changeRole(String appUuid, String userUuid, AppRole roleType) {
        AppUserRole role = AppUserRole.find("appUuid=?1 and userUuid=?2", appUuid, userUuid).firstResult();
        if (role != null) {
            role.setRole(roleType);
            log.info("Changed role of " + userUuid + " to " + roleType + " in app " + appUuid);
        } else {
            log.warn("Attempted to change role for non-member " + userUuid + " in app " + appUuid);
        }
    }

    @Transactional
    public void removeMember(String appUuid, String userUuid) {
        long deleted = AppUserRole.delete("appUuid=?1 and userUuid=?2", appUuid, userUuid);
        if (deleted > 0) {
            log.info("Removed member " + userUuid + " from app " + appUuid);
        } else {
            log.warn("Attempted to remove non-member " + userUuid + " from app " + appUuid);
        }
    }
}
