// =============================================================================
// API Gateway: unico punto de entrada. Valida el JWT, limita la peticiones, genera
// el identificador de correlacion y enruta con lb:// a traves de Eureka.
// =============================================================================

plugins {
    alias(libs.plugins.spring.boot)
}

description = "API Gateway: autenticacion, limitacion de peticiones y enrutado con descubrimiento de servicios"

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.spring.cloud.bom))
    // El BOM de Spring Cloud fija Resilience4j 2.1.0: se importa el BOM propio para
    // usar la misma version (2.2.0) que el resto de modulos.
    implementation(platform(libs.resilience4j.bom))

    implementation(libs.spring.cloud.starter.gateway)
    implementation(libs.spring.cloud.starter.loadbalancer)
    implementation(libs.spring.cloud.starter.netflix.eureka.client)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.oauth2.resource.server)
    implementation(libs.spring.boot.starter.actuator)

    implementation(libs.resilience4j.spring.boot3)
    implementation(libs.resilience4j.reactor)
    // Filtro CircuitBreaker de las rutas: un circuito por microservicio de destino.
    implementation(libs.spring.cloud.starter.circuitbreaker.reactor.resilience4j)

    testImplementation(project(":pedidos-testkit"))
    testImplementation(libs.wiremock.standalone)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.reactor.test)
}
