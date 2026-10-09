export interface Product {
  readonly sku: string;
  readonly name: string;
  readonly priceCents: number;
  readonly stock: number;
}

export interface CartLine {
  readonly sku: string;
  readonly quantity: number;
}

export interface Cart {
  readonly id: string;
  readonly status: "open" | "checked_out";
  readonly lines: readonly CartLine[];
}

export interface Order {
  readonly id: number;
  readonly cartId: string;
  readonly totalCents: number;
}

export class ShopRequestError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
    this.name = "ShopRequestError";
  }
}

interface ProductBody {
  readonly sku: string;
  readonly name: string;
  readonly price_cents: number;
  readonly stock: number;
}

interface CartBody {
  readonly id: string;
  readonly status: "open" | "checked_out";
  readonly lines: readonly { readonly sku: string; readonly quantity: number }[];
}

interface OrderBody {
  readonly id: number;
  readonly cart_id: string;
  readonly total_cents: number;
}

function productFromBody(body: ProductBody): Product {
  return { sku: body.sku, name: body.name, priceCents: body.price_cents, stock: body.stock };
}

function cartFromBody(body: CartBody): Cart {
  return {
    id: body.id,
    status: body.status,
    lines: body.lines.map((line) => ({ sku: line.sku, quantity: line.quantity })),
  };
}

async function readError(response: Response): Promise<ShopRequestError> {
  let code = "request_failed";
  let message = response.statusText;
  try {
    const body = (await response.json()) as { error?: { code?: string; message?: string } };
    if (body.error?.code) code = body.error.code;
    if (body.error?.message) message = body.error.message;
  } catch {
    message = "the shop could not read the response";
  }
  return new ShopRequestError(response.status, code, message);
}

async function requestJson<T>(apiBase: string, path: string, init?: RequestInit): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`${apiBase}${path}`, init);
  } catch {
    throw new ShopRequestError(0, "network", "the shop API is unreachable");
  }
  if (!response.ok) throw await readError(response);
  return (await response.json()) as T;
}

export async function listProducts(apiBase: string): Promise<readonly Product[]> {
  const body = await requestJson<{ items: ProductBody[] }>(apiBase, "/v1/products");
  return body.items.map(productFromBody);
}

export async function openCart(apiBase: string): Promise<Cart> {
  return cartFromBody(await requestJson<CartBody>(apiBase, "/v1/carts", { method: "POST" }));
}

export async function addCartItem(apiBase: string, cartId: string, sku: string, quantity: number): Promise<Cart> {
  return cartFromBody(
    await requestJson<CartBody>(apiBase, `/v1/carts/${cartId}/items`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ sku, quantity }),
    }),
  );
}

export async function checkoutCart(apiBase: string, cartId: string): Promise<Order> {
  const body = await requestJson<OrderBody>(apiBase, `/v1/carts/${cartId}/checkout`, { method: "POST" });
  return { id: body.id, cartId: body.cart_id, totalCents: body.total_cents };
}
