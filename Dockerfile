FROM eclipse-temurin:21-jre

LABEL maintainer="chenximeng"

ENV TZ=Asia/Shanghai
ENV JAVA_OPTS=""

WORKDIR /app

RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime \
    && echo "$TZ" > /etc/timezone \
    && useradd -r -u 10001 appuser

COPY simon-ledger-api.jar /app/app.jar

EXPOSE 18080

USER appuser

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar --spring.profiles.active=prod"]
