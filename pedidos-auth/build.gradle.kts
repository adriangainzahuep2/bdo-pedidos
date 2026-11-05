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
    // Spring Authorization Server no lo gestiona el BOM de Spring Boot: version propia.
    implementation(libs.spring.boot.starter.oauth2.authorization.server)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.security.test)
}
