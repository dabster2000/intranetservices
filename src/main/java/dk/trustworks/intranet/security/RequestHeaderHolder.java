package dk.trustworks.intranet.security;

import lombok.Data;

import javax.enterprise.context.RequestScoped;

@Data
@RequestScoped
//@Unremovable
public class RequestHeaderHolder {
    private String username;
}
