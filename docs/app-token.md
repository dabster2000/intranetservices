# App & Token API

The `/apps` endpoints manage applications and their access tokens. All calls require an `Authorization: Bearer <accessToken>` header.
Refresh tokens are opaque UUID strings that are only returned once at creation time. The plain value is never persisted.
Every call to `POST /auth/refresh` returns a new short lived access token **and** a new refresh token, invalidating the previous one (rotation).

## Create an App
```bash
curl -X POST http://localhost:8080/apps \
  -H "Authorization: Bearer <accessToken>" \
  -H "Content-Type: application/json" \
  -d '{"name":"demo"}'
```

## List Apps for a User
```bash
curl http://localhost:8080/apps?user=<userUuid> \
  -H "Authorization: Bearer <accessToken>"
```

## Create a Refresh Token
```bash
curl -X POST http://localhost:8080/apps/{appUuid}/tokens \
  -H "Authorization: Bearer <accessToken>"
```

## Refresh a Token
```bash
curl -X POST http://localhost:8080/auth/refresh \
  -H "Authorization: Bearer <refreshToken>"
```

## Revoke a Token
```bash
curl -X DELETE http://localhost:8080/apps/{appUuid}/tokens/{tokenId} \
  -H "Authorization: Bearer <accessToken>"
```

For details on refresh tokens see [refresh-token.md](refresh-token.md). Pagination of list endpoints follows the conventions in [invoice-pagination.md](invoice-pagination.md).
