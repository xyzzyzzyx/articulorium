# build base image
FROM openjdk:14.0.1-slim

EXPOSE 8000

# set deployment directory
WORKDIR /articulorum/index

# copy over the built artifact
COPY ./target/index*.jar ./articulorum-index.jar

COPY ./wait-for-it.sh ./wait-for-it.sh
RUN chmod +x wait-for-it.sh

# set the startup command to run your binary
CMD ["./wait-for-it.sh", "http://consul-server-bootstrap:8500", "--", "./wait-for-it.sh", "http://transform:9000", "--", "./wait-for-it.sh", "http://solr1:8983/solr/#/", "--", "java", "-jar", "./articulorum-index.jar"]