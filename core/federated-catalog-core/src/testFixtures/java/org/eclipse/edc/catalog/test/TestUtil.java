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

package org.eclipse.edc.catalog.test;

import org.eclipse.edc.connector.controlplane.catalog.spi.Catalog;
import org.eclipse.edc.connector.controlplane.catalog.spi.DataService;
import org.eclipse.edc.connector.controlplane.catalog.spi.Dataset;
import org.eclipse.edc.connector.controlplane.catalog.spi.Distribution;
import org.eclipse.edc.crawler.spi.TargetNode;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

public class TestUtil {

    public static final String TEST_PROTOCOL = "test-protocol";

    public static Catalog createCatalog(String id) {
        var dataService = DataService.Builder.newInstance().build();
        return buildCatalog(id)
                .datasets(List.of(Dataset.Builder.newInstance().id(id + "-dataset").distributions(List.of(Distribution.Builder.newInstance().dataService(dataService).format("test-format").build())).build()))
                .dataServices(List.of(dataService))
                .build();
    }

    public static Catalog.Builder buildCatalog(String id) {
        return Catalog.Builder.newInstance()
                .participantId("test-participant")
                .id(id)
                .properties(new HashMap<>());
    }

    @NotNull
    public static TargetNode createNode() {
        return new TargetNode("testnode" + UUID.randomUUID(), "did:web:" + UUID.randomUUID(), "http://test.com", List.of(TEST_PROTOCOL));
    }
}
