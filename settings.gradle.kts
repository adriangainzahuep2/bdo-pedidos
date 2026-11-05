// =============================================================================
// Configuracion del build multi-modulo.
//
// migrated-from: Maven (pom.xml) -> Gradle 8.10.2 (Kotlin DSL).
// Las versiones de las librerias NO han cambiado: es una migracion del sistema de
// construccion, no de las dependencias. La tabla de equivalencias esta en el README.
// =============================================================================

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "bdo-pedidos"

include(
    "pedidos-dominio",
    "pedidos-testkit",
    "pedidos-api",
    "pedidos-cliente",
    "pedidos-auth",
    "pedidos-gateway",
    "pedidos-registry",
    "pedidos-config",
)
