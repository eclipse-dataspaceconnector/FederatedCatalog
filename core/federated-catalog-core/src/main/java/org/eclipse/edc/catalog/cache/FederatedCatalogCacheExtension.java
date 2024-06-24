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

import jakarta.json.Json;
import org.eclipse.edc.catalog.cache.crawler.CrawlerActionRegistryImpl;
import org.eclipse.edc.catalog.cache.query.DspCatalogRequestAction;
import org.eclipse.edc.catalog.spi.CatalogConstants;
import org.eclipse.edc.catalog.spi.FederatedCatalogCache;
import org.eclipse.edc.catalog.spi.model.CatalogUpdateResponse;
import org.eclipse.edc.catalog.transform.JsonObjectToCatalogTransformer;
import org.eclipse.edc.catalog.transform.JsonObjectToDataServiceTransformer;
import org.eclipse.edc.catalog.transform.JsonObjectToDatasetTransformer;
import org.eclipse.edc.catalog.transform.JsonObjectToDistributionTransformer;
import org.eclipse.edc.connector.controlplane.catalog.spi.Catalog;
import org.eclipse.edc.connector.controlplane.transform.odrl.from.JsonObjectFromPolicyTransformer;
import org.eclipse.edc.connector.controlplane.transform.odrl.to.JsonObjectToPolicyTransformer;
import org.eclipse.edc.connector.core.agent.NoOpParticipantIdMapper;
import org.eclipse.edc.crawler.spi.CrawlerActionRegistry;
import org.eclipse.edc.crawler.spi.TargetNodeDirectory;
import org.eclipse.edc.crawler.spi.TargetNodeFilter;
import org.eclipse.edc.crawler.spi.model.ExecutionPlan;
import org.eclipse.edc.crawler.spi.model.UpdateResponse;
import org.eclipse.edc.jsonld.spi.JsonLd;
import org.eclipse.edc.protocol.dsp.catalog.transform.from.JsonObjectFromCatalogTransformer;
import org.eclipse.edc.protocol.dsp.catalog.transform.from.JsonObjectFromDataServiceTransformer;
import org.eclipse.edc.protocol.dsp.catalog.transform.from.JsonObjectFromDatasetTransformer;
import org.eclipse.edc.protocol.dsp.catalog.transform.from.JsonObjectFromDistributionTransformer;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Provider;
import org.eclipse.edc.spi.message.RemoteMessageDispatcherRegistry;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.eclipse.edc.spi.system.health.HealthCheckResult;
import org.eclipse.edc.spi.system.health.HealthCheckService;
import org.eclipse.edc.spi.types.TypeManager;
import org.eclipse.edc.transform.spi.TypeTransformerRegistry;
import org.eclipse.edc.transform.transformer.edc.to.JsonObjectToQuerySpecTransformer;
import org.eclipse.edc.transform.transformer.edc.to.JsonValueToGenericTypeTransformer;

import java.util.Map;

import static java.util.Optional.ofNullable;
import static org.eclipse.edc.catalog.cache.FederatedCatalogDefaultServicesExtension.NUM_CRAWLER_SETTING;
import static org.eclipse.edc.catalog.spi.CacheSettings.DEFAULT_NUMBER_OF_CRAWLERS;
import static org.eclipse.edc.catalog.spi.CatalogConstants.DATASPACE_PROTOCOL;
import static org.eclipse.edc.spi.constants.CoreConstants.JSON_LD;

@Extension(value = FederatedCatalogCacheExtension.NAME)
public class FederatedCatalogCacheExtension implements ServiceExtension {

    public static final String NAME = "Federated Catalog Cache";

    @Inject
    private FederatedCatalogCache store;
    @Inject(required = false)
    private HealthCheckService healthCheckService;
    @Inject
    private RemoteMessageDispatcherRegistry dispatcherRegistry;
    // get all known nodes from node directory - must be supplied by another extension
    @Inject
    private TargetNodeDirectory directory;
    // optional filter function to select FC nodes eligible for crawling.
    @Inject(required = false)
    private TargetNodeFilter nodeFilter;

    @Inject(required = false)
    private ExecutionPlan executionPlan;
    private CrawlerActionRegistryImpl nodeQueryAdapterRegistry;
    private ExecutionManager executionManager;
    @Inject
    private TypeManager typeManager;

    @Inject
    private TypeTransformerRegistry registry;
    @Inject
    private JsonLd jsonLdService;

    @Inject
    private Monitor monitor;
    @Inject
    private TypeTransformerRegistry transformerRegistry;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void initialize(ServiceExtensionContext context) {
        // CRAWLER SUBSYSTEM
        // contribute to the liveness probe
        if (healthCheckService != null) {
            healthCheckService.addReadinessProvider(() -> HealthCheckResult.Builder.newInstance().component("Crawler Subsystem").build());
        }
        int numCrawlers = context.getSetting(NUM_CRAWLER_SETTING, DEFAULT_NUMBER_OF_CRAWLERS);

        // by default only uses FC nodes that are not "self"
        nodeFilter = ofNullable(nodeFilter).orElse(node -> !node.name().equals(context.getRuntimeId()));

        executionManager = ExecutionManager.Builder.newInstance()
                .monitor(context.getMonitor().withPrefix("ExecutionManager"))
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

        registerTransformers(context);
    }

    @Override
    public void start() {
        executionManager.executePlan(executionPlan);
    }

    @Provider
    public CrawlerActionRegistry createNodeQueryAdapterRegistry(ServiceExtensionContext context) {
        if (nodeQueryAdapterRegistry == null) {
            nodeQueryAdapterRegistry = new CrawlerActionRegistryImpl();
            // catalog queries via IDS multipart and DSP are supported by default
            var mapper = typeManager.getMapper(JSON_LD);
            nodeQueryAdapterRegistry.register(DATASPACE_PROTOCOL, new DspCatalogRequestAction(dispatcherRegistry, context.getMonitor(), mapper, registry.forContext("dsp-api"), jsonLdService));
        }
        return nodeQueryAdapterRegistry;
    }

    private void registerTransformers(ServiceExtensionContext context) {
        transformerRegistry.register(new JsonObjectToCatalogTransformer());
        transformerRegistry.register(new JsonObjectToDatasetTransformer());
        transformerRegistry.register(new JsonObjectToDataServiceTransformer());
        transformerRegistry.register(new JsonObjectToDistributionTransformer());
        transformerRegistry.register(new JsonObjectToQuerySpecTransformer());

        var jsonFactory = Json.createBuilderFactory(Map.of());
        var mapper = context.getService(TypeManager.class).getMapper(JSON_LD);
        var participantIdMapper = new NoOpParticipantIdMapper();
        transformerRegistry.register(new JsonObjectFromCatalogTransformer(jsonFactory, mapper, participantIdMapper));
        transformerRegistry.register(new JsonObjectFromDatasetTransformer(jsonFactory, mapper));
        transformerRegistry.register(new JsonObjectFromDistributionTransformer(jsonFactory));
        transformerRegistry.register(new JsonObjectFromDataServiceTransformer(jsonFactory));

        transformerRegistry.register(new JsonObjectFromPolicyTransformer(jsonFactory, participantIdMapper));
        transformerRegistry.register(new JsonObjectToPolicyTransformer(participantIdMapper));
        transformerRegistry.register(new JsonValueToGenericTypeTransformer(mapper));
    }

    /**
     * inserts a particular {@link Catalog} in the {@link FederatedCatalogCache}
     *
     * @param updateResponse The response that contains the catalog
     */
    private void persist(UpdateResponse updateResponse) {
        if (updateResponse instanceof CatalogUpdateResponse catalogUpdateResponse) {
            var catalog = catalogUpdateResponse.getCatalog();
            catalog.getProperties().put(CatalogConstants.PROPERTY_ORIGINATOR, updateResponse.getSource());
            store.save(catalog);
        } else {
            monitor.warning("Expected a response of type %s but got %s. Will discard".formatted(CatalogUpdateResponse.class, updateResponse.getClass()));
        }
    }


}
