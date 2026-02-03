FROM eclipse-temurin:17-jdk-alpine

WORKDIR /app

# Download Postgres Driver
RUN wget https://jdbc.postgresql.org/download/postgresql-42.7.2.jar

# Copy files
COPY . .

# Compile
RUN javac SocialServer.java

# Run
CMD ["java", "-cp", ".:postgresql-42.7.2.jar", "SocialServer"]
