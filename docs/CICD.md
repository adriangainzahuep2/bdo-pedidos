# CI/CD: Jenkins, SonarQube y Gitea

El pipeline, cómo se ejecuta en local con las tres piezas en Docker y cómo se integran. La salida
real de las ejecuciones está en [EVIDENCIA.md](../documentacion/EVIDENCIA.md) §4 bis.

## El pipeline

`Jenkinsfile` (pipeline declarativo) con estas etapas:

| Etapa | Qué hace |
|---|---|
| Checkout | Recupera el commit y lo registra en el log |
| Build | `./gradlew clean build -x test` |
| Esquema PostgreSQL | `./scripts/probar-postgresql.sh` (PostgreSQL real, desechable) |
| Tests | `./gradlew test :pedidos-api:testJavaScript` (Java + `node --test` de la consola) |
| Cobertura | Informe JaCoCo (el que consume Sonar) |
| SonarQube | `./gradlew sonar` + *quality gate* (bloquea si la cobertura o las reglas no pasan) |
| Empaquetado | JARs de los ocho módulos |
| Imágenes Docker | Una por servicio, etiquetada con el número de build |
| Extremo a extremo | `./scripts/verificar-e2e.sh` sobre el stack completo |

* **Sonar**: configurado en `build.gradle.kts` (el plugin de Gradle **no** lee
  `sonar-project.properties`, solo el *scanner* de línea de comandos), con la cobertura de JaCoCo.
  El análisis es de **un solo proyecto** de Sonar: el plugin aplicado en la raíz ya agrega los
  módulos, así que **no** se declara `sonar.sources` (cada fichero se indexaría dos veces); lo que
  se indica es dónde está la cobertura de cada módulo
  (`sonar.coverage.jacoco.xmlReportPaths=**/build/reports/jacoco/test/jacoco.xml`).
* **Secretos**: el token del proveedor llega de una credencial de Jenkins
  (`bdo-proveedor-token`), nunca del repositorio. El de SonarQube, de otra
  (`sonarqube-token`), y ambos se inyectan como variables de entorno.
* **Gitea**: el *Config Server* ya admite el repositorio de configuración en Gitea
  (`CONFIG_PROFILE=git`); el resto del pipeline es agnóstico al servidor Git, así que el mismo
  `Jenkinsfile` vale para GitHub o Gitea cambiando el origen del *checkout*.

## El stack de CI/CD en local (Jenkins + SonarQube + Gitea)

Hay un `compose` aparte, `docker-compose.ci.yml`, que levanta las tres piezas para poder ejecutar
el pipeline **de verdad** sin depender de una infraestructura externa:

```bash
./scripts/verificar-ci.sh      # levanta el stack, lo configura y lanza el pipeline
```

| Servicio | URL | Qué hace |
|---|---|---|
| Jenkins | <http://localhost:8091/> | Ejecuta el `Jenkinsfile` del repositorio (job `bdo-pedidos`) |
| SonarQube | <http://localhost:9001/> | Análisis y *quality gate* del proyecto `bdo-pedidos` |
| Gitea | <http://localhost:3000/> | Repositorio `bdo/config-repo` con la configuración que sirve el Config Server |

El script hace todo el Bootstrap:

1. Levanta SonarQube y Gitea y espera a que estén sanos.
2. Crea en Gitea el usuario `bdo` y el repositorio `config-repo`, y sube el contenido de
   `config-repo/` (la configuración compartida y la de cada servicio).
3. Crea en SonarQube el token de API y el **webhook** hacia Jenkins (`http://jenkins:8095/sonarqube-webhook/`).
4. Levanta Jenkins: su script de arranque (`docker/ci/jenkins/init.groovy.d/`) deja creados el
   job, las dos credenciales y el servidor de SonarQube.
5. Lanza el pipeline y sigue el log hasta el resultado.

**El agente de Jenkins es una imagen propia** (`docker/ci/jenkins/Dockerfile`) porque el pipeline
necesita, además del JDK 17 de la imagen oficial: Node 20 (los tests de JavaScript de la consola),
Python 3 (el proveedor simulado), los binarios de PostgreSQL (`initdb`/`pg_ctl`/`psql` para
`scripts/probar-postgresql.sh`) y el socket de Docker (para la etapa que construye las imágenes).

> Los puertos del stack de CI (8091, 9001, 3000) son distintos de los de la aplicación a
> propósito: el pipeline ejecuta `scripts/verificar-e2e.sh` **dentro** del contenedor de Jenkins, y
> ese script levanta los servicios de la aplicación en los puertos 8080, 9000, 8761, 8081 y 8082.
> Por eso Jenkins tampoco escucha en su 8080 habitual dentro del contenedor, sino en el 8095
> (`JENKINS_OPTS=--httpPort=8095`): con Jenkins en el 8080, el API Gateway del E2E no podía
> arrancar y la última etapa fallaba.

> **Nota de entorno**: Elasticsearch (el motor de SonarQube) bloquea sus índices en solo lectura
> cuando el disco del host supera el 95 % de uso (*flood-stage watermark*), y entonces el
> análisis falla. No es una propiedad de la JVM sino del clúster: `scripts/verificar-ci.sh` la
> desactiva por la API de Elasticsearch y quita el bloqueo de los índices al arrancar.

> La configuración del *Config Server* puede servirse desde Gitea en lugar del classpath:
> `CONFIG_PROFILE=git` y `CONFIG_GIT_URI` (con `CONFIG_GIT_USUARIO` y `CONFIG_GIT_PASSWORD`) en el
> `.env` hacen que el Config Server clone el repositorio `bdo/config-repo` de Gitea.

## Jenkins, SonarQube y Gitea: cómo se integran

| Pieza | Cómo entra en el flujo | Qué necesita |
|---|---|---|
| **Jenkins** | Lee el `Jenkinsfile` del repositorio (pipeline declarativo) y ejecuta las etapas en un agente | Agente con JDK 17+, Node.js 18+, Docker, Python 3 y binarios de PostgreSQL (`initdb`/`psql`) |
| **SonarQube** | La etapa `withSonarQubeEnv('SonarQube')` inyecta `SONAR_HOST_URL` y `SONAR_TOKEN` en el *build*; `./gradlew sonar` sube el código y la cobertura de JaCoCo | Un servidor SonarQube con el *webhook* apuntando a Jenkins (`<jenkins>/sonarqube-webhook/`), para que el *quality gate* bloquee la build |
| **Gitea** | El *Config Server* puede leer la configuración de un repositorio Gitea (`CONFIG_PROFILE=git` + `spring.cloud.config.server.git.uri`), con los secretos cifrados con JCE (`{cipher}`) | Un repositorio Gitea con el `config-repo/` y la clave `CONFIG_ENCRYPT_KEY` |

El `Jenkinsfile` no depende del servidor Git: `checkout scm` toma el origen de donde se haya
configurado el *job*, así que el mismo pipeline vale contra GitHub (como está ahora), GitLab o
Gitea. Los secretos viajan por la credencial de Jenkins `bdo-proveedor-token`, nunca por el
repositorio.

## Estado de la verificación de esta parte

El pipeline **se ha ejecutado de verdad** en Jenkins, contra SonarQube y Gitea levantados con
`docker-compose.ci.yml`: el job clona este repositorio de GitHub, pasa las diez etapas (con
`Quality gate is 'OK'`, cerrado por el *webhook*) y termina en `Finished: SUCCESS`. La salida, las
métricas de Sonar y los defectos que destapó esa ejecución están en
[`documentacion/EVIDENCIA.md` §4 bis](../documentacion/EVIDENCIA.md).

## Trabajo en equipo

El repositorio está en GitHub (`adriangainzahuep2/bdo-pedidos`) y el historial refleja el
trabajo por ramas y revisiones:

* Cada cambio va en su **rama** (`feature/...`, `docs/...`, `fix/...`), nunca directamente en
  `main`.
* Cada rama se sube y se abre un **pull request** con la descripción de qué cambia y **cómo se ha
  verificado**; ahí es donde se revisa.
* La fusión es un **merge commit** (no *squash*), para que se conserve el historial individual de
  commits y se vea qué tocó cada cambio.

El historial de *pull requests* fusionados (con su rama conservada) está en la pestaña *Pull
requests* de GitHub: cada uno describe qué cambia y cómo se ha verificado.
