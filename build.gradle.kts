// =============================================================================
// Build raiz: configuracion comun a todos los modulos.
//
// Toolchain Java 21, plataforma de tests JUnit 5, codificacion, JaCoCo y
// empaquetado.
// =============================================================================

plugins {
    alias(libs.plugins.sonarqube)
}

// La version de Java se lee del catalogo fuera del bloque "subprojects": el acceso
// tipado a "libs" solo existe en el ambito del script raiz.
val versionJava = libs.versions.java.get().toInt()

allprojects {
    group = "com.bdo.pedidos"
    version = "1.0.0"
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "jacoco")

    // Toolchain: el proyecto se compila con Java 21 (LTS) aunque la JVM que lance
    // Gradle sea otra (debe existir un JDK 21 instalado: no se configura descarga automatica).
    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(versionJava))
        }
        withSourcesJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all,-serial,-processing", "-parameters"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // El informe de tests en consola: en CI se lee mucho mejor que un "BUILD SUCCESS".
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    // Cobertura: "check" depende del informe de JaCoCo.
    tasks.named("check") {
        dependsOn(tasks.named("jacocoTestReport"))
    }

    tasks.named<JacocoReport>("jacocoTestReport") {
        dependsOn(tasks.named("test"))
        reports {
            xml.required.set(true)
            // Ruta convencional del informe: la consume Sonar (sonar.coverage.jacoco.xmlReportPaths).
            xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/test/jacoco.xml"))
            html.required.set(true)
        }
    }
}

// -----------------------------------------------------------------------------
// SonarQube: el plugin se aplica en la raiz para que analice todos los modulos.
// El plugin de Gradle NO lee sonar-project.properties (eso solo lo hace el scanner
// de linea de comandos): la configuracion vive aqui. El token y la URL del servidor
// los inyecta Jenkins (withSonarQubeEnv), nunca el repositorio.
// -----------------------------------------------------------------------------
sonar {
    properties {
        property("sonar.projectKey", "bdo-pedidos")
        property("sonar.projectName", "BDO - Servicio de pedidos")
        property("sonar.sourceEncoding", "UTF-8")
        // Informe de cobertura de cada modulo (tarea jacocoTestReport).
        property("sonar.coverage.jacoco.xmlReportPaths", "build/reports/jacoco/test/jacoco.xml")
        // Los DTO, las clases de arranque y la configuracion no tienen logica de negocio.
        property("sonar.coverage.exclusions", "**/dto/**,**/*Application.java,**/config/**")
        property("sonar.exclusions", "**/static/**")
        property("sonar.cpd.exclusions", "**/dto/**")
    }
}
