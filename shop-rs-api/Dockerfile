FROM rust:1.88-bookworm AS builder
WORKDIR /build
RUN printf '%s\n' \
  '[workspace]' \
  'resolver = "2"' \
  'members = ["shop-rs-api"]' \
  '' \
  '[workspace.package]' \
  'version = "0.1.0"' \
  'edition = "2021"' \
  'license = "MIT"' \
  > Cargo.toml
COPY shop-rs-api ./shop-rs-api
RUN sed -i '/^\[dev-dependencies\]/,$d' shop-rs-api/Cargo.toml
RUN cargo build --release -p shop-rs-api

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates wget && rm -rf /var/lib/apt/lists/*
COPY --from=builder /build/target/release/shop-rs-api /usr/local/bin/shop-rs-api
ENV SHOP_API_ADDR=0.0.0.0:3000
EXPOSE 3000
CMD ["shop-rs-api"]
