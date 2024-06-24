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

plugins {
    `java-library`
    id("application")
    alias(libs.plugins.shadow)
}

dependencies {
    runtimeOnly(project(":core:federated-catalog-core"))
    runtimeOnly(project(":extensions:api:federated-catalog-api"))
    runtimeOnly(project(":spi:federated-catalog-spi"))
    runtimeOnly(libs.edc.lib.util)
    runtimeOnly(libs.edc.spi.jsonld)

    runtimeOnly(libs.bundles.edc.connector)
    runtimeOnly(libs.edc.core.controlplane)
    runtimeOnly(libs.edc.core.jetty)
    runtimeOnly(libs.edc.lib.providers.jersey)
    runtimeOnly(libs.edc.lib.boot)


    runtimeOnly(libs.edc.dsp.all)
    runtimeOnly(libs.edc.iam.mock)
}

application {
    mainClass.set("org.eclipse.edc.boot.system.runtime.BaseRuntime")
}

tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
    mergeServiceFiles()
    archiveFileName.set("fc.jar")
}

edcBuild {
    publish.set(false)
}
