# Immutable Snapshot System

## Overview

The Immutable Snapshot System is a generic, reusable infrastructure for creating and managing immutable snapshots of business data across different entity types. It provides audit compliance, data integrity verification, and temporal versioning capabilities.

## Architecture

### Design Patterns

The system implements several industry-standard patterns:

1. **Snapshot Pattern** (Martin Fowler) - Captures state at a point in time for audit trails
2. **Strategy Pattern** - Entity-specific behavior (serialization, validation) isolated in strategies
3. **Facade Pattern** - Old LockedBonusPoolResource delegates to new generic service
4. **Repository Pattern** - Data access abstracted through Panache repositories
5. **Domain-Driven Design** - ImmutableSnapshot as Aggregate Root in its own bounded context

### Components

```
dk.trustworks.intranet.snapshot/
├── model/
│   └── ImmutableSnapshot.java          # Generic JPA entity
├── repository/
│   └── ImmutableSnapshotRepository.java # Data access layer
├── service/
│   └── SnapshotService.java            # Business logic
├── resources/
│   └── SnapshotResource.java           # REST API
├── strategy/
│   ├── SnapshotStrategy.java           # Interface
│   ├── SnapshotStrategyRegistry.java   # CDI registry
│   └── impl/
│       └── BonusPoolSnapshotStrategy.java # Bonus pool implementation
└── exceptions/
    ├── SnapshotException.java          # Generic exception
    └── DataIntegrityException.java     # Checksum failures
```

## Core Concepts

### Entity Natural Key

Snapshots are identified by a composite natural key:
- **entity_type**: Discriminator (e.g., "bonus_pool", "contract", "financial_report")
- **entity_id**: Business identifier (e.g., "2024", "CONTRACT-123", UUID)
- **snapshot_version**: Version number (1, 2, 3, ...) allowing multiple snapshots over time

### Immutability

Once created, snapshots are immutable:
- Data never changes after creation
- Cache strategy is READ_ONLY (better performance than READ_WRITE)
- Updates are not supported (except in exceptional admin cases)
- Each change creates a new version rather than modifying existing

### Data Integrity

Every snapshot includes:
- **SHA-256 checksum**: Calculated during creation, validated on read
- **Checksum validation**: Automatic on retrieval, throws DataIntegrityException on mismatch
- **Optimistic locking**: JPA @Version field prevents concurrent modifications

### Versioning

Multiple snapshots of the same entity are supported:
- Version 1: First snapshot
- Version 2: Second snapshot (after data changed)
- Version 3: Third snapshot, etc.
- Latest version retrieved by default
- All versions queryable via `/versions` endpoint

## Database Schema

```sql
CREATE TABLE immutable_snapshots (
    snapshot_id BIGINT AUTO_INCREMENT PRIMARY KEY,  -- Surrogate key

    -- Natural key
    entity_type VARCHAR(100) NOT NULL,
    entity_id VARCHAR(255) NOT NULL,
    snapshot_version INT NOT NULL DEFAULT 1,

    -- Data
    snapshot_data TEXT NOT NULL,                     -- JSON payload
    checksum VARCHAR(64) NOT NULL,                   -- SHA-256
    metadata JSON,                                    -- Entity-specific metadata

    -- Audit
    locked_at TIMESTAMP NOT NULL,
    locked_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,

    -- Optimistic locking
    version INT NOT NULL DEFAULT 1,

    UNIQUE KEY uk_snapshot_natural_key (entity_type, entity_id, snapshot_version),
    INDEX idx_entity_lookup (entity_type, entity_id),
    INDEX idx_entity_type_time (entity_type, locked_at),
    INDEX idx_locked_by (locked_by),
    INDEX idx_created_at (created_at)
);
```

### Migration from Legacy

V91 migration script:
- Creates `immutable_snapshots` table
- Migrates data from `locked_bonus_pool_data`
- Validates checksums during migration
- Keeps old table for backward compatibility

## Strategy Pattern

### Purpose

Allows entity-specific logic without coupling to generic snapshot system.

### Interface

```java
public interface SnapshotStrategy<T> {
    String getEntityType();                        // "bonus_pool"
    String serializeToJson(T entity);              // Object → JSON
    T deserializeFromJson(String json);            // JSON → Object
    void validateBeforeSnapshot(T entity);         // Business validation
    Map<String, String> extractMetadata(T entity); // Queryable metadata
}
```

### Implementing a New Strategy

1. Create class implementing `SnapshotStrategy<YourType>`
2. Add `@ApplicationScoped` annotation (CDI will auto-register)
3. Implement all interface methods
4. No code changes needed elsewhere - registry discovers automatically

Example:

```java
@ApplicationScoped
public class ContractSnapshotStrategy implements SnapshotStrategy<Contract> {

    @Inject
    ObjectMapper objectMapper;

    @Override
    public String getEntityType() {
        return "contract";
    }

    @Override
    public String serializeToJson(Contract contract) {
        try {
            return objectMapper.writeValueAsString(contract);
        } catch (JsonProcessingException e) {
            throw new SnapshotException("Serialization failed", e);
        }
    }

    @Override
    public Contract deserializeFromJson(String json) {
        try {
            return objectMapper.readValue(json, Contract.class);
        } catch (JsonProcessingException e) {
            throw new SnapshotException("Deserialization failed", e);
        }
    }

    @Override
    public void validateBeforeSnapshot(Contract contract) {
        if (contract.getContractNumber() == null) {
            throw new ValidationException("Contract number required");
        }
        // Additional validation...
    }

    @Override
    public Map<String, String> extractMetadata(Contract contract) {
        return Map.of(
            "contractNumber", contract.getContractNumber(),
            "clientName", contract.getClientName(),
            "startDate", contract.getStartDate().toString()
        );
    }
}
```

## REST API

Base path: `/snapshots`

### Endpoints

#### List All Snapshots
```http
GET /snapshots?entityType=bonus_pool&page=0&size=50
```

Returns paginated list of all snapshots with optional filtering.

#### List by Entity Type
```http
GET /snapshots/{entityType}?page=0&size=50
```

Returns all snapshots for a specific entity type.

#### Get Latest Snapshot
```http
GET /snapshots/{entityType}/{entityId}
```

Returns the most recent version for an entity.

#### Get Specific Version
```http
GET /snapshots/{entityType}/{entityId}/{version}
```

Returns a specific snapshot version.

#### List All Versions
```http
GET /snapshots/{entityType}/{entityId}/versions
```

Returns all versions for an entity (latest first).

#### Check Existence
```http
GET /snapshots/{entityType}/{entityId}/exists
```

Returns boolean indicating if any snapshot exists.

#### Get Statistics
```http
GET /snapshots/stats
```

Returns counts by entity type:
```json
{
  "bonus_pool": 5,
  "contract": 120,
  "total": 125
}
```

#### Create Snapshot
```http
POST /snapshots
Content-Type: application/json

{
  "entityType": "bonus_pool",
  "entityId": "2024",
  "data": { /* entity data or JSON string */ },
  "lockedBy": "admin@trustworks.dk"
}
```

Creates new snapshot with automatic versioning and checksum.

#### Delete Snapshot Version
```http
DELETE /snapshots/{entityType}/{entityId}/{version}
```

**DANGEROUS**: Deletes specific version. Breaks audit trail.

#### Delete All Versions
```http
DELETE /snapshots/{entityType}/{entityId}
```

**DANGEROUS**: Deletes all versions of an entity.

### Security

All endpoints require JWT authentication and one of:
- `SYSTEM` role - Full access
- `ADMIN` role - Full access

For production, consider entity-type-specific permissions:
- `SNAPSHOT_CREATE_BONUS_POOL`
- `SNAPSHOT_READ_CONTRACT`
- etc.

### Response Formats

**Full Snapshot** (individual GET endpoints):
```json
{
  "id": 123,
  "entityType": "bonus_pool",
  "entityId": "2024",
  "snapshotVersion": 1,
  "snapshotData": "{...}",
  "checksum": "a1b2c3d4...",
  "metadata": "{\"fiscalYear\": 2024}",
  "lockedAt": "2024-07-01T10:00:00",
  "lockedBy": "admin@trustworks.dk",
  "createdAt": "2024-07-01T10:00:00",
  "updatedAt": "2024-07-01T10:00:00",
  "version": 1
}
```

**Summary** (list endpoints):
```json
{
  "entityType": "bonus_pool",
  "entityId": "2024",
  "snapshotVersion": 1,
  "lockedBy": "admin@trustworks.dk",
  "lockedAt": "2024-07-01T10:00:00",
  "checksum": "a1b2c3d4...",
  "dataSize": 12345,
  "metadata": "{\"fiscalYear\": 2024}"
}
```

## Usage Examples

### Java Service Layer

```java
@ApplicationScoped
public class MyService {

    @Inject
    SnapshotService snapshotService;

    public void createBonusPoolSnapshot(String fiscalYear, String json) {
        ImmutableSnapshot snapshot = snapshotService.createSnapshot(
            "bonus_pool",
            fiscalYear,
            json,
            "system"
        );

        Log.infof("Created snapshot: %s", snapshot.getNaturalKey());
    }

    public String getBonusPoolData(String fiscalYear) {
        Optional<ImmutableSnapshot> snapshot =
            snapshotService.getLatestSnapshot("bonus_pool", fiscalYear);

        return snapshot
            .map(ImmutableSnapshot::getSnapshotData)
            .orElseThrow(() -> new NotFoundException("No snapshot found"));
    }
}
```

### REST Client (curl)

```bash
# Create snapshot
curl -X POST http://localhost:9093/snapshots \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "entityType": "bonus_pool",
    "entityId": "2024",
    "data": "{\"fiscalYear\": 2024, \"poolSize\": 100000}",
    "lockedBy": "admin@trustworks.dk"
  }'

# Get latest snapshot
curl http://localhost:9093/snapshots/bonus_pool/2024 \
  -H "Authorization: Bearer $TOKEN"

# List all versions
curl http://localhost:9093/snapshots/bonus_pool/2024/versions \
  -H "Authorization: Bearer $TOKEN"

# Check if exists
curl http://localhost:9093/snapshots/bonus_pool/2024/exists \
  -H "Authorization: Bearer $TOKEN"

# Get statistics
curl http://localhost:9093/snapshots/stats \
  -H "Authorization: Bearer $TOKEN"
```

## Performance Considerations

### Caching

- **Strategy**: READ_ONLY cache (immutable data)
- **Cache name**: `immutableSnapshots`
- **Cached queries**:
  - `findLatestByEntityTypeAndId`
  - `findByEntityTypeAndIdAndVersion`
- **Eviction**: Automatic on create/delete

### Pagination

All list endpoints support pagination:
- Default page size: 50
- Max page size: 1000 (recommended)
- Use for large result sets

### Indexes

Optimized for common queries:
- `(entity_type, entity_id)` - Entity lookup
- `(entity_type, locked_at)` - Time-based queries
- `(locked_by)` - User audit queries
- `(created_at)` - Chronological listing

### Compression (Future Enhancement)

For large payloads (>10KB), consider adding GZIP compression:
- Add `compression_type` column
- Compress before storage
- Decompress transparently on read

## Domain-Driven Design Perspective

### Bounded Context

**Context**: Snapshot Management / Immutable Records

This is a separate bounded context from the business domains (bonus, contracts, etc.). It provides generic snapshot capabilities as an infrastructure service.

### Aggregate Root

**ImmutableSnapshot** is the Aggregate Root:
- **Identity**: Composite natural key (entity_type, entity_id, version)
- **Invariants**:
  - Checksum must match data
  - Data is immutable once created
  - Version must be unique per entity
- **Consistency Boundary**: All snapshot operations
- **Lifecycle**: Created → Never Modified → Optionally Deleted

### Value Objects

- **EntityReference**: (entity_type, entity_id) - identifies what's snapshotted
- **Checksum**: SHA-256 hash - ensures integrity
- **AuditInfo**: (locked_by, locked_at) - tracks who/when

### Domain Events

Consider publishing events for:
- `SnapshotCreatedEvent` - New snapshot created
- `SnapshotDeletedEvent` - Audit trail broken (exceptional)
- `SnapshotIntegrityFailedEvent` - Checksum validation failed (critical alert)

## Error Handling

### Exceptions

- **SnapshotException**: Generic snapshot operation failure
  - Serialization errors
  - Invalid entity type
  - Strategy not found

- **DataIntegrityException**: Checksum validation failure
  - Corrupted data
  - Tampered snapshot
  - Storage corruption

- **ValidationException**: Business validation failure
  - Missing required fields
  - Invalid data format
  - Business rule violations

### HTTP Status Codes

- `200 OK` - Success
- `201 Created` - Snapshot created
- `204 No Content` - Deleted successfully
- `400 Bad Request` - Invalid input
- `404 Not Found` - Snapshot doesn't exist
- `409 Conflict` - Already exists (if enforced)
- `500 Internal Server Error` - Checksum validation failed

## Monitoring and Observability

### Metrics to Track

Business metrics:
- `snapshot.created.count` (by entity_type)
- `snapshot.deleted.count` (should be rare)
- `snapshot.integrity_check.failed` (critical)
- `snapshot.size.bytes` (histogram by entity_type)

Performance metrics:
- `snapshot.create.duration`
- `snapshot.read.duration`
- `cache.hit.rate`

### Logging

- **INFO**: Snapshot created/deleted with entity details
- **WARN**: Integrity check failed, unusual deletion, metadata extraction failure
- **ERROR**: Serialization failures, database errors

## Testing Strategy

### Unit Tests

- SnapshotService: Checksum calculation, validation
- SnapshotStrategy implementations: Serialization, validation
- Repository: Query methods with in-memory H2

### Integration Tests

- End-to-end lifecycle: create → read → verify → delete
- Multi-version scenarios
- Cache behavior
- Checksum tampering detection

### Performance Tests

- Large payload serialization (1MB+ JSON)
- Concurrent snapshot creation
- Query performance with 10k+ snapshots

## Backward Compatibility

### Legacy API (Deprecated)

Old bonus pool API at `/bonuspool/locked` continues working:
- Marked `@Deprecated`
- Delegates to new `SnapshotService`
- Transforms responses to maintain contract
- Will be removed in future version after client migration

### Migration Path

1. **V91 migration** creates new table and migrates data
2. **Both APIs work** simultaneously during transition
3. **Clients migrate** to new `/snapshots` API
4. **Old API removed** in future version (V100+)
5. **Old table dropped** after all clients migrated

## Best Practices

1. **Always validate checksums** when retrieving critical data
2. **Use strategies** for entity-specific logic (don't modify core service)
3. **Metadata is your friend** - extract searchable fields for querying
4. **Pagination is required** for production list queries
5. **Monitor integrity failures** - set up alerts for checksum mismatches
6. **Document entity types** - maintain registry of what each type contains
7. **Version carefully** - create new version when data changes, don't modify existing
8. **Delete sparingly** - snapshots are audit trails, deletion breaks compliance

## Future Enhancements

### Event Sourcing Integration

Add optional event tracking:
- Store domain events that led to snapshot
- `event_id` reference in snapshots
- Can replay events to understand history

### Temporal Queries

Support "as of date" queries:
- "What was the state on date X?"
- Requires indexing by effective dates
- Useful for regulatory reporting

### Data Classification

Add sensitivity levels:
- PII, Financial, Confidential, Public
- Controls access and retention
- Supports GDPR compliance

### Retention Policies

Configurable per entity type:
- Auto-delete after N years
- Archive to cold storage
- Redaction of sensitive data

### Full-Text Search

Index metadata for searching:
- Elasticsearch integration
- Query across all entity types
- Fast lookups without deserializing

## References

- Martin Fowler - Snapshot Pattern: https://martinfowler.com/eaaDev/Snapshot.html
- REST API Best Practices (2024): https://daily.dev/blog/restful-api-design-best-practices-guide-2024
- DDD Tactical Patterns: https://vaadin.com/blog/ddd-part-2-tactical-domain-driven-design
- Immutable Data Pattern: https://questdb.com/glossary/immutable-data-pattern/
