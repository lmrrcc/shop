import { createInstrumenter } from "istanbul-lib-instrument";
import react from "@vitejs/plugin-react";
import { defineConfig, type Plugin } from "vite";

const api = process.env.SHOP_API_URL ?? "http://127.0.0.1:3000";

const istanbulParserPlugins = [
  "typescript",
  "jsx",
  "importAssertions",
  "explicitResourceManagement",
  "decorators",
  "classProperties",
  "classPrivateProperties",
  "classPrivateMethods",
  "optionalChaining",
  "nullishCoalescingOperator",
  "dynamicImport",
  "importMeta",
  "topLevelAwait",
  "asyncGenerators",
  "objectRestSpread",
  "optionalCatchBinding",
];

function shopIstanbul(): Plugin {
  return {
    name: "shop-istanbul",
    enforce: "pre",
    transform(code, id) {
      if (process.env.SHOP_ISTANBUL !== "1") return null;
      if (code.includes('var gcv = "__coverage__"')) return null;
      const file = id.split("?", 1)[0] ?? id;
      const script = file.endsWith(".ts") || file.endsWith(".tsx");
      const owned = file.includes("/shop-ts-web-react/src/") || file.includes("/shop-ts-lib-cart/src/");
      if (!script || !owned || file.includes("/node_modules/") || file.includes(".test.") || file.endsWith(".d.ts")) return null;
      const instrumenter = createInstrumenter({
        esModules: true,
        compact: false,
        produceSourceMap: false,
        coverageVariable: "__coverage__",
        coverageGlobalScope: "globalThis",
        autoWrap: false,
        parserPlugins: istanbulParserPlugins,
      });
      return { code: instrumenter.instrumentSync(code, file) };
    },
  };
}

export default defineConfig({
  plugins: [shopIstanbul(), react()],
  server: {
    host: "127.0.0.1",
    port: Number(process.env.SHOP_WEB_PORT ?? 5173),
    strictPort: true,
    proxy: {
      "/v1": { target: api, changeOrigin: true },
      "/health": { target: api, changeOrigin: true },
      "/openapi.json": { target: api, changeOrigin: true },
    },
  },
});
