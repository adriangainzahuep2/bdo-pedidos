// =============================================================================
// Pipeline de integracion continua (Jenkins, pipeline declarativo).
//
// Etapas: checkout -> build -> esquema PostgreSQL -> tests -> cobertura -> Sonar
//         (con quality gate) -> empaquetado -> imagenes Docker -> E2E
//
// Requisitos del agente: JDK 17 o posterior (se compila con --release 17), Docker,
// Python 3, binarios de PostgreSQL (initdb/psql) y el servidor SonarQube configurado
// en Jenkins con el nombre "SonarQube".
// =============================================================================

pipeline {
    agent any

    options {
        timestamps()
        buildDiscarder(logRotator(numToKeepStr: '20'))
        timeout(time: 30, unit: 'MINUTES')
        disableConcurrentBuilds()
    }

    environment {
        GRADLE_OPTS = '-Dorg.gradle.daemon=false -Dorg.gradle.caching=true'
        // Version del artefacto (la del build Gradle): da nombre al JAR que copia el Dockerfile.
        VERSION_APP = '1.0.0'
        // Etiqueta de las imagenes: una por build de Jenkins, trazable.
        ETIQUETA    = "${env.VERSION_APP}-${env.BUILD_NUMBER}"
        // El token del proveedor lo inyecta el Jenkins (credencial), no el repositorio.
        PROVEEDOR_LOGISTICO_TOKEN = credentials('bdo-proveedor-token')
    }

    stages {

        stage('Checkout') {
            steps {
                checkout scm
                sh 'git log --oneline -10'
            }
        }

        stage('Build') {
            steps {
                sh './gradlew --console=plain clean build -x test'
            }
        }

        stage('Esquema PostgreSQL') {
            steps {
                // Esquema + 10 filas por tabla + verificacion sobre un PostgreSQL desechable.
                sh './scripts/probar-postgresql.sh'
            }
        }

        stage('Tests unitarios e integracion') {
            steps {
                sh './gradlew --console=plain test'
                junit testResults: '**/build/test-results/test/*.xml', allowEmptyResults: false
            }
        }

        stage('Cobertura (JaCoCo)') {
            steps {
                sh './gradlew --console=plain jacocoTestReport'
                // Publica el informe como artefacto de la build.
                archiveArtifacts artifacts: '**/build/reports/jacoco/**', allowEmptyArchive: true
            }
        }

        stage('Analisis de calidad (SonarQube)') {
            steps {
                withSonarQubeEnv('SonarQube') {
                    sh './gradlew --console=plain sonar'
                }
            }
        }

        stage('Quality gate') {
            steps {
                // Detiene el pipeline si el analisis no supera el quality gate. Requiere el
                // webhook de SonarQube hacia Jenkins (<jenkins>/sonarqube-webhook/).
                timeout(time: 10, unit: 'MINUTES') {
                    waitForQualityGate abortPipeline: true
                }
            }
        }

        stage('Empaquetado') {
            steps {
                sh './gradlew --console=plain build -x test'
                archiveArtifacts artifacts: 'pedidos-*/build/libs/*.jar', fingerprint: true
            }
        }

        stage('Imagenes Docker') {
            steps {
                script {
                    def modulos = ['pedidos-api', 'pedidos-cliente', 'pedidos-auth',
                                   'pedidos-gateway', 'pedidos-registry', 'pedidos-config']
                    modulos.each { modulo ->
                        sh "docker build --build-arg MODULO=${modulo} --build-arg VERSION=${env.VERSION_APP} -t bdo/${modulo}:${env.ETIQUETA} ."
                    }
                }
            }
        }

        stage('Verificacion de extremo a extremo') {
            steps {
                // El script levanta el stack (registry, auth, gateway, api, cliente y
                // proveedor simulado) y comprueba los escenarios del enunciado.
                sh './scripts/verificar-e2e.sh'
                archiveArtifacts artifacts: '**/build/e2e/**', allowEmptyArchive: true
            }
        }
    }

    post {
        success {
            echo "Build correcta: imagenes etiquetadas como ${env.ETIQUETA}"
        }
        failure {
            echo "Build fallida. Revisa el informe de tests y los artefactos de la verificacion E2E."
        }
        always {
            cleanWs()
        }
    }
}
