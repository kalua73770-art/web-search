import * as cheerio from "cheerio";

export type SearchResult = {
  title: string;
  url: string;
  snippet: string;
};

export type SearchResponse = {
  query: string;
  provider: string;
  count: number;
  results: SearchResult[];
};

async function fetchWithTimeout(
  url: string,
  options: RequestInit,
  timeoutMs = 10000,
): Promise<Response> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetch(url, { ...options, signal: controller.signal });
  } finally {
    clearTimeout(timeout);
  }
}

async function bingSearch(query: string): Promise<SearchResult[]> {
  const url = `https://www.bing.com/search?q=${encodeURIComponent(query)}&setmkt=en-US&setlang=en&cc=US`;

  try {
    const response = await fetchWithTimeout(
      url,
      {
        headers: {
          "User-Agent":
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
          Accept: "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
          "Accept-Language": "en-US,en;q=0.9",
          "Accept-Encoding": "gzip, deflate, br",
          DNT: "1",
        },
      },
      10000,
    );

    if (!response.ok) {
      throw new Error(`Bing search failed: ${response.status}`);
    }

    const html = await response.text();
    const $ = cheerio.load(html);
    const results: SearchResult[] = [];

    $(".b_algo").each((_, element) => {
      const titleEl = $(element).find("h2 a");
      const title = titleEl.text().trim();
      const href = titleEl.attr("href") || "";
      let snippet = "";
      const snippetSelectors = [".b_caption p", ".b_snippet", "p", ".tabcontent"];
      for (const selector of snippetSelectors) {
        const text = $(element).find(selector).first().text().trim();
        if (text) {
          snippet = text;
          break;
        }
      }

      if (title && href.startsWith("http")) {
        results.push({
          title,
          url: href,
          snippet: snippet || "No description available",
        });
      }
    });

    if (results.length === 0) {
      $("li, div").each((_, element) => {
        const el = $(element);
        const a = el.find("h2 a").first();
        const title = a.text().trim();
        const href = a.attr("href") || "";
        if (title && href.startsWith("http") && !results.some((r) => r.url === href)) {
          const snippet = el.find("p").first().text().trim() || "No description available";
          results.push({ title, url: href, snippet });
        }
      });
    }

    return results.slice(0, 10);
  } catch (error) {
    console.error("Bing search error:", error);
    return [];
  }
}

async function duckDuckGoSearch(query: string): Promise<SearchResult[]> {
  const url = `https://duckduckgo.com/html/?q=${encodeURIComponent(query)}`;

  try {
    const response = await fetchWithTimeout(
      url,
      {
        headers: {
          "User-Agent":
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
          Accept: "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
          "Accept-Language": "en-US,en;q=0.9",
        },
      },
      8000,
    );

    if (!response.ok) {
      throw new Error(`DuckDuckGo search failed: ${response.status}`);
    }

    const html = await response.text();
    const $ = cheerio.load(html);
    const results: SearchResult[] = [];

    $(".result").each((_, element) => {
      const titleEl = $(element).find(".result__a");
      const snippetEl = $(element).find(".result__snippet");
      const urlEl = $(element).find(".result__url");
      const title = titleEl.text().trim();
      const snippet = snippetEl.text().trim();
      let resultUrl = titleEl.attr("href") || urlEl.text().trim();

      if (resultUrl.startsWith("//duckduckgo.com/l/?")) {
        const uddg = new URLSearchParams(resultUrl.split("?")[1]).get("uddg");
        if (uddg) {
          resultUrl = decodeURIComponent(uddg);
        }
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

async function braveSearch(query: string): Promise<SearchResult[]> {
  const url = `https://search.brave.com/search?q=${encodeURIComponent(query)}&source=web`;

  try {
    const response = await fetchWithTimeout(
      url,
      {
        headers: {
          "User-Agent":
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
          Accept: "text/html",
          "Accept-Language": "en-US,en;q=0.9",
        },
      },
      8000,
    );

    if (!response.ok) {
      return [];
    }

    const html = await response.text();
    const $ = cheerio.load(html);
    const results: SearchResult[] = [];

    $("[data-lid], .snippet, .result").each((_, element) => {
      const titleEl = $(element).find("a[href]").first();
      const title = titleEl.text().trim();
      const href = titleEl.attr("href") || "";
      const snippet = $(element).find("p, .description, .snippet").first().text().trim();

      if (title && href.startsWith("http") && !results.some((r) => r.url === href)) {
        results.push({
          title,
          url: href,
          snippet: snippet || "No description available",
        });
      }
    });

    return results.slice(0, 10);
  } catch (error) {
    console.error("Brave search error:", error);
    return [];
  }
}

export async function webSearch(
  query: string,
  limit = 10,
): Promise<SearchResponse> {
  const safeLimit = Math.min(Math.max(Math.trunc(limit) || 10, 1), 20);
  let results = await bingSearch(query);
  let provider = "bing";

  if (results.length === 0) {
    results = await duckDuckGoSearch(query);
    provider = "duckduckgo";
  }

  if (results.length === 0) {
    results = await braveSearch(query);
    provider = "brave";
  }

  return {
    query,
    provider,
    count: results.length,
    results: results.slice(0, safeLimit),
  };
}
