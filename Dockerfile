FROM maven:3-jdk-8

COPY . /usr/src/slack-plugin

WORKDIR /usr/src/slack-plugin

RUN mvn test

ENTRYPOINT [ "bash" ]

# Build with:
# docker build -t slack-plugin .

# Run with:
# docker run -it --rm -v "$(pwd)":/usr/src/slack-plugin slack-plugin
