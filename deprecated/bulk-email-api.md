# Bulk Email API - Developer Guide

## Overview

The Bulk Email API allows you to send the same email message with attachments to multiple recipients efficiently. Unlike the single email endpoint (`/knowledge/conferences/message`), the bulk email endpoint queues emails for asynchronous batch processing with automatic throttling to prevent spam and respect rate limits.

## When to Use Bulk vs Single Email

### Use Bulk Email (`/knowledge/conferences/bulk-message`) when:
- ✅ Sending the **same message** to **multiple recipients** (2+)
- ✅ Sending to 10+ recipients
- ✅ You can tolerate asynchronous delivery (emails sent over time)
- ✅ You need progress tracking and detailed status per recipient

### Use Single Email (`/knowledge/conferences/message`) when:
- ✅ Sending to a **single recipient**
- ✅ Message content is **personalized** per recipient
- ✅ Immediate delivery is required
- ✅ No throttling needed

## API Endpoint

**POST** `/knowledge/conferences/bulk-message`

**Content-Type:** `application/json`

## Request Format

```json
{
  "subject": "Conference Information Update",
  "body": "<html><body><h1>Hello!</h1><p>Please find the agenda attached.</p></body></html>",
  "recipients": [
    "participant1@example.com",
    "participant2@example.com",
    "participant3@example.com"
  ],
  "attachments": [
    {
      "filename": "agenda.pdf",
      "contentType": "application/pdf",
      "content": "JVBERi0xLjQKJeLjz9MKMyAwIG9iaiA8PC9UeXBlIC9QYWdlcyAvS2lkcyBbNCAwIFIgXSA..."
    }
  ]
}
```

### Request Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `subject` | String | Yes | Email subject line (max 255 chars) |
| `body` | String | Yes | Email body (HTML content) |
| `recipients` | Array[String] | Yes | List of recipient email addresses (1-1000) |
| `attachments` | Array[Object] | No | Optional file attachments (max 10 files) |

### Attachment Object

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `filename` | String | Yes | Original filename (e.g., "agenda.pdf") |
| `contentType` | String | Yes | MIME type (e.g., "application/pdf") |
| `content` | String (Base64) | Yes | File content encoded as base64 |

## Response Format

### Success (200 OK)

```json
{
  "jobId": "123e4567-e89b-12d3-a456-426614174000",
  "recipientCount": 50,
  "status": "PENDING",
  "message": "Bulk email job created successfully. Emails will be sent asynchronously."
}
```

### Error Responses

#### 400 Bad Request - Invalid File Type
```json
{
  "error": "Invalid file type",
  "message": "File 'document.exe' has unsupported type. Allowed types: pdf, doc, docx, xls, xlsx, ppt, pptx, jpg, jpeg, png, gif"
}
```

#### 413 Payload Too Large
```json
{
  "error": "Payload too large",
  "message": "Total attachment size (30.00 MB) exceeds maximum allowed (25 MB)"
}
```

#### 500 Internal Server Error
```json
{
  "error": "Bulk email creation failed",
  "message": "Failed to create bulk email job: Database connection error"
}
```

## Validation Rules

### Recipients
- **Minimum:** 1 recipient
- **Maximum:** 1000 recipients per bulk job
- All recipients must be valid email addresses

### Attachments (same as single email)
- **Max file count:** 10 files
- **Max file size:** 10 MB per file
- **Max total size:** 25 MB (all attachments combined)
- **Allowed file types:**
  - Documents: PDF, DOC, DOCX, XLS, XLSX, PPT, PPTX
  - Images: JPG, JPEG, PNG, GIF

## How It Works

### 1. Request Submission
When you POST to `/knowledge/conferences/bulk-message`, the API:
- Validates attachments
- Creates a bulk email job in the database
- Stores all recipients with `PENDING` status
- Stores attachments (once, reused for all recipients)
- Returns immediately with job ID

### 2. Asynchronous Processing
Every minute, a JBeret batch job (`bulk-mail-send`) runs:
- Finds the oldest `PENDING` bulk email job
- Marks it as `PROCESSING`
- Loads all pending recipients and attachments
- Sends emails one by one with **5-second throttling** between each
- Updates each recipient's status to `SENT` or `FAILED`

### 3. Job Completion
When all recipients are processed:
- Job status is updated to `COMPLETED`
- Final counts are recorded (sent, failed)
- Detailed logs are available in batch job tracking

## Performance Characteristics

- **Throttle Rate:** 1 email every 5 seconds (configurable)
- **Throughput:** ~12 emails per minute, ~720 emails per hour
- **Chunk Size:** 10 recipients per transaction (configurable)
- **Efficiency:** Attachments loaded once, reused for all recipients

### Example Timing
- 10 recipients: ~50 seconds
- 50 recipients: ~4.2 minutes
- 100 recipients: ~8.3 minutes
- 500 recipients: ~42 minutes
- 1000 recipients: ~83 minutes

## Monitoring Job Progress

### Database Queries

Check job status:
```sql
SELECT uuid, subject, status, total_recipients, sent_count, failed_count,
       created_at, started_at, completed_at
FROM bulk_email_job
WHERE uuid = '123e4567-e89b-12d3-a456-426614174000';
```

Check failed recipients:
```sql
SELECT recipient_email, error_message, sent_at
FROM bulk_email_recipient
WHERE job_uuid = '123e4567-e89b-12d3-a456-426614174000'
  AND status = 'FAILED';
```

### JBeret Batch Monitoring

The bulk email job uses the standard JBeret batch tracking infrastructure:
- Job execution logs in `batch_job_execution_tracking` table
- Progress tracking with completed/total subtasks
- Exception tracking for failures
- Detailed trace logs

## Error Handling

### Individual Recipient Failures
If sending to one recipient fails:
- That recipient is marked as `FAILED` with error message
- Processing continues for remaining recipients
- Job completes with partial success

### Job Failures
If the entire job fails (e.g., database error):
- Job is marked as `FAILED`
- Batch tracking records the exception
- Job can be manually retried if needed

### Retry Strategy
- Failed individual sends are NOT automatically retried
- You can create a new bulk job for failed recipients
- Query failed recipients and resubmit

## Code Examples

### cURL

```bash
curl -X POST "https://api.trustworks.dk/knowledge/conferences/bulk-message" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -d '{
    "subject": "Conference Agenda",
    "body": "<html><body><h1>Conference Agenda</h1><p>Please review the attached agenda.</p></body></html>",
    "recipients": [
      "participant1@example.com",
      "participant2@example.com",
      "participant3@example.com"
    ],
    "attachments": [
      {
        "filename": "agenda.pdf",
        "contentType": "application/pdf",
        "content": "'$(base64 -i agenda.pdf)'"
      }
    ]
  }'
```

### Java (Spring RestTemplate)

```java
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class BulkEmailClient {

    public void sendBulkEmail() throws Exception {
        RestTemplate restTemplate = new RestTemplate();

        // Read and encode attachment
        byte[] fileContent = Files.readAllBytes(Paths.get("agenda.pdf"));
        String base64Content = Base64.getEncoder().encodeToString(fileContent);

        // Build request
        Map<String, Object> request = new HashMap<>();
        request.put("subject", "Conference Agenda");
        request.put("body", "<html><body><h1>Hello!</h1></body></html>");
        request.put("recipients", Arrays.asList(
            "participant1@example.com",
            "participant2@example.com"
        ));

        Map<String, String> attachment = new HashMap<>();
        attachment.put("filename", "agenda.pdf");
        attachment.put("contentType", "application/pdf");
        attachment.put("content", base64Content);
        request.put("attachments", Collections.singletonList(attachment));

        // Send request
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth("YOUR_JWT_TOKEN");

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

        ResponseEntity<Map> response = restTemplate.exchange(
            "https://api.trustworks.dk/knowledge/conferences/bulk-message",
            HttpMethod.POST,
            entity,
            Map.class
        );

        System.out.println("Job ID: " + response.getBody().get("jobId"));
        System.out.println("Status: " + response.getBody().get("status"));
    }
}
```

### JavaScript (Fetch API)

```javascript
async function sendBulkEmail() {
  // Read file and convert to base64
  const fileInput = document.getElementById('fileInput');
  const file = fileInput.files[0];
  const base64Content = await fileToBase64(file);

  const request = {
    subject: "Conference Agenda",
    body: "<html><body><h1>Conference Information</h1></body></html>",
    recipients: [
      "participant1@example.com",
      "participant2@example.com",
      "participant3@example.com"
    ],
    attachments: [
      {
        filename: file.name,
        contentType: file.type,
        content: base64Content
      }
    ]
  };

  const response = await fetch('https://api.trustworks.dk/knowledge/conferences/bulk-message', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': 'Bearer YOUR_JWT_TOKEN'
    },
    body: JSON.stringify(request)
  });

  const result = await response.json();
  console.log('Job ID:', result.jobId);
  console.log('Status:', result.status);
}

function fileToBase64(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.readAsDataURL(file);
    reader.onload = () => {
      const base64 = reader.result.split(',')[1];
      resolve(base64);
    };
    reader.onerror = error => reject(error);
  });
}
```

## Best Practices

### 1. Recipient List Management
- ✅ Remove duplicates from recipient list before sending
- ✅ Validate email addresses client-side
- ✅ Split very large lists (1000+ recipients) into multiple jobs

### 2. Attachment Handling
- ✅ Compress large files before attaching (e.g., use PDF compression)
- ✅ Optimize images (resize, compress)
- ✅ Consider links instead of attachments for very large files

### 3. Error Handling
- ✅ Always check the response status code
- ✅ Store the returned `jobId` for tracking
- ✅ Query failed recipients and handle appropriately

### 4. Performance
- ✅ Send bulk emails during off-peak hours if possible
- ✅ Monitor job progress via database queries
- ✅ Don't submit multiple bulk jobs for the same content (merge recipients)

### 5. Content
- ✅ Use HTML for rich formatting
- ✅ Include unsubscribe links where appropriate
- ✅ Test email rendering across different clients

## FAQ

### Q: Can I customize the throttle delay?
**A:** The throttle is configured at 5 seconds by default in the batch job XML (`bulk-mail-send.xml`). System administrators can modify the `throttleMs` property.

### Q: What happens if I submit multiple bulk jobs?
**A:** They are queued and processed one at a time (oldest first). The batch job runs every minute and processes one job per execution.

### Q: Can I cancel a bulk email job?
**A:** Not via the API. Contact system administrators to manually update the job status in the database.

### Q: How do I get notified when the job completes?
**A:** Currently, no notification system exists. You must poll the database or check batch job logs.

### Q: What if some emails fail?
**A:** Failed recipients are marked with error messages. You can query them and create a new bulk job for retry.

### Q: Is there a rate limit?
**A:** Beyond the 5-second throttle, there's no additional rate limiting. However, sending 1000+ emails may trigger spam filters.

## Troubleshooting

### Issue: "Bulk email creation failed"
**Cause:** Database error, validation failure, or system error
**Solution:** Check error message details, verify request format

### Issue: Job stuck in PROCESSING
**Cause:** Batch job crashed or was killed
**Solution:** Check batch job execution logs, manually update job status if needed

### Issue: All recipients marked as FAILED
**Cause:** Email server configuration issue
**Solution:** Check SMTP settings in `application.yml`, verify email credentials

### Issue: Slow processing
**Cause:** 5-second throttle is working as designed
**Solution:** This is expected. For 100 recipients, expect ~8 minutes

## Related Documentation

- [API Usage Logging](api-usage-logging.md)
- [Batch Exception Tracking](batch-exception-tracking-migration.md)
- Single Email Endpoint (see OpenAPI docs)

## Support

For issues or questions:
- Create an issue in the project repository
- Contact the development team
- Check application logs: `/var/log/trustworks-intranet/`
