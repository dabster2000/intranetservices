# Photo Service Optimization

Images are cached after the first retrieval from S3. Subsequent requests for the same UUID avoid an S3 round-trip.

The photo endpoints support an optional `width` query parameter. Both `/files/photos/{uuid}` and `/files/photos/{uuid}/jpg` as well as the internal `/photos/{uuid}` resource accept it. When supplied the service resizes the image using the Claid API before returning it as WebP.

Resized images are looked up in S3 using the key `resized/{width}/{uuid}`. If a resized image is not present a new one is created through Claid, returned to the caller and stored asynchronously back to S3. Both original and resized images are cached in memory for fast repeated access. JSON requests to Claid are constructed programmatically to keep the integration consistent with existing Claid usage.
