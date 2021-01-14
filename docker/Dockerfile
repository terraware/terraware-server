FROM openjdk:15
WORKDIR /home/app
COPY BOOT-INF/lib lib
COPY META-INF META-INF
COPY BOOT-INF/classes .
EXPOSE 8080
CMD ["java", "-cp", ".:lib/*", "com.terraformation.seedbank.ApplicationKt"]
