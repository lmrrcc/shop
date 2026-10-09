import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { Shop } from "./Shop";

const mug = { sku: "MUG-STONE", name: "Stoneware mug", price_cents: 1800, stock: 998 };
const lamp = { sku: "LAMP-DESK", name: "Brass desk lamp", price_cents: 12900, stock: 2 };
const catalog = { items: [mug, lamp] };
const cartId = "0b8f6c1e-4a2d-4e1f-9c3b-2d5e7f8a9b0c";

function json(body: unknown, status = 200, statusText = "OK"): Response {
  return new Response(JSON.stringify(body), { status, statusText, headers: { "content-type": "application/json" } });
}

function cart(lines: readonly { sku: string; quantity: number }[]) {
  return { id: cartId, status: "open" as const, lines };
}

function install(handler: (url: string, init?: RequestInit) => Response | Promise<Response>) {
  const fetchMock: typeof fetch = (input, init) => {
    const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : input.url;
    return Promise.resolve(handler(url, init));
  };
  vi.stubGlobal("fetch", fetchMock);
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

async function openShop() {
  install((url) => (url.endsWith("/v1/products") ? json(catalog) : json(cart([]))));
  render(<Shop apiBase="" />);
  expect(await screen.findByText("Stoneware mug")).toBeInTheDocument();
}

describe("Shop", () => {
  it("shows the catalog and an empty cart", async () => {
    await openShop();
    expect(screen.getByText("$18.00")).toBeInTheDocument();
    expect(screen.getByText("998 in stock")).toBeInTheDocument();
    expect(screen.getByLabelText("Cart")).toHaveTextContent("The cart is empty.");
    expect(screen.getByText("Total $0.00")).toBeInTheDocument();
  });

  it("shows a loading line until the catalog arrives", async () => {
    let release: (response: Response) => void = () => undefined;
    install(() => new Promise<Response>((done) => { release = done; }));
    render(<Shop apiBase="" />);
    expect(screen.getByText("Loading the catalog.")).toBeInTheDocument();
    release(json(catalog));
    expect(await screen.findByText("Stoneware mug")).toBeInTheDocument();
  });

  it("ignores a catalog response that arrives after unmount", async () => {
    let release: (response: Response) => void = () => undefined;
    install(() => new Promise<Response>((done) => { release = done; }));
    const view = render(<Shop apiBase="" />);
    view.unmount();
    release(json(catalog));
    await Promise.resolve();
    expect(screen.queryByText("Stoneware mug")).not.toBeInTheDocument();
  });

  it("shows a storage failure from the catalog", async () => {
    install(() => json({ error: { code: "storage_unavailable", message: "storage is unavailable" } }, 500, "Error"));
    render(<Shop apiBase="" />);
    expect(await screen.findByRole("alert")).toHaveTextContent("storage is unavailable");
  });

  it("shows a generic message when the catalog body is not a list", async () => {
    install(() => json({}));
    render(<Shop apiBase="" />);
    expect(await screen.findByRole("alert")).toHaveTextContent("something went wrong");
  });

  it("shows a network failure", async () => {
    vi.stubGlobal("fetch", () => Promise.reject(new Error("offline")));
    render(<Shop apiBase="" />);
    expect(await screen.findByRole("alert")).toHaveTextContent("the shop API is unreachable");
  });

  it("adds two mugs and shows the line total", async () => {
    const user = userEvent.setup();
    install((url, init) => {
      if (url.endsWith("/v1/products")) return json(catalog);
      if (url.endsWith("/v1/carts")) return json(cart([]));
      const body = JSON.parse(String(init?.body)) as { quantity: number };
      return json(cart([{ sku: "MUG-STONE", quantity: body.quantity }]));
    });
    render(<Shop apiBase="" />);
    expect(await screen.findByText("Stoneware mug")).toBeInTheDocument();
    await user.clear(screen.getByLabelText("Quantity for Stoneware mug"));
    await user.type(screen.getByLabelText("Quantity for Stoneware mug"), "2");
    await user.click(screen.getByRole("button", { name: "Add Stoneware mug" }));
    expect(await screen.findByText("Total $36.00")).toBeInTheDocument();
    expect(screen.getByLabelText("Cart")).toHaveTextContent("Stoneware mug");
    expect(screen.getByLabelText("Cart")).toHaveTextContent("$36.00");
  });

  it("disables actions while an add is in flight", async () => {
    const user = userEvent.setup();
    let release: (response: Response) => void = () => undefined;
    install((url) => {
      if (url.endsWith("/items")) return new Promise<Response>((done) => { release = done; });
      if (url.endsWith("/v1/carts")) return json(cart([]));
      return json(catalog);
    });
    render(<Shop apiBase="" />);
    const add = await screen.findByRole("button", { name: "Add Stoneware mug" });
    await user.click(add);
    expect(add).toBeDisabled();
    release(json(cart([{ sku: "MUG-STONE", quantity: 1 }])));
    expect(await screen.findByText("Total $18.00")).toBeInTheDocument();
    expect(add).toBeEnabled();
  });

  it("shows the sku when a cart line is no longer in the catalog", async () => {
    const user = userEvent.setup();
    let lists = 0;
    install((url) => {
      if (url.endsWith("/v1/products")) {
        lists += 1;
        return json(lists === 1 ? catalog : { items: [] });
      }
      if (url.endsWith("/items")) return json(cart([{ sku: "MUG-STONE", quantity: 1 }]));
      return json(cart([]));
    });
    render(<Shop apiBase="" />);
    await user.click(await screen.findByRole("button", { name: "Add Stoneware mug" }));
    expect(await screen.findByText("MUG-STONE")).toBeInTheDocument();
    expect(screen.getByText("Total $0.00")).toBeInTheDocument();
  });

  it("places an order and rejects the next empty checkout", async () => {
    const user = userEvent.setup();
    let checkouts = 0;
    install((url) => {
      if (url.endsWith("/checkout")) {
        checkouts += 1;
        if (checkouts > 1) return json({ error: { code: "empty_cart", message: "cart has no items" } }, 422, "Unprocessable Entity");
        return json({ id: 4, cart_id: cartId, total_cents: 1800 });
      }
      if (url.endsWith("/items")) return json(cart([{ sku: "MUG-STONE", quantity: 1 }]));
      if (url.endsWith("/v1/carts")) return json(cart([]));
      return json(catalog);
    });
    render(<Shop apiBase="" />);
    await user.click(await screen.findByRole("button", { name: "Add Stoneware mug" }));
    await screen.findByText("Total $18.00");
    await user.click(screen.getByRole("button", { name: "Checkout" }));
    expect(await screen.findByRole("status")).toHaveTextContent("Order 4 placed for $18.00");
    expect(screen.getByLabelText("Cart")).toHaveTextContent("The cart is empty.");
    await user.click(screen.getByRole("button", { name: "Checkout" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("cart has no items");
  });

  it("shows an empty-cart error and a second checkout error", async () => {
    const user = userEvent.setup();
    let checkouts = 0;
    install((url) => {
      if (url.endsWith("/checkout")) {
        checkouts += 1;
        if (checkouts === 1) return json({ error: { code: "empty_cart", message: "cart has no items" } }, 422, "Unprocessable Entity");
        return json({ error: { code: "empty_cart", message: "cart has no items" } }, 422, "Unprocessable Entity");
      }
      if (url.endsWith("/v1/carts")) return json(cart([]));
      return json(catalog);
    });
    render(<Shop apiBase="" />);
    await screen.findByText("Stoneware mug");
    await user.click(screen.getByRole("button", { name: "Checkout" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("cart has no items");
    await user.click(screen.getByRole("button", { name: "Checkout" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("cart has no items");
  });

  it("shows a zero quantity error", async () => {
    const user = userEvent.setup();
    install((url, init) => {
      if (url.endsWith("/items")) {
        const body = JSON.parse(String(init?.body)) as { quantity: number };
        if (body.quantity === 0) return json({ error: { code: "invalid_quantity", message: "quantity must be between 1 and 99" } }, 422, "Unprocessable Entity");
      }
      if (url.endsWith("/v1/carts")) return json(cart([]));
      return json(catalog);
    });
    render(<Shop apiBase="" />);
    const quantity = await screen.findByLabelText("Quantity for Stoneware mug");
    await user.clear(quantity);
    await user.type(quantity, "0");
    await user.click(screen.getByRole("button", { name: "Add Stoneware mug" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("quantity must be between 1 and 99");
  });

  it("shows an out of stock error", async () => {
    const user = userEvent.setup();
    install((url) => {
      if (url.endsWith("/items")) return json({ error: { code: "out_of_stock", message: "not enough stock for LAMP-DESK" } }, 409, "Conflict");
      if (url.endsWith("/v1/carts")) return json(cart([]));
      return json(catalog);
    });
    render(<Shop apiBase="" />);
    const quantity = await screen.findByLabelText("Quantity for Brass desk lamp");
    await user.clear(quantity);
    await user.type(quantity, "3");
    await user.click(screen.getByRole("button", { name: "Add Brass desk lamp" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("not enough stock for LAMP-DESK");
  });

  it("shows an unreadable add failure", async () => {
    const user = userEvent.setup();
    install((url) => {
      if (url.endsWith("/items")) return new Response("<<", { status: 500, statusText: "Error" });
      if (url.endsWith("/v1/carts")) return json(cart([]));
      return json(catalog);
    });
    render(<Shop apiBase="" />);
    await user.click(await screen.findByRole("button", { name: "Add Stoneware mug" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("the shop could not read the response");
  });
});
