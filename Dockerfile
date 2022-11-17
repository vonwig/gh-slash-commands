# builder phase
FROM

WORKDIR /usr/src/app

COPY package*.json /usr/src/app/

RUN npm ci 

COPY deps.edn shadow-cljs.edn /usr/src/app/
COPY src /usr/src/app/src
RUN npx shadow-cljs release release -A:build

# Skill runtime
FROM alpine:3.15.1@sha256:525ce2f9b0d7f40dd7aaa177b44cf8ab2e0bdebb906a9f66aa36c32f7d934f67

RUN apk update && apk add --update --no-cache nodejs-current=17.9.0-r0 npm=8.1.3-r0 git=2.34.5-r0
WORKDIR /usr/src/app
LABEL com.docker.skill.api.version="container/v2"

COPY package*.json /usr/src/app/

RUN npm ci --production
COPY --from=builder /usr/src/app/index.js /usr/src/app/

ENTRYPOINT [ "node" ]
CMD [ "index.js" ]
