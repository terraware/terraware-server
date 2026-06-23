#!/bin/bash

# Default to a 5GB maximum heap size.
JAVA_OPTS=${JAVA_OPTS:--Xmx5g}

exec java \
    -Djava.locale.providers=SPI,CLDR \
    --sun-misc-unsafe-memory-access=allow \
    --enable-native-access=ALL-UNNAMED \
    ${JAVA_OPTS} \
    "org.springframework.boot.loader.launch.JarLauncher"
