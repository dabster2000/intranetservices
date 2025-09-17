# File and Photo System Documentation

## System Overview

The Trustworks intranet services file and photo system provides comprehensive file management capabilities with specialized handling for photos, documents, and expense files. The system utilizes AWS S3 for persistent storage and implements intelligent caching and image processing features.

## Architecture Components

### 1. Storage Layer
- **Primary Storage**: AWS S3 buckets
  - Files bucket: General file and photo storage
  - Expenses bucket: Dedicated expense file storage
- **Database**: MariaDB for file metadata
  - Table: `files` - Stores file metadata and relationships
  - Primary key: UUID-based identification

### 2. Service Layer

#### PhotoService (`fileservice/resources/PhotoService.java`)
Core photo management service with advanced features:
- **Photo Retrieval**: Fetches photos by UUID or related entity UUID
- **Dynamic Resizing**: On-demand image resizing with Claid AI integration
- **WebP Conversion**: Automatic format optimization
- **Multi-tier Caching**:
  - L1: In-memory cache using Quarkus/Caffeine
  - L2: S3 resized image cache (`resized/{width}/{uuid}`)
- **Portrait Enhancement**: Specialized processing for user portraits
- **Logo Processing**: Optimized handling for company logos

#### S3FileService (`fileservice/services/S3FileService.java`)
Generic S3 file operations:
- Direct S3 interaction for file CRUD operations
- MIME type detection using Apache Tika
- File listing and retrieval
- Transactional file persistence

#### UserDocumentResource (`fileservice/resources/UserDocumentResource.java`)
Document management service:
- User-specific document retrieval
- Document metadata management
- S3 backed document storage

#### ExpenseFileService (`expenseservice/services/ExpenseFileService.java`)
Specialized service for expense receipts:
- Dedicated S3 bucket for expense files
- String-based file storage for receipt data
- Separate from general file storage for compliance

### 3. API Layer

#### FileResource (`apigateway/resources/FileResource.java`)
Main REST API endpoint at `/files`:

**Photo Endpoints:**
- `GET /files/photos/{relateduuid}` - Get photo metadata with optional resizing
- `GET /files/photos/{relateduuid}/jpg` - Get raw image bytes with caching headers
- `GET /files/photos/types/{type}` - Get random photo by type
- `GET /files/photos/types/{type}/all` - List all photos of a type
- `PUT /files/photos` - Upload/update general photo
- `PUT /files/photos/logo` - Upload/update logo with specialized processing
- `PUT /files/photos/portrait` - Upload/update portrait with face enhancement
- `DELETE /files/photos/{uuid}` - Delete photo

**Document Endpoints:**
- `GET /files/documents` - List all documents
- `GET /files/documents/{uuid}` - Get document by UUID
- `DELETE /files/documents/{uuid}` - Delete document

**S3 Management:**
- `GET /files/s3` - List all S3 files
- `GET /files/s3/{uuid}` - Get specific S3 file

## Data Model

### File Entity
```java
- uuid: String (Primary Key)
- relateduuid: String (Links to related entities)
- type: String (PHOTO, DOCUMENT, etc.)
- name: String
- filename: String
- uploaddate: LocalDate
- file: byte[] (Transient - not persisted to DB)
```

## Key Features

### 1. Intelligent Photo Resizing
- **On-Demand Processing**: Images resized when first requested
- **Claid AI Integration**: High-quality resizing with face detection
- **Cache Strategy**:
  1. Check memory cache
  2. Check S3 for pre-resized version
  3. Resize via Claid if not found
  4. Store in S3 asynchronously
  5. Return to client with cache headers

### 2. Multi-Format Support
- Automatic MIME type detection
- WebP conversion for optimization
- Format-appropriate processing
- Dynamic content-type headers

### 3. Performance Optimizations
- **Caching Layers**:
  - Caffeine in-memory cache (photo-cache, photo-resize-cache)
  - S3 persistent cache for resized images
  - HTTP cache headers (max-age=600)
- **Async Operations**: Background S3 uploads for resized images
- **Lazy Loading**: Files loaded from S3 only when accessed

### 4. Specialized Processing Pipelines
- **Portraits**: Face enhancement and smart cropping
- **Logos**: Business-appropriate sizing and enhancement
- **Documents**: Direct binary storage without processing

## Security Considerations
- Role-based access control (SYSTEM role required)
- UUID-based resource identification
- Separate buckets for different data types
- No direct S3 access from clients

## Configuration
```yaml
bucket.files: [S3 bucket for general files]
bucket.expenses: [S3 bucket for expense files]
claid.ai.apikey: [Claid API key for image processing]
```

## Processing Flow Examples

### Photo Retrieval with Resizing
1. Client requests `/files/photos/{uuid}/jpg?width=400`
2. System checks memory cache for resized version
3. If not cached, checks S3 for `resized/400/{uuid}`
4. If not in S3, loads original from S3
5. Sends to Claid AI for resizing
6. Returns resized image to client
7. Asynchronously stores in S3 for future use

### Document Upload
1. Client sends document to `/files/documents`
2. System generates UUID if not provided
3. Persists metadata to database
4. Uploads binary to S3
5. Returns confirmation

## Task List - Proposed Improvements

### Performance Optimizations
- [ ] Implement image lazy loading with progressive enhancement
- [ ] Add Redis as distributed cache layer to reduce S3 calls
- [ ] Implement batch photo processing for bulk uploads
- [ ] Add CDN integration for frequently accessed images
- [ ] Optimize Claid API calls with request batching
- [ ] Implement smart pre-generation of common image sizes

### Architecture Refactoring
- [ ] Separate photo and document services into distinct modules
- [ ] Extract S3 operations into a shared library
- [ ] Implement event-driven architecture for image processing
- [ ] Create dedicated image processing queue with workers
- [ ] Standardize error handling across all file services
- [ ] Implement circuit breaker pattern for Claid API calls

### Feature Enhancements
- [ ] Add image format conversion capabilities (HEIC, AVIF support)
- [ ] Implement image compression levels based on use case
- [ ] Add watermarking capability for protected images
- [ ] Implement image metadata extraction and storage
- [ ] Add support for video file handling
- [ ] Create thumbnail generation service

### Data Management
- [ ] Implement S3 lifecycle policies for old resized images
- [ ] Add database migration to separate photos and documents tables
- [ ] Implement soft delete with retention policies
- [ ] Add file versioning support
- [ ] Create audit log for file operations
- [ ] Implement data archival strategy

### Security Improvements
- [ ] Add virus scanning for uploaded files
- [ ] Implement file type validation beyond MIME detection
- [ ] Add rate limiting for file operations
- [ ] Implement signed URLs for temporary file access
- [ ] Add encryption at rest for sensitive documents
- [ ] Create file access audit trail

### Developer Experience
- [ ] Create comprehensive API documentation with OpenAPI
- [ ] Add integration tests for all file operations
- [ ] Implement health checks for S3 and Claid connectivity
- [ ] Create file service dashboard for monitoring
- [ ] Add detailed logging with correlation IDs
- [ ] Create local development mode without S3/Claid dependencies

### Better Design Considerations
- [ ] **Microservice Extraction**: Consider extracting file service as standalone microservice
- [ ] **Storage Abstraction**: Create storage interface to support multiple providers beyond S3
- [ ] **Processing Pipeline**: Implement configurable processing pipeline for different file types
- [ ] **Event Sourcing**: Track all file operations as events for better auditability
- [ ] **CQRS Pattern**: Separate read and write models for better scalability
- [ ] **GraphQL Support**: Add GraphQL endpoint for flexible file queries