# Invoice Sorting API

The `/invoices` endpoint supports optional `sort` parameters to order the returned invoices server-side.

`sort` values use the format `field,dir` where `dir` is `asc` or `desc`. The parameter may be repeated to specify secondary fields. When omitted the result defaults to `invoicedate,desc`.

## Supported fields

- `invoicenumber`
- `uuid`
- `clientname`
- `projectname`
- `sumnotax`
- `type`
- `invoicedate`
- `bookingdate`

Unknown fields result in `400 Bad Request` with a body like `{"error":"Unsupported sort field: field"}`.

### Examples

Single sort

```http
GET /invoices?page=0&size=50&sort=invoicedate,desc
```

Composite sort

```http
GET /invoices?page=2&size=25&sort=clientname,asc&sort=projectname,asc&sort=invoicedate,desc
```

Only sorting

```http
GET /invoices?sort=sumnotax,desc
```
