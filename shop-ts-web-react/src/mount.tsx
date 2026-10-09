import { createRoot } from "react-dom/client";

import { Shop } from "./Shop";

export function mountShop(root: HTMLElement | null, apiBase: string) {
  if (!root) throw new Error("root element is missing");
  createRoot(root).render(<Shop apiBase={apiBase} />);
}
