FROM docker.io/library/golang:1.18-bullseye as signaldctl

WORKDIR /src
RUN git clone https://gitlab.com/signald/signald-go.git . \
    && git checkout e8131dc92864034910703f1125f4011a5f3e6512 \
    && make signaldctl

FROM docker.io/library/gradle:7-jdk${JAVA_VERSION:-17} AS build

COPY . /tmp/src
WORKDIR /tmp/src

ARG CI_BUILD_REF_NAME
ARG CI_COMMIT_SHA

RUN VERSION=$(./version.sh) gradle -Dorg.gradle.daemon=false build

FROM openjdk:17 AS release
RUN useradd -mu 1337 signald && mkdir /signald && chown -R signald:signald /signald
COPY --from=build /opt/signald /opt/signald/
COPY --from=signaldctl /src/signaldctl /bin/signaldctl
RUN ln -sf /opt/signald/bin/signald /usr/local/bin/
ADD docker-entrypoint.sh /bin/entrypoint.sh
USER signald
RUN ["signaldctl", "config", "set", "socketpath", "/signald/signald.sock"]

VOLUME /signald

ENTRYPOINT ["/bin/entrypoint.sh"]
CMD ["-d", "/signald", "-s", "/signald/signald.sock"]
