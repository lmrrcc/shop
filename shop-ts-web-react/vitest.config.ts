import { defineConfig } from "vitest/config";

const jsonReport = process.env.VITEST_JSON_REPORT;
const coverageDir = process.env.VITEST_COVERAGE_DIR;

export default defineConfig({
  esbuild: { jsx: "automatic" },
  test: {
    environment: "jsdom",
    include: ["src/**/*.test.ts", "src/**/*.test.tsx"],
    setupFiles: ["./src/testSetup.ts"],
    reporters: jsonReport ? ["default", "json"] : ["default"],
    outputFile: jsonReport ? { json: jsonReport } : undefined,
    coverage: {
      provider: "v8",
      include: ["src/**/*.{ts,tsx}"],
      exclude: ["src/**/*.test.ts", "src/**/*.test.tsx", "src/vite-env.d.ts", "src/main.tsx"],
      enabled: Boolean(coverageDir),
      reportsDirectory: coverageDir ?? "coverage",
      reporter: ["lcov", "text"],
    },
  },
});
