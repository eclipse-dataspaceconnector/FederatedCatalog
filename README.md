# Federated Catalog

The Federated Catalog (FC) represents the aggregated catalogs of multiple participants in a dataspace. To achieve that,
the FC employs a set of crawlers, that periodically scrape the dataspace requesting the catalog from each participant in
a list of participants and consolidates them in a local cache.

Keeping a locally cached version of every participant's catalog makes catalog queries more responsive and robust, and it
can cause a reduction in network load.

The Federated Catalog is based on EDC components for core functionality, specifically those of
the [connector](https://github.com/eclipse-edc/Connector) for extension loading, runtime bootstrap, configuration, API
handling etc., while adding specific functionality using the EDC extensibility mechanism.

This repository contains all components needed to build and run a standalone version of the Federated Catalog.

## Quick start

A basic launcher configured with in-memory stores (i.e. no persistent storage) can be
found [here](launchers/catalog-mocked). Note that this runtime also contains a _mocked identity service_, for demo
purposes. There is another variant called `catalog-dcp` which uses DCP as identity system and thus requires a properly
configured DCP infrastructure to be set up. There are two ways of running Federated Catalog:

1. As native Java process
2. Inside a Docker image

### Build the `*.jar` files

```bash
./gradlew shadowJar
# or
./gradlew -p launchers shadowJar
```

### Start Federated Catalog as Java process

Once the jar files are built, Federated Catalog can be launched using this shell command:

```bash
java -Dweb.http.catalog.path="/api/catalog" \
     -Dweb.http.catalog.port=8181 \
     -Dweb.http.path="/api" \
     -Dweb.http.port=8080 \
     -jar launchers/catalog-mocked/build/libs/fc-mocked.jar
```

this will expose the Catalog API at `http://localhost:8181/api/catalog`. More information about Federated Catalog's APIs
can be found [here](docs/developer/architecture/federated-catalog-apis).

### Create the Docker image

```bash
docker build -t fc ./launchers/catalog-mocked
```

### Start the Federated Catalog

```bash
docker run  --rm --name fc \
    -e "WEB_HTTP_PATH=/api" \
    -e "WEB_HTTP_PORT=8080" \
    -e "WEB_HTTP_CATALOG_PORT=8181" \
    -e "WEB_HTTP_CATALOG_PATH=/api/catalog" \
    fc:latest
```

## Architectural concepts of the Federated Catalog

Key architectural concepts are
outlined [here](docs/developer/architecture/federated-catalog.architecture.md).

## API overview of Federated Catalog

Federated Catalog exposes several APIs that are described in more
detail [here](docs/developer/architecture/federated-catalog-apis.md).

## Future work

- Generalization of the Crawler class
- Additional informational endpoints to the [Catalog API](docs/developer/architecture/federated-catalog.architecture.md)

## Other documentation

Developer documentation can be found under [docs/developer](docs/developer), where the main concepts and decisions are
captured as [decision records](docs/developer/decision-records).

## References

- Decentralized Claims Protocol (DCP): https://projects.eclipse.org/projects/technology.dataspace-dcp
- EDC Connector: https://github.com/eclipse-edc/Connector

## Contributing

See [how to contribute](https://github.com/eclipse-edc/docs/blob/main/CONTRIBUTING.md) for details.