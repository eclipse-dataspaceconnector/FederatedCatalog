# Federated Catalog Architecture

## Terminology

- TCN: Target Catalog Node - serves DSP catalog requests.
- FC: Federated Catalog - maintains a snapshot of the catalogs of all participants in a dataspace.

## General aspects

- The Federated Catalog (FC) is the one that does the crawling (Federated Catalog Crawler, FCC) and that goes to
  Target Catalog Nodes (TCN) to get their catalog. The Federated Catalog exposes the Catalog API to client apps such
  as web UIs
- The Target Catalog Node (TCN) hosts the catalog and it responds to DSP catalog requests. Typically, TCNs are EDC
  connectors.
- Both the FCC and TCN are thought of as logical components (similar to the EDC) rather than physical things. They can
  be embedded in a runtime via extensions, deployed alongside it as standalone runtimes or be deployed completely
  separate (even in a different environment). It's also possible to deploy a runtime that hosts both an TCN and an FCC.
- we distinguish between a catalog _query_ and a catalog _update request_: the former is coming from a client
  application through the Catalog API, e.g. from a UI application that wants to get the entire catalog, whereas the
  latter (_update request_) is a DSP request issued by the crawler to other TCNs

## TCN endpoint resolution

The Federated Catalog requires a list of Target Catalog Nodes, so it knows _which endpoints to crawl_. This list is
provided by the `TargetNodeDirectory`. During the preparation of a crawl run, the `ExecutionManager` queries that
directory and obtains the list of TCNs.

In the simplest of cases, the `TargetNodeDirectory` is backed by a static file, but more complex implementations such as
a centralized participant registry are conceivable.

## Important classes and objects

- `Crawler`: a piece of software within the FC that periodically issues `update-requests` to other TCNs. Receives
  a `WorkItem` and executes the DSP catalog request.
- `WorkItem`: a unit of work (= "crawl target") for the crawler
- `UpdateRequest`: a DSP catalog request from the Crawler to a TCN to get that TCN's catalog
- `UpdateResponse`: the response to an `UpdateRequest`, containing one `Catalog`
- `ExecutionPlan`: defines how the crawlers should run, i.e. periodically, based on an event, etc. By default, FC runs
  on a periodic schedule.
- `ExecutionManager`: this is the central component that instantiates `Crawlers` and schedules/distributes the work
  among them.
- `QueryService`: a service that interprets and executes a catalog query against the cache

## Architectural and deployment considerations

The design of the crawlers aims at being ephemeral, scalable and low-maintenance. The ultimate goal is
to crawl a dataspace as fast and efficiently as possible while maintaining robustness. They are relatively dumb pieces
of software: when there's work, they are instantiated, they run off and crawl.

The amount of crawlers is one variable that can have great influence on the performance of the FC. When there is a
lot of participants, but they rarely ever update their asset catalogs, one might get away by spawning a few crawlers,
and updates would trickle in at a relatively moderate pace.
Lots of participants with frequent updates to their asset catalogs might warrant the additional compute cost of spinning
up many crawlers.

Another way to tweak the performance is to change the `ExecutionPlan` to the needs of the dataspace. Out of the box, FC
supports a periodic execution, but in some dataspace there might be additional triggers such as dataspace events or even
manual triggers through a web API.