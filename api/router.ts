import { createRouter, publicQuery } from "./middleware";
import { searchRouter } from "./routers/search";

export const appRouter = createRouter({
  ping: publicQuery.query(() => ({ ok: true, ts: Date.now() })),
  search: searchRouter,
});

export type AppRouter = typeof appRouter;
