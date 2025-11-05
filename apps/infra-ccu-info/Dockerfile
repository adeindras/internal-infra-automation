############################
## Builder 
############################
FROM rust:latest AS builder

RUN rustup target add x86_64-unknown-linux-musl
RUN apt update && apt install -y musl-tools musl-dev
RUN update-ca-certificates

ENV USER=infra-ccu-info
ENV UID=10001

RUN adduser \
    --disabled-password \
    --gecos "" \
    --home "/nonexistent" \
    --shell "/sbin/nologin" \
    --no-create-home \
    --uid "${UID}" \
    "${USER}"


WORKDIR /infra-ccu-info

COPY ./ .

RUN cargo build --target x86_64-unknown-linux-musl --release

############################
## Final image
############################
FROM scratch

COPY --from=builder /etc/passwd /etc/passwd
COPY --from=builder /etc/group /etc/group

WORKDIR /infra-ccu-info
COPY --from=builder /infra-ccu-info/target/x86_64-unknown-linux-musl/release/infra-ccu-info ./

USER infra-ccu-info:infra-ccu-info
EXPOSE 8888
CMD ["/infra-ccu-info/infra-ccu-info"]