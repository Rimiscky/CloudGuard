# Etape 1 : compiler avec Maven et le JDK complet
FROM maven:3.10.0-eclipse-temurin-25-noble AS construction
WORKDIR /src
COPY pom.xml .
# Dependances telechargees dans une couche a part, gardee en cache
# tant que pom.xml ne change pas
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# Etape 2 : l'image finale ne contient que le JRE et l'application
FROM eclipse-temurin:25.0.4.1_1-jre-alpine-3.24
WORKDIR /app
RUN adduser -D appli
COPY --from=construction /src/target/reservations-1.0.0.jar app.jar
USER appli
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
