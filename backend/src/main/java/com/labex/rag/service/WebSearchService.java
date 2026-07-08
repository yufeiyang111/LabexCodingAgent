package com.labex.rag.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.labex.rag.config.RagConfig;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
public class WebSearchService {

    @Autowired
    private RagConfig ragConfig;

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final int TIMEOUT_MS = 7000;
    private static final int FETCH_TIMEOUT_MS = 10000;
    private static final String TAVILY_URL = "https://api.tavily.com/search";
    private static final Gson GSON = new Gson();

    public SearchBundle search(String question, int limit) {
        return search(question, limit, Math.min(4, Math.max(0, limit)));
    }

    public SearchBundle search(String question, int limit, int fetchContentLimit) {
        List<String> keywords = extractKeywords(question);
        return search(question, limit, fetchContentLimit, keywords);
    }

    public SearchBundle search(String query, int limit, int fetchContentLimit, List<String> keywords) {
        int safeLimit = Math.max(1, Math.min(40, limit));
        List<String> safeKeywords = keywords == null ? new ArrayList<>() : new ArrayList<>(keywords);
        String searchQuery = query == null || query.isBlank() ? String.join(" ", safeKeywords) : query;
        List<String> displayKeywords = safeKeywords.isEmpty() ? extractKeywords(searchQuery) : safeKeywords;

        // 优先使用 Tavily API（如果配置了 API Key）
        String tavilyKey = ragConfig.getTavilyApiKey();
        if (tavilyKey != null && !tavilyKey.isBlank()) {
            try {
                List<WebSearchResult> tavilyResults = searchTavily(searchQuery, safeLimit);
                if (!tavilyResults.isEmpty()) {
                    log.info("Tavily search returned {} results for: {}", tavilyResults.size(), searchQuery);
                    return new SearchBundle(displayKeywords, List.of(), tavilyResults);
                }
                log.warn("Tavily returned empty results, falling back to Jsoup scraping");
            } catch (Exception e) {
                log.warn("Tavily search failed, falling back to Jsoup scraping: {}", e.getMessage());
            }
        }

        // 降级：Jsoup 爬取 DuckDuckGo / Bing
        List<String> exactPhrases = extractExactPhrases(searchQuery, displayKeywords);
        boolean temporal = isTemporalQuery(searchQuery);

        List<WebSearchResult> results = new ArrayList<>();
        int poolLimit = Math.max(safeLimit * 3, 50);
        int perQueryLimit = Math.max(10, Math.min(20, safeLimit));

        for (String currentQuery : expandSearchQueries(searchQuery, displayKeywords)) {
            if (temporal) {
                try {
                    results.addAll(searchBingNews(currentQuery, perQueryLimit));
                } catch (Exception e) {
                    log.warn("Bing News search failed: {}", e.getMessage());
                }
            }
            try {
                results.addAll(searchDuckDuckGo(currentQuery, perQueryLimit));
            } catch (Exception e) {
                log.warn("DuckDuckGo search failed: {}", e.getMessage());
            }
            try {
                results.addAll(searchBing(currentQuery, perQueryLimit));
            } catch (Exception e) {
                log.warn("Bing search failed: {}", e.getMessage());
            }
            results = dedupeAndLimit(rankResults(results, exactPhrases, temporal), poolLimit);
            if (results.size() >= poolLimit) {
                break;
            }
        }

        results = dedupeAndLimit(rankResults(results, exactPhrases, temporal), poolLimit);
        if (results.isEmpty()) {
            results.add(fallbackResult(searchQuery));
        }
        if (fetchContentLimit > 0) {
            results = enrichWithPageContent(results, Math.min(fetchContentLimit, safeLimit));
        }
        results = markExactMatches(results, exactPhrases);
        results = dedupeAndLimit(rankResults(results, exactPhrases, temporal), safeLimit);
        return new SearchBundle(displayKeywords, exactPhrases, results);
    }

    private List<String> expandSearchQueries(String query, List<String> keywords) {
        Set<String> queries = new LinkedHashSet<>();
        String base = clean(query);
        List<String> exactPhrases = extractExactPhrases(query, keywords);
        String intentTail = buildIntentTail(base, exactPhrases);

        for (String phrase : exactPhrases) {
            queries.add("\"" + phrase + "\"" + (intentTail.isBlank() ? "" : " " + intentTail));
            String hyphenVariant = phrase.replaceAll("\\s+", "-");
            if (!hyphenVariant.equals(phrase)) {
                queries.add("\"" + hyphenVariant + "\"" + (intentTail.isBlank() ? "" : " " + intentTail));
            }
        }
        if (!base.isBlank()) {
            queries.add(base);
        }
        if (isTemporalQuery(base)) {
            int year = LocalDate.now().getYear();
            if (!base.contains(String.valueOf(year))) {
                queries.add(base + " " + year);
            }
            queries.add(base + " latest official");
            queries.add(base + " release announcement");
        }
        if (keywords != null) {
            for (String keyword : keywords) {
                String value = clean(keyword);
                if (!exactPhrases.isEmpty() && isBroadKeywordForExactPhrase(value, exactPhrases)) {
                    continue;
                }
                if (!value.isBlank() && value.length() <= 80) {
                    queries.add(value);
                }
            }
        }
        return queries.stream().filter(q -> !q.isBlank()).limit(8).toList();
    }

    private boolean isBroadKeywordForExactPhrase(String keyword, List<String> exactPhrases) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String normalizedKeyword = normalizeForMatch(keyword);
        if (normalizedKeyword.length() < 4) {
            return true;
        }
        for (String phrase : exactPhrases) {
            String normalizedPhrase = normalizeForMatch(phrase);
            if (normalizedPhrase.contains(normalizedKeyword) && !normalizedPhrase.equals(normalizedKeyword)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeForMatch(String value) {
        return clean(value).toLowerCase(Locale.ROOT)
                .replaceAll("[\"'`]+", "")
                .replaceAll("[\\s_-]+", " ")
                .trim();
    }

    private String buildIntentTail(String query, List<String> exactPhrases) {
        String tail = query == null ? "" : query;
        for (String phrase : exactPhrases) {
            tail = tail.replace(phrase, " ");
            tail = tail.replace(phrase.replaceAll("\\s+", "-"), " ");
        }
        tail = tail.replaceAll("[\"'`]+", " ").replaceAll("\\s+", " ").trim();
        return truncate(tail, 90);
    }

    private List<String> extractExactPhrases(String query, List<String> keywords) {
        Set<String> phrases = new LinkedHashSet<>();
        String source = (query == null ? "" : query) + " " + (keywords == null ? "" : String.join(" ", keywords));

        java.util.regex.Matcher quotedMatcher = java.util.regex.Pattern.compile("\"([^\"]{2,120})\"").matcher(source);
        while (quotedMatcher.find()) {
            phrases.add(clean(quotedMatcher.group(1)));
        }

        java.util.regex.Matcher versionMatcher = java.util.regex.Pattern
                .compile("(?iu)([\\p{L}][\\p{L}\\p{N}+#./_]*[\\s-]+\\d+(?:\\.\\d+)*(?:[\\p{L}\\p{N}+#./_-]{0,24})?)")
                .matcher(source);
        while (versionMatcher.find()) {
            String phrase = normalizeExactPhrase(versionMatcher.group(1));
            if (phrase.length() >= 3 && phrase.length() <= 90) {
                phrases.add(phrase);
            }
        }
        return new ArrayList<>(phrases).stream().limit(5).toList();
    }

    private String normalizeExactPhrase(String phrase) {
        String value = clean(phrase);
        value = value.replaceAll("(?iu)^(.+?\\d+(?:\\.\\d+)*)(?:是什么|是什麼|是什么模型|是什麼模型|具体参数|具體參數|参数|參數|模型|相比|比较|比較|优缺点|優缺點).*$", "$1");
        value = value.replaceAll("(?iu)^(.+?\\d+(?:\\.\\d+)*)([\\u4e00-\\u9fa5].*)$", "$1");
        return clean(value);
    }

    public List<String> extractKeywords(String question) {
        String original = clean(question == null ? "" : question);
        String normalized = clean(original.replaceAll("[\\p{Punct}\\p{P}]+", " "));
        Set<String> keywords = new LinkedHashSet<>();

        for (String phrase : extractExactPhrases(original, List.of())) {
            keywords.add(truncate(phrase, 80));
        }
        for (String token : normalized.split("\\s+")) {
            String lower = token.toLowerCase(Locale.ROOT);
            if (token.length() >= 2 && !isStopWord(lower)) {
                keywords.add(truncate(token, 60));
            }
            if (keywords.size() >= 6) {
                break;
            }
        }
        if (keywords.isEmpty() && !normalized.isBlank()) {
            keywords.add(truncate(normalized, 80));
        }
        return new ArrayList<>(keywords);
    }

    /**
     * Tavily API 搜索（专为 AI 应用设计的搜索 API）
     * 文档：https://docs.tavily.com/
     */
    private List<WebSearchResult> searchTavily(String query, int maxResults) {
        List<WebSearchResult> results = new ArrayList<>();
        HttpURLConnection connection = null;
        try {
            JsonObject body = new JsonObject();
            body.addProperty("api_key", ragConfig.getTavilyApiKey());
            body.addProperty("query", query);
            body.addProperty("max_results", Math.min(maxResults, 20));
            body.addProperty("search_depth", "advanced");
            body.addProperty("include_answer", false);
            body.addProperty("include_raw_content", true);

            connection = (HttpURLConnection) URI.create(TAVILY_URL).toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);

            try (OutputStream os = connection.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            int status = connection.getResponseCode();
            if (status != 200) {
                log.warn("Tavily API returned status {}: {}", status, readStream(connection.getErrorStream()));
                return results;
            }

            String response = readStream(connection.getInputStream());
            JsonObject root = JsonParser.parseString(response).getAsJsonObject();
            JsonArray tavilyResults = root.has("results") && root.get("results").isJsonArray()
                    ? root.getAsJsonArray("results")
                    : new JsonArray();

            for (JsonElement el : tavilyResults) {
                JsonObject item = el.getAsJsonObject();
                String title = getString(item, "title");
                String url = getString(item, "url");
                String content = getString(item, "content");
                String rawContent = getString(item, "raw_content");
                String evidence = rawContent.isBlank() ? content : rawContent;
                if (!evidence.isBlank()) {
                    content = clean(evidence);
                }
                double score = item.has("score") && item.get("score").isJsonPrimitive()
                        ? item.getAsJsonPrimitive("score").getAsDouble() : 0.0;

                if (url.isBlank()) continue;

                results.add(new WebSearchResult(
                        title, url,
                        truncate(content, 520),   // snippet
                        truncate(content, 3600),   // content（Tavily 已经过清理，直接用）
                        "tavily",
                        false,
                        score > 0.6,               // 高分结果标记为 exactMatch
                        "",
                        LocalDate.now().toString()
                ));
            }

            log.info("Tavily search for '{}' returned {} results", query, results.size());
        } catch (Exception e) {
            log.warn("Tavily search error: {}", e.getMessage());
        } finally {
            if (connection != null) connection.disconnect();
        }
        return results;
    }

    private String readStream(java.io.InputStream stream) {
        if (stream == null) return "";
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String getString(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : "";
    }

    private List<WebSearchResult> searchDuckDuckGo(String query, int limit) throws Exception {
        String url = "https://duckduckgo.com/html/?q=" + encode(query) + "&kl=wt-wt";
        Document doc = Jsoup.connect(url).userAgent(USER_AGENT).timeout(TIMEOUT_MS).get();
        List<WebSearchResult> results = new ArrayList<>();
        Elements items = doc.select(".result");
        for (Element item : items) {
            Element link = item.selectFirst("a.result__a");
            if (link == null) {
                continue;
            }
            String title = clean(link.text());
            String href = resolveDuckDuckGoUrl(link.hasAttr("href") ? link.attr("href") : link.absUrl("href"));
            String snippet = clean(textOf(item, ".result__snippet"));
            if (!title.isBlank() && !href.isBlank()) {
                results.add(new WebSearchResult(title, href, snippet, "", "DuckDuckGo", false));
            }
            if (results.size() >= limit) {
                break;
            }
        }
        return results;
    }

    private List<WebSearchResult> searchBing(String query, int limit) throws Exception {
        String url = "https://www.bing.com/search?q=" + encode(query) + "&setlang=zh-Hans";
        Document doc = Jsoup.connect(url).userAgent(USER_AGENT).timeout(TIMEOUT_MS).get();
        List<WebSearchResult> results = new ArrayList<>();
        Elements items = doc.select("li.b_algo");
        for (Element item : items) {
            Element link = item.selectFirst("h2 a");
            if (link == null) {
                continue;
            }
            String title = clean(link.text());
            String href = resolveBingUrl(link.absUrl("href"));
            String snippet = clean(textOf(item, ".b_caption p"));
            if (!title.isBlank() && !href.isBlank()) {
                results.add(new WebSearchResult(title, href, snippet, "", "Bing", false));
            }
            if (results.size() >= limit) {
                break;
            }
        }
        return results;
    }

    private String resolveBingUrl(String href) {
        if (href == null || href.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(href);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (!host.contains("bing.com") || uri.getRawQuery() == null) {
                return href;
            }
            Map<String, String> params = queryParams(uri.getRawQuery());
            String encoded = params.get("u");
            if (encoded == null || encoded.isBlank()) {
                return href;
            }
            String value = URLDecoder.decode(encoded, StandardCharsets.UTF_8);
            if (value.startsWith("a1") && value.length() > 2) {
                value = value.substring(2);
            }
            String padded = value + "=".repeat((4 - value.length() % 4) % 4);
            String decoded = new String(Base64.getUrlDecoder().decode(padded), StandardCharsets.UTF_8);
            return decoded.startsWith("http") ? decoded : href;
        } catch (Exception ignored) {
            return href;
        }
    }

    private Map<String, String> queryParams(String rawQuery) {
        Map<String, String> params = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return params;
        }
        for (String pair : rawQuery.split("&")) {
            int idx = pair.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            params.put(pair.substring(0, idx), pair.substring(idx + 1));
        }
        return params;
    }

    private List<WebSearchResult> searchBingNews(String query, int limit) throws Exception {
        String url = "https://www.bing.com/news/search?q=" + encode(query) + "&setlang=zh-Hans";
        Document doc = Jsoup.connect(url).userAgent(USER_AGENT).timeout(TIMEOUT_MS).get();
        List<WebSearchResult> results = new ArrayList<>();
        Elements items = doc.select(".news-card, .newsitem, .t_s");
        for (Element item : items) {
            Element link = item.selectFirst("a.title, .title a, a[href]");
            if (link == null) {
                continue;
            }
            String title = clean(link.text());
            String href = link.absUrl("href");
            if (isBingNewsNavigationUrl(href)) {
                continue;
            }
            String snippet = clean(firstNonBlank(textOf(item, ".snippet"), textOf(item, ".caption"), textOf(item, ".description")));
            String published = clean(firstNonBlank(textOf(item, ".source span"), textOf(item, ".time"), textOf(item, "span[aria-label]")));
            if (!title.isBlank() && !href.isBlank() && href.startsWith("http")) {
                results.add(new WebSearchResult(title, href, snippet, "", "Bing News", false, false, published, nowText()));
            }
            if (results.size() >= limit) {
                break;
            }
        }
        return results;
    }

    private boolean isBingNewsNavigationUrl(String href) {
        if (href == null || href.isBlank()) {
            return false;
        }
        String lower = href.toLowerCase(Locale.ROOT);
        return lower.contains("bing.com/news/search?q=site%3a")
                || lower.contains("bing.com/news/search?q=site:")
                || lower.contains("&form=nwbclm")
                || lower.contains("?form=nwbclm");
    }

    private List<WebSearchResult> enrichWithPageContent(List<WebSearchResult> results, int limit) {
        List<WebSearchResult> enriched = new ArrayList<>();
        int fetched = 0;
        for (WebSearchResult result : results) {
            if (!result.isFallback() && fetched < limit) {
                FetchedPage page = fetchReadableContent(result.getUrl());
                if (!page.content().isBlank()) {
                    enriched.add(result.withContent(page.content()).withPublishedAt(firstNonBlank(result.getPublishedAt(), page.publishedAt())));
                    fetched++;
                    continue;
                }
            }
            enriched.add(result);
        }
        return enriched;
    }

    private FetchedPage fetchReadableContent(String url) {
        if (url == null || url.isBlank() || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            return new FetchedPage("", "");
        }
        try {
            Document doc = Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .timeout(FETCH_TIMEOUT_MS)
                    .maxBodySize(1_800_000)
                    .ignoreHttpErrors(true)
                    .ignoreContentType(true)
                    .followRedirects(true)
                    .get();
            doc.select("script,style,noscript,svg,nav,footer,header,aside,form").remove();
            String title = clean(doc.title());
            String publishedAt = extractPublishedAt(doc);
            StringBuilder content = new StringBuilder();
            Elements paragraphs = doc.select("main p, article p, .content p, .post p, .article p, p");
            for (Element paragraph : paragraphs) {
                String text = clean(paragraph.text());
                if (text.length() < 35 || content.indexOf(text) >= 0) {
                    continue;
                }
                content.append(text).append("\n");
                if (content.length() >= 3600) {
                    break;
                }
            }
            String readable = clean((title.isBlank() ? "" : title + "\n")
                    + (publishedAt.isBlank() ? "" : "Published/updated: " + publishedAt + "\n")
                    + content);
            return new FetchedPage(truncate(readable, 3600), publishedAt);
        } catch (Exception e) {
            log.debug("Fetch search result content failed: {} -> {}", url, e.getMessage());
            return new FetchedPage("", "");
        }
    }

    private List<WebSearchResult> rankResults(List<WebSearchResult> results, List<String> exactPhrases, boolean temporal) {
        if (results == null || results.size() <= 1) {
            return results == null ? List.of() : results;
        }
        List<WebSearchResult> sorted = new ArrayList<>(results);
        sorted.sort(Comparator.comparingInt((WebSearchResult result) -> resultScore(result, exactPhrases, temporal)).reversed());
        return sorted;
    }

    private int resultScore(WebSearchResult result, List<String> exactPhrases, boolean temporal) {
        int score = exactMatchScore(result, exactPhrases);
        if (!result.getContent().isBlank()) {
            score += 12;
        }
        if (!result.getPublishedAt().isBlank()) {
            score += temporal ? 12 : 4;
        }
        String host = host(result.getUrl());
        if (host.endsWith(".edu") || host.endsWith(".gov") || host.contains("docs.") || host.contains("developer.") || host.contains("github.com")) {
            score += 4;
        }
        if (result.isFallback()) {
            score -= 100;
        }
        return score;
    }

    private int exactMatchScore(WebSearchResult result, List<String> exactPhrases) {
        if (exactPhrases == null || exactPhrases.isEmpty()) {
            return 0;
        }
        String title = normalizeForExactMatch(result.getTitle());
        String haystack = normalizeForExactMatch(result.getTitle() + " " + result.getSnippet() + " " + result.getContent());
        int score = 0;
        for (String phrase : exactPhrases) {
            String normalized = normalizeForExactMatch(phrase);
            if (!normalized.isBlank() && haystack.contains(normalized)) {
                score += 30;
                if (title.contains(normalized)) {
                    score += 20;
                }
            }
        }
        return score;
    }

    private List<WebSearchResult> markExactMatches(List<WebSearchResult> results, List<String> exactPhrases) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        boolean required = exactPhrases != null && !exactPhrases.isEmpty();
        List<WebSearchResult> marked = new ArrayList<>();
        for (WebSearchResult result : results) {
            marked.add(result.withExactMatch(!required || exactMatchScore(result, exactPhrases) > 0));
        }
        return marked;
    }

    private List<WebSearchResult> dedupeAndLimit(List<WebSearchResult> raw, int limit) {
        Map<String, WebSearchResult> byUrl = new LinkedHashMap<>();
        for (WebSearchResult result : raw) {
            if (result.getUrl() == null || result.getUrl().isBlank()) {
                continue;
            }
            byUrl.putIfAbsent(result.getUrl(), result);
            if (byUrl.size() >= limit) {
                break;
            }
        }
        return new ArrayList<>(byUrl.values());
    }

    private WebSearchResult fallbackResult(String query) {
        String url = "https://www.bing.com/search?q=" + encode(query);
        return new WebSearchResult(
                "Open live search results",
                url,
                "Automatic search returned no parseable results. Open this link to inspect live results manually.",
                "",
                "Search",
                true,
                false,
                "",
                nowText()
        );
    }

    private String resolveDuckDuckGoUrl(String href) {
        if (href == null || href.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(href.startsWith("//") ? "https:" + href : href);
            String rawQuery = uri.getRawQuery();
            if (rawQuery == null) {
                return href;
            }
            for (String param : rawQuery.split("&")) {
                int idx = param.indexOf('=');
                if (idx <= 0) {
                    continue;
                }
                if ("uddg".equals(param.substring(0, idx))) {
                    return URLDecoder.decode(param.substring(idx + 1), StandardCharsets.UTF_8);
                }
            }
        } catch (Exception ignored) {
            return href;
        }
        return href;
    }

    private String extractPublishedAt(Document doc) {
        String[] selectors = {
                "meta[property=article:published_time]",
                "meta[property=article:modified_time]",
                "meta[name=date]",
                "meta[name=pubdate]",
                "meta[name=lastmod]",
                "time[datetime]"
        };
        for (String selector : selectors) {
            Element element = doc.selectFirst(selector);
            if (element == null) {
                continue;
            }
            String value = element.hasAttr("content") ? element.attr("content") : element.attr("datetime");
            if (!value.isBlank()) {
                return truncate(clean(value), 100);
            }
        }
        return "";
    }

    private boolean isTemporalQuery(String value) {
        String text = value == null ? "" : value.toLowerCase(Locale.ROOT);
        int year = LocalDate.now().getYear();
        if (text.matches(".*(最新|近期|最近|现在|目前|今天|今年|实时|发布|参数|价格|版本|新闻|更新|现状|趋势).*")) {
            return true;
        }
        return text.contains("最新")
                || text.contains("近期")
                || text.contains("最近")
                || text.contains("现在")
                || text.contains("目前")
                || text.contains("今天")
                || text.contains("今年")
                || text.contains("实时")
                || text.contains("发布")
                || text.contains("参数")
                || text.contains("价格")
                || text.contains("current")
                || text.contains("latest")
                || text.contains("recent")
                || text.contains("today")
                || text.contains("release")
                || text.contains("pricing")
                || text.contains(String.valueOf(year));
    }

    private boolean isStopWord(String token) {
        return Set.of("the", "and", "for", "with", "from", "this", "that", "what", "how", "why", "are", "is")
                .contains(token);
    }

    private String textOf(Element element, String selector) {
        Element match = element.selectFirst(selector);
        return match == null ? "" : match.text();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String host(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return "";
        }
    }

    private String normalizeForExactMatch(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s_-]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String clean(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxLength);
    }

    private String nowText() {
        return LocalDate.now().toString();
    }

    private record FetchedPage(String content, String publishedAt) {
    }

    @Getter
    public static class SearchBundle {
        private final List<String> keywords;
        private final List<String> exactPhrases;
        private final List<WebSearchResult> results;

        public SearchBundle(List<String> keywords, List<String> exactPhrases, List<WebSearchResult> results) {
            this.keywords = keywords;
            this.exactPhrases = exactPhrases;
            this.results = results;
        }
    }

    @Getter
    public static class WebSearchResult {
        private final String title;
        private final String url;
        private final String snippet;
        private final String content;
        private final String engine;
        private final boolean fallback;
        private final boolean exactMatch;
        private final String publishedAt;
        private final String fetchedAt;

        public WebSearchResult(String title, String url, String snippet, String content, String engine, boolean fallback) {
            this(title, url, snippet, content, engine, fallback, true, "", LocalDate.now().toString());
        }

        public WebSearchResult(String title, String url, String snippet, String content, String engine, boolean fallback, boolean exactMatch, String publishedAt, String fetchedAt) {
            this.title = title;
            this.url = url;
            this.snippet = snippet;
            this.content = content;
            this.engine = engine;
            this.fallback = fallback;
            this.exactMatch = exactMatch;
            this.publishedAt = publishedAt == null ? "" : publishedAt;
            this.fetchedAt = fetchedAt == null ? "" : fetchedAt;
        }

        public WebSearchResult withContent(String content) {
            return new WebSearchResult(title, url, snippet, content, engine, fallback, exactMatch, publishedAt, fetchedAt);
        }

        public WebSearchResult withExactMatch(boolean exactMatch) {
            return new WebSearchResult(title, url, snippet, content, engine, fallback, exactMatch, publishedAt, fetchedAt);
        }

        public WebSearchResult withPublishedAt(String publishedAt) {
            return new WebSearchResult(title, url, snippet, content, engine, fallback, exactMatch, publishedAt, fetchedAt);
        }
    }
}
