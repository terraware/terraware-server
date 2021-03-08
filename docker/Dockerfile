FROM openjdk:15-buster
WORKDIR /home/app
COPY entrypoint.sh /

# Copy libraries as an explicit step so they end up in a separate layer from the
# application code. This way, pulling a new version of the application when the
# dependencies haven't changed will be able to reuse the existing image layer from
# the previous version.
COPY BOOT-INF/lib lib
COPY META-INF META-INF

# Copy the classes directory. This creates a layer with individual classfiles
# rather than a single jarfile with the entire application.
COPY BOOT-INF/classes .

EXPOSE 8080

ENTRYPOINT ["/entrypoint.sh"]
CMD ["java", "-cp", ".:lib/*", "com.terraformation.seedbank.ApplicationKt"]
