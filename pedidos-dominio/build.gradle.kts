// =============================================================================
// Dominio de pedidos: modelo, maquina de estados, calculo de importes y puertos.
//
// Modulo 100% Java: no depende de Spring, de Spring Data ni de la infraestructura.
// Unicamente reactor-core en los puertos (contratos reactivos), nunca en las entidades.
// =============================================================================

description = "Dominio de pedidos: modelo, maquina de estados, calculo de importes y puertos"

dependencies {
    // Contratos reactivos. Se expone como "api" porque los consumidores (servicio de
    // pedidos) necesitan ver Mono en las firmas de los puertos.
    api(platform(libs.reactor.bom))
    api(libs.reactor.core)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
}
