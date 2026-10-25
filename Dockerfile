FROM eclipse-temurin:21-jre

LABEL maintainer="chenximeng"

ENV TZ=Asia/Shanghai
ENV JAVA_OPTS=""

WORKDIR /app

RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime \
    && echo "$TZ" > /etc/timezone \
    && useradd -r -u 10001 appuser \
    && mkdir -p /logs \
    && chown appuser:appuser /logs \
    && ln -s /logs /app/logs

ARG APP_JAR=target/app.jar

COPY ${APP_JAR} /app/app.jar

EXPOSE 18080

USER appuser

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar --spring.profiles.active=prod"]
