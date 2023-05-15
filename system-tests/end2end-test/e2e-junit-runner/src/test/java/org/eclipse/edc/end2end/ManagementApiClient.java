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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.json.JsonObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.eclipse.edc.catalog.spi.Catalog;
import org.eclipse.edc.catalog.spi.model.FederatedCatalogCacheQuery;
import org.eclipse.edc.spi.result.Result;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.List;

import static java.lang.String.format;

class ManagementApiClient {
    private static final String MANAGEMENT_BASE_URL = "http://localhost:9192/management/v2";
    private static final String CATALOG_BASE_URL = "http://localhost:8181/api";
    private static final TypeReference<List<Catalog>> LIST_TYPE_REFERENCE = new TypeReference<>() {
    };
    private static final MediaType JSON = MediaType.parse("application/json");
    private final ObjectMapper mapper;

    ManagementApiClient(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    private static String mgmt(String path) {
        return MANAGEMENT_BASE_URL + path;
    }

    private static String catalog(String path) {
        return CATALOG_BASE_URL + path;
    }

    Result<String> postAsset(JsonObject entry) {
        return postObjectWithId(createPostRequest(entry, mgmt("/assets")));
    }

    Result<String> postPolicy(String policyJsonLd) {
        return postObjectWithId(createPostRequest(policyJsonLd, mgmt("/policydefinitions")));
    }

    Result<String> postContractDefinition(JsonObject definition) {
        return postObjectWithId(createPostRequest(definition, mgmt("/contractdefinitions")));
    }

    List<Catalog> getContractOffers() {
        var rq = createPostRequest(FederatedCatalogCacheQuery.Builder.newInstance().build(), catalog("/federatedcatalog"));

        try (var response = getClient().newCall(rq).execute()) {
            if (response.isSuccessful()) {
                return mapper.readValue(response.body().string(), LIST_TYPE_REFERENCE);
            }
            throw new RuntimeException(format("Error getting catalog: %s", response));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @NotNull
    private Result<String> postObjectWithId(Request policy) {
        try (var response = getClient()
                .newCall(policy)
                .execute()) {
            var stringbody = response.body().string();
            return response.isSuccessful() ?
                    Result.success(fromJson(stringbody, JsonObject.class).getString("@id")) :
                    Result.failure(response.message());


        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private <T> T fromJson(String string, Class<T> clazz) {
        try {
            return mapper.readValue(string, clazz);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @NotNull
    private Request createPostRequest(Object object, String path) {
        return new Request.Builder().url(path).post(RequestBody.create(asJson(object), JSON)).build();
    }

    private String asJson(Object entry) {
        try {
            return entry instanceof String ? (String) entry : mapper.writeValueAsString(entry);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private OkHttpClient getClient() {
        return new OkHttpClient();
    }
}
