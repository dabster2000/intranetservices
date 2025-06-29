# intranetservices Project

This project uses Quarkus, the Supersonic Subatomic Java Framework.

If you want to learn more about Quarkus, please visit its website: https://quarkus.io/ .

## Running the application in dev mode

You can run your application in dev mode that enables live coding using:
```shell script
./mvnw compile quarkus:dev
```

> **_NOTE:_**  Quarkus now ships with a Dev UI, which is available in dev mode only at http://localhost:8080/q/dev/.

## Packaging and running the application

The application can be packaged using:
```shell script
./mvnw package
```
It produces the `quarkus-run.jar` file in the `target/quarkus-app/` directory.
Be aware that it’s not an _über-jar_ as the dependencies are copied into the `target/quarkus-app/lib/` directory.

The application is now runnable using `java -jar target/quarkus-app/quarkus-run.jar`.

If you want to build an _über-jar_, execute the following command:
```shell script
./mvnw package -Dquarkus.package.type=uber-jar
```

The application, packaged as an _über-jar_, is now runnable using `java -jar target/*-runner.jar`.

## Creating a native executable

You can create a native executable using: 
```shell script
./mvnw package -Pnative
```

Or, if you don't have GraalVM installed, you can run the native executable build in a container using: 
```shell script
./mvnw package -Pnative -Dquarkus.native.container-build=true
```

You can then execute your native executable with: `./target/intranetservices-1.0.0-SNAPSHOT-runner`

If you want to learn more about building native executables, please consult https://quarkus.io/guides/maven-tooling.

## Related Guides

- Mutiny ([guide](https://quarkus.io/guides/mutiny-primer)): Write reactive applications with the modern Reactive Programming library Mutiny
- Flyway ([guide](https://quarkus.io/guides/flyway)): Handle your database schema migrations
- RESTEasy Reactive ([guide](https://quarkus.io/guides/resteasy-reactive)): A JAX-RS implementation utilizing build time processing and Vert.x. This extension is not compatible with the quarkus-resteasy extension, or any of the extensions that depend on it.
- YAML Configuration ([guide](https://quarkus.io/guides/config#yaml)): Use YAML to configure your Quarkus application
- Scheduler - tasks ([guide](https://quarkus.io/guides/scheduler)): Schedule jobs and tasks
- JDBC Driver - MySQL ([guide](https://quarkus.io/guides/datasource)): Connect to the MySQL database via JDBC
- Micrometer metrics ([guide](https://quarkus.io/guides/micrometer)): Instrument the runtime and your application with dimensional metrics using Micrometer.
- Reactive MySQL client ([guide](https://quarkus.io/guides/reactive-sql-clients)): Connect to the MySQL database using the reactive pattern
- SmallRye Reactive Messaging ([guide](https://quarkus.io/guides/reactive-messaging)): Produce and consume messages and implement event driven and data streaming applications

## Provided Code

### YAML Config

Configure your application with YAML

[Related guide section...](https://quarkus.io/guides/config-reference#configuration-examples)

The Quarkus application configuration is located in `src/main/resources/application.yml`.

### RESTEasy Reactive

Easily start your Reactive RESTful Web Services

[Related guide section...](https://quarkus.io/guides/getting-started-reactive#reactive-jax-rs-resources)

## Borrowed Device API

The `BorrowedDevice` endpoints manage devices lent to users. A new `clientuuid` field links a device to a client. Developers should include this property when creating or updating devices. See `docs/borroweddevice-client-update.md` for migration details.

## Expense Processing

The expense upload flow is documented in [docs/expense-processing.md](docs/expense-processing.md).

## Invoice Sorting

The sorting options for invoices are documented in [docs/invoice-sorting.md](docs/invoice-sorting.md).

## Guest Registration

The guest registration endpoints are documented in [docs/guest-registration.md](docs/guest-registration.md).

## Slack User Sync

Slack user ids are synchronized nightly. Details in [docs/slack-user-sync.md](docs/slack-user-sync.md).

## Draft Invoice Creation

The improved draft invoice creation flow is documented in [docs/draft-invoice-creation.md](docs/draft-invoice-creation.md).

## Draft Invoice API for Frontend

See [docs/draft-invoice-frontend.md](docs/draft-invoice-frontend.md) for usage instructions and error handling guidelines.

## Photo Service

Caching behaviour, S3 storage layout and dynamic resizing are described in [docs/photo-service.md](docs/photo-service.md). Images are resized through the Claid API.

## API Usage Logging

The centralized request logging and the `/api-usage-logs` endpoint are described
in [docs/api-usage-logging.md](docs/api-usage-logging.md).
