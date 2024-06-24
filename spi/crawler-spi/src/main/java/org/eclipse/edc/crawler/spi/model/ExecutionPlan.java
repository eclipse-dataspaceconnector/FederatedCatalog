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

package org.eclipse.edc.crawler.spi.model;

/**
 * Interface for any sort of planned execution of a {@link Runnable} task.
 */
@FunctionalInterface
public interface ExecutionPlan {

    /**
     * Execute the task. While not strictly required, spawning another
     * thread it is highly recommended e.g. by forwarding to an {@link java.util.concurrent.Executor}
     *
     * @param task A runnable
     */
    void run(Runnable task);
}
