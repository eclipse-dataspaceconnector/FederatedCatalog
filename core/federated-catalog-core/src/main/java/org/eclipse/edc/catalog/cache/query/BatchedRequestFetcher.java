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

package org.eclipse.edc.catalog.cache.query;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.json.JsonObject;
import org.eclipse.edc.catalog.spi.Catalog;
import org.eclipse.edc.catalog.spi.CatalogConstants;
import org.eclipse.edc.catalog.spi.CatalogRequestMessage;
import org.eclipse.edc.connector.contract.spi.types.offer.ContractOffer;
import org.eclipse.edc.jsonld.spi.JsonLd;
import org.eclipse.edc.spi.EdcException;
import org.eclipse.edc.spi.message.Range;
import org.eclipse.edc.spi.message.RemoteMessageDispatcherRegistry;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.query.QuerySpec;
import org.eclipse.edc.spi.result.Failure;
import org.eclipse.edc.transform.spi.TypeTransformerRegistry;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static java.lang.String.format;
import static java.util.Optional.ofNullable;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.failedFuture;

/**
 * Helper class that runs through a loop and sends {@link CatalogRequestMessage}s until no more {@link ContractOffer}s are
 * received. This is useful to avoid overloading the provider connector by chunking the resulting response payload
 * size.
 */
public class BatchedRequestFetcher {
    private final RemoteMessageDispatcherRegistry dispatcherRegistry;
    private final Monitor monitor;
    private final ObjectMapper objectMapper;
    private final TypeTransformerRegistry transformerRegistry;
    private final JsonLd jsonLdService;

    public BatchedRequestFetcher(RemoteMessageDispatcherRegistry dispatcherRegistry, Monitor monitor, ObjectMapper objectMapper, TypeTransformerRegistry transformerRegistry, JsonLd jsonLdService) {
        this.dispatcherRegistry = dispatcherRegistry;
        this.monitor = monitor;
        this.objectMapper = objectMapper;
        this.transformerRegistry = transformerRegistry;
        this.jsonLdService = jsonLdService;
    }

    private static Catalog copyCatalogWithoutNulls(Catalog catalog) {
        return Catalog.Builder.newInstance().id(catalog.getId())
                .contractOffers(ofNullable(catalog.getContractOffers()).orElseGet(ArrayList::new))
                .properties(ofNullable(catalog.getProperties()).orElseGet(HashMap::new))
                .dataServices(ofNullable(catalog.getDataServices()).orElseGet(ArrayList::new))
                .datasets(ofNullable(catalog.getDatasets()).orElseGet(ArrayList::new))
                .build();
    }


    /**
     * Gets all contract offers. Requests are split in digestible chunks to match {@code batchSize} until no more offers
     * can be obtained.
     *
     * @param catalogRequest The catalog request. This will be copied for every request.
     * @param from           The (zero-based) index of the first item
     * @param batchSize      The size of one batch
     * @return A list of {@link ContractOffer} objects
     */
    public @NotNull CompletableFuture<Catalog> fetch(CatalogRequestMessage catalogRequest, int from, int batchSize) {

        var range = new Range(from, from + batchSize);
        var rq = toBuilder(catalogRequest)
                .protocol(CatalogConstants.DATASPACE_PROTOCOL)
                .querySpec(QuerySpec.Builder.newInstance().range(range).build())
                .build();

        return dispatcherRegistry.send(byte[].class, rq)
                .thenCompose(this::readCatalogFrom)
                .thenCompose(catalog -> completedFuture(copyCatalogWithoutNulls(catalog)))
                .thenCompose(catalog -> {

                    var offers = catalog.getContractOffers();
                    if (offers.size() >= batchSize) {
                        monitor.debug(format("Fetching next batch from %s to %s", from, from + batchSize));
                        return fetch(rq, range.getFrom() + batchSize, batchSize)
                                .thenApply(o -> concat(catalog, o));
                    } else {
                        return completedFuture(catalog);
                    }
                });
    }

    private CompletableFuture<Catalog> readCatalogFrom(byte[] bytes) {
        try {
            var json = new String(bytes);
            var catalogJsonObject = objectMapper.readValue(json, JsonObject.class);
            return jsonLdService.expand(catalogJsonObject)
                    .compose(expandedJson -> transformerRegistry.transform(expandedJson, Catalog.class))
                    .map(CompletableFuture::completedFuture)
                    .orElse((Failure f) -> failedFuture(new EdcException(f.getFailureDetail())));
        } catch (JsonProcessingException e) {
            monitor.severe(() -> "Error parsing Catalog from byes", e);
            return failedFuture(e);
        }
    }

    private CatalogRequestMessage.Builder toBuilder(CatalogRequestMessage catalogRequest) {
        return CatalogRequestMessage.Builder.newInstance()
                .counterPartyAddress(catalogRequest.getCounterPartyAddress());
    }

    private QuerySpec forRange(Range range) {
        return QuerySpec.Builder.newInstance().range(range).build();
    }

    private Catalog concat(Catalog target, Catalog source) {
        target.getContractOffers().addAll(source.getContractOffers());
        return target;
    }

    private List<ContractOffer> concat(List<ContractOffer> list1, List<ContractOffer> list2) {
        list1.addAll(list2);
        return list1;
    }
}
