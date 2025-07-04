package dk.trustworks.intranet.appplatform.services;

import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.jbosslog.JBossLog;

import java.util.HashSet;
import java.util.List;

@JBossLog
@ApplicationScoped
public class TokenUtils {

    public String issueAppAccessToken(String appUuid, List<String> roles, long expiresInSeconds) {
        long iat = System.currentTimeMillis() / 1000;
        long exp = iat + expiresInSeconds;
        log.debug("Issuing access token for app=" + appUuid + " exp=" + exp);
        return Jwt.claims()
                .issuedAt(iat)
                .expiresAt(exp)
                .subject(appUuid)
                .claim("scope", "APP")
                .groups(new HashSet<>(roles))
                .sign();
    }
}
