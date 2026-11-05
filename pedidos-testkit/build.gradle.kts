// =============================================================================
// Utilidades compartidas por las suites de test (generacion de tokens JWT reales).
//
// Se consume SIEMPRE con alcance "test": nunca forma parte de las imagenes de
// produccion.
// =============================================================================

description = "Utilidades compartidas por las suites de test (generacion de tokens JWT reales)"

dependencies {
    api(platform(libs.spring.boot.bom))
    api(libs.spring.security.oauth2.jose)
}
