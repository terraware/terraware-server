FROM eclipse-temurin:17-jre

ARG USER_ID=1001

RUN useradd -u $USER_ID -c "Terraware server" -d /home/app -m terraware

WORKDIR /home/app

# Copy libraries as an explicit step so they end up in a separate layer from the
# application code. This way, pulling a new version of the application when the
# dependencies haven't changed will be able to reuse the existing image layer from
# the previous version.
COPY --chown=$USER_ID META-INF META-INF
COPY --chown=$USER_ID BOOT-INF/lib lib

# Copy the classes directory. This creates a layer with individual classfiles
# rather than a single jarfile with the entire application.
COPY --chown=$USER_ID BOOT-INF/classes .

EXPOSE 8080

USER $USER_ID
CMD ["java", "-cp", ".:lib/*", "com.terraformation.backend.ApplicationKt"]
