package com.labex.labexagent.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HtmlToMarkdownConverterTest {

    @Test
    void convertsHeadingsAndParagraphs() {
        String html = """
                <html>
                <body>
                    <h1>Main Title</h1>
                    <p>This is a <strong>bold</strong> and <em>italic</em> paragraph.</p>
                    <h2>Subheading</h2>
                    <p>Second paragraph with <a href="https://example.com">link text</a>.</p>
                </body>
                </html>
                """;
        String markdown = HtmlToMarkdownConverter.convert(html);

        assertTrue(markdown.contains("# Main Title"));
        assertTrue(markdown.contains("## Subheading"));
        assertTrue(markdown.contains("**bold**"));
        assertTrue(markdown.contains("*italic*"));
        assertTrue(markdown.contains("[link text](https://example.com)"));
    }

    @Test
    void convertsFencedCodeBlocksAndInlineCode() {
        String html = """
                <div>
                    <p>Check the <code>Config</code> class.</p>
                    <pre><code class="language-java">public class Hello {
    public static void main(String[] args) {
        System.out.println("Hello");
    }
}</code></pre>
                </div>
                """;
        String markdown = HtmlToMarkdownConverter.convert(html);

        assertTrue(markdown.contains("`Config`"));
        assertTrue(markdown.contains("```java"));
        assertTrue(markdown.contains("public class Hello"));
        assertTrue(markdown.contains("System.out.println(\"Hello\");"));
    }

    @Test
    void convertsHtmlTablesToMarkdownTables() {
        String html = """
                <table>
                    <thead>
                        <tr><th>Method</th><th>Endpoint</th><th>Description</th></tr>
                    </thead>
                    <tbody>
                        <tr><td>GET</td><td>/api/users</td><td>List users</td></tr>
                        <tr><td>POST</td><td>/api/users</td><td>Create user</td></tr>
                    </tbody>
                </table>
                """;
        String markdown = HtmlToMarkdownConverter.convert(html);

        assertTrue(markdown.contains("| Method | Endpoint | Description |"));
        assertTrue(markdown.contains("| --- | --- | --- |"));
        assertTrue(markdown.contains("| GET | /api/users | List users |"));
        assertTrue(markdown.contains("| POST | /api/users | Create user |"));
    }

    @Test
    void convertsListsAndQuotes() {
        String html = """
                <ul>
                    <li>Feature A</li>
                    <li>Feature B</li>
                </ul>
                <ol>
                    <li>Step 1</li>
                    <li>Step 2</li>
                </ol>
                <blockquote>Important note for developers</blockquote>
                """;
        String markdown = HtmlToMarkdownConverter.convert(html);

        assertTrue(markdown.contains("- Feature A"));
        assertTrue(markdown.contains("- Feature B"));
        assertTrue(markdown.contains("1. Step 1"));
        assertTrue(markdown.contains("2. Step 2"));
        assertTrue(markdown.contains("> Important note for developers"));
    }

    @Test
    void stripsNoisyTags() {
        String html = """
                <nav><a href="/home">Home</a></nav>
                <script>console.log("noisy script");</script>
                <style>.body { color: red; }</style>
                <header>Header content</header>
                <main>
                    <h1>Real Content</h1>
                </main>
                <footer>Footer links</footer>
                """;
        String markdown = HtmlToMarkdownConverter.convert(html);

        assertTrue(markdown.contains("# Real Content"));
        assertFalse(markdown.contains("noisy script"));
        assertFalse(markdown.contains("color: red"));
        assertFalse(markdown.contains("Header content"));
        assertFalse(markdown.contains("Footer links"));
    }
}
