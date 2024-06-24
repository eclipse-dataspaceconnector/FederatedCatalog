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

package org.eclipse.edc.end2end;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.json.Json;
import org.eclipse.edc.catalog.directory.InMemoryNodeDirectory;
import org.eclipse.edc.catalog.spi.CatalogConstants;
import org.eclipse.edc.catalog.transform.JsonObjectToCatalogTransformer;
import org.eclipse.edc.catalog.transform.JsonObjectToDataServiceTransformer;
import org.eclipse.edc.catalog.transform.JsonObjectToDatasetTransformer;
import org.eclipse.edc.catalog.transform.JsonObjectToDistributionTransformer;
import org.eclipse.edc.connector.controlplane.transform.odrl.from.JsonObjectFromPolicyTransformer;
import org.eclipse.edc.connector.core.agent.NoOpParticipantIdMapper;
import org.eclipse.edc.crawler.spi.TargetNode;
import org.eclipse.edc.crawler.spi.TargetNodeDirectory;
import org.eclipse.edc.jsonld.TitaniumJsonLd;
import org.eclipse.edc.jsonld.util.JacksonJsonLd;
import org.eclipse.edc.junit.annotations.EndToEndTest;
import org.eclipse.edc.junit.extensions.EmbeddedRuntime;
import org.eclipse.edc.junit.extensions.RuntimeExtension;
import org.eclipse.edc.junit.extensions.RuntimePerClassExtension;
import org.eclipse.edc.junit.extensions.RuntimePerMethodExtension;
import org.eclipse.edc.protocol.dsp.catalog.transform.from.JsonObjectFromCatalogTransformer;
import org.eclipse.edc.protocol.dsp.catalog.transform.from.JsonObjectFromDataServiceTransformer;
import org.eclipse.edc.protocol.dsp.catalog.transform.from.JsonObjectFromDatasetTransformer;
import org.eclipse.edc.protocol.dsp.catalog.transform.from.JsonObjectFromDistributionTransformer;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.result.Result;
import org.eclipse.edc.transform.TypeTransformerRegistryImpl;
import org.eclipse.edc.transform.spi.TypeTransformerRegistry;
import org.eclipse.edc.transform.transformer.edc.to.JsonValueToGenericTypeTransformer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static java.time.Duration.ofSeconds;
import static java.util.Optional.ofNullable;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.eclipse.edc.connector.controlplane.transform.odrl.OdrlTransformersFactory.jsonObjectToOdrlTransformers;
import static org.eclipse.edc.end2end.TestFunctions.createContractDef;
import static org.eclipse.edc.end2end.TestFunctions.createPolicy;
import static org.mockito.Mockito.mock;

@EndToEndTest
class FederatedCatalogTest {

    public static final Duration TIMEOUT = ofSeconds(60);
    private static final Endpoint CONNECTOR_MANAGEMENT = new Endpoint("/management", "8081");
    private static final Endpoint CONNECTOR_PROTOCOL = new Endpoint("/api/v1/dsp", "8082");
    private static final Endpoint CONNECTOR_DEFAULT = new Endpoint("/api/v1/", "8080");
    private static final Endpoint CONNECTOR_CONTROL = new Endpoint("/api/v1/control", "8083");

    private static final Endpoint CATALOG_MANAGEMENT = new Endpoint("/management", "8091");
    private static final Endpoint CATALOG_PROTOCOL = new Endpoint("/api/v1/dsp", "8092");
    private static final Endpoint CATALOG_DEFAULT = new Endpoint("/api/v1/", "8090");
    private static final Endpoint CATALOG_CATALOG = new Endpoint("/catalog", "8093");

    @RegisterExtension
    static RuntimeExtension connector = new RuntimePerClassExtension(new EmbeddedRuntime("connector",
            configOf("edc.connector.name", "connector1",
                    "edc.web.rest.cors.enabled", "true",
                    "web.http.port", CONNECTOR_DEFAULT.port(),
                    "web.http.path", CONNECTOR_DEFAULT.path(),
                    "web.http.protocol.port", CONNECTOR_PROTOCOL.port(),
                    "web.http.protocol.path", CONNECTOR_PROTOCOL.path(),
                    "web.http.control.port", CONNECTOR_CONTROL.port(),
                    "web.http.control.path", CONNECTOR_CONTROL.path(),
                    "web.http.management.port", CONNECTOR_MANAGEMENT.port(),
                    "edc.participant.id", "test-connector",
                    "web.http.management.path", CONNECTOR_MANAGEMENT.path(),
                    "edc.web.rest.cors.headers", "origin,content-type,accept,authorization,x-api-key",
                    "edc.dsp.callback.address", "http://localhost:%s%s".formatted(CONNECTOR_PROTOCOL.port(), CONNECTOR_PROTOCOL.path())),
            ":system-tests:end2end-test:connector-runtime"));

    @RegisterExtension
    static RuntimeExtension catalog = new RuntimePerMethodExtension(new EmbeddedRuntime("catalog",
            configOf("edc.catalog.cache.execution.delay.seconds", "0",
                    "edc.catalog.cache.execution.period.seconds", "2",
                    "edc.catalog.cache.partition.num.crawlers", "5",
                    "edc.web.rest.cors.enabled", "true",
                    "edc.participant.id", "test-catalog",
                    "web.http.port", CATALOG_DEFAULT.port(),
                    "web.http.path", CATALOG_DEFAULT.path(),
                    "web.http.protocol.port", CATALOG_PROTOCOL.port(),
                    "web.http.protocol.path", CATALOG_PROTOCOL.path(),
                    "web.http.management.port", CATALOG_MANAGEMENT.port(),
                    "web.http.management.path", CATALOG_MANAGEMENT.path(),
                    "web.http.catalog.port", CATALOG_CATALOG.port(),
                    "web.http.catalog.path", CATALOG_CATALOG.path(),
                    "edc.web.rest.cors.headers", "origin,content-type,accept,authorization,x-api-key"),
            ":system-tests:end2end-test:catalog-runtime"));
    private final TypeTransformerRegistry typeTransformerRegistry = new TypeTransformerRegistryImpl();
    private final ObjectMapper mapper = JacksonJsonLd.createObjectMapper();
    private final CatalogApiClient apiClient = new CatalogApiClient(CATALOG_CATALOG, CONNECTOR_MANAGEMENT, mapper, new TitaniumJsonLd(mock(Monitor.class)), typeTransformerRegistry);

    private static Map<String, String> configOf(String... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("Must have an even number of key value pairs, was " + keyValuePairs.length);
        }

        var map = new HashMap<String, String>();
        for (int i = 0; i < keyValuePairs.length - 1; i += 2) {
            map.put(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return map;
    }

    @BeforeEach
    void setUp() {
        //needed for ZonedDateTime
        mapper.registerModule(new JavaTimeModule());
        var factory = Json.createBuilderFactory(Map.of());
        var participantIdMapper = new NoOpParticipantIdMapper();
        typeTransformerRegistry.register(new JsonObjectFromCatalogTransformer(factory, mapper, participantIdMapper));
        typeTransformerRegistry.register(new JsonObjectFromDatasetTransformer(factory, mapper));
        typeTransformerRegistry.register(new JsonObjectFromDataServiceTransformer(factory));
        typeTransformerRegistry.register(new JsonObjectFromPolicyTransformer(factory, participantIdMapper));
        typeTransformerRegistry.register(new JsonObjectFromDistributionTransformer(factory));
        typeTransformerRegistry.register(new JsonObjectToCatalogTransformer());
        typeTransformerRegistry.register(new JsonObjectToDatasetTransformer());
        typeTransformerRegistry.register(new JsonObjectToDataServiceTransformer());
        jsonObjectToOdrlTransformers(participantIdMapper).forEach(typeTransformerRegistry::register);
        typeTransformerRegistry.register(new JsonObjectToDistributionTransformer());
        typeTransformerRegistry.register(new JsonValueToGenericTypeTransformer(mapper));

        var directory = new InMemoryNodeDirectory();
        directory.insert(new TargetNode("connector", "did:web:" + UUID.randomUUID(), "http://localhost:%s%s".formatted(CONNECTOR_PROTOCOL.port(), CONNECTOR_PROTOCOL.path()), List.of(CatalogConstants.DATASPACE_PROTOCOL)));
        catalog.registerServiceMock(TargetNodeDirectory.class, directory);
    }

    @Test
    void crawl_whenOfferAvailable_shouldContainOffer(TestInfo testInfo) {
        // setup
        var id = String.format("%s-%s", testInfo.getDisplayName(), UUID.randomUUID());
        var asset = TestFunctions.createAssetJson(id);
        var r = apiClient.postAsset(asset);
        assertThat(r.succeeded()).withFailMessage(getError(r)).isTrue();

        var assetId = r.getContent();

        var policyId = "policy-" + id;
        var policy = createPolicy(policyId, id);
        var pr = apiClient.postPolicy(policy);
        assertThat(r.succeeded()).withFailMessage(getError(pr)).isTrue();

        policyId = pr.getContent();

        var request = createContractDef("def-" + id, policyId, policyId, assetId);

        var dr = apiClient.postContractDefinition(request);
        assertThat(dr.succeeded()).withFailMessage(getError(dr)).isTrue();

        var assetIdBase64 = Base64.getEncoder().encodeToString(assetId.getBytes());
        // act-assert
        await().pollDelay(ofSeconds(1))
                .pollInterval(ofSeconds(1))
                .atMost(TIMEOUT)
                .untilAsserted(() -> {
                    var catalogs = apiClient.getContractOffers();

                    assertThat(catalogs).hasSizeGreaterThanOrEqualTo(1);
                    assertThat(catalogs).anySatisfy(catalog -> assertThat(catalog.getDatasets())
                            .anySatisfy(dataset -> {
                                assertThat(dataset.getOffers()).hasSizeGreaterThanOrEqualTo(1);
                                assertThat(dataset.getOffers().keySet()).anyMatch(key -> key.contains(assetIdBase64));
                            }));

                });
    }

    private String getError(Result<String> r) {
        return ofNullable(r.getFailureDetail()).orElse("No error");
    }
}
