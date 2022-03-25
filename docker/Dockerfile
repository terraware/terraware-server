FROM openjdk:19-slim-buster

# Run the server with a unique user ID so we can enforce disk quotas.
ARG USER_ID=1001

RUN useradd -u $USER_ID -c "Seed bank server" -d /home/app -m sbserver \
    && mkdir /file-storage-volume \
    && chown $USER_ID /file-storage-volume

COPY entrypoint.sh /usr/local/bin/
RUN cd /usr/local/bin && chmod 755 entrypoint.sh

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
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
CMD ["java", "-cp", ".:lib/*", "com.terraformation.backend.ApplicationKt"]
