package dk.trustworks.intranet.recruitmentservice.security;

import jakarta.ws.rs.NameBinding;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * JAX-RS {@link NameBinding} marker that scopes
 * {@link RecruitmentRevisionResponseFilter} to recruitment endpoints only.
 * <p>
 * Apply at class level on {@code RecruitmentResource} so every method on the
 * resource gets the response filter. The filter strips sensitive placeholder
 * values from {@code RevisionResponse} bodies for callers without
 * {@code users:read} scope, without affecting any other resource in the
 * application.
 */
@NameBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface RecruitmentSecuredResponse {
}
