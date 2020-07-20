FROM gmolaire/sbt:1.3.13_14.0.2-jdk as builder

# Bring the source code
WORKDIR /usr/app/lila-ws
COPY . .

# Build and package as a tar
RUN sbt test universal:packageZipTarball

FROM openjdk:14.0.2-slim as runner

WORKDIR /usr/app
ARG APP_DIR=/usr/app/lila-ws

COPY --from=builder ${APP_DIR}/target/universal/*.tgz .

RUN tar -xvf *.tgz &&\
    rm -f *.tgz && \
    mv lila-ws* lila-ws

ENV PATH $PATH:${APP_DIR}/lib:${APP_DIR}/bin

EXPOSE 9664

# Run
CMD lila-ws
