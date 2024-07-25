/*
 *  Copyright (c) 2024 Bayerische Motoren Werke Aktiengesellschaft (BMW AG)
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Bayerische Motoren Werke Aktiengesellschaft (BMW AG) - initial API and implementation
 *
 */

plugins {
    `java-library`
}

dependencies {
    api(project(":spi:federated-catalog-spi"))
    testImplementation(testFixtures(libs.edc.core.sql))
    implementation(libs.edc.core.sql) // for the SqlStatements
    implementation(libs.edc.spi.transaction.datasource)
    implementation(libs.edc.lib.util)

    testImplementation(libs.edc.junit)
    testImplementation(testFixtures(libs.edc.core.sql))
    testImplementation(testFixtures(project(":spi:federated-catalog-spi")))
}
