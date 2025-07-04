# Refresh Token

`POST /auth/refresh` exchanges a refresh token for a new access token and returns a new refresh token. The provided token must be sent in the `Authorization: Bearer` header and becomes invalid immediately after use.

See [app-token.md](app-token.md) for examples on how to create and revoke tokens.
