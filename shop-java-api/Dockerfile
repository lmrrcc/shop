FROM eclipse-temurin:25-jdk-alpine AS builder
WORKDIR /build
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY shop-java-api shop-java-api
RUN printf '%s\n' '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion><parent><groupId>com.cherrytest.shop</groupId><artifactId>shop-workspace</artifactId><version>0.1.0</version><relativePath>pom.xml</relativePath></parent><artifactId>shop-docker</artifactId><packaging>pom</packaging><modules><module>shop-java-api</module></modules></project>' > build.xml \
 && ./mvnw -B -ntp -q -f build.xml -Dmaven.test.skip=true package

FROM eclipse-temurin:25-jre-alpine
RUN apk add --no-cache wget
COPY --from=builder /build/shop-java-api/target/shop-java-api.jar /opt/shop-java-api.jar
ENV SHOP_API_ADDR=0.0.0.0:3000
EXPOSE 3000
ENTRYPOINT ["java", "-jar", "/opt/shop-java-api.jar"]
