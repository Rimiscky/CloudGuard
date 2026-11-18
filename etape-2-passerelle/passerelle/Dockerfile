# Etape 1 : compiler avec Maven et le JDK complet
FROM maven:3.10.0-eclipse-temurin-25-noble AS construction
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# Etape 2 : l'image finale ne contient que le JRE et l'application
FROM eclipse-temurin:25.0.4.1_1-jre-alpine-3.24
WORKDIR /app
RUN adduser -D appli
COPY --from=construction /src/target/passerelle-1.0.0.jar app.jar
USER appli
EXPOSE 8080
# --enable-native-access : Netty charge une bibliotheque native, Java 25
#   l'accepte sans avertissement si on l'autorise explicitement
# accessLogEnabled : une ligne de journal par requete (methode, chemin, code)
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", \
            "-Dreactor.netty.http.server.accessLogEnabled=true", \
            "-jar", "app.jar"]
