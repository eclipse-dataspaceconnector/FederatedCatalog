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
 *       Microsoft Corporation - initial API and implementation
 *
 */

plugins {
    `java-library`
    `java-test-fixtures`
}

dependencies {
    api(libs.edc.spi.core)
    api(libs.edc.spi.web)
    api(libs.edc.spi.catalog)
    api(libs.edc.spi.dsp)
    api(project(":core:crawler-core"))
    api(project(":spi:federated-catalog-spi"))
    implementation(project(":core:common:lib:catalog-util-lib"))

    implementation(libs.edc.lib.util)
    implementation(libs.edc.core.connector)
    implementation(libs.edc.lib.query)
    implementation(libs.edc.dsp.transform.catalog)
    implementation(libs.edc.controlplane.transform)
    implementation(libs.edc.lib.transform)
    implementation(libs.edc.dsp.api.configuration)
    implementation(libs.edc.spi.jsonld)
    implementation(libs.edc.json.ld.lib)
    implementation(libs.edc.lib.store)

    // required for integration test
    testImplementation(libs.edc.dsp.transform.catalog)
    testImplementation(libs.edc.junit)
    testImplementation(libs.edc.ext.http)
    testImplementation(libs.awaitility)

    testImplementation(testFixtures(project(":spi:federated-catalog-spi")))
}

edcBuild {
    swagger {
        apiGroup.set("management-api")
    }
}
