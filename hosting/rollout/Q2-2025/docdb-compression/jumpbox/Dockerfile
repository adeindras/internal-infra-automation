FROM ubuntu:noble

USER root

RUN apt-get update && apt-get install -y wget gnupg && apt clean
RUN wget -qO- https://www.mongodb.org/static/pgp/server-8.0.asc | tee /etc/apt/trusted.gpg.d/server-8.0.asc
RUN echo "deb [ arch=amd64,arm64 ] https://repo.mongodb.org/apt/ubuntu noble/mongodb-org/8.0 multiverse" | tee /etc/apt/sources.list.d/mongodb-org-8.0.list
RUN apt-get update
RUN apt-get install -y mongodb-mongosh && apt clean

USER ubuntu
