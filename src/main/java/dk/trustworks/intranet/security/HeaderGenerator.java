package dk.trustworks.intranet.security;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class HeaderGenerator {
    public static String authorizationHeader() {
        return TokenContainer.TOKEN;
    }
}
