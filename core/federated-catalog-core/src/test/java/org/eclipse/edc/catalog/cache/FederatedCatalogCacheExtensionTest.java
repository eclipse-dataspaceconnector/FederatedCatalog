/*
 *  Copyright (c) 2022 Microsoft Corporation
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Microsoft Corporation - initial API and implementation
 *
 */

package org.eclipse.edc.catalog.cache;

import org.eclipse.edc.boot.system.injection.ObjectFactory;
import org.eclipse.edc.catalog.cache.query.DspCatalogRequestAction;
import org.eclipse.edc.catalog.spi.CatalogConstants;
import org.eclipse.edc.catalog.spi.FederatedCatalogCache;
import org.eclipse.edc.catalog.spi.model.CatalogUpdateResponse;
import org.eclipse.edc.crawler.spi.CrawlerAction;
import org.eclipse.edc.crawler.spi.TargetNodeDirectory;
import org.eclipse.edc.crawler.spi.TargetNodeFilter;
import org.eclipse.edc.crawler.spi.model.ExecutionPlan;
import org.eclipse.edc.crawler.spi.model.RecurringExecutionPlan;
import org.eclipse.edc.junit.extensions.DependencyInjectionExtension;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.eclipse.edc.spi.system.health.HealthCheckService;
import org.eclipse.edc.spi.types.TypeManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;
import java.util.List;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.eclipse.edc.catalog.test.TestUtil.TEST_PROTOCOL;
import static org.eclipse.edc.catalog.test.TestUtil.createCatalog;
import static org.eclipse.edc.catalog.test.TestUtil.createNode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(DependencyInjectionExtension.class)
class FederatedCatalogCacheExtensionTest {
    private final FederatedCatalogCache storeMock = mock();
    private final TargetNodeDirectory nodeDirectoryMock = mock();
    private FederatedCatalogCacheExtension extension;

    @BeforeEach
    void setUp(ServiceExtensionContext context, ObjectFactory factory) {
        var monitorWithPrefix = mock(Monitor.class);
        var monitor = mock(Monitor.class);
        when(monitor.withPrefix(anyString())).thenReturn(monitorWithPrefix);

        context.registerService(TargetNodeDirectory.class, nodeDirectoryMock);
        context.registerService(FederatedCatalogCache.class, storeMock);
        context.registerService(TargetNodeFilter.class, null);
        context.registerService(ExecutionPlan.class, new RecurringExecutionPlan(Duration.ofSeconds(1), Duration.ofSeconds(0), mock()));
        context.registerService(TypeManager.class, mock());
        context.registerService(Monitor.class, monitor);

        extension = factory.constructInstance(FederatedCatalogCacheExtension.class);
    }

    @Test
    void name() {
        assertThat(extension.name()).isEqualTo("Federated Catalog Cache");
    }

    @Test
    void initialize(ServiceExtensionContext context) {
        extension.initialize(context);

        verify(context, atLeastOnce()).getMonitor();
        verify(context).getSetting("edc.catalog.cache.partition.num.crawlers", 2);
    }

    @Test
    void initialize_withHealthCheck(ServiceExtensionContext context, ObjectFactory factory) {
        var healthCheckServiceMock = mock(HealthCheckService.class);
        context.registerService(HealthCheckService.class, healthCheckServiceMock);
        extension = factory.constructInstance(FederatedCatalogCacheExtension.class); //reconstruct to honor health service

        extension.initialize(context);

        verify(healthCheckServiceMock).addReadinessProvider(any());
    }

    @Test
    void verify_successHandler_persistIsCalled(ServiceExtensionContext context) {
        when(nodeDirectoryMock.getAll()).thenReturn(List.of(createNode()));
        extension.initialize(context);
        var crawlerAction = mock(CrawlerAction.class);
        when(crawlerAction.apply(any())).thenReturn(completedFuture(new CatalogUpdateResponse("test-url", createCatalog("test-catalog"))));
        extension.createNodeQueryAdapterRegistry(context).register(TEST_PROTOCOL, crawlerAction);

        extension.start();

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> verify(storeMock, atLeastOnce()).save(any()));
    }

    @Test
    void start(ServiceExtensionContext context) {
        extension.initialize(context);
    }

    @Test
    void verifyProvider_cacheNodeAdapterRegistry(ServiceExtensionContext context) {
        var n = extension.createNodeQueryAdapterRegistry(context);
        assertThat(extension.createNodeQueryAdapterRegistry(context)).isSameAs(n);
        assertThat(n.findForProtocol(CatalogConstants.DATASPACE_PROTOCOL)).hasSize(1)
                .allSatisfy(qa -> assertThat(qa).isInstanceOf(DspCatalogRequestAction.class));
    }

}
