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

package org.eclipse.edc.catalog.query;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.eclipse.edc.catalog.cache.query.PagingCatalogFetcher;
import org.eclipse.edc.catalog.spi.CatalogConstants;
import org.eclipse.edc.catalog.transform.JsonObjectToCatalogTransformer;
import org.eclipse.edc.catalog.transform.JsonObjectToDataServiceTransformer;
import org.eclipse.edc.catalog.transform.JsonObjectToDatasetTransformer;
import org.eclipse.edc.catalog.transform.JsonObjectToDistributionTransformer;
import org.eclipse.edc.connector.controlplane.catalog.spi.Catalog;
import org.eclipse.edc.connector.controlplane.catalog.spi.CatalogRequestMessage;
import org.eclipse.edc.connector.controlplane.catalog.spi.DataService;
import org.eclipse.edc.connector.controlplane.catalog.spi.Dataset;
import org.eclipse.edc.connector.controlplane.catalog.spi.Distribution;
import org.eclipse.edc.connector.controlplane.transform.odrl.from.JsonObjectFromPolicyTransformer;
import org.eclipse.edc.connector.controlplane.transform.odrl.to.JsonObjectToPolicyTransformer;
import org.eclipse.edc.connector.core.agent.NoOpParticipantIdMapper;
import org.eclipse.edc.jsonld.TitaniumJsonLd;
import org.eclipse.edc.jsonld.util.JacksonJsonLd;
import org.eclipse.edc.junit.annotations.ComponentTest;
import org.eclipse.edc.policy.model.Policy;
import org.eclipse.edc.protocol.dsp.catalog.transform.from.JsonObjectFromCatalogTransformer;
import org.eclipse.edc.protocol.dsp.catalog.transform.from.JsonObjectFromDataServiceTransformer;
import org.eclipse.edc.protocol.dsp.catalog.transform.from.JsonObjectFromDatasetTransformer;
import org.eclipse.edc.protocol.dsp.catalog.transform.from.JsonObjectFromDistributionTransformer;
import org.eclipse.edc.spi.message.Range;
import org.eclipse.edc.spi.message.RemoteMessageDispatcherRegistry;
import org.eclipse.edc.spi.response.StatusResult;
import org.eclipse.edc.transform.TypeTransformerRegistryImpl;
import org.eclipse.edc.transform.spi.TypeTransformerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.util.Collections.emptyList;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.edc.jsonld.util.JacksonJsonLd.createObjectMapper;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ComponentTest
class PagingCatalogFetcherTest {

    private final RemoteMessageDispatcherRegistry dispatcherRegistryMock = mock();
    private final ObjectMapper objectMapper = createObjectMapper();
    private final TitaniumJsonLd jsonLdService = new TitaniumJsonLd(mock());
    private final TypeTransformerRegistry typeTransformerRegistry = new TypeTransformerRegistryImpl();
    private PagingCatalogFetcher fetcher;

    @BeforeEach
    void setup() {
        registerTransformers();

        fetcher = new PagingCatalogFetcher(dispatcherRegistryMock, mock(), objectMapper, typeTransformerRegistry, jsonLdService);
    }

    @Test
    void fetchAll() throws JsonProcessingException {
        var cat1 = createCatalog(5);
        var cat2 = createCatalog(5);
        var cat3 = createCatalog(3);
        when(dispatcherRegistryMock.dispatch(eq(byte[].class), any(CatalogRequestMessage.class)))
                .thenReturn(completedFuture(toBytes(cat1)))
                .thenReturn(completedFuture(toBytes(cat2)))
                .thenReturn(completedFuture(toBytes(cat3)))
                .thenReturn(completedFuture(toBytes(emptyCatalog())));

        var request = createRequest();

        var catalog = fetcher.fetch(request, 0, 5);
        assertThat(catalog).isCompletedWithValueMatching(list -> list.getDatasets().size() == 13 &&
                list.getDatasets().stream().allMatch(o -> o.getId().matches("(dataset-)\\d|1[0-3]")));


        var captor = forClass(CatalogRequestMessage.class);
        verify(dispatcherRegistryMock, times(3)).dispatch(eq(byte[].class), captor.capture());

        // verify the sequence of requests
        assertThat(captor.getAllValues())
                .extracting(l -> l.getQuerySpec().getRange())
                .usingRecursiveFieldByFieldElementComparator()
                .containsExactly(new Range(0, 5), new Range(5, 10), new Range(10, 15));
    }

    public Dataset createDataset(String id) {
        return Dataset.Builder.newInstance()
                .offer("test-offer", Policy.Builder.newInstance().build())
                .distribution(Distribution.Builder.newInstance().format("test-format").dataService(DataService.Builder.newInstance().build()).build())
                .id(id)
                .build();
    }

    private StatusResult<byte[]> toBytes(Catalog catalog) throws JsonProcessingException {
        var jo = typeTransformerRegistry.transform(catalog, JsonObject.class).getContent();
        var expanded = jsonLdService.expand(jo).getContent();
        var expandedStr = objectMapper.writeValueAsString(expanded);
        return StatusResult.success(expandedStr.getBytes());
    }

    private CatalogRequestMessage createRequest() {
        return CatalogRequestMessage.Builder.newInstance()
                .counterPartyAddress("test-address")
                .protocol(CatalogConstants.DATASPACE_PROTOCOL)
                .build();
    }

    private Catalog emptyCatalog() {
        return Catalog.Builder.newInstance().id("id").participantId("test-participant").datasets(emptyList()).dataServices(emptyList()).build();
    }

    private Catalog createCatalog(int howManyOffers) {
        var datasets = IntStream.range(0, howManyOffers)
                .mapToObj(i -> createDataset("dataset-" + i))
                .collect(Collectors.toList());

        var build = List.of(DataService.Builder.newInstance().build());
        return Catalog.Builder.newInstance().participantId("test-participant").id("catalog").datasets(datasets).dataServices(build).build();
    }

    // registers all the necessary transformers to avoid duplicating their behaviour in mocks
    private void registerTransformers() {
        var factory = Json.createBuilderFactory(Map.of());
        var mapper = JacksonJsonLd.createObjectMapper();
        var participantIdMapper = new NoOpParticipantIdMapper();
        typeTransformerRegistry.register(new JsonObjectFromCatalogTransformer(factory, mapper, participantIdMapper));
        typeTransformerRegistry.register(new JsonObjectFromDatasetTransformer(factory, mapper));
        typeTransformerRegistry.register(new JsonObjectFromDataServiceTransformer(factory));
        typeTransformerRegistry.register(new JsonObjectFromPolicyTransformer(factory, participantIdMapper));
        typeTransformerRegistry.register(new JsonObjectFromDistributionTransformer(factory));
        typeTransformerRegistry.register(new JsonObjectToCatalogTransformer());
        typeTransformerRegistry.register(new JsonObjectToDatasetTransformer());
        typeTransformerRegistry.register(new JsonObjectToDataServiceTransformer());
        typeTransformerRegistry.register(new JsonObjectToPolicyTransformer(participantIdMapper));
        typeTransformerRegistry.register(new JsonObjectToDistributionTransformer());
    }

}
