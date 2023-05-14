/*
 *  Copyright (c) 2021 Microsoft Corporation
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Microsoft Corporation - Initial implementation
 *
 */

package org.eclipse.edc.catalog.cache;

import org.eclipse.edc.catalog.cache.crawler.NodeQueryAdapterRegistryImpl;
import org.eclipse.edc.catalog.cache.query.DspNodeQueryAdapter;
import org.eclipse.edc.catalog.spi.CacheConfiguration;
import org.eclipse.edc.catalog.spi.Catalog;
import org.eclipse.edc.catalog.spi.CatalogConstants;
import org.eclipse.edc.catalog.spi.FederatedCacheNodeDirectory;
import org.eclipse.edc.catalog.spi.FederatedCacheNodeFilter;
import org.eclipse.edc.catalog.spi.FederatedCacheStore;
import org.eclipse.edc.catalog.spi.NodeQueryAdapterRegistry;
import org.eclipse.edc.catalog.spi.model.ExecutionPlan;
import org.eclipse.edc.catalog.spi.model.UpdateResponse;
import org.eclipse.edc.jsonld.spi.JsonLd;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Provider;
import org.eclipse.edc.spi.message.RemoteMessageDispatcherRegistry;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.eclipse.edc.spi.system.health.HealthCheckResult;
import org.eclipse.edc.spi.system.health.HealthCheckService;
import org.eclipse.edc.spi.types.TypeManager;
import org.eclipse.edc.transform.spi.TypeTransformerRegistry;

import static java.util.Optional.ofNullable;
import static org.eclipse.edc.catalog.spi.CatalogConstants.DATASPACE_PROTOCOL;
import static org.eclipse.edc.spi.CoreConstants.JSON_LD;

@Extension(value = FederatedCatalogCacheExtension.NAME)
public class FederatedCatalogCacheExtension implements ServiceExtension {

    public static final String NAME = "Federated Catalog Cache";

    @Inject
    private FederatedCacheStore store;
    @Inject(required = false)
    private HealthCheckService healthCheckService;
    @Inject
    private RemoteMessageDispatcherRegistry dispatcherRegistry;
    // get all known nodes from node directory - must be supplied by another extension
    @Inject
    private FederatedCacheNodeDirectory directory;
    // optional filter function to select FC nodes eligible for crawling.
    @Inject(required = false)
    private FederatedCacheNodeFilter nodeFilter;

    private ExecutionPlan executionPlan;
    private NodeQueryAdapterRegistryImpl nodeQueryAdapterRegistry;
    private ExecutionManager executionManager;
    @Inject
    private TypeManager typeManager;

    @Inject
    private TypeTransformerRegistry registry;
    @Inject
    private JsonLd jsonLdService;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void initialize(ServiceExtensionContext context) {
        // CRAWLER SUBSYSTEM
        // contribute to the liveness probe
        if (healthCheckService != null) {
            healthCheckService.addReadinessProvider(() -> HealthCheckResult.Builder.newInstance().component("FCC Crawler Subsystem").build());
        }
        var cacheConfiguration = new CacheConfiguration(context);
        int numCrawlers = cacheConfiguration.getNumCrawlers();
        // and a loader manager

        executionPlan = cacheConfiguration.getExecutionPlan();

        // by default only uses FC nodes that are not "self"
        nodeFilter = ofNullable(nodeFilter).orElse(node -> !node.getName().equals(context.getConnectorId()));

        executionManager = ExecutionManager.Builder.newInstance()
                .monitor(context.getMonitor())
                .preExecutionTask(() -> {
                    store.deleteExpired();
                    store.expireAll();
                })
                .numCrawlers(numCrawlers)
                .nodeQueryAdapterRegistry(createNodeQueryAdapterRegistry(context))
                .onSuccess(this::persist)
                .nodeDirectory(directory)
                .nodeFilterFunction(nodeFilter)
                .build();
    }

    @Override
    public void start() {
        executionManager.executePlan(executionPlan);
    }

    @Provider
    public NodeQueryAdapterRegistry createNodeQueryAdapterRegistry(ServiceExtensionContext context) {
        if (nodeQueryAdapterRegistry == null) {
            nodeQueryAdapterRegistry = new NodeQueryAdapterRegistryImpl();
            // catalog queries via IDS multipart and DSP are supported by default
            var mapper = typeManager.getMapper(JSON_LD);
            nodeQueryAdapterRegistry.register(DATASPACE_PROTOCOL, new DspNodeQueryAdapter(dispatcherRegistry, context.getMonitor(), mapper, registry, jsonLdService));
        }
        return nodeQueryAdapterRegistry;
    }

    /**
     * inserts a particular {@link Catalog} in the {@link FederatedCacheStore}
     *
     * @param updateResponse The response that contains the catalog
     */
    private void persist(UpdateResponse updateResponse) {
        var catalog = updateResponse.getCatalog();
        catalog.getProperties().put(CatalogConstants.PROPERTY_ORIGINATOR, updateResponse.getSource());
        store.save(catalog);
    }
}
