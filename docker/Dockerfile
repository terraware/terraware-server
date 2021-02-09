FROM openjdk:15-buster
WORKDIR /home/app
COPY entrypoint.sh /
COPY BOOT-INF/lib lib
COPY META-INF META-INF
COPY BOOT-INF/classes .
EXPOSE 8080
ENTRYPOINT ["/entrypoint.sh"]
CMD ["java", "-cp", ".:lib/*", "com.terraformation.seedbank.ApplicationKt"]
