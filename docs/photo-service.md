# Photo Service Optimization

Images are cached after the first retrieval from S3. Subsequent requests for the same UUID avoid an S3 round-trip.

The `/files/photos/{uuid}/jpg` endpoint accepts an optional `width` query parameter. When provided the photo is resized using Claid before being returned.

Resized images are looked up in S3 using the key `resized/{width}/{uuid}`. If not present a new one is generated, returned to the caller and stored asynchronously back to S3. Both original and resized images are cached in memory for fast repeated access.
