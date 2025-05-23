# Updating BorrowedDevice REST Clients

The `BorrowedDevice` API now tracks which client a device belongs to. Each `BorrowedDevice` includes a `clientuuid` property referencing the `Client` table.

## Required changes

1. When creating or updating a borrowed device, include the `clientuuid` field in the JSON payload. Example:
   ```json
   {
     "uuid": "<device uuid>",
     "useruuid": "<user uuid>",
     "clientuuid": "<client uuid>",
     "type": "LAPTOP",
     "description": "MacBook Pro",
     "serial": "C02XXX",
     "borrowedDate": "2024-05-01",
     "returnedDate": null
   }
   ```
2. Existing GET endpoints will now return `clientuuid` for each device. Update your models accordingly.
3. The REST service methods `saveBorrowedDevice` and `updateBorrowedDevice` must set this property before calling the API.

Failing to provide `clientuuid` will result in a validation error once the new database column is active.
