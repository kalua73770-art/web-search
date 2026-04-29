import { z } from "zod";
import { createRouter, publicQuery } from "../middleware";
import * as cheerio from "cheerio";

// Timeout wrapper for fetch
async function fetchWithTimeout(url: string, options: RequestInit, timeoutMs = 10000): Promise<Response> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(url, { ...options, signal: controller.signal });
    clearTimeout(timeout);
    return response;
  } catch (error) {
    clearTimeout(timeout);
    throw error;
  }
}

// Bing search scraper (Primary - works in most regions)
async function bingSearch(query: string): Promise<
  Array<{ title: string; url: string; snippet: string }>
> {
  const encodedQuery = encodeURIComponent(query);
  // cc=US and setmkt=en-US for English results
  const url = `https://www.bing.com/search?q=${encodedQuery}&setmkt=en-US&setlang=en&cc=US`;

  try {
    const response = await fetchWithTimeout(url, {
      headers: {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "en-US,en;q=0.9",
        "Accept-Encoding": "gzip, deflate, br",
        "DNT": "1",
      },
    }, 10000);

    if (!response.ok) {
      throw new Error(`Bing search failed: ${response.status}`);
    }

    const html = await response.text();
    const $ = cheerio.load(html);
    const results: Array<{ title: string; url: string; snippet: string }> = [];

    // Primary selector: .b_algo is Bing's standard result container
    $(".b_algo").each((_, element) => {
      const titleEl = $(element).find("h2 a");
      const title = titleEl.text().trim();
      const href = titleEl.attr("href") || "";

      // Try multiple snippet selectors
      let snippet = "";
      const snippetSelectors = [".b_caption p", ".b_snippet", "p", ".tabcontent"];
      for (const sel of snippetSelectors) {
        const snip = $(element).find(sel).first().text().trim();
        if (snip) {
          snippet = snip;
          break;
        }
      }

      if (title && href && href.startsWith("http")) {
        results.push({
          title,
          url: href,
          snippet: snippet || "No description available",
        });
      }
    });

    // Fallback: parse any h2 > a inside result-like containers
    if (results.length === 0) {
      $("li, div").each((_, element) => {
        const el = $(element);
        // Look for elements that have h2 with a link - common search result pattern
        const h2 = el.find("h2").first();
        const a = h2.find("a").first();
        const title = a.text().trim();
        const href = a.attr("href") || "";

        if (title && href && href.startsWith("http") && !results.find(r => r.url === href)) {
          // Look for nearby paragraph as snippet
          let snippet = el.find("p").first().text().trim();
          if (!snippet) {
            snippet = el.parent().find("p").first().text().trim();
          }

          results.push({
            title,
            url: href,
            snippet: snippet || "No description available",
          });
        }
      });
    }

    return results.slice(0, 10);
  } catch (error) {
    console.error("Bing search error:", error);
    return [];
  }
}

// DuckDuckGo search scraper (Fallback)
async function duckDuckGoSearch(query: string): Promise<
  Array<{ title: string; url: string; snippet: string }>
> {
  const encodedQuery = encodeURIComponent(query);
  const url = `https://duckduckgo.com/html/?q=${encodedQuery}`;

  try {
    const response = await fetchWithTimeout(url, {
      headers: {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "en-US,en;q=0.9",
      },
    }, 8000);

    if (!response.ok) {
      throw new Error(`DuckDuckGo search failed: ${response.status}`);
    }

    const html = await response.text();
    const $ = cheerio.load(html);
    const results: Array<{ title: string; url: string; snippet: string }> = [];

    $(".result").each((_, element) => {
      const titleEl = $(element).find(".result__a");
      const snippetEl = $(element).find(".result__snippet");
      const urlEl = $(element).find(".result__url");

      const title = titleEl.text().trim();
      const snippet = snippetEl.text().trim();
      let resultUrl = titleEl.attr("href") || urlEl.text().trim();

      if (resultUrl && resultUrl.startsWith("//duckduckgo.com/l/?")) {
        const uddg = new URLSearchParams(resultUrl.split("?")[1]).get("uddg");
        if (uddg) resultUrl = decodeURIComponent(uddg);
      }

      if (title && resultUrl) {
        results.push({
          title,
          url: resultUrl,
          snippet: snippet || "No description available",
        });
      }
    });

    return results.slice(0, 10);
  } catch (error) {
    console.error("DuckDuckGo search error:", error);
    return [];
  }
}

// Brave Search (Secondary fallback using search.brave.com)
async function braveSearch(query: string): Promise<
  Array<{ title: string; url: string; snippet: string }>
> {
  const encodedQuery = encodeURIComponent(query);
  const url = `https://search.brave.com/search?q=${encodedQuery}&source=web`;

  try {
    const response = await fetchWithTimeout(url, {
      headers: {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept": "text/html",
        "Accept-Language": "en-US,en;q=0.9",
      },
    }, 8000);

    if (!response.ok) return [];

    const html = await response.text();
    const $ = cheerio.load(html);
    const results: Array<{ title: string; url: string; snippet: string }> = [];

    $("[data-lid], .snippet, .result").each((_, element) => {
      const titleEl = $(element).find("a[href]").first();
      const title = titleEl.text().trim();
      const href = titleEl.attr("href") || "";
      const snippet = $(element).find("p, .description, .snippet").first().text().trim();

      if (title && href && href.startsWith("http") && !results.find(r => r.url === href)) {
        results.push({ title, url: href, snippet: snippet || "No description available" });
      }
    });

    return results.slice(0, 10);
  } catch (error) {
    console.error("Brave search error:", error);
    return [];
  }
}

export const searchRouter = createRouter({
  webSearch: publicQuery
    .input(
      z.object({
        query: z.string().min(1).max(500),
        limit: z.number().int().min(1).max(20).optional(),
      }),
    )
    .mutation(async ({ input }) => {
      const { query, limit = 10 } = input;

      // Try Bing first (works in most server environments)
      let results = await bingSearch(query);
      let provider = "bing";

      // Fallback to DuckDuckGo
      if (results.length === 0) {
        results = await duckDuckGoSearch(query);
        provider = "duckduckgo";
      }

      // Final fallback to Brave
      if (results.length === 0) {
        results = await braveSearch(query);
        provider = "brave";
      }

      return { query, provider, count: results.length, results: results.slice(0, limit) };
    }),
});
