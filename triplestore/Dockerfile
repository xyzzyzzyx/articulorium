# build base image
FROM openjdk:14.0.1-slim

EXPOSE 9000

# set deployment directory
WORKDIR /articulorum/triplestore

# copy over the built artifact
COPY ./target/triplestore*.jar ./articulorum-triplestore.jar

COPY ./wait-for-it.sh ./wait-for-it.sh
RUN chmod +x wait-for-it.sh

# set the startup command to run your binary
CMD ["./wait-for-it.sh", "http://consul-server-bootstrap:8500", "--", "./wait-for-it.sh", "http://platform:9000", "--", "./wait-for-it.sh", "http://blazegraph:8080/bigdata/#splash", "--", "java", "-jar", "./articulorum-triplestore.jar"]