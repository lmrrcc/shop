import { expect, test } from "@playwright/test";
import { appendFileSync } from "node:fs";

import { resetShopStock } from "./reset";

test.beforeEach(() => {
  resetShopStock();
});

test.afterEach(async ({ page }, testInfo) => {
  const coverageFile = process.env.SHOP_COVERAGE_FILE;
  if (coverageFile) {
    const coverage: unknown = await page.evaluate(() => Reflect.get(globalThis, "__coverage__") ?? null);
    if (coverage) appendFileSync(coverageFile, `${JSON.stringify(coverage)}\n`);
  }
  const shot = await page.screenshot({ fullPage: true });
  await testInfo.attach("shop", { body: shot, contentType: "image/png" });
});

test("catalog", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByText("Stoneware mug", { exact: true })).toBeVisible();
  await expect(page.getByText("1000 in stock")).toBeVisible();
  await expect(page).toHaveScreenshot("catalog.png", { fullPage: true });
});

test("empty checkout", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByText("Stoneware mug", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Checkout" }).click();
  await expect(page.getByRole("alert")).toHaveText("cart has no items");
  await expect(page).toHaveScreenshot("empty-cart.png", { fullPage: true });
});

test("cart lines", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByText("Stoneware mug", { exact: true })).toBeVisible();
  await page.getByLabel("Quantity for Stoneware mug").fill("2");
  await page.getByRole("button", { name: "Add Stoneware mug" }).click();
  await expect(page.getByLabel("Cart")).toContainText("Total $36.00");
  await expect(page).toHaveScreenshot("cart.png", { fullPage: true });
});

test("order placed", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByText("Stoneware mug", { exact: true })).toBeVisible();
  await page.getByLabel("Quantity for Stoneware mug").fill("2");
  await page.getByRole("button", { name: "Add Stoneware mug" }).click();
  await expect(page.getByLabel("Cart")).toContainText("Total $36.00");
  await page.getByRole("button", { name: "Checkout" }).click();
  await expect(page.getByRole("status")).toHaveText("Order 1 placed for $36.00");
  await expect(page).toHaveScreenshot("order.png", { fullPage: true });
});
