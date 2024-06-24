# APIs of the Federated Catalog

## Catalog API

This API exposes the internal cache. That means, that instead of querying each dataspace participant individually,
clients can use this API to get a _consolidated_ list of catalogs.

When the catalog runtime starts up, the internal cache will be empty. Once the crawlers start to pick up work, and
results are coming in, the cache will get populated. This means, during an execution phase, until all crawlers have
finished, results returned by this API _will be inconsistent_!

Future work may add additional endpoints that can be used to determine the status of the FC, i.e. "crawling in progress"
or "idle". This information can be used to gauge the stability of the result.

NB: the results returned by this API will be influenced by the access policies configured in remote Target Catalog
Nodes and the `participantId` that is configured on the FC. Update requests are DSP requests, so all authentication and
authorization mechanisms apply.

Once all catalogs are collected, clients may add additional query parameters to narrow down the search. Please refer to
the [QuerySpec class](https://github.com/eclipse-edc/Connector/blob/main/spi/common/core-spi/src/main/java/org/eclipse/edc/spi/query/QuerySpec.java)
for details.

Please also check out [this link](https://eclipse-edc.github.io/FederatedCAtalog/openapi/catalog-api) for the complete
OpenAPI documentation.

## Observability API

The Observability API is intended to provide information about the application health to the Docker daemon via Docker
health checks and the Kubernetes control plane via Kubernetes Readiness Probes. It is not intended to be reachable
from outside the container as it lacks access control.