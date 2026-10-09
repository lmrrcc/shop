import { describe, expect, it } from "vitest";
import { cartTotal, cents, formatCents, InvalidAmountError, InvalidQuantityError, lineTotal } from "./cart";

describe("cents", () => {
  it("accepts zero", () => {
    expect(cents(0)).toBe(0);
  });
  it("rejects negative amounts", () => {
    expect(() => cents(-1)).toThrow(InvalidAmountError);
  });
  it("rejects fractional amounts", () => {
    expect(() => cents(10.5)).toThrow(InvalidAmountError);
  });
});

describe("lineTotal", () => {
  it("multiplies unit price by quantity", () => {
    expect(lineTotal({ sku: "MUG-STONE", quantity: 2, unitPriceCents: cents(1800) })).toBe(3600);
  });
  it("rejects a zero quantity", () => {
    expect(() => lineTotal({ sku: "MUG-STONE", quantity: 0, unitPriceCents: cents(1800) })).toThrow(InvalidQuantityError);
  });
  it("rejects a quantity above the cart limit", () => {
    expect(() => lineTotal({ sku: "MUG-STONE", quantity: 100, unitPriceCents: cents(1800) })).toThrow(InvalidQuantityError);
  });
});

describe("cartTotal", () => {
  it("is zero for an empty cart", () => {
    expect(cartTotal([])).toBe(0);
  });
  it("sums every line", () => {
    const total = cartTotal([
      { sku: "MUG-STONE", quantity: 2, unitPriceCents: cents(1800) },
      { sku: "NB-DOT-A5", quantity: 3, unitPriceCents: cents(1400) },
    ]);
    expect(total).toBe(7800);
  });
});

describe("formatCents", () => {
  it("formats euros for an Italian locale", () => {
    expect(formatCents(cents(12900), "EUR", "it-IT")).toBe("129,00 €");
  });
  it("formats dollars for a US locale", () => {
    expect(formatCents(cents(1400), "USD", "en-US")).toBe("$14.00");
  });
});
