package dk.trustworks.intranet.aggregates.invoice.bonus.dto;

/**
 * CDI event fired when bonus approval/rejection changes occur,
 * triggering cache invalidation in the dashboard service.
 */
public record BonusCacheInvalidationEvent(int fiscalYear) {}
