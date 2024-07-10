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

import org.eclipse.edc.catalog.cache.crawler.Crawler;
import org.eclipse.edc.crawler.spi.CrawlerActionRegistry;
import org.eclipse.edc.crawler.spi.CrawlerErrorHandler;
import org.eclipse.edc.crawler.spi.CrawlerSuccessHandler;
import org.eclipse.edc.crawler.spi.TargetNodeDirectory;
import org.eclipse.edc.crawler.spi.TargetNodeFilter;
import org.eclipse.edc.crawler.spi.WorkItem;
import org.eclipse.edc.crawler.spi.model.ExecutionPlan;
import org.eclipse.edc.spi.monitor.Monitor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.lang.String.format;

/**
 * The execution manager is responsible for instantiating crawlers and delegating the incoming work items among them.
 * Work items are fetched directly from the {@link TargetNodeDirectory}, crawlers are instantiated before starting the run and will be reused.
 * For example, a list of 10 work items and 2 {@link Crawler} objects would mean that every crawler gets invoked 5 times.
 * <p>
 * Pre- and Post-Tasks can be registered to perform preparatory or cleanup operations.
 * <p>
 * The ExecutionManager delegates the actual task to the {@link ExecutionPlan}, which determines, when and how often it needs to be run.
 */
public class ExecutionManager {

    private Monitor monitor;
    private Runnable preExecutionTask;
    private Runnable postExecutionTask;
    private TargetNodeDirectory directory;
    private TargetNodeFilter nodeFilter;
    private int numCrawlers = 1;
    private CrawlerActionRegistry crawlerActionRegistry;
    private CrawlerSuccessHandler successHandler;
    private boolean enabled = true;

    private ExecutionManager() {
        nodeFilter = n -> true;
    }

    public void executePlan(ExecutionPlan plan) {
        if (!enabled) {
            monitor.warning("Execution of crawlers is globally disabled.");
            return;
        }
        plan.run(() -> {
            runPreExecution();
            doWork();
            runPostExecution();
        });

    }

    private void doWork() {
        // load work items from directory
        var workItems = fetchWorkItems();
        if (workItems.isEmpty()) {
            monitor.debug("No WorkItems found, skipping execution");
            return;
        }
        monitor.debug("Loaded " + workItems.size() + " work items from storage");
        var allItems = new ArrayBlockingQueue<>(workItems.size(), true, workItems);

        //instantiate fixed pool of crawlers
        var errorHandler = createErrorHandlers(monitor, allItems);

        var actualNumCrawlers = Math.min(allItems.size(), numCrawlers);
        monitor.debug(format("Crawler parallelism is %s, based on config and number of work items", actualNumCrawlers));
        var availableCrawlers = createCrawlers(errorHandler, actualNumCrawlers);

        while (!allItems.isEmpty()) {
            // try get next available crawler
            var crawler = nextAvailableCrawler(availableCrawlers);
            if (crawler == null) {
                monitor.debug("No crawler available, will retry later");
                continue;
            }

            var item = allItems.poll();
            if (item == null) {
                monitor.debug("WorkItem queue empty, skip execution");
                break;
            }

            // for now use the first adapter that can handle the protocol
            var adapter = crawlerActionRegistry.findForProtocol(item.getProtocol()).stream().findFirst();
            if (adapter.isEmpty()) {
                monitor.warning(format("No protocol adapter found for protocol '%s'", item.getProtocol()));
            } else {
                crawler.run(item, adapter.get())
                        .whenComplete((updateResponse, throwable) -> {
                            if (throwable != null) {
                                monitor.severe(format("Unexpected exception occurred during in crawler %s", crawler.getId()), throwable);
                            } else {
                                monitor.debug(format("Crawler [%s] is done", crawler.getId()));
                            }
                            availableCrawlers.add(crawler);
                        });
            }
        }
    }

    @Nullable
    private Crawler nextAvailableCrawler(ArrayBlockingQueue<Crawler> availableCrawlers) {
        Crawler crawler = null;
        try {
            crawler = availableCrawlers.poll(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            monitor.debug("interrupted while waiting for crawler to become available");
        }
        return crawler;
    }

    private void runPostExecution() {
        if (postExecutionTask != null) {
            try {
                monitor.debug("Run post-execution task");
                postExecutionTask.run();
            } catch (Throwable thr) {
                monitor.severe("Error running post execution task", thr);
            }
        }
    }

    private void runPreExecution() {
        if (preExecutionTask != null) {
            try {
                monitor.debug("Run pre-execution task");
                preExecutionTask.run();
            } catch (Throwable thr) {
                monitor.severe("Error running pre execution task", thr);
            }
        }
    }

    @NotNull
    private ArrayBlockingQueue<Crawler> createCrawlers(CrawlerErrorHandler errorHandler, int numCrawlers) {
        var crawlers = IntStream.range(0, numCrawlers)
                .mapToObj(i -> new Crawler(monitor, errorHandler, successHandler))
                .toList();
        return new ArrayBlockingQueue<>(numCrawlers, true, crawlers);
    }

    private List<WorkItem> fetchWorkItems() {
        // use all nodes EXCEPT self
        return directory.getAll().stream()
                .filter(nodeFilter)
                .map(n -> new WorkItem(n.id(), n.targetUrl(), selectProtocol(n.supportedProtocols())))
                .collect(Collectors.toList());
    }

    private String selectProtocol(List<String> supportedProtocols) {
        //just take the first matching one.
        return supportedProtocols.isEmpty() ? null : supportedProtocols.get(0);
    }

    @NotNull
    private CrawlerErrorHandler createErrorHandlers(Monitor monitor, Queue<WorkItem> workItems) {
        return workItem -> {
            if (workItem.getErrors().size() > 7) {
                monitor.severe(format("The following WorkItem has errored out more than 7 times. We'll discard it now: [%s]", workItem));
            } else {
                var random = new Random();
                var delaySeconds = 5 + random.nextInt(20);
                monitor.debug(format("The following work item has errored out. Will re-queue after a delay of %s seconds: [%s]", delaySeconds, workItem));
                Executors.newSingleThreadScheduledExecutor().schedule(() -> workItems.offer(workItem), delaySeconds, TimeUnit.SECONDS);
            }
        };
    }


    public static final class Builder {

        private final ExecutionManager instance;

        private Builder() {
            instance = new ExecutionManager();
        }

        public static Builder newInstance() {
            return new Builder();
        }

        public Builder monitor(Monitor monitor) {
            instance.monitor = monitor;
            return this;
        }

        public Builder preExecutionTask(Runnable preExecutionTask) {
            instance.preExecutionTask = preExecutionTask;
            return this;
        }

        public Builder numCrawlers(int numCrawlers) {
            instance.numCrawlers = numCrawlers;
            return this;
        }

        public Builder isEnabled(boolean isEnabled) {
            instance.enabled = isEnabled;
            return this;
        }

        public Builder postExecutionTask(Runnable postExecutionTask) {
            instance.postExecutionTask = postExecutionTask;
            return this;
        }

        public Builder nodeQueryAdapterRegistry(CrawlerActionRegistry registry) {
            instance.crawlerActionRegistry = registry;
            return this;
        }

        public Builder nodeDirectory(TargetNodeDirectory directory) {
            instance.directory = directory;
            return this;
        }

        public Builder nodeFilterFunction(TargetNodeFilter filter) {
            instance.nodeFilter = filter;
            return this;
        }

        public Builder onSuccess(CrawlerSuccessHandler successConsumer) {
            instance.successHandler = successConsumer;
            return this;
        }

        public ExecutionManager build() {
            Objects.requireNonNull(instance.monitor, "ExecutionManager.Builder: Monitor cannot be null");
            Objects.requireNonNull(instance.crawlerActionRegistry, "ExecutionManager.Builder: nodeQueryAdapterRegistry cannot be null");
            Objects.requireNonNull(instance.directory, "ExecutionManager.Builder: nodeDirectory cannot be null");
            return instance;
        }
    }
}
