package dk.trustworks.intranet.security.apiclient.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;

import java.util.Set;

public record UpdateScopesRequest(
        @NotNull @Size(min = 1)
        Set<@NotBlank @Size(max = 100) String> scopes
) {}
