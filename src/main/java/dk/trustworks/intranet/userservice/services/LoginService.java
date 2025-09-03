package dk.trustworks.intranet.userservice.services;

import dk.trustworks.intranet.userservice.dto.LoginTokenResult;
import dk.trustworks.intranet.domain.user.entity.Role;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.userservice.model.enums.RoleType;
import dk.trustworks.intranet.userservice.utils.TokenUtils;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.jwt.Claims;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.concurrent.TimeUnit;


@JBossLog
@ApplicationScoped
public class LoginService {

    public LoginTokenResult login(String username, String password) throws Exception {
        log.logv(Logger.Level.INFO, "Login attempt {0}: {1}", username, password);
        Optional<User> optionalUser = User.findByUsername(username);
        log.info("found: "+optionalUser);
        if(optionalUser.isEmpty()) return new LoginTokenResult("", "", false, "Login failed", new ArrayList<>());
        User user = optionalUser.get();
        log.logv(Logger.Level.INFO, "Login attempt user found: {0}", user);
        List<Role> roles = Role.findByUseruuid(user.uuid);
        if(user.checkPassword(password)) {
            log.logv(Logger.Level.INFO, "Login by {0}: {1}", username, "valid");
            return new LoginTokenResult(createToken(user), user.getUuid(), true, "", roles);
        } else {
            log.logv(Logger.Level.WARN, "Login by {0}: {1}", username, "invalid");
            return new LoginTokenResult("", "", false, "Login failed", new ArrayList<>());
        }
    }

    private String createToken(User user) throws Exception {
        HashMap<String, Long> timeClaims = new HashMap<>();
        long duration = 6000;
        switch (user.getType()) {
            case "USER": duration = TimeUnit.HOURS.toSeconds(10); break;
            case "SYSTEM": duration = TimeUnit.DAYS.toSeconds(365); break;
        }
        if(user.getUsername().equals("apple")) duration = TimeUnit.SECONDS.toSeconds(30);
        log.debug("duration = " + duration);
        long exp = TokenUtils.currentTimeInSecs() + duration;
        timeClaims.put(Claims.exp.name(), exp);

        return TokenUtils.generateTokenString(user, timeClaims);
    }

    public LoginTokenResult createSystemToken(String role) throws Exception {
        HashMap<String, Long> timeClaims = new HashMap<>();
        long duration = TimeUnit.DAYS.toMillis(365);
        long exp = TokenUtils.currentTimeInSecs() + duration;
        timeClaims.put(Claims.exp.name(), exp);

        User user = new User();
        user.getRoleList().add(new Role(UUID.randomUUID().toString(), RoleType.valueOf(role), user.uuid));
        user.setUsername("system.intra");

        return new LoginTokenResult(TokenUtils.generateSystemUserTokenString(user, timeClaims, user.getRoleList()), user.getUuid(), true, "", user.getRoleList());
    }

    /**
     * Validates a token and returns validation result
     *
     * @param token JWT token to validate
     * @return LoginTokenResult with validation status
     */
    public LoginTokenResult validateToken(String token) {
        try {
            if (token == null || token.isEmpty()) {
                return new LoginTokenResult("", "", false, "Invalid token: Token is empty", new ArrayList<>());
            }

            // Validate token
            boolean isValid = TokenUtils.validateToken(token);

            if (isValid) {
                // Parse the token to extract username
                String[] tokenParts = token.split("\\.");
                String payload = new String(Base64.getDecoder().decode(tokenParts[1]));
                jakarta.json.JsonObject payloadJson = jakarta.json.Json.createReader(
                        new java.io.StringReader(payload)).readObject();

                String username = payloadJson.getString("preferred_username", "");
                Optional<User> userOpt = User.findByUsername(username);

                if (userOpt.isPresent()) {
                    User user = userOpt.get();
                    List<Role> roles = Role.findByUseruuid(user.getUuid());
                    return new LoginTokenResult(token, user.getUuid(), true, "", roles);
                } else {
                    return new LoginTokenResult("", "", false, "User not found", new ArrayList<>());
                }
            } else {
                return new LoginTokenResult("", "", false, "Invalid token: Validation failed", new ArrayList<>());
            }
        } catch (Exception e) {
            log.error("Error validating token", e);
            return new LoginTokenResult("", "", false, "Error validating token: " + e.getMessage(), new ArrayList<>());
        }
    }

}
