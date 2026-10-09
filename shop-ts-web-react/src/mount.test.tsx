import { cleanup, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { mountShop } from "./mount";

afterEach(() => {
  cleanup();
  document.body.replaceChildren();
  vi.unstubAllGlobals();
});

describe("mountShop", () => {
  it("rejects a missing root", () => {
    expect(() => mountShop(null, "")).toThrow("root element is missing");
  });

  it("renders the shop into the root element", async () => {
    vi.stubGlobal("fetch", () => Promise.resolve(new Response(JSON.stringify({ items: [] }), { status: 200, headers: { "content-type": "application/json" } })));
    const root = document.createElement("div");
    document.body.appendChild(root);
    mountShop(root, "");
    expect(await screen.findByRole("heading", { name: "Shop" })).toBeInTheDocument();
    expect(await screen.findByText("The cart is empty.")).toBeInTheDocument();
  });
});
