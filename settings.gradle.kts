rootProject.name = "federated-catalog"

include(":spi:crawler-spi")
include(":spi:federated-catalog-spi")

include(":core:crawler-core")
include(":core:federated-catalog-core")
include(":core:common:lib:catalog-util-lib")

include(":extensions:api:federated-catalog-api")
include(":extensions:store:sql:target-node-directory-sql")
include(":extensions:store:sql:federated-catalog-cache-sql")
include(":launchers:catalog-dcp")
include(":launchers:catalog-mocked")
include(":system-tests:component-tests")
include(":system-tests:end2end-test:connector-runtime")
include(":system-tests:end2end-test:e2e-junit-runner")
include(":system-tests:bom-tests")
include(":version-catalog")

// BOM modules
include(":dist:bom:federatedcatalog-base-bom")
include(":dist:bom:federatedcatalog-dcp-bom")
include(":dist:bom:federatedcatalog-feature-sql-bom")

// this is needed to have access to snapshot builds of plugins
pluginManagement {
    repositories {
        mavenLocal()
        maven {
            url = uri("https://oss.sonatype.org/content/repositories/snapshots/")
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        maven {
            url = uri("https://oss.sonatype.org/content/repositories/snapshots/")
        }
        mavenCentral()
    }
}
