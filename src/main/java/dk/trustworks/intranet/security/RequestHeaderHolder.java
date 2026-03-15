package dk.trustworks.intranet.security;

import lombok.Data;

import jakarta.enterprise.context.RequestScoped;

@Data
@RequestScoped
public class RequestHeaderHolder {
    private String userUuid;
}
