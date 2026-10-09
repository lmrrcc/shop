/// <reference types="vite/client" />

declare module "istanbul-lib-instrument" {
  export interface ShopInstrumenter {
    instrumentSync(code: string, filename: string, inputSourceMap?: object): string;
    lastSourceMap(): object | null;
  }
  export function createInstrumenter(options: object): ShopInstrumenter;
}
