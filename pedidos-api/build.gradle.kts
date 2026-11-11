// =============================================================================
// Microservicio de pedidos.
//
// WebFlux + Spring Data R2DBC + OAuth2/JWT, con la integracion con el proveedor
// logistico externo (WebClient por defecto, RestTemplate como alternativa) y las
// capas de resiliencia de Resilience4j.
// =============================================================================

plugins {
    alias(libs.plugins.spring.boot)
}

description = "Microservicio de pedidos: CRUD, confirmacion con coste de envio e integracion con el proveedor logistico"

dependencies {
    implementation(project(":pedidos-dominio"))
    testImplementation(project(":pedidos-testkit"))

    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.spring.cloud.bom))
    implementation(platform(libs.resilience4j.bom))

    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.spring.boot.starter.data.r2dbc)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.oauth2.resource.server)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)
    // Observabilidad: trazas distribuidas (Micrometer Tracing + Brave -> Zipkin) y
    // metricas en formato Prometheus.
    implementation(libs.micrometer.tracing.bridge.brave)
    implementation(libs.zipkin.reporter.brave)
    runtimeOnly(libs.micrometer.registry.prometheus)
    implementation(libs.resilience4j.micrometer)

    // Descubrimiento de servicios y balanceo de carga (Netflix OSS -> Spring Cloud).
    implementation(libs.spring.cloud.starter.loadbalancer)
    implementation(libs.spring.cloud.starter.netflix.eureka.client)

    // Resiliencia: circuito, limitador, bulkhead y reintentos.
    implementation(libs.resilience4j.spring.boot3)
    implementation(libs.resilience4j.reactor)

    // Drivers R2DBC (solo en tiempo de ejecucion).
    runtimeOnly(libs.r2dbc.pool)
    runtimeOnly(libs.r2dbc.h2)
    runtimeOnly(libs.r2dbc.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.reactor.test)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.wiremock.standalone)
}

// -----------------------------------------------------------------------------
// Tests de JavaScript de la consola web (node --test, sin dependencias).
// Forman parte de "check" cuando hay Node.js 18+ en el PATH; si no lo hay, la tarea se
// omite con un aviso en lugar de romper el build de quien solo tiene Java.
// -----------------------------------------------------------------------------
fun nodeDisponible(): Boolean = try {
    ProcessBuilder("node", "--version").start().waitFor() == 0
} catch (error: java.io.IOException) {
    false
}

val testJavaScript by tasks.registering(Exec::class) {
    description = "Ejecuta los tests de JavaScript de la consola web con node --test"
    group = "verification"
    val tests = fileTree("src/test/js") { include("**/*.test.mjs") }
    inputs.files(tests, fileTree("src/main/resources/static"))
    workingDir = projectDir
    commandLine(listOf("node", "--test") + tests.files.map { it.relativeTo(projectDir).path }.sorted())
    onlyIf("Node.js disponible en el PATH") { nodeDisponible() }
}

tasks.named("check") {
    dependsOn(testJavaScript)
}
