import { expect, test } from "@playwright/test";
import { appendFileSync } from "node:fs";

import { deleteProduct } from "./reset";

test.afterEach(async ({ page }) => {
  const coverageFile = process.env.SHOP_COVERAGE_FILE;
  if (!coverageFile) return;
  const coverage: unknown = await page.evaluate(() => Reflect.get(globalThis, "__coverage__") ?? null);
  if (coverage) appendFileSync(coverageFile, `${JSON.stringify(coverage)}\n`);
});

test("shows the seeded catalog", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Shop" })).toBeVisible();
  await expect(page.getByText("Stoneware mug", { exact: true })).toBeVisible();
  await expect(page.getByText("Brass desk lamp", { exact: true })).toBeVisible();
  await expect(page.getByText("$18.00")).toBeVisible();
  await expect(page.getByLabel("Cart")).toContainText("The cart is empty.");
});

test("rejects a zero quantity", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByText("Stoneware mug", { exact: true })).toBeVisible();
  await page.getByLabel("Quantity for Stoneware mug").fill("0");
  await page.getByRole("button", { name: "Add Stoneware mug" }).click();
  await expect(page.getByRole("alert")).toHaveText("quantity must be between 1 and 99");
});

test("rejects checkout of an empty cart", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByText("Stoneware mug", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Checkout" }).click();
  await expect(page.getByRole("alert")).toHaveText("cart has no items");
});

test("checks out two mugs and a second empty cart", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByText("Stoneware mug", { exact: true })).toBeVisible();
  await page.getByLabel("Quantity for Stoneware mug").fill("2");
  await page.getByRole("button", { name: "Add Stoneware mug" }).click();
  await expect(page.getByLabel("Cart")).toContainText("$32.40");
  await page.getByRole("button", { name: "Checkout" }).click();
  await expect(page.getByRole("status")).toContainText("placed for $32.40");
  await expect(page.getByLabel("Cart")).toContainText("The cart is empty.");
  await page.getByRole("button", { name: "Checkout" }).click();
  await expect(page.getByRole("alert")).toHaveText("cart has no items");
});

test("rejects a lamp quantity above the stock on hand", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByText("Brass desk lamp", { exact: true })).toBeVisible();
  await page.getByLabel("Quantity for Brass desk lamp").fill("3");
  await page.getByRole("button", { name: "Add Brass desk lamp" }).click();
  await expect(page.getByRole("alert")).toHaveText("not enough stock for LAMP-DESK");
});

test("shows an unknown product when the catalog row disappears", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByText("Dot-grid notebook A5", { exact: true })).toBeVisible();
  deleteProduct("NB-DOT-A5");
  await page.getByRole("button", { name: "Add Dot-grid notebook A5" }).click();
  await expect(page.getByRole("alert")).toHaveText("product NB-DOT-A5 does not exist");
});
