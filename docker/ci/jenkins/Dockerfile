# =============================================================================
# Agente de Jenkins para el pipeline de bdo-pedidos.
#
# La imagen oficial (jenkins/jenkins:lts-jdk17) trae JDK 17, pero el pipeline
# necesita ademas:
#   * node 18+    : los tests de JavaScript de la consola (node --test)
#   * python3     : el proveedor logístico simulado y los scripts
#   * postgresql  : initdb/pg_ctl/psql para scripts/probar-postgresql.sh
#   * git, curl   : checkout del repositorio y descarga de artefactos
#
# El socket de Docker se monta en tiempo de ejecucion (docker compose ci.yml),
# de modo que la etapa "Imagenes Docker" construye en el daemon del host.
# =============================================================================
FROM jenkins/jenkins:lts-jdk17

USER root

# Node 20 desde NodeSource: la de los repositorios de Ubuntu es demasiado
# antigua para "node --test" (disponible desde Node 18).
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates curl gnupg git unzip zip make \
        python3 python3-pip \
        postgresql postgresql-client \
    && curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/*

# Plugins del pipeline. Los que exigen la etapa:
#   workflow-aggregator : pipeline declarativo (Jenkinsfile)
#   git                 : checkout del repositorio
#   sonarqube           : withSonarQubeEnv, waitForQualityGate y /sonar-webhook/
#   credentials, plain-credentials, credentials-binding : la credencial del token
#   junit               : publicacion de resultados de test
#   timestamper         : la opcion timestamps() del pipeline
#   build-timeout       : la opcion timeout() del pipeline
#   ws-cleanup          : el paso cleanWs() del pipeline
COPY plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt

# Cliente de Docker (solo el CLI, el demonio es el del host): la etapa
# "Imagenes Docker" del pipeline construye las imagenes con el socket montado.
# Se usa el binario estatico oficial: no añade repositorios de APT ni el
# demonio, que no hacen falta aqui.
ARG DOCKER_VERSION=27.3.1
RUN curl -fsSL "https://download.docker.com/linux/static/stable/x86_64/docker-${DOCKER_VERSION}.tgz" \
      -o /tmp/docker.tgz \
    && tar -xzf /tmp/docker.tgz -C /tmp \
    && mv /tmp/docker/docker /usr/local/bin/docker \
    && rm -rf /tmp/docker /tmp/docker.tgz \
    && docker --version

# Script de arranque idempotente: crea la credencial, el servidor de SonarQube
# y el job del pipeline. Solo hace algo la primera vez (el home es un volumen).
COPY init.groovy.d/ /usr/share/jenkins/ref/init.groovy.d/

USER jenkins
