package dk.trustworks.intranet.snapshot.strategy;

import dk.trustworks.intranet.snapshot.strategy.impl.GenericJsonSnapshotStrategy;
import io.quarkus.logging.Log;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Registry for managing snapshot strategies.
 * Automatically discovers and registers all CDI beans implementing SnapshotStrategy.
 * <p>
 * Strategies are registered by their entity type discriminator and can be
 * retrieved for use in the SnapshotService.
 * <p>
 * When a specific strategy is not found, a generic fallback strategy is used,
 * which logs a warning encouraging proper strategy implementation.
 */
@ApplicationScoped
public class SnapshotStrategyRegistry {

    @Inject
    Instance<SnapshotStrategy<?>> strategies;

    @Inject
    GenericJsonSnapshotStrategy fallbackStrategy;

    private final Map<String, SnapshotStrategy<?>> strategyMap = new HashMap<>();

    /**
     * Initialize registry by discovering all CDI strategy beans.
     * Automatically called after construction.
     */
    @PostConstruct
    void initialize() {
        Log.info("Initializing SnapshotStrategyRegistry");

        for (SnapshotStrategy<?> strategy : strategies) {
            // Skip the fallback strategy - it's injected separately and used as fallback
            if (GenericJsonSnapshotStrategy.FALLBACK_ENTITY_TYPE.equals(strategy.getEntityType())) {
                Log.debugf("Skipping registration of fallback strategy: %s",
                    strategy.getClass().getSimpleName());
                continue;
            }
            registerStrategy(strategy);
        }

        if (strategyMap.isEmpty()) {
            Log.warn("No specific snapshot strategies registered. All snapshots will use generic fallback.");
        } else {
            Log.infof("Registered %d snapshot strategies: %s",
                strategyMap.size(), String.join(", ", strategyMap.keySet()));
        }

        Log.infof("Fallback strategy available: %s", fallbackStrategy.getClass().getSimpleName());
    }

    /**
     * Register a strategy.
     * Can be called manually or automatically during CDI initialization.
     *
     * @param strategy the strategy to register
     * @throws IllegalArgumentException if entity type is already registered
     */
    public void registerStrategy(SnapshotStrategy<?> strategy) {
        String entityType = strategy.getEntityType();

        if (entityType == null || entityType.trim().isEmpty()) {
            throw new IllegalArgumentException(
                "Strategy entity type cannot be null or empty: " + strategy.getClass().getName());
        }

        if (strategyMap.containsKey(entityType)) {
            throw new IllegalArgumentException(
                String.format("Strategy for entity type '%s' is already registered by %s",
                    entityType, strategyMap.get(entityType).getClass().getName()));
        }

        strategyMap.put(entityType, strategy);
        Log.infof("Registered snapshot strategy for entity type '%s': %s",
            entityType, strategy.getClass().getSimpleName());
    }

    /**
     * Get strategy for an entity type.
     * If no specific strategy is registered, returns the generic fallback strategy
     * and logs a warning.
     *
     * @param entityType the entity type
     * @return the strategy (specific or fallback)
     */
    public SnapshotStrategy<?> getStrategy(String entityType) {
        SnapshotStrategy<?> strategy = strategyMap.get(entityType);

        if (strategy == null) {
            // Use fallback strategy and log warning
            fallbackStrategy.logFallbackUsage(entityType);
            return fallbackStrategy;
        }

        return strategy;
    }

    /**
     * Get strategy with type safety.
     * Use when you know the expected entity type.
     *
     * @param entityType  the entity type
     * @param entityClass the expected entity class for type checking
     * @param <T>         entity type
     * @return the typed strategy
     * @throws IllegalArgumentException if no strategy found or type mismatch
     */
    @SuppressWarnings("unchecked")
    public <T> SnapshotStrategy<T> getStrategy(String entityType, Class<T> entityClass) {
        SnapshotStrategy<?> strategy = getStrategy(entityType);

        // Type safety check (best effort)
        if (!Object.class.equals(strategy.getEntityClass()) &&
            !strategy.getEntityClass().isAssignableFrom(entityClass)) {
            Log.warnf("Strategy type mismatch for entity type '%s'. " +
                "Expected: %s, Strategy handles: %s",
                entityType, entityClass.getName(), strategy.getEntityClass().getName());
        }

        return (SnapshotStrategy<T>) strategy;
    }

    /**
     * Check if strategy is registered for an entity type.
     *
     * @param entityType the entity type
     * @return true if strategy exists
     */
    public boolean hasStrategy(String entityType) {
        return strategyMap.containsKey(entityType);
    }

    /**
     * Get all registered entity types.
     *
     * @return set of entity type discriminators
     */
    public Set<String> getRegisteredEntityTypes() {
        return strategyMap.keySet();
    }

    /**
     * Get count of registered strategies.
     *
     * @return strategy count
     */
    public int getStrategyCount() {
        return strategyMap.size();
    }

    /**
     * Clear all strategies.
     * Primarily for testing purposes.
     */
    void clearAll() {
        Log.warn("Clearing all snapshot strategies from registry");
        strategyMap.clear();
    }
}
