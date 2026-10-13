# Shop (Java edition)

Shop Checkout sample for [Cherry Test](https://github.com/lmrrcc/cherry-test): Java 25 Spring Boot API and adapter,
TypeScript cart library and React storefront. Postgres starts in the `shop_checkout` scope.

This `java` branch is the Java twin of `main`: `shop-java-api` replaces `shop-rs-api` with the same HTTP contract,
error codes and SQL schema, and `tools/shop-adapter` is the Java port of the Rust adapter. The TypeScript packages and
the Playwright suites are unchanged.

```bash
cp .env.example .env
pnpm install
./mvnw -f ../../java/pom.xml install -DskipTests
cherry-test-java --no-open
./mvnw cherry-test:run -Dcherry-test.args=--no-open
```

`./mvnw verify` runs the unit tests (Postgres adapters on Testcontainers) and the `@CherryTest` integration tests in
`shop-java-it`, which boot the `shop_checkout` scope themselves when no Cherry Test runner is present.
