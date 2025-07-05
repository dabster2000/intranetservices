# App Membership API

The `/apps` resource now exposes endpoints for managing users in an application.
All requests require authentication via the `Authorization` header.

## Listing Apps with Paging

```bash
GET /apps?user=<userUuid>&page=0&size=50
```

Returns page `0` with up to `50` apps for the given user. Use
`GET /apps/count?user=<userUuid>` to retrieve the total number of apps.

## Managing Members

```bash
GET    /apps/{appUuid}/members
POST   /apps/{appUuid}/members/{userUuid}
PUT    /apps/{appUuid}/members/{userUuid}?role=APP_ADMIN
DELETE /apps/{appUuid}/members/{userUuid}
```

These endpoints list, add, update and remove members. Roles are `APP_ADMIN` or
`APP_MEMBER`.

Debug and info log statements in `AppResource` and `AppService` describe each
operation.
