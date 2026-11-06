// =============================================================================
// Pipeline de integracion continua (Jenkins, pipeline declarativo).
//
// Etapas: checkout -> build -> esquema PostgreSQL -> tests -> cobertura -> Sonar
//         (con quality gate) -> empaquetado -> imagenes Docker -> E2E
//
// Requisitos del agente: JDK 17 o posterior (se compila con --release 17), Node.js 18+
// (tests de JavaScript), Docker, Python 3, binarios de PostgreSQL (initdb/psql) y el servidor SonarQube configurado
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
                sh './gradlew --console=plain test :pedidos-api:testJavaScript'
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
                script {
                    // El plugin "sonar" ya no trae el paso withSonarQubeEnv, asi que se
                    // inyectan a mano las variables que espera el plugin de Gradle
                    // (SONAR_HOST_URL y SONAR_TOKEN). El token sale de la credencial
                    // de Jenkins, nunca del repositorio.
                    def urlSonar = env.SONAR_HOST_URL ?: 'http://sonarqube:9000'
                    withCredentials([string(credentialsId: 'sonarqube-token', variable: 'SONAR_TOKEN')]) {
                        withEnv(["SONAR_HOST_URL=${urlSonar}"]) {
                            sh './gradlew --console=plain sonar'
                        }
                    }
                }
            }
        }

        stage('Quality gate') {
            steps {
                // Bloquea la build si el analisis no supera el quality gate. El id de la
                // tarea de SonarQube lo deja el scanner en .scannerwork/report-task.txt.
                timeout(time: 10, unit: 'MINUTES') {
                    script {
                        def tarea = readFile('.scannerwork/report-task.txt').trim()
                                .replaceAll('^ceTaskId=', '')
                        echo "Tarea de SonarQube: ${tarea}"
                        waitForQualityGate abortPipeline: true,
                                serverUrl: (env.SONAR_HOST_URL ?: 'http://sonarqube:9000'),
                                credentialsId: 'sonarqube-token',
                                taskId: tarea
                    }
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
