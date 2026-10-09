import { defineConfig } from "vitest/config";

const jsonReport = process.env.VITEST_JSON_REPORT;
const coverageDir = process.env.VITEST_COVERAGE_DIR;

export default defineConfig({
  test: {
    environment: "node",
    reporters: jsonReport ? ["default", "json"] : ["default"],
    outputFile: jsonReport ? { json: jsonReport } : undefined,
    include: ["src/**/*.test.ts", "integration/**/*.test.ts"],
    coverage: {
      provider: "v8",
      include: ["src/**/*.ts"],
      exclude: ["src/**/*.test.ts"],
      enabled: Boolean(coverageDir),
      reportsDirectory: coverageDir ?? "coverage",
      reporter: ["lcov", "text"],
    },
  },
});
