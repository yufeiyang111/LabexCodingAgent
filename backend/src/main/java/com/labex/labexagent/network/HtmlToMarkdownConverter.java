package com.labex.labexagent.network;

import java.util.ArrayList;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;

/**
 * 将 HTML 转换为结构清晰、易于 LLM 阅读的标准 Markdown。
 * 对齐 OpenCode Turndown 规范，保留标题、代码块、表格、列表、引用和加粗排版。
 */
public final class HtmlToMarkdownConverter {

    private HtmlToMarkdownConverter() {}

    public static String convert(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        Document doc = Jsoup.parse(html);
        // 移除无关的噪声标签
        doc.select("script, style, noscript, svg, nav, footer, header, aside, form, iframe, button, input, select, textarea, meta, link")
                .remove();

        Element body = doc.body();
        if (body == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        convertNode(body, sb, 0);
        return cleanMarkdown(sb.toString());
    }

    private static void convertNode(Node node, StringBuilder sb, int listDepth) {
        if (node instanceof TextNode textNode) {
            String text = textNode.text();
            if (!text.isBlank()) {
                sb.append(text);
            } else if (text.contains(" ") && !endsWithWhitespace(sb)) {
                sb.append(" ");
            }
            return;
        }

        if (!(node instanceof Element element)) {
            return;
        }

        String tagName = element.tagName().toLowerCase();

        switch (tagName) {
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                ensureNewline(sb);
                int level = tagName.charAt(1) - '0';
                sb.append("#".repeat(level)).append(" ");
                for (Node child : element.childNodes()) {
                    convertNode(child, sb, listDepth);
                }
                sb.append("\n\n");
            }
            case "p", "div", "article", "section", "main" -> {
                ensureNewline(sb);
                for (Node child : element.childNodes()) {
                    convertNode(child, sb, listDepth);
                }
                sb.append("\n\n");
            }
            case "pre" -> {
                ensureNewline(sb);
                Element codeEl = element.selectFirst("code");
                String language = "";
                if (codeEl != null) {
                    for (String cls : codeEl.classNames()) {
                        if (cls.startsWith("language-") || cls.startsWith("lang-")) {
                            language = cls.replaceFirst("^(language|lang)-", "");
                            break;
                        }
                    }
                }
                String codeContent = element.wholeText().trim();
                sb.append("```").append(language).append("\n");
                sb.append(codeContent).append("\n");
                sb.append("```\n\n");
            }
            case "code" -> {
                // 如果父节点不是 pre，则是行内 code
                if (element.parent() != null && "pre".equalsIgnoreCase(element.parent().tagName())) {
                    for (Node child : element.childNodes()) {
                        convertNode(child, sb, listDepth);
                    }
                } else {
                    String code = element.text();
                    sb.append("`").append(code).append("`");
                }
            }
            case "strong", "b" -> {
                sb.append("**");
                for (Node child : element.childNodes()) {
                    convertNode(child, sb, listDepth);
                }
                sb.append("**");
            }
            case "em", "i" -> {
                sb.append("*");
                for (Node child : element.childNodes()) {
                    convertNode(child, sb, listDepth);
                }
                sb.append("*");
            }
            case "a" -> {
                String href = element.attr("href").trim();
                String text = element.text().trim();
                if (!href.isBlank() && !text.isBlank() && !href.startsWith("javascript:")) {
                    sb.append("[").append(text).append("](").append(href).append(")");
                } else if (!text.isBlank()) {
                    sb.append(text);
                }
            }
            case "blockquote" -> {
                ensureNewline(sb);
                StringBuilder quoteContent = new StringBuilder();
                for (Node child : element.childNodes()) {
                    convertNode(child, quoteContent, listDepth);
                }
                String[] lines = quoteContent.toString().trim().split("\n");
                for (String line : lines) {
                    if (!line.isBlank()) {
                        sb.append("> ").append(line.trim()).append("\n");
                    }
                }
                sb.append("\n");
            }
            case "ul" -> {
                ensureNewline(sb);
                for (Element li : element.children()) {
                    if ("li".equalsIgnoreCase(li.tagName())) {
                        sb.append("  ".repeat(listDepth)).append("- ");
                        StringBuilder liContent = new StringBuilder();
                        for (Node child : li.childNodes()) {
                            convertNode(child, liContent, listDepth + 1);
                        }
                        sb.append(liContent.toString().trim()).append("\n");
                    }
                }
                sb.append("\n");
            }
            case "ol" -> {
                ensureNewline(sb);
                int index = 1;
                for (Element li : element.children()) {
                    if ("li".equalsIgnoreCase(li.tagName())) {
                        sb.append("  ".repeat(listDepth)).append(index++).append(". ");
                        StringBuilder liContent = new StringBuilder();
                        for (Node child : li.childNodes()) {
                            convertNode(child, liContent, listDepth + 1);
                        }
                        sb.append(liContent.toString().trim()).append("\n");
                    }
                }
                sb.append("\n");
            }
            case "table" -> {
                ensureNewline(sb);
                convertTable(element, sb);
                sb.append("\n");
            }
            case "hr" -> {
                ensureNewline(sb);
                sb.append("---\n\n");
            }
            case "br" -> sb.append("\n");
            default -> {
                for (Node child : element.childNodes()) {
                    convertNode(child, sb, listDepth);
                }
            }
        }
    }

    private static void convertTable(Element table, StringBuilder sb) {
        Elements rows = table.select("tr");
        if (rows.isEmpty()) {
            return;
        }

        List<List<String>> tableData = new ArrayList<>();
        int maxCols = 0;

        for (Element row : rows) {
            List<String> rowData = new ArrayList<>();
            Elements cells = row.select("th, td");
            for (Element cell : cells) {
                rowData.add(cell.text().trim().replace("|", "\\|"));
            }
            if (!rowData.isEmpty()) {
                maxCols = Math.max(maxCols, rowData.size());
                tableData.add(rowData);
            }
        }

        if (tableData.isEmpty() || maxCols == 0) {
            return;
        }

        // Header row
        List<String> header = tableData.get(0);
        sb.append("|");
        for (int i = 0; i < maxCols; i++) {
            String val = i < header.size() ? header.get(i) : "";
            sb.append(" ").append(val).append(" |");
        }
        sb.append("\n|");

        // Separator row
        for (int i = 0; i < maxCols; i++) {
            sb.append(" --- |");
        }
        sb.append("\n");

        // Body rows
        for (int r = 1; r < tableData.size(); r++) {
            List<String> row = tableData.get(r);
            sb.append("|");
            for (int i = 0; i < maxCols; i++) {
                String val = i < row.size() ? row.get(i) : "";
                sb.append(" ").append(val).append(" |");
            }
            sb.append("\n");
        }
    }

    private static void ensureNewline(StringBuilder sb) {
        if (!sb.isEmpty() && !endsWithNewline(sb)) {
            sb.append("\n");
        }
    }

    private static boolean endsWithNewline(StringBuilder sb) {
        return sb.charAt(sb.length() - 1) == '\n';
    }

    private static boolean endsWithWhitespace(StringBuilder sb) {
        if (sb.isEmpty()) return true;
        char last = sb.charAt(sb.length() - 1);
        return Character.isWhitespace(last);
    }

    private static String cleanMarkdown(String text) {
        return text.replaceAll("\n{3,}", "\n\n").trim();
    }
}
