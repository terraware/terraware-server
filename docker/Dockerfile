FROM amazoncorretto:21.0.2

ARG USER_ID=1001

RUN mkdir /app && chown $USER_ID /app
WORKDIR /app

# Copy libraries as an explicit step so they end up in a separate layer from the
# application code. This way, pulling a new version of the application when the
# dependencies haven't changed will be able to reuse the existing image layer from
# the previous version.
COPY --chown=$USER_ID dependencies/ ./
COPY --chown=$USER_ID spring-boot-loader/ ./
COPY --chown=$USER_ID snapshot-dependencies/ ./
COPY --chown=$USER_ID application/ ./

EXPOSE 8080

USER $USER_ID
CMD ["java", "-Djava.locale.providers=SPI,CLDR,COMPAT", "org.springframework.boot.loader.launch.JarLauncher"]
