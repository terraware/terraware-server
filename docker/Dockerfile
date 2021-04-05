FROM openjdk:16-slim-buster
WORKDIR /home/app

RUN apt-get update \
    && apt-get upgrade -y \
    && apt-get install -y curl gosu \
    && apt-get clean \
    && rm -rf /var/cache/apt
RUN useradd -u 1001 -c "Seed bank server" -d /home/app sbserver \
    && chown -R sbserver /home/app

COPY entrypoint.sh metrics.sh /usr/local/bin/
RUN cd /usr/local/bin && chmod 755 entrypoint.sh metrics.sh

# Copy libraries as an explicit step so they end up in a separate layer from the
# application code. This way, pulling a new version of the application when the
# dependencies haven't changed will be able to reuse the existing image layer from
# the previous version.
COPY --chown=1001 BOOT-INF/lib lib
COPY --chown=1001 META-INF META-INF

# Copy the classes directory. This creates a layer with individual classfiles
# rather than a single jarfile with the entire application.
COPY --chown=1001 BOOT-INF/classes .

EXPOSE 8080

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
CMD ["java", "-cp", ".:lib/*", "com.terraformation.seedbank.ApplicationKt"]
