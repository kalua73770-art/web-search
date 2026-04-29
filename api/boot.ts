import { Hono } from "hono";
import { bodyLimit } from "hono/body-limit";
import type { HttpBindings } from "@hono/node-server";
import { fetchRequestHandler } from "@trpc/server/adapters/fetch";
import { appRouter } from "./router";
import { createContext } from "./context";
import { searchRouter } from "./routers/search";

const app = new Hono<{ Bindings: HttpBindings }>();

app.use(bodyLimit({ maxSize: 50 * 1024 * 1024 }));

app.use("*", async (c, next) => {
  c.header("Access-Control-Allow-Origin", "*");
  c.header("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
  c.header("Access-Control-Allow-Headers", "Content-Type, Authorization");
  if (c.req.method === "OPTIONS") {
    return c.body(null, 204);
  }
  return next();
});

app.get("/health", (c) => c.json({ ok: true, ts: Date.now() }));

app.get("/api/search", async (c) => {
  const query = c.req.query("q") ?? "";
  const limitRaw = c.req.query("limit");
  const limit = limitRaw ? Number(limitRaw) : undefined;

  if (!query.trim()) {
    return c.json({ error: "Missing query. Use ?q=your+query" }, 400);
  }

  const result = await searchRouter.createCaller({ req: c.req.raw, resHeaders: new Headers() }).webSearch({
    query,
    limit,
  });
  return c.json(result);
});

app.post("/api/search", async (c) => {
  let body: unknown;
  try {
    body = await c.req.json();
  } catch {
    return c.json({ error: "Invalid JSON body" }, 400);
  }

  const payload = (body ?? {}) as { query?: string; limit?: number };
  if (!payload.query?.trim()) {
    return c.json({ error: "Missing query in JSON body" }, 400);
  }

  const result = await searchRouter.createCaller({ req: c.req.raw, resHeaders: new Headers() }).webSearch({
    query: payload.query,
    limit: payload.limit,
  });
  return c.json(result);
});

app.use("/api/trpc/*", async (c) => {
  return fetchRequestHandler({
    endpoint: "/api/trpc",
    req: c.req.raw,
    router: appRouter,
    createContext,
  });
});
app.all("/api/*", (c) => c.json({ error: "Not Found" }, 404));
app.get("/", (c) => c.json({ ok: true, service: "web-search-backend", route: "/api/search" }));
app.notFound((c) => c.json({ error: "Not Found" }, 404));

export default app;
const { serve } = await import("@hono/node-server");
const port = Number.parseInt(process.env.PORT || "3000", 10);
serve({ fetch: app.fetch, port }, () => {
  console.log(`Server running on http://localhost:${port}/`);
});
