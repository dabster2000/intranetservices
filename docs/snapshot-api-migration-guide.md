# Snapshot API Migration Guide

## For Client Developers

This guide helps you migrate from the old `/bonuspool/locked` API to the new generic `/snapshots` API.

## Why Migrate?

The old API (`/bonuspool/locked`) is **deprecated** and will be removed in a future version. Benefits of migrating:

✅ **Generic Design** - Works with any entity type, not just bonus pools
✅ **Versioning Support** - Multiple snapshots per entity over time
✅ **Better Performance** - Optimized caching and indexing
✅ **RESTful API** - Follows modern REST best practices
✅ **Future-Proof** - Designed for long-term maintainability

## Timeline

- **Now (V91)**: New API available, old API still works
- **V92-V99**: Transition period - migrate your clients
- **V100+**: Old API will be removed

## Quick Reference

### URL Mapping

| Old API | New API |
|---------|---------|
| `GET /bonuspool/locked` | `GET /snapshots/bonus_pool` |
| `GET /bonuspool/locked/{year}` | `GET /snapshots/bonus_pool/{year}` |
| `GET /bonuspool/locked/{year}/exists` | `GET /snapshots/bonus_pool/{year}/exists` |
| `POST /bonuspool/locked` | `POST /snapshots` |
| `DELETE /bonuspool/locked/{year}` | `DELETE /snapshots/bonus_pool/{year}` |
| `GET /bonuspool/locked/by-user/{user}` | `GET /snapshots?lockedBy={user}&entityType=bonus_pool` |
| `GET /bonuspool/locked/after/{date}` | `GET /snapshots?after={date}&entityType=bonus_pool` |

### Key Differences

| Aspect | Old API | New API |
|--------|---------|---------|
| **Entity Type** | Hardcoded (bonus_pool only) | Generic (any entity type) |
| **Versioning** | One lock per fiscal year | Multiple versions per entity |
| **URL Structure** | `/bonuspool/locked/{year}` | `/snapshots/{type}/{id}` |
| **Request Body** | `{fiscalYear, poolContextJson, lockedBy}` | `{entityType, entityId, data, lockedBy}` |
| **Response** | `LockedBonusPoolData` | `ImmutableSnapshot` |

## Migration Steps

### Step 1: Understand Response Format Changes

**Old Response** (`LockedBonusPoolData`):
```json
{
  "fiscalYear": 2024,
  "poolContextJson": "{...}",
  "lockedAt": "2024-07-01T10:00:00",
  "lockedBy": "admin@trustworks.dk",
  "checksum": "a1b2c3d4...",
  "version": 1,
  "createdAt": "2024-07-01T10:00:00",
  "updatedAt": "2024-07-01T10:00:00"
}
```

**New Response** (`ImmutableSnapshot`):
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

**Field Mapping**:
- `fiscalYear` → `entityId` (as string)
- `poolContextJson` → `snapshotData`
- `(no equivalent)` → `entityType` (always "bonus_pool" for bonus pools)
- `(no equivalent)` → `snapshotVersion` (new feature)
- `(no equivalent)` → `metadata` (new feature)

### Step 2: Update Your API Calls

#### Create Snapshot

**Before**:
```http
POST /bonuspool/locked
Content-Type: application/json
Authorization: Bearer {token}

{
  "fiscalYear": 2024,
  "poolContextJson": "{\"fiscalYear\": 2024, \"poolSize\": 100000}",
  "lockedBy": "admin@trustworks.dk"
}
```

**After**:
```http
POST /snapshots
Content-Type: application/json
Authorization: Bearer {token}

{
  "entityType": "bonus_pool",
  "entityId": "2024",
  "data": "{\"fiscalYear\": 2024, \"poolSize\": 100000}",
  "lockedBy": "admin@trustworks.dk"
}
```

#### Get Snapshot

**Before**:
```http
GET /bonuspool/locked/2024
Authorization: Bearer {token}
```

**After**:
```http
GET /snapshots/bonus_pool/2024
Authorization: Bearer {token}
```

#### Check if Exists

**Before**:
```http
GET /bonuspool/locked/2024/exists
Authorization: Bearer {token}
```

**After**:
```http
GET /snapshots/bonus_pool/2024/exists
Authorization: Bearer {token}
```

#### Delete Snapshot

**Before**:
```http
DELETE /bonuspool/locked/2024
Authorization: Bearer {token}
```

**After**:
```http
DELETE /snapshots/bonus_pool/2024
Authorization: Bearer {token}
```

#### List All

**Before**:
```http
GET /bonuspool/locked
Authorization: Bearer {token}
```

**After**:
```http
GET /snapshots/bonus_pool?page=0&size=50
Authorization: Bearer {token}
```

Note: New API adds pagination support!

#### Query by User

**Before**:
```http
GET /bonuspool/locked/by-user/admin@trustworks.dk
Authorization: Bearer {token}
```

**After**:
```http
GET /snapshots?lockedBy=admin@trustworks.dk&entityType=bonus_pool
Authorization: Bearer {token}
```

#### Query by Date

**Before**:
```http
GET /bonuspool/locked/after/2024-01-01T00:00:00
Authorization: Bearer {token}
```

**After**:
```http
GET /snapshots?after=2024-01-01T00:00:00&entityType=bonus_pool
Authorization: Bearer {token}
```

### Step 3: Update Client Code

#### Java Client (REST Assured)

**Before**:
```java
public class BonusPoolClient {

    public LockedBonusPoolData createLock(int fiscalYear, String json) {
        return given()
            .auth().oauth2(token)
            .contentType(ContentType.JSON)
            .body(Map.of(
                "fiscalYear", fiscalYear,
                "poolContextJson", json,
                "lockedBy", "admin@trustworks.dk"
            ))
            .when()
            .post("/bonuspool/locked")
            .then()
            .statusCode(201)
            .extract()
            .as(LockedBonusPoolData.class);
    }

    public LockedBonusPoolData getByYear(int fiscalYear) {
        return given()
            .auth().oauth2(token)
            .when()
            .get("/bonuspool/locked/" + fiscalYear)
            .then()
            .statusCode(200)
            .extract()
            .as(LockedBonusPoolData.class);
    }
}
```

**After**:
```java
public class SnapshotClient {

    public ImmutableSnapshot createSnapshot(String entityType, String entityId, String data) {
        return given()
            .auth().oauth2(token)
            .contentType(ContentType.JSON)
            .body(Map.of(
                "entityType", entityType,
                "entityId", entityId,
                "data", data,
                "lockedBy", "admin@trustworks.dk"
            ))
            .when()
            .post("/snapshots")
            .then()
            .statusCode(201)
            .extract()
            .as(ImmutableSnapshot.class);
    }

    public ImmutableSnapshot getSnapshot(String entityType, String entityId) {
        return given()
            .auth().oauth2(token)
            .when()
            .get(String.format("/snapshots/%s/%s", entityType, entityId))
            .then()
            .statusCode(200)
            .extract()
            .as(ImmutableSnapshot.class);
    }

    // Bonus pool specific convenience methods
    public ImmutableSnapshot createBonusPoolSnapshot(int fiscalYear, String json) {
        return createSnapshot("bonus_pool", String.valueOf(fiscalYear), json);
    }

    public ImmutableSnapshot getBonusPoolSnapshot(int fiscalYear) {
        return getSnapshot("bonus_pool", String.valueOf(fiscalYear));
    }
}
```

#### JavaScript/TypeScript Client

**Before**:
```javascript
class BonusPoolClient {
    async createLock(fiscalYear, poolContextJson) {
        const response = await fetch('/bonuspool/locked', {
            method: 'POST',
            headers: {
                'Authorization': `Bearer ${token}`,
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                fiscalYear,
                poolContextJson,
                lockedBy: 'admin@trustworks.dk'
            })
        });
        return await response.json();
    }

    async getByYear(fiscalYear) {
        const response = await fetch(`/bonuspool/locked/${fiscalYear}`, {
            headers: {
                'Authorization': `Bearer ${token}`
            }
        });
        return await response.json();
    }
}
```

**After**:
```typescript
interface CreateSnapshotRequest {
    entityType: string;
    entityId: string;
    data: any;
    lockedBy: string;
}

interface ImmutableSnapshot {
    id: number;
    entityType: string;
    entityId: string;
    snapshotVersion: number;
    snapshotData: string;
    checksum: string;
    metadata?: string;
    lockedAt: string;
    lockedBy: string;
    createdAt: string;
    updatedAt: string;
    version: number;
}

class SnapshotClient {
    async createSnapshot(request: CreateSnapshotRequest): Promise<ImmutableSnapshot> {
        const response = await fetch('/snapshots', {
            method: 'POST',
            headers: {
                'Authorization': `Bearer ${token}`,
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(request)
        });
        return await response.json();
    }

    async getSnapshot(entityType: string, entityId: string): Promise<ImmutableSnapshot> {
        const response = await fetch(`/snapshots/${entityType}/${entityId}`, {
            headers: {
                'Authorization': `Bearer ${token}`
            }
        });
        return await response.json();
    }

    // Bonus pool specific convenience methods
    async createBonusPoolSnapshot(fiscalYear: number, data: any): Promise<ImmutableSnapshot> {
        return this.createSnapshot({
            entityType: 'bonus_pool',
            entityId: fiscalYear.toString(),
            data,
            lockedBy: 'admin@trustworks.dk'
        });
    }

    async getBonusPoolSnapshot(fiscalYear: number): Promise<ImmutableSnapshot> {
        return this.getSnapshot('bonus_pool', fiscalYear.toString());
    }
}
```

### Step 4: Adapt to Response Format

If your code depends on the old response structure, create an adapter:

#### Java Adapter

```java
public class SnapshotAdapter {

    /**
     * Convert ImmutableSnapshot to legacy LockedBonusPoolData format.
     * Use during migration period for backward compatibility.
     */
    public static LockedBonusPoolData toLegacyFormat(ImmutableSnapshot snapshot) {
        if (!"bonus_pool".equals(snapshot.getEntityType())) {
            throw new IllegalArgumentException("Not a bonus pool snapshot");
        }

        LockedBonusPoolData legacy = new LockedBonusPoolData();
        legacy.fiscalYear = Integer.parseInt(snapshot.getEntityId());
        legacy.poolContextJson = snapshot.getSnapshotData();
        legacy.lockedAt = snapshot.getLockedAt();
        legacy.lockedBy = snapshot.getLockedBy();
        legacy.checksum = snapshot.getChecksum();
        legacy.version = snapshot.getVersion();
        legacy.createdAt = snapshot.getCreatedAt();
        legacy.updatedAt = snapshot.getUpdatedAt();
        return legacy;
    }

    /**
     * Extract fiscal year from snapshot.
     */
    public static int getFiscalYear(ImmutableSnapshot snapshot) {
        return Integer.parseInt(snapshot.getEntityId());
    }

    /**
     * Extract pool context JSON from snapshot.
     */
    public static String getPoolContextJson(ImmutableSnapshot snapshot) {
        return snapshot.getSnapshotData();
    }
}
```

#### TypeScript Adapter

```typescript
interface LockedBonusPoolData {
    fiscalYear: number;
    poolContextJson: string;
    lockedAt: string;
    lockedBy: string;
    checksum: string;
    version: number;
    createdAt: string;
    updatedAt: string;
}

class SnapshotAdapter {
    static toLegacyFormat(snapshot: ImmutableSnapshot): LockedBonusPoolData {
        if (snapshot.entityType !== 'bonus_pool') {
            throw new Error('Not a bonus pool snapshot');
        }

        return {
            fiscalYear: parseInt(snapshot.entityId),
            poolContextJson: snapshot.snapshotData,
            lockedAt: snapshot.lockedAt,
            lockedBy: snapshot.lockedBy,
            checksum: snapshot.checksum,
            version: snapshot.version,
            createdAt: snapshot.createdAt,
            updatedAt: snapshot.updatedAt
        };
    }

    static getFiscalYear(snapshot: ImmutableSnapshot): number {
        return parseInt(snapshot.entityId);
    }

    static getPoolContextJson(snapshot: ImmutableSnapshot): string {
        return snapshot.snapshotData;
    }
}
```

## New Features Available

### Versioning

The new API supports multiple snapshots of the same entity:

```http
# Get latest version
GET /snapshots/bonus_pool/2024

# Get specific version
GET /snapshots/bonus_pool/2024/1
GET /snapshots/bonus_pool/2024/2

# List all versions
GET /snapshots/bonus_pool/2024/versions
```

### Pagination

List endpoints now support pagination:

```http
GET /snapshots/bonus_pool?page=0&size=50
GET /snapshots/bonus_pool?page=1&size=50
```

### Statistics

Get counts by entity type:

```http
GET /snapshots/stats

Response:
{
  "bonus_pool": 5,
  "contract": 120,
  "total": 125
}
```

### Metadata

Snapshots include queryable metadata:

```json
{
  "metadata": "{\"fiscalYear\": 2024, \"poolSize\": 100000}"
}
```

## Testing Your Migration

### Compatibility Test

Ensure old and new APIs return equivalent data:

```java
@Test
void testApiCompatibility() {
    int fiscalYear = 2024;

    // Get from old API
    LockedBonusPoolData oldData =
        oldClient.getByYear(fiscalYear);

    // Get from new API
    ImmutableSnapshot newData =
        newClient.getSnapshot("bonus_pool", String.valueOf(fiscalYear));

    // Verify equivalence
    assertEquals(fiscalYear, Integer.parseInt(newData.getEntityId()));
    assertEquals(oldData.poolContextJson, newData.getSnapshotData());
    assertEquals(oldData.checksum, newData.getChecksum());
    assertEquals(oldData.lockedBy, newData.getLockedBy());
    assertEquals(oldData.lockedAt, newData.getLockedAt());
}
```

### Integration Test

Test full workflow with new API:

```java
@Test
void testNewApiWorkflow() {
    String fiscalYear = "2024";
    String json = "{\"fiscalYear\": 2024, \"poolSize\": 100000}";

    // Create snapshot
    ImmutableSnapshot created = client.createSnapshot(
        "bonus_pool",
        fiscalYear,
        json,
        "test@example.com"
    );

    assertNotNull(created.getId());
    assertEquals("bonus_pool", created.getEntityType());
    assertEquals(fiscalYear, created.getEntityId());
    assertEquals(1, created.getSnapshotVersion());

    // Verify exists
    assertTrue(client.exists("bonus_pool", fiscalYear));

    // Retrieve
    ImmutableSnapshot retrieved =
        client.getSnapshot("bonus_pool", fiscalYear);

    assertEquals(created.getId(), retrieved.getId());
    assertEquals(json, retrieved.getSnapshotData());

    // Verify checksum
    assertNotNull(retrieved.getChecksum());
}
```

## Troubleshooting

### Issue: 404 Not Found

**Symptom**: `GET /snapshots/bonus_pool/2024` returns 404

**Cause**: No snapshot exists for that entity

**Solution**: Check if snapshot was created, verify entity_id format

### Issue: Response Format Different

**Symptom**: Client code breaks because fields are missing

**Cause**: Response structure changed from `LockedBonusPoolData` to `ImmutableSnapshot`

**Solution**: Use adapter pattern (see Step 4) or update your DTOs

### Issue: Integer vs String

**Symptom**: `entityId` is string but you need integer

**Cause**: New API uses flexible string IDs

**Solution**: Convert with `Integer.parseInt(snapshot.getEntityId())`

### Issue: Missing fiscalYear Field

**Symptom**: Old code accesses `data.fiscalYear` which doesn't exist

**Cause**: fiscalYear is now in entityId and metadata

**Solution**: Parse from entityId or extract from metadata JSON

### Issue: Authorization Failed

**Symptom**: 403 Forbidden

**Cause**: New API requires SYSTEM or ADMIN role

**Solution**: Ensure your JWT token has correct roles

## Rollback Plan

If you encounter issues after migration:

1. **Old API still works** - You can continue using it during transition
2. **Dual support** - Call both APIs in parallel and compare results
3. **Gradual migration** - Migrate one feature at a time
4. **Feature flag** - Use flag to toggle between old and new API

Example feature flag approach:

```java
@Inject
@ConfigProperty(name = "snapshot.api.use-new", defaultValue = "false")
boolean useNewApi;

public SnapshotData getSnapshot(int fiscalYear) {
    if (useNewApi) {
        return newClient.getSnapshot("bonus_pool", String.valueOf(fiscalYear));
    } else {
        return oldClient.getByYear(fiscalYear);
    }
}
```

## Support

### Documentation

- Technical documentation: `docs/immutable-snapshot-system.md`
- OpenAPI spec: Available at `/q/openapi` in dev mode
- Swagger UI: Available at `/q/swagger-ui` in dev mode

### Contact

For questions or issues during migration:
- Create issue in GitHub repository
- Contact development team
- Check logs for error details

## Checklist

Use this checklist to track your migration progress:

- [ ] Read and understand this guide
- [ ] Review response format changes
- [ ] Update API endpoint URLs
- [ ] Update request body structure
- [ ] Update response parsing logic
- [ ] Add adapter if needed for backward compatibility
- [ ] Write compatibility tests
- [ ] Test in development environment
- [ ] Test in staging environment
- [ ] Update error handling
- [ ] Update logging/monitoring
- [ ] Deploy to production
- [ ] Monitor for errors
- [ ] Remove old API usage
- [ ] Remove adapters (if used)
- [ ] Clean up deprecated code

## Example: Complete Migration

Here's a complete before/after example:

### Before (Old API)

```java
@ApplicationScoped
public class BonusPoolService {

    @Inject
    @RestClient
    IntranetClient client;

    public void lockBonusPool(int fiscalYear, FiscalYearPoolContext context) {
        String json = serializeToJson(context);

        LockRequest request = new LockRequest(
            fiscalYear,
            json,
            "system"
        );

        LockedBonusPoolData result = client.lockBonusPool(request);

        Log.infof("Locked fiscal year %d with checksum %s",
            result.fiscalYear, result.checksum);
    }

    public FiscalYearPoolContext getBonusPool(int fiscalYear) {
        LockedBonusPoolData data = client.getBonusPool(fiscalYear);

        if (data == null) {
            throw new NotFoundException("Fiscal year " + fiscalYear + " not locked");
        }

        return deserializeFromJson(data.poolContextJson);
    }
}
```

### After (New API)

```java
@ApplicationScoped
public class BonusPoolService {

    @Inject
    @RestClient
    IntranetClient client;

    public void lockBonusPool(int fiscalYear, FiscalYearPoolContext context) {
        String json = serializeToJson(context);

        CreateSnapshotRequest request = new CreateSnapshotRequest(
            "bonus_pool",
            String.valueOf(fiscalYear),
            json,
            "system"
        );

        ImmutableSnapshot result = client.createSnapshot(request);

        Log.infof("Created snapshot %s:%s v%d with checksum %s",
            result.getEntityType(),
            result.getEntityId(),
            result.getSnapshotVersion(),
            result.getChecksum());
    }

    public FiscalYearPoolContext getBonusPool(int fiscalYear) {
        ImmutableSnapshot snapshot = client.getSnapshot(
            "bonus_pool",
            String.valueOf(fiscalYear)
        );

        if (snapshot == null) {
            throw new NotFoundException("Fiscal year " + fiscalYear + " not locked");
        }

        return deserializeFromJson(snapshot.getSnapshotData());
    }
}
```

## Summary

**Key Takeaways**:

1. Old API is deprecated but still works - no rush, but plan migration
2. Main changes: URL structure, request/response format
3. New API adds versioning, pagination, and generic entity support
4. Use adapters during transition for backward compatibility
5. Test thoroughly before deploying
6. Old API will be removed in V100+

**Migration effort**: Low to Medium
- Small clients: 1-2 hours
- Medium clients: 1 day
- Large clients: 2-3 days

**Benefits**: Future-proof, better performance, more features

Good luck with your migration! 🚀
