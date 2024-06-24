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

import org.eclipse.edc.crawler.spi.CrawlerAction;
import org.eclipse.edc.crawler.spi.CrawlerActionRegistry;
import org.eclipse.edc.crawler.spi.CrawlerSuccessHandler;
import org.eclipse.edc.crawler.spi.TargetNodeDirectory;
import org.eclipse.edc.crawler.spi.TargetNodeFilter;
import org.eclipse.edc.crawler.spi.model.ExecutionPlan;
import org.eclipse.edc.crawler.spi.model.UpdateResponse;
import org.eclipse.edc.spi.EdcException;
import org.eclipse.edc.spi.monitor.Monitor;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.delayedExecutor;
import static java.util.concurrent.CompletableFuture.failedFuture;
import static org.eclipse.edc.catalog.test.TestUtil.TEST_PROTOCOL;
import static org.eclipse.edc.catalog.test.TestUtil.createNode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ExecutionManagerTest {

    private final TargetNodeDirectory nodeDirectoryMock = mock(TargetNodeDirectory.class);
    private final Monitor monitorMock = mock(Monitor.class);
    private final CrawlerActionRegistry crawlerActionRegistry = mock(CrawlerActionRegistry.class);
    private final Runnable preExecutionTaskMock = mock(Runnable.class);
    private final CrawlerAction queryAdapterMock = mock(CrawlerAction.class);
    private final CrawlerSuccessHandler successConsumerMock = mock(CrawlerSuccessHandler.class);
    private final Runnable postExecutionTask = mock(Runnable.class);
    private ExecutionManager manager;

    @BeforeEach
    void setUp() {
        manager = createManager();
    }

    @Test
    void executePlan() {
        when(nodeDirectoryMock.getAll()).thenReturn(List.of(createNode()));
        when(crawlerActionRegistry.findForProtocol(TEST_PROTOCOL)).thenReturn(List.of(queryAdapterMock));
        when(queryAdapterMock.apply(any())).thenReturn(completedFuture(new TestUpdateResponse("test-url")));
        manager.executePlan(simplePlan());

        var inOrder = inOrder(preExecutionTaskMock, queryAdapterMock, successConsumerMock);
        inOrder.verify(preExecutionTaskMock).run();
        inOrder.verify(queryAdapterMock).apply(any());
        inOrder.verify(successConsumerMock).accept(any());
    }

    @Test
    void executePlan_waitsForCrawler() {
        when(nodeDirectoryMock.getAll()).thenReturn(List.of(createNode(), createNode()));
        when(crawlerActionRegistry.findForProtocol(TEST_PROTOCOL)).thenReturn(List.of(queryAdapterMock));

        var response = new TestUpdateResponse("test-url");
        var future = new CompletableFuture<UpdateResponse>();
        future.completeAsync(() -> response, delayedExecutor(2, TimeUnit.SECONDS));

        when(queryAdapterMock.apply(any())).thenReturn(future);
        manager.executePlan(simplePlan());

        var inOrder = inOrder(preExecutionTaskMock, queryAdapterMock, successConsumerMock);
        inOrder.verify(preExecutionTaskMock).run();
        inOrder.verify(queryAdapterMock).apply(any());
        inOrder.verify(successConsumerMock).accept(any());
        verify(monitorMock, atLeastOnce()).debug(contains("No crawler available"));
    }


    @Test
    void executePlan_noQueryAdapter() {
        when(nodeDirectoryMock.getAll()).thenReturn(List.of(createNode()));
        when(crawlerActionRegistry.findForProtocol(TEST_PROTOCOL)).thenReturn(List.of());

        manager.executePlan(simplePlan());

        var inOrder = inOrder(preExecutionTaskMock);
        inOrder.verify(preExecutionTaskMock).run();
        verifyNoInteractions(queryAdapterMock, successConsumerMock);
    }

    @Test
    void executePlan_preTaskThrowsException() {
        doThrow(new RuntimeException("test-exception")).when(preExecutionTaskMock).run();
        when(nodeDirectoryMock.getAll()).thenReturn(List.of(createNode()));
        when(crawlerActionRegistry.findForProtocol(TEST_PROTOCOL)).thenReturn(List.of(queryAdapterMock));
        when(queryAdapterMock.apply(any())).thenReturn(completedFuture(new TestUpdateResponse("test-url")));
        manager.executePlan(simplePlan());

        verify(successConsumerMock).accept(any());
        verify(monitorMock, atLeastOnce()).severe(anyString(), any(Throwable.class));
    }

    @Test
    void executePlan_postTaskThrowsException() {
        doThrow(new RuntimeException("test-exception")).when(postExecutionTask).run();
        when(nodeDirectoryMock.getAll()).thenReturn(List.of(createNode()));
        when(crawlerActionRegistry.findForProtocol(TEST_PROTOCOL)).thenReturn(List.of(queryAdapterMock));
        when(queryAdapterMock.apply(any())).thenReturn(completedFuture(new TestUpdateResponse("test-url")));
        manager.executePlan(simplePlan());

        verify(successConsumerMock).accept(any());
        verify(monitorMock, atLeastOnce()).severe(anyString(), any(Throwable.class));
    }

    @Test
    void executePlan_completesExceptionally() {
        when(nodeDirectoryMock.getAll()).thenReturn(List.of(createNode()));
        when(crawlerActionRegistry.findForProtocol(TEST_PROTOCOL)).thenReturn(List.of(queryAdapterMock));
        var exc = new EdcException("some exception");
        when(queryAdapterMock.apply(any())).thenReturn(failedFuture(exc));
        manager.executePlan(simplePlan());

        var inOrder = inOrder(preExecutionTaskMock, queryAdapterMock, successConsumerMock);
        inOrder.verify(preExecutionTaskMock).run();
        inOrder.verify(queryAdapterMock).apply(any());

        verifyNoInteractions(successConsumerMock);
        verify(monitorMock, atLeastOnce()).severe(anyString(), isA(CompletionException.class));
    }

    @Test
    void executePlan_workItemsEmpty() {
        when(nodeDirectoryMock.getAll()).thenReturn(List.of());
        manager.executePlan(simplePlan());

        verifyNoInteractions(crawlerActionRegistry);
    }

    @Test
    void executePlan_withCustomFiltering() {
        when(nodeDirectoryMock.getAll()).thenReturn(List.of(createNode(), createNode(), createNode()));
        var filter = mock(TargetNodeFilter.class);
        manager = createManagerBuilder().nodeFilterFunction(filter).build();

        manager.executePlan(simplePlan());

        verify(filter, times(3)).test(any());
    }


    private ExecutionPlan simplePlan() {
        return Runnable::run;
    }

    private ExecutionManager createManager() {
        return createManagerBuilder()
                .build();
    }

    @NotNull
    private ExecutionManager.Builder createManagerBuilder() {
        return ExecutionManager.Builder.newInstance()
                .nodeDirectory(nodeDirectoryMock)
                .nodeQueryAdapterRegistry(crawlerActionRegistry)
                .preExecutionTask(preExecutionTaskMock)
                .postExecutionTask(postExecutionTask)
                .monitor(monitorMock)
                .onSuccess(successConsumerMock);
    }

}