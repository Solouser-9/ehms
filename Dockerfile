FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY deploy/classes/ classes/
COPY deploy/lib/ lib/
EXPOSE 8000
CMD ["sh", "-c", "java -Xmx300m -cp 'classes:lib/*' ehms.boot.EhmsApplication --server.port= --server.address=0.0.0.0"]
