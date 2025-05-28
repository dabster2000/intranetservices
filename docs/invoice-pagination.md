# Invoice Pagination API

The `/invoices` endpoint now supports optional paging parameters to limit the number of invoices returned.

`GET /invoices?page=X&size=Y` returns page `X` with `Y` invoices per page. If `page` and `size` are omitted the endpoint behaves as before.

To get the total number of invoices without loading them use:

`GET /invoices/count`

Both endpoints log debug messages indicating requested page information and counts.
