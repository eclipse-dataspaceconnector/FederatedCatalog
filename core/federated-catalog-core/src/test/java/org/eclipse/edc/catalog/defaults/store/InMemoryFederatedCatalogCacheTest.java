/*
 *  Copyright (c) 2020 - 2022 Microsoft Corporation
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

package org.eclipse.edc.catalog.defaults.store;


import org.eclipse.edc.catalog.spi.CatalogConstants;
import org.eclipse.edc.catalog.store.InMemoryFederatedCatalogCache;
import org.eclipse.edc.connector.controlplane.asset.spi.domain.Asset;
import org.eclipse.edc.connector.controlplane.catalog.spi.Catalog;
import org.eclipse.edc.connector.controlplane.catalog.spi.DataService;
import org.eclipse.edc.connector.controlplane.catalog.spi.Dataset;
import org.eclipse.edc.connector.controlplane.catalog.spi.Distribution;
import org.eclipse.edc.query.CriterionOperatorRegistryImpl;
import org.eclipse.edc.spi.query.QuerySpec;
import org.eclipse.edc.util.concurrency.LockManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryFederatedCatalogCacheTest {

    private final InMemoryFederatedCatalogCache store = new InMemoryFederatedCatalogCache(new LockManager(new ReentrantReadWriteLock()), CriterionOperatorRegistryImpl.ofDefaults());

    @Test
    void queryCacheContainingOneElementWithNoCriterion_shouldReturnUniqueElement() {
        var contractOfferId = UUID.randomUUID().toString();
        var assetId = UUID.randomUUID().toString();
        var catalogEntry = createCatalog(contractOfferId, createAsset(assetId));

        store.save(catalogEntry);

        var result = store.query(QuerySpec.none());

        assertThat(result)
                .hasSize(1)
                .allSatisfy(co -> assertThat(co.getDatasets().get(0).getId()).isEqualTo(assetId));
    }

    @Test
    void queryCacheAfterInsertingSameAssetTwice_shouldReturnLastInsertedContractOfferOnly() {
        var contractOfferId1 = UUID.randomUUID().toString();
        var assetId = UUID.randomUUID().toString();
        var entry1 = createCatalog(contractOfferId1, createAsset(assetId));
        var entry2 = createCatalog(contractOfferId1, createAsset(assetId));

        store.save(entry1);
        store.save(entry2);

        var result = store.query(QuerySpec.none());

        assertThat(result)
                .hasSize(1)
                .allSatisfy(co -> {
                    assertThat(co.getId()).isEqualTo(contractOfferId1);
                    assertThat(co.getDatasets().get(0).getId()).isEqualTo(assetId);
                });

    }

    @Test
    void queryCacheContainingTwoDistinctAssets_shouldReturnBothContractOffers() {
        var contractOfferId1 = UUID.randomUUID().toString();
        var contractOfferId2 = UUID.randomUUID().toString();
        var assetId1 = UUID.randomUUID().toString();
        var assetId2 = UUID.randomUUID().toString();
        var entry1 = createCatalog(contractOfferId1, createAsset(assetId1));
        var entry2 = createCatalog(contractOfferId2, createAsset(assetId2));

        store.save(entry1);
        store.save(entry2);

        var result = store.query(QuerySpec.none());

        assertThat(result)
                .hasSize(2)
                .anySatisfy(co -> assertThat(co.getDatasets().get(0).getId()).isEqualTo(assetId1))
                .anySatisfy(co -> assertThat(co.getDatasets().get(0).getId()).isEqualTo(assetId2));
    }

    @Test
    void removedMarked_noneMarked() {
        var contractOfferId1 = UUID.randomUUID().toString();
        var contractOfferId2 = UUID.randomUUID().toString();
        var assetId1 = UUID.randomUUID().toString();
        var assetId2 = UUID.randomUUID().toString();
        var entry1 = createCatalog(contractOfferId1, createAsset(assetId1));
        var entry2 = createCatalog(contractOfferId2, createAsset(assetId2));

        store.save(entry1);
        store.save(entry2);

        assertThat(store.query(QuerySpec.none())).hasSize(2);

        store.deleteExpired(); // none of them is marked, d
        assertThat(store.query(QuerySpec.none())).containsExactlyInAnyOrder(entry1, entry2);

    }

    @Test
    void removedMarked_shouldDeleteMarked() {
        var contractOfferId1 = UUID.randomUUID().toString();
        var contractOfferId2 = UUID.randomUUID().toString();
        var assetId1 = UUID.randomUUID().toString();
        var assetId2 = UUID.randomUUID().toString();
        var entry1 = createCatalog(contractOfferId1, createAsset(assetId1));
        var entry2 = createCatalog(contractOfferId2, createAsset(assetId2));

        store.save(entry1);
        store.save(entry2);

        assertThat(store.query(QuerySpec.none())).hasSize(2);

        store.expireAll(); // two items marked
        store.save(createCatalog(UUID.randomUUID().toString(), createAsset(UUID.randomUUID().toString())));
        store.deleteExpired(); // should delete only marked items
        assertThat(store.query(QuerySpec.none())).hasSize(1)
                .doesNotContain(entry1, entry2);

    }

    private Catalog createCatalog(String id, Asset asset) {
        var dataService = DataService.Builder.newInstance().build();
        return Catalog.Builder.newInstance()
                .id(id)
                .dataServices(List.of(dataService))
                .datasets(List.of(Dataset.Builder.newInstance().id(asset.getId()).distributions(List.of(Distribution.Builder.newInstance().dataService(dataService).format("test-format").build())).build()))
                .property(CatalogConstants.PROPERTY_ORIGINATOR, "https://test.source/" + id)
                .build();
    }

    private Asset createAsset(String id) {
        return Asset.Builder.newInstance()
                .id(id)
                .build();
    }
}
