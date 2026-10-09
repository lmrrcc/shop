export type Cents = number & { readonly __brand: "Cents" };

export interface CartLine {
  readonly sku: string;
  readonly quantity: number;
  readonly unitPriceCents: Cents;
}

export class InvalidAmountError extends Error {
  constructor(readonly amount: number) {
    super(`amount must be a non-negative safe integer of cents, got ${amount}`);
    this.name = "InvalidAmountError";
  }
}

export class InvalidQuantityError extends Error {
  constructor(readonly sku: string, readonly quantity: number) {
    super(`quantity for ${sku} must be an integer between 1 and 99, got ${quantity}`);
    this.name = "InvalidQuantityError";
  }
}

export class InvalidDiscountError extends Error {
  constructor(readonly percent: number) {
    super(`discount percent must be an integer from 0 through 100, got ${percent}`);
    this.name = "InvalidDiscountError";
  }
}

const MINIMUM_QUANTITY = 1;
const MAXIMUM_QUANTITY = 99;
const CENTS_PER_UNIT = 100;
const FULL_DISCOUNT_PERCENT = 100;

export function cents(amount: number): Cents {
  if (!Number.isSafeInteger(amount) || amount < 0) {
    throw new InvalidAmountError(amount);
  }
  return amount as Cents;
}

export function discountedUnitCents(listPriceCents: Cents, discountPercent: number): Cents {
  if (!Number.isInteger(discountPercent) || discountPercent < 0 || discountPercent > FULL_DISCOUNT_PERCENT) {
    throw new InvalidDiscountError(discountPercent);
  }
  const reduction = Math.floor((listPriceCents * discountPercent) / FULL_DISCOUNT_PERCENT);
  return cents(listPriceCents - reduction);
}

export function lineTotal(line: CartLine): Cents {
  if (!Number.isInteger(line.quantity) || line.quantity < MINIMUM_QUANTITY || line.quantity > MAXIMUM_QUANTITY) {
    throw new InvalidQuantityError(line.sku, line.quantity);
  }
  return cents(line.unitPriceCents * line.quantity);
}

export function cartTotal(lines: readonly CartLine[]): Cents {
  return lines.reduce<Cents>((total, line) => cents(total + lineTotal(line)), cents(0));
}

export function formatCents(amount: Cents, currency: string, locale: string): string {
  return new Intl.NumberFormat(locale, { style: "currency", currency }).format(amount / CENTS_PER_UNIT);
}
