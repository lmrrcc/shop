# Shop

Checkout sample for [Cherry Test](https://github.com/lmrrcc/cherry-test): a Rust API, a TypeScript cart library, and a React storefront. Postgres starts with the `shop_checkout` scope.

```bash
cp .env.example .env
pnpm install
cherry-test --no-open
```

When this repository is checked out at `app/shop` inside Cherry Test:

```bash
git submodule update --init app/shop
pnpm --dir app/shop install
cherry-test --root app/shop --no-open
```
