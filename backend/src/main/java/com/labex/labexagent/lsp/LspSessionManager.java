package com.labex.labexagent.lsp;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.MarkedString;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class LspSessionManager {
    private static final Logger log = LoggerFactory.getLogger(LspSessionManager.class);
    private static final int START_TIMEOUT_SECONDS = 12;
    private static final int REQUEST_TIMEOUT_SECONDS = 6;

    private final Map<String, ManagedSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> failedStarts = new ConcurrentHashMap<>();

    @Value("${labex-agent.lsp.commands.java:jdtls}")
    private String javaCommand;

    @Value("${labex-agent.lsp.commands.typescript:typescript-language-server --stdio}")
    private String typescriptCommand;

    @Value("${labex-agent.lsp.commands.vue:vue-language-server --stdio}")
    private String vueCommand;

    @Value("${labex-agent.lsp.commands.python:pyright-langserver --stdio}")
    private String pythonCommand;

    public Optional<String> status(Path workspaceRoot, Path file) {
        LanguageSpec spec = specFor(file);
        if (spec == null) {
            return Optional.of("unsupported file type: " + file.getFileName());
        }
        String key = key(workspaceRoot, spec.languageId());
        ManagedSession session = sessions.get(key);
        if (session != null && session.isAlive()) {
            return Optional.of("running " + spec.languageId() + " via `" + String.join(" ", spec.command()) + "`");
        }
        String failed = failedStarts.get(key);
        if (failed != null) {
            return Optional.of("unavailable " + spec.languageId() + ": " + failed);
        }
        return Optional.of("available command candidate: `" + String.join(" ", spec.command()) + "`; session starts on first LSP request");
    }

    public LspDiagnosticsResult diagnostics(Path workspaceRoot, Path file) {
        LanguageSpec spec = specFor(file);
        if (spec == null) {
            return LspDiagnosticsResult.unavailable("unsupported file type: " + file.getFileName());
        }
        try {
            ManagedSession session = getOrStart(workspaceRoot, spec);
            String uri = file.toUri().toString();
            session.openDocument(file, spec.languageId());
            List<Diagnostic> diagnostics = session.awaitDiagnostics(uri, 1500);
            return LspDiagnosticsResult.ok(spec.languageId(), diagnostics);
        } catch (Exception e) {
            return LspDiagnosticsResult.unavailable("real LSP unavailable for " + spec.languageId() + ": " + e.getMessage());
        }
    }

    public LspSymbolsResult documentSymbols(Path workspaceRoot, Path file) {
        LanguageSpec spec = specFor(file);
        if (spec == null) {
            return LspSymbolsResult.unavailable("unsupported file type: " + file.getFileName());
        }
        try {
            ManagedSession session = getOrStart(workspaceRoot, spec);
            session.openDocument(file, spec.languageId());
            DocumentSymbolParams params = new DocumentSymbolParams(new TextDocumentIdentifier(file.toUri().toString()));
            var raw = session.server().getTextDocumentService().documentSymbol(params).get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            List<String> lines = new ArrayList<>();
            for (var either : raw) {
                if (either.isLeft()) {
                    SymbolInformation info = either.getLeft();
                    lines.add("L" + (info.getLocation().getRange().getStart().getLine() + 1) + " " + info.getKind() + " " + info.getName());
                } else {
                    DocumentSymbol symbol = either.getRight();
                    flatten(symbol, lines, 0);
                }
            }
            return LspSymbolsResult.ok(spec.languageId(), lines);
        } catch (Exception e) {
            return LspSymbolsResult.unavailable("real LSP symbols unavailable for " + spec.languageId() + ": " + e.getMessage());
        }
    }

    public LspLocationsResult definition(Path workspaceRoot, Path file, int zeroBasedLine, int zeroBasedCharacter) {
        LanguageSpec spec = specFor(file);
        if (spec == null) {
            return LspLocationsResult.unavailable("unsupported file type: " + file.getFileName());
        }
        try {
            ManagedSession session = getOrStart(workspaceRoot, spec);
            session.openDocument(file, spec.languageId());
            var params = new DefinitionParams(identifier(file), position(zeroBasedLine, zeroBasedCharacter));
            var raw = session.server().getTextDocumentService().definition(params).get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            List<String> locations = new ArrayList<>();
            if (raw != null) {
                if (raw.isLeft()) {
                    for (Location location : raw.getLeft()) {
                        locations.add(formatLocation(workspaceRoot, location));
                    }
                } else {
                    for (LocationLink link : raw.getRight()) {
                        locations.add(formatLocationLink(workspaceRoot, link));
                    }
                }
            }
            return LspLocationsResult.ok(spec.languageId(), locations);
        } catch (Exception e) {
            return LspLocationsResult.unavailable("real LSP definition unavailable for " + spec.languageId() + ": " + e.getMessage());
        }
    }

    public LspLocationsResult references(Path workspaceRoot, Path file, int zeroBasedLine, int zeroBasedCharacter, boolean includeDeclaration) {
        LanguageSpec spec = specFor(file);
        if (spec == null) {
            return LspLocationsResult.unavailable("unsupported file type: " + file.getFileName());
        }
        try {
            ManagedSession session = getOrStart(workspaceRoot, spec);
            session.openDocument(file, spec.languageId());
            var params = new ReferenceParams(identifier(file), position(zeroBasedLine, zeroBasedCharacter), new ReferenceContext(includeDeclaration));
            var raw = session.server().getTextDocumentService().references(params).get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            List<String> locations = new ArrayList<>();
            if (raw != null) {
                for (Location location : raw) {
                    locations.add(formatLocation(workspaceRoot, location));
                }
            }
            return LspLocationsResult.ok(spec.languageId(), locations);
        } catch (Exception e) {
            return LspLocationsResult.unavailable("real LSP references unavailable for " + spec.languageId() + ": " + e.getMessage());
        }
    }

    public LspHoverResult hover(Path workspaceRoot, Path file, int zeroBasedLine, int zeroBasedCharacter) {
        LanguageSpec spec = specFor(file);
        if (spec == null) {
            return LspHoverResult.unavailable("unsupported file type: " + file.getFileName());
        }
        try {
            ManagedSession session = getOrStart(workspaceRoot, spec);
            session.openDocument(file, spec.languageId());
            var params = new HoverParams(identifier(file), position(zeroBasedLine, zeroBasedCharacter));
            Hover hover = session.server().getTextDocumentService().hover(params).get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return LspHoverResult.ok(spec.languageId(), hoverText(hover));
        } catch (Exception e) {
            return LspHoverResult.unavailable("real LSP hover unavailable for " + spec.languageId() + ": " + e.getMessage());
        }
    }

    private ManagedSession getOrStart(Path workspaceRoot, LanguageSpec spec) throws Exception {
        Path root = workspaceRoot.toAbsolutePath().normalize();
        String key = key(root, spec.languageId());
        ManagedSession existing = sessions.get(key);
        if (existing != null && existing.isAlive()) {
            return existing;
        }
        String failed = failedStarts.get(key);
        if (failed != null) {
            throw new IllegalStateException(failed);
        }
        try {
            ManagedSession created = start(root, spec);
            sessions.put(key, created);
            return created;
        } catch (Exception e) {
            failedStarts.put(key, e.getMessage());
            throw e;
        }
    }

    private ManagedSession start(Path root, LanguageSpec spec) throws Exception {
        List<String> configuredCommand = commandForRoot(root, spec);
        List<String> command = processCommand(configuredCommand);
        if (command.isEmpty()) {
            throw new IllegalStateException("language server command is empty");
        }
        Process process = new ProcessBuilder(command)
                .directory(root.toFile())
                .start();
        LspClient client = new LspClient();
        InputStream in = process.getInputStream();
        OutputStream out = process.getOutputStream();
        Launcher<LanguageServer> launcher = LSPLauncher.createClientLauncher(client, in, out);
        Future<?> listening = launcher.startListening();
        LanguageServer server = launcher.getRemoteProxy();

        InitializeParams init = new InitializeParams();
        init.setProcessId((int) ProcessHandle.current().pid());
        init.setRootUri(root.toUri().toString());
        server.initialize(init).get(START_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        server.initialized(new InitializedParams());
        return new ManagedSession(spec.languageId(), configuredCommand, process, server, client, listening);
    }

    private List<String> commandForRoot(Path root, LanguageSpec spec) throws Exception {
        List<String> command = new ArrayList<>(spec.command());
        if ("java".equals(spec.languageId()) && command.stream().noneMatch("-data"::equalsIgnoreCase)) {
            Path dataDir = root.resolve(".labexagent").resolve("jdtls-workspace").toAbsolutePath().normalize();
            Files.createDirectories(dataDir);
            command.add("-data");
            command.add(dataDir.toString());
        }
        return command;
    }

    private LanguageSpec specFor(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".java")) {
            return new LanguageSpec("java", configuredCommand("LABEX_LSP_JAVA_CMD", javaCommand));
        }
        if (name.endsWith(".vue")) {
            return new LanguageSpec("vue", configuredCommand("LABEX_LSP_VUE_CMD", vueCommand));
        }
        if (name.endsWith(".ts") || name.endsWith(".tsx") || name.endsWith(".js") || name.endsWith(".jsx")) {
            return new LanguageSpec("typescript", configuredCommand("LABEX_LSP_TS_CMD", typescriptCommand));
        }
        if (name.endsWith(".py")) {
            return new LanguageSpec("python", configuredCommand("LABEX_LSP_PY_CMD", pythonCommand));
        }
        return null;
    }

    private List<String> configuredCommand(String envName, String fallback) {
        String raw = System.getenv(envName);
        if (raw == null || raw.isBlank()) {
            raw = System.getProperty(envName.toLowerCase(Locale.ROOT).replace('_', '.'), fallback);
        }
        return splitCommand(raw);
    }

    private List<String> splitCommand(String command) {
        if (command == null || command.isBlank()) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        char quote = 0;
        for (int i = 0; i < command.length(); i++) {
            char ch = command.charAt(i);
            if ((ch == '"' || ch == '\'') && (!quoted || quote == ch)) {
                quoted = !quoted;
                quote = quoted ? ch : 0;
                continue;
            }
            if (Character.isWhitespace(ch) && !quoted) {
                if (!current.isEmpty()) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(ch);
        }
        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        return parts;
    }

    private List<String> processCommand(List<String> command) {
        if (command == null || command.isEmpty()) {
            return List.of();
        }
        if (!isWindows()) {
            return command;
        }
        String executable = command.get(0).toLowerCase(Locale.ROOT);
        if (executable.endsWith(".exe")) {
            return command;
        }
        return List.of("cmd.exe", "/c", command.stream().map(this::quoteForCmd).collect(Collectors.joining(" ")));
    }

    private String quoteForCmd(String part) {
        if (part == null) {
            return "\"\"";
        }
        boolean needsQuote = part.isBlank() || part.chars().anyMatch(ch -> Character.isWhitespace(ch)
                || ch == '&' || ch == '(' || ch == ')' || ch == '[' || ch == ']' || ch == '{' || ch == '}'
                || ch == '^' || ch == '=' || ch == ';' || ch == '!' || ch == '\'' || ch == '+' || ch == ','
                || ch == '`' || ch == '~');
        String escaped = part.replace("\"", "\\\"");
        return needsQuote ? "\"" + escaped + "\"" : escaped;
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private String key(Path root, String languageId) {
        return root.toAbsolutePath().normalize() + "::" + languageId;
    }

    private void flatten(DocumentSymbol symbol, List<String> lines, int depth) {
        String prefix = "  ".repeat(Math.max(0, depth));
        lines.add(prefix + "L" + (symbol.getRange().getStart().getLine() + 1) + " " + symbol.getKind() + " " + symbol.getName());
        if (symbol.getChildren() != null) {
            for (DocumentSymbol child : symbol.getChildren()) {
                flatten(child, lines, depth + 1);
            }
        }
    }

    private TextDocumentIdentifier identifier(Path file) {
        return new TextDocumentIdentifier(file.toUri().toString());
    }

    private Position position(int zeroBasedLine, int zeroBasedCharacter) {
        return new Position(Math.max(0, zeroBasedLine), Math.max(0, zeroBasedCharacter));
    }

    private String formatLocation(Path root, Location location) {
        if (location == null) {
            return "";
        }
        return formatUriRange(root, location.getUri(), location.getRange());
    }

    private String formatLocationLink(Path root, LocationLink link) {
        if (link == null) {
            return "";
        }
        Range range = link.getTargetSelectionRange() == null ? link.getTargetRange() : link.getTargetSelectionRange();
        return formatUriRange(root, link.getTargetUri(), range);
    }

    private String formatUriRange(Path root, String uri, Range range) {
        String path = readablePath(root, uri);
        int line = range == null || range.getStart() == null ? 1 : range.getStart().getLine() + 1;
        int character = range == null || range.getStart() == null ? 1 : range.getStart().getCharacter() + 1;
        return path + ":" + line + ":" + character;
    }

    private String readablePath(Path root, String uri) {
        try {
            Path path = Path.of(URI.create(uri)).toAbsolutePath().normalize();
            Path normalizedRoot = root.toAbsolutePath().normalize();
            if (path.startsWith(normalizedRoot)) {
                return normalizedRoot.relativize(path).toString().replace('\\', '/');
            }
            return path.toString();
        } catch (Exception e) {
            return uri == null ? "" : uri;
        }
    }

    private String hoverText(Hover hover) {
        if (hover == null || hover.getContents() == null) {
            return "";
        }
        Either<List<Either<String, MarkedString>>, MarkupContent> contents = hover.getContents();
        if (contents.isRight()) {
            MarkupContent markup = contents.getRight();
            return markup == null || markup.getValue() == null ? "" : markup.getValue();
        }
        List<Either<String, MarkedString>> marked = contents.getLeft();
        if (marked == null || marked.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (Either<String, MarkedString> entry : marked) {
            if (entry == null) continue;
            if (entry.isLeft()) {
                parts.add(entry.getLeft());
            } else if (entry.getRight() != null && entry.getRight().getValue() != null) {
                MarkedString value = entry.getRight();
                if (value.getLanguage() == null || value.getLanguage().isBlank()) {
                    parts.add(value.getValue());
                } else {
                    parts.add("```" + value.getLanguage() + "\n" + value.getValue() + "\n```");
                }
            }
        }
        return String.join("\n\n", parts);
    }

    private record LanguageSpec(String languageId, List<String> command) {}

    public record LspDiagnosticsResult(boolean available, String languageId, String message, List<Diagnostic> diagnostics) {
        static LspDiagnosticsResult ok(String languageId, List<Diagnostic> diagnostics) {
            return new LspDiagnosticsResult(true, languageId, "", diagnostics == null ? List.of() : diagnostics);
        }

        static LspDiagnosticsResult unavailable(String message) {
            return new LspDiagnosticsResult(false, "", message, List.of());
        }
    }

    public record LspSymbolsResult(boolean available, String languageId, String message, List<String> symbols) {
        static LspSymbolsResult ok(String languageId, List<String> symbols) {
            return new LspSymbolsResult(true, languageId, "", symbols == null ? List.of() : symbols);
        }

        static LspSymbolsResult unavailable(String message) {
            return new LspSymbolsResult(false, "", message, List.of());
        }
    }

    public record LspLocationsResult(boolean available, String languageId, String message, List<String> locations) {
        static LspLocationsResult ok(String languageId, List<String> locations) {
            return new LspLocationsResult(true, languageId, "", locations == null ? List.of() : locations);
        }

        static LspLocationsResult unavailable(String message) {
            return new LspLocationsResult(false, "", message, List.of());
        }
    }

    public record LspHoverResult(boolean available, String languageId, String message, String content) {
        static LspHoverResult ok(String languageId, String content) {
            return new LspHoverResult(true, languageId, "", content == null ? "" : content);
        }

        static LspHoverResult unavailable(String message) {
            return new LspHoverResult(false, "", message, "");
        }
    }

    private static class ManagedSession {
        private final String languageId;
        private final List<String> command;
        private final Process process;
        private final LanguageServer server;
        private final LspClient client;
        private final Future<?> listening;
        private final Map<String, Integer> versions = new ConcurrentHashMap<>();

        ManagedSession(String languageId, List<String> command, Process process, LanguageServer server, LspClient client, Future<?> listening) {
            this.languageId = languageId;
            this.command = command;
            this.process = process;
            this.server = server;
            this.client = client;
            this.listening = listening;
        }

        boolean isAlive() {
            return process.isAlive() && !listening.isDone();
        }

        LanguageServer server() {
            return server;
        }

        void openDocument(Path file, String languageId) throws Exception {
            String uri = file.toUri().toString();
            String content = Files.readString(file, StandardCharsets.UTF_8);
            Integer currentVersion = versions.get(uri);
            if (currentVersion == null) {
                TextDocumentItem item = new TextDocumentItem(uri, languageId, 1, content);
                server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(item));
                versions.put(uri, 1);
                return;
            }
            int nextVersion = currentVersion + 1;
            VersionedTextDocumentIdentifier id = new VersionedTextDocumentIdentifier(uri, nextVersion);
            TextDocumentContentChangeEvent change = new TextDocumentContentChangeEvent(content);
            server.getTextDocumentService().didChange(new DidChangeTextDocumentParams(id, List.of(change)));
            versions.put(uri, nextVersion);
        }

        List<Diagnostic> awaitDiagnostics(String uri, long timeoutMillis) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (System.currentTimeMillis() < deadline) {
                List<Diagnostic> diagnostics = client.diagnostics(uri);
                if (diagnostics != null) return diagnostics;
                Thread.sleep(80);
            }
            List<Diagnostic> diagnostics = client.diagnostics(uri);
            return diagnostics == null ? List.of() : diagnostics;
        }
    }

    private static class LspClient implements LanguageClient {
        private final Map<String, List<Diagnostic>> diagnostics = new ConcurrentHashMap<>();

        @Override
        public void telemetryEvent(Object object) {
            // ignored
        }

        @Override
        public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
            this.diagnostics.put(diagnostics.getUri(), diagnostics.getDiagnostics());
        }

        @Override
        public void showMessage(MessageParams messageParams) {
            // ignored
        }

        @Override
        public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void logMessage(MessageParams message) {
            log.debug("LSP: {}", message.getMessage());
        }

        List<Diagnostic> diagnostics(String uri) {
            return diagnostics.get(uri);
        }
    }
}
