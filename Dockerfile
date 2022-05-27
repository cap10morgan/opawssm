ARG CLJ_VERSION
ARG GRAAL_VERSION
FROM clojure:tools-deps-$CLJ_VERSION AS clojure

FROM ghcr.io/graalvm/native-image:ol8-java17-$GRAAL_VERSION AS graalvm

RUN microdnf install git && microdnf clean all

COPY --from=clojure /usr/local/bin/clojure /usr/local/bin/clojure
COPY --from=clojure /usr/local/lib/clojure /usr/local/lib/clojure

RUN mkdir /opawssm
WORKDIR /opawssm

COPY deps.edn .
RUN clojure -P && clojure -A:build -P

COPY . .

ENTRYPOINT [""]

CMD ["clojure", "-T:build", "native-image"]
