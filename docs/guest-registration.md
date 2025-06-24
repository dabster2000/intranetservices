# Guest Registration API

The `/registration/guest` endpoints store visitor information in the database.

## Endpoints

- `POST /registration/guest` – save a new guest registration. The body matches `RegistrationRequest`.
- `GET /registration/guest` – retrieve all registrations sorted by time.
- `GET /registration/guest/today` – list todays guests.
- `GET /registration/guest/day/{yyyy-MM-dd}` – list guests for a specific date.
- `GET /registration/guest/employee/{uuid}` – list guests associated with a specific employee.

Each call logs a debug message describing the operation.

When a guest is registered the employee from the request is notified through
Slack using the configured bot token.
