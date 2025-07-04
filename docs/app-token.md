# App & Token API

The `/apps` endpoints manage applications and their access tokens. All calls require an `Authorization: Bearer <accessToken>` header.

## Create an App
```bash
curl -X POST http://localhost:8080/apps \
  -H "Authorization: Bearer <accessToken>" \
  -H "Content-Type: application/json" \
  -d '{"name":"demo"}'
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
