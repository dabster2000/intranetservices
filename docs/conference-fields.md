# Conference Data Fields

The `Conference` entity now exposes additional metadata fields. These values are stored in the database and returned by the `/knowledge/conferences` API.

| Field | Description |
|-------|-------------|
| `description` | Optional description of the conference. |
| `noteText` | Text shown on the registration form as a note. |
| `consentText` | Text describing the consent given by the participant. |
| `thanksText` | Text shown after successfully registering. |

Database migrations are provided via Flyway to add these columns.
