// =============================================================================
// Microservicio de clientes.
//
// Es dueno de sus datos: el servicio de pedidos lo consulta por HTTP (descubrimiento
// de servicios + balanceo de carga), nunca comparte base de datos.
// =============================================================================

plugins {
    alias(libs.plugins.spring.boot)
}

description = "Microservicio de clientes: alta y consulta de clientes"

dependencies {
    testImplementation(project(":pedidos-testkit"))

    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.spring.cloud.bom))

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
    // Eventos asincronos entre servicios: Spring Cloud Stream sobre RabbitMQ.
    implementation(libs.spring.cloud.starter.stream.rabbit)

    implementation(libs.spring.cloud.starter.loadbalancer)
    implementation(libs.spring.cloud.starter.netflix.eureka.client)

    runtimeOnly(libs.r2dbc.pool)
    runtimeOnly(libs.r2dbc.h2)
    runtimeOnly(libs.r2dbc.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.cloud.stream.test.binder)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.reactor.test)
}
