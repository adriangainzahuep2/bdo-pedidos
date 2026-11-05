# =============================================================================
# Imagen multi-etapa para los microservicios.
#
# Uso:
#   docker build --build-arg MODULO=pedidos-api -t bdo/pedidos-api .
#   docker build --build-arg MODULO=pedidos-api -t bdo/pedidos-api --build-arg VERSION=1.0.0 .
#
# El modulo se construye junto con sus dependencias del reactor Gradle
# (pedidos-dominio es dependencia de pedidos-api).
# =============================================================================

# ---- Etapa 1: compilacion --------------------------------------------------
FROM eclipse-temurin:21-jdk-jammy AS build

ARG MODULO=pedidos-api
ARG VERSION=1.0.0

WORKDIR /build

# El wrapper de Gradle va primero: su descarga se cachea en una capa propia y
# no se invalida cada vez que cambia el codigo.
COPY gradlew gradlew
COPY gradle/wrapper/ gradle/wrapper/
RUN chmod +x gradlew && ./gradlew --version

# Solo los ficheros de construccion: la capa de resolucion de dependencias se
# cachea entre compilaciones.
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/libs.versions.toml gradle/
COPY pedidos-dominio/build.gradle.kts pedidos-dominio/
COPY pedidos-testkit/build.gradle.kts pedidos-testkit/
COPY pedidos-api/build.gradle.kts pedidos-api/
COPY pedidos-cliente/build.gradle.kts pedidos-cliente/
COPY pedidos-auth/build.gradle.kts pedidos-auth/
COPY pedidos-gateway/build.gradle.kts pedidos-gateway/
COPY pedidos-registry/build.gradle.kts pedidos-registry/
COPY pedidos-config/build.gradle.kts pedidos-config/

RUN ./gradlew --console=plain dependencies --configuration runtimeClasspath || true

COPY . .
RUN ./gradlew --console=plain :${MODULO}:bootJar -x test

# ---- Etapa 2: ejecucion ----------------------------------------------------
FROM eclipse-temurin:21-jre-jammy AS runtime

ARG MODULO=pedidos-api
ARG VERSION=1.0.0

# Usuario sin privilegios: el contenedor no corre como root.
RUN groupadd --system bdo && useradd --system --gid bdo --create-home bdo

WORKDIR /app
COPY --from=build /build/${MODULO}/build/libs/${MODULO}-${VERSION}.jar /app/app.jar

USER bdo
EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseZGC -Djava.security.egd=file:/dev/./urandom"

# Healthcheck sin herramientas extra en la imagen (ni curl ni wget): peticion HTTP con
# el /dev/tcp de bash y comprobacion de que el estado es UP.
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=5 \
    CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/${SERVER_PORT:-8080} && printf 'GET /actuator/health HTTP/1.0\\r\\nHost: localhost\\r\\n\\r\\n' >&3 && grep -q '\"status\":\"UP\"' <&3"]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
