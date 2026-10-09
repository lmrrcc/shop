import { describe, expect, it } from "vitest";
import { cartTotal, cents, formatCents, lineTotal } from "shop-ts-lib-cart";

describe("published cart entry", () => {
  it("prices two mugs through the package export", () => {
    const line = { sku: "MUG-STONE", quantity: 2, unitPriceCents: cents(1800) };
    expect(lineTotal(line)).toBe(3600);
    expect(cartTotal([line])).toBe(3600);
    expect(formatCents(cents(3600), "USD", "en-US")).toBe("$36.00");
  });
});
