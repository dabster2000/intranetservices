# Economics Error Mapper

An exception mapper exposes the validation details returned by e‑conomics when a request fails.

`EconomicsErrorMapper` reads the response body and includes it in the thrown exception. Each `EconomicsAPI*` REST client registers this mapper using `@RegisterProvider` so Quarkus logs now contain the exact validation error instead of a generic 400.
