# Audit Tracking System

## Overview

The audit tracking system automatically captures creation and modification metadata for JPA entities. This includes:
- **Timestamps**: When entities are created and modified
- **User identifiers**: Which user created or modified the entity (typically UUIDs)

The system is designed to be transparent and requires no changes to REST interfaces or service layer code.

## Architecture

### Components

1. **`Auditable` Interface** (`dk.trustworks.intranet.model.Auditable`)
   - Contract defining four audit fields: `createdAt`, `updatedAt`, `createdBy`, `modifiedBy`
   - Entities implement this interface to enable audit tracking

2. **`AuditEntityListener`** (`dk.trustworks.intranet.security.AuditEntityListener`)
   - JPA lifecycle listener that automatically populates audit fields
   - Triggered on `@PrePersist` (creation) and `@PreUpdate` (modification)
   - Uses CDI to access request context and extract user information

3. **`RequestHeaderHolder`** (`dk.trustworks.intranet.security.RequestHeaderHolder`)
   - Request-scoped bean holding the current user identifier
   - Populated by `HeaderInterceptor` from HTTP headers or JWT token

4. **`HeaderInterceptor`** (`dk.trustworks.intranet.security.HeaderInterceptor`)
   - Extracts user identifier from incoming requests with the following priority:
     1. `X-Requested-By` HTTP header (contains user UUID) - **Primary method**
     2. JWT token `preferred_username` claim (fallback)
     3. Query parameter `username` (fallback)
     4. Default to "anonymous" if none found

### Data Flow

```
1. Client request → X-Requested-By: {userUuid}
2. HeaderInterceptor extracts UUID → RequestHeaderHolder.username = {userUuid}
3. Service method called → entity.persist() or entity update
4. JPA triggers @PrePersist or @PreUpdate
5. AuditEntityListener executes
6. Listener accesses RequestHeaderHolder via CDI
7. Listener sets audit fields (timestamps + user UUID)
8. Entity persisted with audit data
9. Response includes audit fields (read-only)
```

## Important Notes

### User Identifiers Are UUIDs

Despite the field name `RequestHeaderHolder.username`, this field typically contains a **user UUID**, not a username string. This is because:

- Clients set the `X-Requested-By` header with `token.getUseruuid()`
- The HeaderInterceptor extracts this value first (highest priority)
- This UUID is then stored in `RequestHeaderHolder.username`

Example client code:
```java
HttpHeaders headers = new HttpHeaders();
headers.set("X-Requested-By", token.getUseruuid()); // UUID, not username
```

### Audit Field Values

The `createdBy` and `modifiedBy` fields can contain:
- **User UUID** (most common) - from `X-Requested-By` header
- **Username** (rare) - from JWT token or query parameter fallback
- **"system"** - default when no user identifier is available
- **"anonymous"** - when HeaderInterceptor can't identify the user

## Making an Entity Auditable

To add audit tracking to an entity:

### 1. Implement the Auditable Interface

```java
@Entity
@Table(name = "your_table")
@EntityListeners(AuditEntityListener.class)
public class YourEntity extends PanacheEntityBase implements Auditable {
    // ... existing fields ...
}
```

### 2. Add Audit Fields

Add the four required fields with proper JPA and Jackson annotations:

```java
@Column(name = "created_at", nullable = false)
@JsonProperty(access = JsonProperty.Access.READ_ONLY)
private LocalDateTime createdAt;

@Column(name = "updated_at", nullable = false)
@JsonProperty(access = JsonProperty.Access.READ_ONLY)
private LocalDateTime updatedAt;

@Column(name = "created_by", nullable = false, length = 255)
@JsonProperty(access = JsonProperty.Access.READ_ONLY)
private String createdBy;

@Column(name = "modified_by", length = 255)
@JsonProperty(access = JsonProperty.Access.READ_ONLY)
private String modifiedBy;
```

**Important annotations:**
- `@JsonProperty(access = READ_ONLY)` - Prevents clients from setting these fields in POST/PUT requests
- `nullable = false` for creation fields - Ensures data integrity
- `nullable = true` for `modified_by` - Allows for records that haven't been updated yet
- `length = 255` - Accommodates UUIDs (36 chars) and other identifier formats

### 3. Create Database Migration

Add columns to your table:

```sql
ALTER TABLE your_table
    ADD COLUMN created_at DATETIME(6) NULL,
    ADD COLUMN updated_at DATETIME(6) NULL,
    ADD COLUMN created_by VARCHAR(255) NULL,
    ADD COLUMN modified_by VARCHAR(255) NULL;

-- Populate existing records
UPDATE your_table
SET created_at = NOW(),
    updated_at = NOW(),
    created_by = 'system',
    modified_by = 'system'
WHERE created_at IS NULL;

-- Make required fields non-nullable
ALTER TABLE your_table
    MODIFY COLUMN created_at DATETIME(6) NOT NULL,
    MODIFY COLUMN updated_at DATETIME(6) NOT NULL,
    MODIFY COLUMN created_by VARCHAR(255) NOT NULL;
```

### 4. No Service Layer Changes Required

The audit tracking happens automatically at the JPA level. No changes are needed to service methods:

```java
@Transactional
public YourEntity create(YourEntity entity) {
    entity.persist(); // Audit fields automatically set
    return entity;
}

@Transactional
public YourEntity update(String id, YourEntity updated) {
    YourEntity existing = YourEntity.findById(id);
    // ... update fields ...
    return existing; // updatedAt/modifiedBy automatically updated
}
```

## REST API Impact

### Backward Compatibility

Adding audit fields to an entity is **backward compatible** with existing REST clients:

- **GET requests**: Responses include new audit fields. Clients can ignore them.
- **POST/PUT requests**: New fields in request body are ignored (marked `READ_ONLY`).
- **No breaking changes**: API contract remains stable.

### Response Example

```json
{
  "uuid": "123e4567-e89b-12d3-a456-426614174000",
  "name": "Project Alpha",
  "purpose": "Internal tool development",
  "createdAt": "2025-10-08T10:30:00",
  "updatedAt": "2025-10-08T14:45:00",
  "createdBy": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "modifiedBy": "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
}
```

## Edge Cases and Error Handling

### Missing User Identifier

If no user identifier is available (rare), the system defaults to "system":
- RequestHeaderHolder returns null → "system"
- X-Requested-By header missing and no JWT → "anonymous" (then "system" in listener)

### CDI Context Unavailable

If CDI context is not available when the listener executes:
- Error is logged
- Transaction continues (audit tracking failure doesn't break data operations)
- This is a graceful degradation

### Batch Operations

Each entity save/update in a batch triggers the listener individually:
- All entities in the batch get proper audit tracking
- User identifier remains consistent within the same request

## Testing

### Unit Testing the Listener

Test the AuditEntityListener in isolation:

```java
@Test
void testPrePersist_setsAllAuditFields() {
    // Mock RequestHeaderHolder to return a user UUID
    // Create entity and call listener.prePersist()
    // Assert all audit fields are set
}
```

### Integration Testing

Test end-to-end with actual REST calls:

```java
@Test
void testCreateEntity_includesAuditFields() {
    // POST entity with X-Requested-By header
    // GET entity and verify audit fields in response
    // Assert createdBy matches header UUID
}

@Test
void testUpdateEntity_updatesModificationFields() {
    // Create entity
    // Update entity with different user UUID
    // Assert updatedAt is later and modifiedBy changed
    // Assert createdAt and createdBy unchanged
}
```

## Examples in Codebase

### ProjectDescription

See `dk.trustworks.intranet.knowledgeservice.model.ProjectDescription` for a complete implementation example.

### InvoiceBonus

See `dk.trustworks.intranet.aggregates.invoice.bonus.model.InvoiceBonus` for a manual audit tracking approach (sets `addedBy` explicitly in service layer rather than using the automatic listener).

## Troubleshooting

### Audit fields are null after creation

1. Check entity has `@EntityListeners(AuditEntityListener.class)`
2. Verify entity implements `Auditable` interface
3. Check RequestHeaderHolder is being populated (debug HeaderInterceptor)
4. Look for errors in logs from AuditEntityListener

### Wrong user UUID in audit fields

1. Verify client sends correct UUID in `X-Requested-By` header
2. Check HeaderInterceptor priority logic
3. Ensure JWT token contains correct claims if using fallback

### Audit fields not included in REST response

1. Verify fields have getters (Lombok `@Data` generates them)
2. Check fields don't have `@JsonIgnore`
3. Ensure `@JsonProperty(access = READ_ONLY)` is present (allows read, blocks write)

## Best Practices

1. **Always use @JsonProperty(access = READ_ONLY)** on audit fields to prevent client override
2. **Make created fields non-nullable** (`createdAt`, `createdBy`) for data integrity
3. **Keep modified_by nullable** to support existing records that haven't been updated
4. **Use VARCHAR(255)** for user identifier fields to accommodate various formats
5. **Document in API schemas** that audit fields are read-only and automatically managed
6. **Don't modify audit fields manually** in service layer - let the listener handle it
7. **Test both creation and update scenarios** to ensure proper audit tracking

## References

- JPA Lifecycle Callbacks: https://jakarta.ee/specifications/persistence/3.1/
- CDI Programmatic Lookup: https://jakarta.ee/specifications/cdi/4.0/
- Jackson JSON Property Access: https://fasterxml.github.io/jackson-annotations/
