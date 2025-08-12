# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Development Commands

### Build and Run
- `./mvnw compile quarkus:dev` - Run in development mode with live reload
- `./mvnw package` - Build the application (JAR in target/quarkus-app/)
- `./mvnw package -Dquarkus.package.type=uber-jar` - Build uber-jar
- `./mvnw package -Pnative` - Build native executable
- `java -jar target/quarkus-app/quarkus-run.jar` - Run packaged application

### Docker
- `docker-compose.yaml` - Main docker compose file
- `config/docker-compose.yaml` - Additional configuration
- `docker-build-native-images.sh` - Build native Docker images
- Docker environment files in `config/` directory for each service

## Architecture Overview

This is a Quarkus-based Java 21 modular monolith for Trustworks intranet services. While organized as logical microservices, it's deployed as a single application with shared database and configuration:

### Core Services
- **API Gateway** (`apigateway/`) - Central routing and authentication
- **User Service** (`userservice/`) - User management, authentication, roles
- **Finance Service** (`financeservice/`) - Financial data, invoicing
- **Expense Service** (`expenseservice/`) - Expense processing and e-conomic integration
- **Work Service** - Time tracking and project work
- **Communication Service** (`communicationsservice/`) - Email and Slack integration
- **Knowledge Service** (`knowledgeservice/`) - Training, courses, certifications
- **File Service** (`fileservice/`) - S3-based file storage and photo processing

### Aggregation Layer
- **Aggregates** (`aggregates/`) - Cross-service data aggregation and business logic
- **Aggregate Services** (`aggregateservices/`) - Cached aggregate data services

### Data Architecture
- MariaDB database with Flyway migrations (`src/main/resources/db/migration/`)
- Hibernate ORM with Panache for data access
- JPA Streamer for advanced querying
- Event-driven architecture using AWS SNS

### Security
- JWT-based authentication with public/private key pairs
- Role-based access control
- Request logging and API usage tracking (`logging/ApiUsageLog`)

### External Integrations
- **e-conomic** - Accounting system integration
- **Slack** - Team communication
- **AWS S3** - File storage
- **Claid AI** - Image processing
- **OpenAI** - AI services for resume parsing and text improvement

## Configuration

- `src/main/resources/application.yml` - Main configuration
- Environment-specific configs in `config/docker-env-*.env` files
- Database connection defaults to localhost:3306/twservices
- HTTP server runs on port 9093
- Health checks available at `/health`
- OpenAPI/Swagger UI enabled in dev mode

## Database

- Uses Flyway for schema migrations
- Migration files in `src/main/resources/db/migration/`
- Baseline version 1.0 with migrate-at-start enabled
- Connection pooling (5-10 connections)

## Key Features

- Multi-tenant company data aggregation
- Invoice generation and management
- Expense processing with receipt upload
- User availability and budget tracking
- Conference and training management
- Slack integration for notifications
- Photo service with dynamic resizing
- API usage logging and monitoring

## Development Notes

- Uses Lombok for boilerplate reduction
- Extensive caching with Caffeine
- Reactive programming with Mutiny
- REST endpoints follow JAX-RS standards
- Background jobs using Quarkus Scheduler
- Comprehensive documentation in `docs/` directory for specific features

## Testing

- `./mvnw test` - Run unit tests
- `./mvnw verify` - Run all tests including integration tests
- Dev UI available at http://localhost:8080/q/dev/ in dev mode

## Feature Documentation

Key features have detailed documentation in the `docs/` directory:
- Expense Processing: `docs/expense-processing.md`
- Photo Service: `docs/photo-service.md`
- API Usage Logging: `docs/api-usage-logging.md`
- Draft Invoice Creation: `docs/draft-invoice-creation.md`
- Guest Registration: `docs/guest-registration.md`
- Slack User Sync: `docs/slack-user-sync.md`