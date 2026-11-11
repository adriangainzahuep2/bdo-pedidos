// =============================================================================
// Servidor de autorizacion OAuth2 (Spring Authorization Server).
//
// Emite tokens RS256 y publica el JWKS. Los secretos NO estan en el codigo fuente:
// llegan por variable de entorno (o por Spring Cloud Config cifrado).
// =============================================================================

plugins {
    alias(libs.plugins.spring.boot)
}

description = "Servidor de autorizacion OAuth2 (Spring Authorization Server)"

dependencies {
    implementation(platform(libs.spring.boot.bom))

    implementation(libs.spring.boot.starter.web)
    // El BOM de Spring Boot gestiona la version (Spring Authorization Server 1.3.x).
    implementation(libs.spring.boot.starter.oauth2.authorization.server)
    implementation(libs.spring.boot.starter.actuator)
    // Observabilidad: trazas distribuidas (Micrometer Tracing + Brave -> Zipkin) y
    // metricas en formato Prometheus.
    implementation(libs.micrometer.tracing.bridge.brave)
    implementation(libs.zipkin.reporter.brave)
    runtimeOnly(libs.micrometer.registry.prometheus)
    implementation(libs.spring.boot.starter.validation)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.security.test)
}
