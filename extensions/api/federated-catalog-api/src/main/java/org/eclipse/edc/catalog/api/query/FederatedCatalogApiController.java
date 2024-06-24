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

package org.eclipse.edc.catalog.api.query;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.edc.catalog.spi.QueryService;
import org.eclipse.edc.spi.query.QuerySpec;
import org.eclipse.edc.spi.result.AbstractResult;
import org.eclipse.edc.spi.result.Result;
import org.eclipse.edc.transform.spi.TypeTransformerRegistry;
import org.eclipse.edc.web.spi.exception.InvalidRequestException;
import org.eclipse.edc.web.spi.exception.ServiceResultHandler;

import javax.xml.catalog.Catalog;

import static jakarta.json.stream.JsonCollectors.toJsonArray;

@Consumes({ MediaType.APPLICATION_JSON })
@Produces({ MediaType.APPLICATION_JSON })
@Path("/v1alpha/catalog/query")
public class FederatedCatalogApiController implements FederatedCatalogApi {

    private final QueryService queryService;
    private final TypeTransformerRegistry transformerRegistry;

    public FederatedCatalogApiController(QueryService queryService, TypeTransformerRegistry transformerRegistry) {
        this.queryService = queryService;
        this.transformerRegistry = transformerRegistry;
    }

    @Override
    @POST
    public JsonArray getCachedCatalog(JsonObject catalogQuery) {
        var querySpec = transformerRegistry.transform(catalogQuery, QuerySpec.class)
                .orElseThrow(InvalidRequestException::new);
        
        var catalogs = queryService.getCatalog(querySpec)
                .orElseThrow(ServiceResultHandler.exceptionMapper(Catalog.class));

        return catalogs.stream().map(c -> transformerRegistry.transform(c, JsonObject.class))
                .filter(Result::succeeded)
                .map(AbstractResult::getContent)
                .collect(toJsonArray());
    }
}
