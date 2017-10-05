FROM openjdk:8-jdk-alpine
VOLUME /tmp
ADD run.sh /run.sh
ADD @project.build.finalName@.jar  /@project.build.finalName@.jar
ENV JAVA_OPTS=""
RUN chmod +x /run.sh
RUN chmod +x /@project.build.finalName@.jar
ENTRYPOINT [ "sh", "-c", "/run.sh" ]
EXPOSE 8080
