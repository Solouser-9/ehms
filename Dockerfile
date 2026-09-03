FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY deploy/ehms-boot.jar .
EXPOSE 8000
CMD ["sh", "-c", "java -Xmx300m -jar ehms-boot.jar --server.port= --server.address=0.0.0.0"]
