import { useEffect, useState } from "react";
import { cartTotal, cents, discountedUnitCents, formatCents, lineTotal, type CartLine as PricedLine } from "shop-ts-lib-cart";

import { addCartItem, checkoutCart, listProducts, openCart, ShopRequestError, type Cart, type Order, type Product } from "./shopApi";

const CURRENCY = "USD";
const LOCALE = "en-US";

interface Props {
  readonly apiBase: string;
}

function pricedLine(line: Cart["lines"][number], products: readonly Product[]): PricedLine {
  const product = products.find((candidate) => candidate.sku === line.sku);
  const listPriceCents = cents(product?.priceCents ?? 0);
  const discountPercent = product?.discountPercent ?? 0;
  return { sku: line.sku, quantity: line.quantity, unitPriceCents: discountedUnitCents(listPriceCents, discountPercent) };
}

function messageOf(error: unknown): string {
  if (error instanceof ShopRequestError) return error.message;
  return "something went wrong";
}

export function Shop({ apiBase }: Props) {
  const [products, setProducts] = useState<readonly Product[] | null>(null);
  const [cart, setCart] = useState<Cart | null>(null);
  const [order, setOrder] = useState<Order | null>(null);
  const [quantities, setQuantities] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let active = true;
    listProducts(apiBase)
      .then((loaded) => {
        if (active) setProducts(loaded);
      })
      .catch((failure: unknown) => {
        if (active) setError(messageOf(failure));
      });
    return () => {
      active = false;
    };
  }, [apiBase]);

  async function add(product: Product) {
    const parsed = Number(quantities[product.sku] ?? "1");
    setBusy(true);
    setError(null);
    setOrder(null);
    try {
      const current = cart ?? (await openCart(apiBase));
      const updated = await addCartItem(apiBase, current.id, product.sku, parsed);
      setCart(updated);
      const refreshed = await listProducts(apiBase);
      setProducts(refreshed);
    } catch (failure: unknown) {
      setError(messageOf(failure));
    } finally {
      setBusy(false);
    }
  }

  async function checkout() {
    setBusy(true);
    setError(null);
    try {
      const current = cart ?? (await openCart(apiBase));
      const placed = await checkoutCart(apiBase, current.id);
      setOrder(placed);
      setCart(null);
      setProducts(await listProducts(apiBase));
    } catch (failure: unknown) {
      setError(messageOf(failure));
    } finally {
      setBusy(false);
    }
  }

  const lines = cart && products ? cart.lines.map((line) => pricedLine(line, products)) : [];
  const total = lines.length > 0 ? cartTotal(lines) : cents(0);

  return (
    <main aria-busy={busy}>
      <h1>Shop</h1>
      {products === null && error === null ? <p>Loading the catalog.</p> : null}
      {error ? <p role="alert">{error}</p> : null}
      <ul>
        {(products ?? []).map((product) => (
          <li key={product.sku}>
            <span>{product.name}</span>
            <span>{formatCents(cents(product.priceCents), CURRENCY, LOCALE)}</span>
            <span>{product.stock} in stock</span>
            <label>
              Quantity
              <input
                aria-label={`Quantity for ${product.name}`}
                value={quantities[product.sku] ?? "1"}
                onChange={(event) => setQuantities({ ...quantities, [product.sku]: event.target.value })}
              />
            </label>
            <button type="button" disabled={busy} onClick={() => void add(product)}>
              Add {product.name}
            </button>
          </li>
        ))}
      </ul>
      <section aria-label="Cart">
        <h2>Cart</h2>
        {lines.length === 0 ? <p>The cart is empty.</p> : null}
        <ul>
          {lines.map((line) => {
            const product = products?.find((candidate) => candidate.sku === line.sku);
            return (
              <li key={line.sku}>
                <span>{product?.name ?? line.sku}</span>
                <span>{line.quantity}</span>
                <span>{formatCents(lineTotal(line), CURRENCY, LOCALE)}</span>
              </li>
            );
          })}
        </ul>
        <p>Total {formatCents(total, CURRENCY, LOCALE)}</p>
        <button type="button" disabled={busy} onClick={() => void checkout()}>
          Checkout
        </button>
      </section>
      {order ? (
        <p role="status">
          Order {order.id} placed for {formatCents(cents(order.totalCents), CURRENCY, LOCALE)}
        </p>
      ) : null}
    </main>
  );
}


