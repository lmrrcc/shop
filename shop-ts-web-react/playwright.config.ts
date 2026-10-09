import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./tests",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  snapshotPathTemplate: "{testDir}/snapshots/{arg}{ext}",
  use: {
    baseURL: process.env.SHOP_WEB_URL ?? "http://127.0.0.1:5173",
    viewport: { width: 880, height: 1280 },
    deviceScaleFactor: 1,
  },
  expect: {
    toHaveScreenshot: { animations: "disabled", scale: "css" },
  },
});
