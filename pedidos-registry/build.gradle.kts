// =============================================================================
// Servidor de descubrimiento de servicios (Eureka).
// =============================================================================

plugins {
    alias(libs.plugins.spring.boot)
}

description = "Servidor de descubrimiento de servicios (Netflix Eureka)"

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.spring.cloud.bom))

    implementation(libs.spring.cloud.starter.netflix.eureka.server)
    implementation(libs.spring.boot.starter.actuator)

    testImplementation(libs.spring.boot.starter.test)
}
