# ==========================================================================
# BlueDebug Management — imagen de producción.
#
# Sale UN SOLO contenedor que sirve las dos mitades: el Angular ya compilado
# como ficheros estáticos y la API en el mismo puerto. Esa es la razón de que no
# haya CORS ni configuración de dominios en producción —el navegador ve un único
# origen— y de que la cookie de sesión funcione sin pelearse con SameSite.
#
# Tres etapas, y ninguna sobra:
#   1. Node compila el front.
#   2. Maven compila el backend con el front ya dentro de sus recursos.
#   3. Una imagen con solo el JRE se queda con el jar. Ni Maven, ni Node, ni el
#      código fuente, ni el ~/.m2 llegan a producción: la imagen final baja de
#      unos 900 MB a unos 250.
# ==========================================================================

# --- 1. el front ----------------------------------------------------------
FROM node:20-alpine AS web

WORKDIR /construccion

# Primero solo los manifiestos: mientras no cambien las dependencias, Docker
# reutiliza la capa de npm ci y se ahorra la descarga entera en cada despliegue.
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY web/ ./
RUN npm run build

# --- 2. el backend --------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS api

WORKDIR /construccion

COPY api/pom.xml ./
# Igual que arriba: las dependencias de Maven en su propia capa.
RUN mvn -B -q dependency:go-offline

COPY api/src ./src

# El front compilado entra como recurso estático del jar. Spring sirve
# /index.html y los ficheros desde ahí, y ReenvioSpa se encarga de que las rutas
# del panel (que no son carpetas de verdad) también devuelvan el index.
COPY --from=web /construccion/dist/ ./src/main/resources/static/

RUN mvn -B -q -DskipTests package

# --- 3. lo que se ejecuta -------------------------------------------------
FROM eclipse-temurin:17-jre-alpine

# Nada de correr como root: si algún día se cuela una ejecución de código, que
# sea con un usuario que no puede tocar el sistema de ficheros de la imagen.
RUN addgroup -S panel && adduser -S panel -G panel
USER panel

WORKDIR /app
COPY --from=api /construccion/target/bluedebug-management.jar app.jar

# Railway inyecta PORT; en local, 8080.
ENV PORT=8080
EXPOSE 8080

# MaxRAMPercentage en vez de -Xmx fijo: el contenedor puede tener 512 MB o 2 GB
# según el plan, y con un tope fijo o se desperdicia memoria o la JVM muere al
# subir de plan. Así se ajusta sola a lo que haya.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Djava.security.egd=file:/dev/./urandom"

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
