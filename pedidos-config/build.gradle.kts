// =============================================================================
// Servidor de configuracion (Spring Cloud Config) con cifrado JCE.
//
// Permite servir la configuracion centralizada y cifrar los secretos antes de
// guardarlos en el repositorio de configuracion.
// =============================================================================

plugins {
    alias(libs.plugins.spring.boot)
}

description = "Servidor de configuracion centralizada con cifrado JCE"

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.spring.cloud.bom))

    implementation(libs.spring.cloud.config.server)
    implementation(libs.spring.boot.starter.actuator)

    testImplementation(libs.spring.boot.starter.test)
}
