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

package org.eclipse.edc.catalog.api.query;

import org.eclipse.edc.catalog.spi.QueryService;
import org.eclipse.edc.jsonld.spi.JsonLd;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.spi.EdcException;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.eclipse.edc.spi.system.apiversion.ApiVersionService;
import org.eclipse.edc.spi.system.apiversion.VersionRecord;
import org.eclipse.edc.spi.system.health.HealthCheckResult;
import org.eclipse.edc.spi.system.health.HealthCheckService;
import org.eclipse.edc.spi.types.TypeManager;
import org.eclipse.edc.transform.spi.TypeTransformerRegistry;
import org.eclipse.edc.web.jersey.providers.jsonld.JerseyJsonLdInterceptor;
import org.eclipse.edc.web.jersey.providers.jsonld.ObjectMapperProvider;
import org.eclipse.edc.web.spi.WebService;

import java.io.IOException;

import static org.eclipse.edc.catalog.spi.FccApiContexts.CATALOG_QUERY;
import static org.eclipse.edc.policy.model.OdrlNamespace.ODRL_PREFIX;
import static org.eclipse.edc.policy.model.OdrlNamespace.ODRL_SCHEMA;
import static org.eclipse.edc.spi.constants.CoreConstants.JSON_LD;

@Extension(value = FederatedCatalogApiExtension.NAME)
public class FederatedCatalogApiExtension implements ServiceExtension {

    public static final String NAME = "Cache Query API Extension";
    private static final String CATALOG_QUERY_SCOPE = "CATALOG_QUERY_API";

    private static final String API_VERSION_JSON_FILE = "version.json";

    @Inject
    private WebService webService;

    @Inject
    private QueryService queryService;

    @Inject(required = false)
    private HealthCheckService healthCheckService;
    @Inject
    private JsonLd jsonLd;
    @Inject
    private TypeManager typeManager;

    @Inject
    private TypeTransformerRegistry transformerRegistry;

    @Inject
    private ApiVersionService apiVersionService;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void initialize(ServiceExtensionContext context) {
        jsonLd.registerNamespace(ODRL_PREFIX, ODRL_SCHEMA, CATALOG_QUERY_SCOPE);
        var jsonLdMapper = typeManager.getMapper(JSON_LD);
        var catalogController = new FederatedCatalogApiController(queryService, transformerRegistry);
        webService.registerResource(CATALOG_QUERY, catalogController);
        webService.registerResource(CATALOG_QUERY, new ObjectMapperProvider(jsonLdMapper));
        webService.registerResource(CATALOG_QUERY, new JerseyJsonLdInterceptor(jsonLd, jsonLdMapper, CATALOG_QUERY_SCOPE));

        // contribute to the liveness probe
        if (healthCheckService != null) {
            var successResult = HealthCheckResult.Builder.newInstance().component("FCC Query API").build();
            healthCheckService.addReadinessProvider(() -> successResult);
            healthCheckService.addLivenessProvider(() -> successResult);
        }
        registerVersionInfo(getClass().getClassLoader());
    }

    private void registerVersionInfo(ClassLoader resourceClassLoader) {
        try (var versionContent = resourceClassLoader.getResourceAsStream(API_VERSION_JSON_FILE)) {
            if (versionContent == null) {
                throw new EdcException("Version file not found or not readable.");
            }
            var content = typeManager.getMapper().readValue(versionContent, VersionRecord.class);
            apiVersionService.addRecord(CATALOG_QUERY, content);
        } catch (IOException e) {
            throw new EdcException(e);
        }
    }
}
