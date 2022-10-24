# builder phase
FROM gcr.io/atomist-container-skills/clojure-base:jdk17@sha256:1fbb9c506a075c65bfb4b6977713b76549bee150884b8404acef51039191c194 AS builder

WORKDIR /usr/src/app

COPY package*.json /usr/src/app/

RUN npm ci 

COPY deps.edn shadow-cljs.edn /usr/src/app/
COPY src /usr/src/app/src
RUN npx shadow-cljs release release -A:build

# Skill runtime
FROM alpine:3.15.1@sha256:d6d0a0eb4d40ef96f2310ead734848b9c819bb97c9d846385c4aca1767186cd4

RUN apk update && apk add --update --no-cache nodejs-current=17.9.0-r0 npm=8.1.3-r0 git=2.34.5-r0
WORKDIR /usr/src/app
LABEL com.docker.skill.api.version="container/v2"

COPY package*.json /usr/src/app/

RUN npm ci --production
COPY --from=builder /usr/src/app/index.js /usr/src/app/

ENTRYPOINT [ "node" ]
CMD [ "index.js" ]
