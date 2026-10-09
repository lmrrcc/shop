import { execFileSync } from "node:child_process";

const RESET = [
  "DELETE FROM customer_order_line",
  "DELETE FROM customer_order",
  "DELETE FROM cart_line",
  "DELETE FROM cart",
  "UPDATE product SET stock = CASE sku WHEN 'MUG-STONE' THEN 1000 WHEN 'TEE-CREW-M' THEN 250 WHEN 'NB-DOT-A5' THEN 500 WHEN 'LAMP-DESK' THEN 2 ELSE stock END",
  "SELECT setval(pg_get_serial_sequence('customer_order', 'id'), 1, false)",
].join("; ");

export function resetShopStock(): void {
  const root = process.env.SHOP_ROOT;
  if (!root) throw new Error("SHOP_ROOT is not set");
  execFileSync(
    "docker",
    [
      "compose",
      "-p",
      process.env.SHOP_COMPOSE_PROJECT ?? "cherry-test",
      "-f",
      "docker-compose.test.yml",
      "exec",
      "-T",
      "shop-db",
      "psql",
      "-U",
      process.env.SHOP_DB_USER ?? "shop",
      "-d",
      process.env.SHOP_DB_NAME ?? "shop",
      "-v",
      "ON_ERROR_STOP=1",
      "-c",
      RESET,
    ],
    { cwd: root, stdio: "pipe" },
  );
}

export function deleteProduct(sku: string): void {
  if (!/^[A-Z0-9-]{3,32}$/.test(sku)) throw new Error("sku is not a catalog identifier");
  const root = process.env.SHOP_ROOT;
  if (!root) throw new Error("SHOP_ROOT is not set");
  execFileSync(
    "docker",
    [
      "compose",
      "-p",
      process.env.SHOP_COMPOSE_PROJECT ?? "cherry-test",
      "-f",
      "docker-compose.test.yml",
      "exec",
      "-T",
      "shop-db",
      "psql",
      "-U",
      process.env.SHOP_DB_USER ?? "shop",
      "-d",
      process.env.SHOP_DB_NAME ?? "shop",
      "-v",
      "ON_ERROR_STOP=1",
      "-c",
      `DELETE FROM product WHERE sku = '${sku}'`,
    ],
    { cwd: root, stdio: "pipe" },
  );
}
