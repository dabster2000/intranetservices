package dk.trustworks.intranet.sharepoint.client;

import jakarta.ws.rs.HttpMethod;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * JAX-RS-compatible PATCH method binding annotation. Jakarta REST does not
 * ship a {@code @PATCH} annotation, so we declare one locally as a
 * {@link HttpMethod} meta-annotation. Used by {@link GraphApiClient} to call
 * the Microsoft Graph "Update DriveItem" endpoint, which is HTTP PATCH.
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@HttpMethod("PATCH")
public @interface PATCH {
}
