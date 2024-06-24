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

package org.eclipse.edc.catalog.instrumentation;

import org.eclipse.edc.catalog.spi.CatalogConstants;
import org.eclipse.edc.connector.dataplane.selector.spi.client.DataPlaneClientFactory;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Provider;
import org.eclipse.edc.spi.message.RemoteMessageDispatcher;
import org.eclipse.edc.spi.message.RemoteMessageDispatcherRegistry;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MockInjectionExtension implements ServiceExtension {

    @Inject
    private RemoteMessageDispatcherRegistry registry;
    private RemoteMessageDispatcher dispatcher;

    @Override
    public void initialize(ServiceExtensionContext context) {
        registry.register(createDispatcher());
    }

    @Provider
    public RemoteMessageDispatcher createDispatcher() {
        if (dispatcher == null) {
            dispatcher = mock(RemoteMessageDispatcher.class);
            when(dispatcher.protocol()).thenReturn(CatalogConstants.DATASPACE_PROTOCOL);
        }

        return dispatcher;
    }

    @Provider
    public DataPlaneClientFactory createDataPlaneClientFactory() {
        return mock();
    }
}
