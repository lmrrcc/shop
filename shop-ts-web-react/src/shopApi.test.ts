import { afterEach, describe, expect, it, vi } from "vitest";

import { addCartItem, checkoutCart, listProducts, openCart, ShopRequestError } from "./shopApi";

const base = "http://shop.test";

function json(body: unknown, status = 200, statusText = "OK"): Response {
  return new Response(JSON.stringify(body), { status, statusText, headers: { "content-type": "application/json" } });
}

function install(response: Response | Promise<Response> | (() => Promise<Response>)): { urls: string[]; init: RequestInit | undefined } {
  const seen = { urls: [] as string[], init: undefined as RequestInit | undefined };
  const fetchMock: typeof fetch = (input, init) => {
    seen.urls.push(typeof input === "string" ? input : input instanceof URL ? input.toString() : input.url);
    seen.init = init;
    return Promise.resolve(typeof response === "function" ? response() : response);
  };
  vi.stubGlobal("fetch", fetchMock);
  return seen;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("shop API", () => {
  it("maps catalog rows into products", async () => {
    install(json({ items: [{ sku: "MUG-STONE", name: "Stoneware mug", price_cents: 1800, stock: 1000 }] }));
    await expect(listProducts(base)).resolves.toEqual([{ sku: "MUG-STONE", name: "Stoneware mug", priceCents: 1800, stock: 1000 }]);
  });

  it("opens a cart", async () => {
    const seen = install(json({ id: "0b8f6c1e-4a2d-4e1f-9c3b-2d5e7f8a9b0c", status: "open", lines: [] }));
    await expect(openCart(base)).resolves.toEqual({ id: "0b8f6c1e-4a2d-4e1f-9c3b-2d5e7f8a9b0c", status: "open", lines: [] });
    expect(seen.urls).toEqual(["http://shop.test/v1/carts"]);
    expect(seen.init?.method).toBe("POST");
  });

  it("adds a line and returns the cart", async () => {
    const seen = install(json({ id: "0b8f6c1e-4a2d-4e1f-9c3b-2d5e7f8a9b0c", status: "open", lines: [{ sku: "MUG-STONE", quantity: 2 }] }));
    const cart = await addCartItem(base, "0b8f6c1e-4a2d-4e1f-9c3b-2d5e7f8a9b0c", "MUG-STONE", 2);
    expect(cart.lines).toEqual([{ sku: "MUG-STONE", quantity: 2 }]);
    expect(seen.init?.body).toBe(JSON.stringify({ sku: "MUG-STONE", quantity: 2 }));
  });

  it("maps a placed order", async () => {
    install(json({ id: 1, cart_id: "0b8f6c1e-4a2d-4e1f-9c3b-2d5e7f8a9b0c", total_cents: 3600 }));
    await expect(checkoutCart(base, "0b8f6c1e-4a2d-4e1f-9c3b-2d5e7f8a9b0c")).resolves.toEqual({
      id: 1,
      cartId: "0b8f6c1e-4a2d-4e1f-9c3b-2d5e7f8a9b0c",
      totalCents: 3600,
    });
  });

  it("reads an API error", async () => {
    install(json({ error: { code: "empty_cart", message: "cart has no items" } }, 422, "Unprocessable Entity"));
    const error = await listProducts(base).catch((failure: unknown) => failure);
    expect(error).toBeInstanceOf(ShopRequestError);
    expect(error).toMatchObject({ status: 422, code: "empty_cart", message: "cart has no items" });
  });

  it("keeps the status text when the error body has no code", async () => {
    install(json({}, 409, "Conflict"));
    const error = await listProducts(base).catch((failure: unknown) => failure);
    expect(error).toMatchObject({ status: 409, code: "request_failed", message: "Conflict" });
  });

  it("reports an unreadable error body", async () => {
    install(new Response("<<", { status: 500, statusText: "Error" }));
    const error = await listProducts(base).catch((failure: unknown) => failure);
    expect(error).toMatchObject({ status: 500, code: "request_failed", message: "the shop could not read the response" });
  });

  it("reports a network failure", async () => {
    vi.stubGlobal("fetch", () => Promise.reject(new Error("offline")));
    const error = await listProducts(base).catch((failure: unknown) => failure);
    expect(error).toMatchObject({ status: 0, code: "network", message: "the shop API is unreachable" });
  });
});
