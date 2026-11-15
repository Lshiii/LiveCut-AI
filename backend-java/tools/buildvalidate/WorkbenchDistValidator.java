import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** JDK-only build input validation. It never reads environment files or starts the application. */
public final class WorkbenchDistValidator {
    private static final int MAX_FILES = 1024, MAX_FILE_BYTES = 16 * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;
    private static final Set<String> EXTENSIONS = Set.of("js", "css", "svg", "png", "jpg", "jpeg", "webp", "avif", "gif", "ico", "woff", "woff2", "ttf", "otf");
    private static final Pattern HASHED_JS = Pattern.compile("assets/(?:[A-Za-z0-9_.-]+/)*[A-Za-z0-9_.-]+-[A-Za-z0-9_-]{8,}\\.js");
    private static final Pattern HASHED_CSS = Pattern.compile("assets/(?:[A-Za-z0-9_.-]+/)*[A-Za-z0-9_.-]+-[A-Za-z0-9_-]{8,}\\.css");
    private static final Pattern HASH_WORKER = Pattern.compile("assets/(?:[A-Za-z0-9_.-]+/)*fileHash\\.worker-[A-Za-z0-9_-]{8,}\\.js");
    private static final String QUOTED = "(['\"`])([^'\"`\\r\\n]*)\\1";
    private static final String GAP = "(?:\\s|/\\*[\\s\\S]*?\\*/|//[^\\r\\n]*(?:\\r?\\n|$))*";
    private static final Pattern LITERAL = Pattern.compile(QUOTED);
    private static final Pattern HTML_REF = Pattern.compile("(?i)\\b(?:src|href)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+))");
    private static final Pattern HTML_REF_START = Pattern.compile("(?i)\\b(?:src|href)\\s*=");
    private static final Pattern JS_IMPORT = Pattern.compile("\\b(?:import" + GAP + "(?:\\(" + GAP + ")?|from" + GAP + ")" + QUOTED);
    private static final Pattern JS_DYNAMIC_IMPORT = Pattern.compile("\\bimport" + GAP + "\\(" + GAP);
    private static final Pattern JS_URL = Pattern.compile("\\bnew\\b" + GAP + "URL" + GAP + "\\(" + GAP + QUOTED);
    private static final Pattern JS_WORKER = Pattern.compile("\\bnew\\b" + GAP + "(?:Worker|SharedWorker)" + GAP + "\\(" + GAP);
    private static final Pattern NEW_URL = Pattern.compile("new\\b" + GAP + "URL" + GAP + "\\(" + GAP);
    private static final Pattern VITE_URL_BASE = Pattern.compile(GAP + "," + GAP + "(?:``" + GAP + "\\+" + GAP + ")?import" + GAP + "\\." + GAP + "meta" + GAP + "\\." + GAP + "url" + GAP + "\\)");
    private static final Pattern JS_GAP = Pattern.compile(GAP);
    private static final Pattern CSS_URL = Pattern.compile("(?i)url\\(\\s*(?:['\"]([^'\"]*)['\"]|([^\\s)'\"]+))\\s*\\)");
    private static final Pattern CSS_IMPORT = Pattern.compile("(?i)@import\\s*" + QUOTED);

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 5) throw invalid("Expected dist, isolated build, project, classes and test-classes paths");
        int count = validate(Path.of(arguments[0]), Path.of(arguments[1]), Path.of(arguments[2]), Path.of(arguments[3]), Path.of(arguments[4]));
        System.out.println("WORKBENCH_DIST_VALID files=" + count + "; no runtime was started");
    }

    public static int validate(Path distribution, Path buildDirectory, Path projectDirectory, Path classes, Path testClasses) throws IOException {
        Path project = projectDirectory.toAbsolutePath().normalize();
        Path dist = distribution.toAbsolutePath().normalize();
        Path build = buildDirectory.toAbsolutePath().normalize();
        Path defaultTarget = project.resolve("target"), resources = project.resolve("src/main/resources");
        noAliases(project); noAliases(dist); noAliases(build);
        if (!Files.isDirectory(dist, LinkOption.NOFOLLOW_LINKS)) throw invalid("Distribution directory is missing");
        if (overlaps(build, defaultTarget) || overlaps(build, resources) || overlaps(build, dist)
                || build.startsWith(project.resolve("src")) || build.startsWith(project.getParent().resolve("frontend"))) {
            throw invalid("Workbench output must be isolated from normal target, sources and distribution");
        }
        if (!classes.toAbsolutePath().normalize().equals(build.resolve("classes"))
                || !testClasses.toAbsolutePath().normalize().equals(build.resolve("test-classes"))) {
            throw invalid("Compiled output paths do not belong to the isolated workbench build");
        }
        if (Files.exists(build, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(build, LinkOption.NOFOLLOW_LINKS)) throw invalid("Build output is not a directory");
            try (var entries = Files.list(build)) {
                if (entries.findAny().isPresent()) throw invalid("Use a fresh workbench build directory; cleanup is disabled in this profile");
            }
        }

        Map<String, byte[]> files = new HashMap<>();
        long total = 0;
        try (var walk = Files.walk(dist, 10)) {
            var entries = walk.iterator(); int entryCount = 0;
            while (entries.hasNext()) {
                Path path = entries.next();
                if (++entryCount > 2048) throw invalid("Distribution exceeds entry bounds");
                noAliases(path);
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    if (!path.equals(dist)) validDirectory(dist.relativize(path).toString().replace('\\', '/'));
                    continue;
                }
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw invalid("Only ordinary distribution files are permitted");
                String key = dist.relativize(path).toString().replace('\\', '/');
                validMember(key);
                if (files.size() >= MAX_FILES || Files.size(path) > MAX_FILE_BYTES) throw invalid("Distribution exceeds file bounds");
                byte[] bytes;
                try (var input = Files.newInputStream(path)) { bytes = input.readNBytes(MAX_FILE_BYTES + 1); }
                if (bytes.length == 0 || bytes.length > MAX_FILE_BYTES || (total += bytes.length) > MAX_TOTAL_BYTES) throw invalid("Distribution exceeds byte bounds");
                files.put(key, bytes);
            }
        }
        if (!files.containsKey("index.html") || !files.containsKey("favicon.svg")) throw invalid("Distribution requires index.html and favicon.svg");
        if (files.keySet().stream().noneMatch(key -> HASHED_JS.matcher(key).matches())
                || files.keySet().stream().noneMatch(key -> HASHED_CSS.matcher(key).matches())
                || files.keySet().stream().noneMatch(key -> HASH_WORKER.matcher(key).matches())) {
            throw invalid("Distribution requires hashed JavaScript, CSS and the file-hash Worker");
        }

        Set<String> visited = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add("index.html");
        while (!pending.isEmpty()) {
            String key = pending.removeFirst();
            if (!visited.add(key)) continue;
            byte[] bytes = files.get(key);
            if (bytes == null) throw invalid("A referenced resource is missing");
            if (key.endsWith(".html") || key.endsWith(".svg")) {
                String html = utf8(bytes).replaceAll("(?s)<!--.*?-->", "");
                int references = 0; var urls = HTML_REF.matcher(html);
                while (urls.find()) { references++; reference(urls.group(1) != null ? urls.group(1) : urls.group(2) != null ? urls.group(2) : urls.group(3), key, files, pending, false); }
                int starts = 0; var attributes = HTML_REF_START.matcher(html); while (attributes.find()) starts++;
                if (starts != references || Pattern.compile("(?i)\\bsrcset\\s*=").matcher(html).find()) throw invalid("Unsupported HTML resource reference syntax");
                collectCss(html, key, files, pending);
            } else if (key.endsWith(".js")) {
                String javascript = utf8(bytes);
                boolean[] code = new JavascriptCode(javascript).positions();
                collectJavascript(JS_IMPORT, javascript, code, key, files, pending);
                collectJavascript(JS_URL, javascript, code, key, files, pending);
                validateJavascriptReferences(javascript, code, key, files, pending);
            } else if (key.endsWith(".css")) {
                collectCss(utf8(bytes), key, files, pending);
            }
        }
        if (!visited.equals(files.keySet())) throw invalid("Every packaged resource must belong to the index resource graph");
        return files.size();
    }

    private static void collect(Pattern pattern, String text, String owner, Map<String, byte[]> files, ArrayDeque<String> pending, boolean dataAllowed) {
        var references = pattern.matcher(text);
        while (references.find()) reference(references.group(2), owner, files, pending, dataAllowed);
    }

    private static void collectJavascript(Pattern pattern, String text, boolean[] code, String owner, Map<String, byte[]> files, ArrayDeque<String> pending) {
        var references = pattern.matcher(text);
        while (findCode(references, code)) reference(references.group(2), owner, files, pending, false);
    }

    private static boolean findCode(java.util.regex.Matcher matcher, boolean[] code) {
        boolean found = matcher.find();
        // A pseudo-match starting inside data can span a real following keyword.
        // Resume after its start, rather than consuming that real reference too.
        while (found && !code[matcher.start()]) found = matcher.find(matcher.start() + 1);
        return found;
    }

    private static void collectCss(String text, String owner, Map<String, byte[]> files, ArrayDeque<String> pending) {
        String css = text.replaceAll("(?s)/\\*.*?\\*/", " ");
        collect(CSS_IMPORT, css, owner, files, pending, false);
        var urls = CSS_URL.matcher(css);
        while (urls.find()) reference(urls.group(1) == null ? urls.group(2) : urls.group(1), owner, files, pending, true);
    }

    /** Supported Vite constant forms only; unknown dynamic imports/Worker inputs fail closed. */
    private static void validateJavascriptReferences(String javascript, boolean[] code, String owner, Map<String, byte[]> files, ArrayDeque<String> pending) {
        var urls = JS_URL.matcher(javascript);
        while (findCode(urls, code)) if (!VITE_URL_BASE.matcher(javascript).region(urls.end(), javascript.length()).lookingAt()) throw invalid("Build resource URL bases must be import.meta.url");
        var imports = JS_DYNAMIC_IMPORT.matcher(javascript);
        while (findCode(imports, code)) {
            var literal = LITERAL.matcher(javascript).region(imports.end(), javascript.length());
            if (!literal.lookingAt()) throw invalid("Dynamic module references must use supported constant Vite paths");
            int end = afterGap(javascript, literal.end());
            if (end == javascript.length() || javascript.charAt(end) != ')') throw invalid("Unsupported dynamic module reference syntax");
            reference(literal.group(2), owner, files, pending, false);
        }
        var workers = JS_WORKER.matcher(javascript);
        while (findCode(workers, code)) {
            int start = workers.end(); var nested = NEW_URL.matcher(javascript).region(start, javascript.length());
            if (!nested.lookingAt()) throw invalid("Only the Vite nested new URL Worker form is supported");
            start = nested.end();
            var literal = LITERAL.matcher(javascript).region(start, javascript.length());
            if (!literal.lookingAt()) throw invalid("Worker resources must use supported constant Vite paths");
            if (!literal.group(2).startsWith("/assets/")) throw invalid("Worker resources must use a root assets path");
            var base = VITE_URL_BASE.matcher(javascript).region(literal.end(), javascript.length());
            if (!base.lookingAt()) throw invalid("Worker URL bases must be import.meta.url");
            int end = afterGap(javascript, base.end());
            if (end == javascript.length() || javascript.charAt(end) != ')' && javascript.charAt(end) != ',') throw invalid("Worker URL expressions cannot be extended or concatenated");
            reference(literal.group(2), owner, files, pending, false);
        }
    }

    private static int afterGap(String text, int start) { var gap = JS_GAP.matcher(text).region(start, text.length()); gap.lookingAt(); return gap.end(); }

    /** Locate executable starts without changing the original literal resource arguments.
     * Template text is data, but its ${...} expressions are still inspected. */
    private static final class JavascriptCode {
        private final String text;
        private final boolean[] code;
        JavascriptCode(String text) { this.text = text; code = new boolean[text.length()]; }
        boolean[] positions() { scan(0, false); return code; }
        private int scan(int start, boolean expression) {
            ArrayDeque<Boolean> braces = new ArrayDeque<>(), parentheses = new ArrayDeque<>();
            boolean operand = true; String previous = "";
            for (int index = start; index < text.length();) {
                char value = text.charAt(index);
                if (Character.isWhitespace(value)) { index++; continue; }
                if (value == '\'' || value == '"') { index = quoted(index, value); operand = false; previous = "literal"; continue; }
                if (value == '`') { index = template(index); operand = false; previous = "literal"; continue; }
                if (value == '/' && index + 1 < text.length()) {
                    char next = text.charAt(index + 1);
                    if (next == '/') { index += 2; while (index < text.length() && text.charAt(index) != '\n' && text.charAt(index) != '\r') index++; continue; }
                    if (next == '*') { int end = text.indexOf("*/", index + 2); if (end < 0) throw invalid("Unterminated JavaScript comment"); index = end + 2; continue; }
                    if (operand) { index = regularExpression(index); operand = false; previous = "literal"; continue; }
                }
                if (Character.isJavaIdentifierStart(value) || value == '$') {
                    int end = index + 1; while (end < text.length() && (Character.isJavaIdentifierPart(text.charAt(end)) || text.charAt(end) == '$')) end++;
                    java.util.Arrays.fill(code, index, end, true); previous = text.substring(index, end);
                    operand = Set.of("return", "throw", "case", "delete", "void", "typeof", "instanceof", "in", "of", "yield", "await", "new", "else", "do").contains(previous);
                    index = end; continue;
                }
                if (Character.isDigit(value)) {
                    int end = index + 1; while (end < text.length() && (Character.isLetterOrDigit(text.charAt(end)) || text.charAt(end) == '.' || text.charAt(end) == '_')) end++;
                    java.util.Arrays.fill(code, index, end, true); index = end; operand = false; previous = "literal"; continue;
                }
                code[index] = true;
                if (value == '{') {
                    braces.push(!operand || Set.of("", ";", "{", "}", ")", "=>", "else", "do").contains(previous));
                    operand = true;
                } else if (value == '}') {
                    if (braces.isEmpty() && expression) return index + 1;
                    operand = !braces.isEmpty() && braces.pop();
                } else if (value == '(') {
                    parentheses.push(Set.of("if", "while", "for", "with", "switch", "catch").contains(previous)); operand = true;
                } else if (value == ')') { operand = !parentheses.isEmpty() && parentheses.pop(); }
                else if (value == ']') operand = false;
                else if (value == '.' ) operand = false;
                else if (index + 1 < text.length() && (value == '+' || value == '-') && text.charAt(index + 1) == value) {
                    code[++index] = true; previous = "increment"; index++; continue;
                } else if (value == '=' && index + 1 < text.length() && text.charAt(index + 1) == '>') {
                    code[++index] = true; previous = "=>"; operand = true; index++; continue;
                } else operand = true;
                previous = Character.toString(value); index++;
            }
            if (expression) throw invalid("Unterminated JavaScript template expression");
            return text.length();
        }
        private int quoted(int start, char quote) {
            for (int index = start + 1; index < text.length(); index++) {
                char value = text.charAt(index);
                if (value == '\\') index++;
                else if (value == quote) return index + 1;
            }
            throw invalid("Unterminated JavaScript string");
        }
        private int template(int start) {
            for (int index = start + 1; index < text.length();) {
                char value = text.charAt(index);
                if (value == '\\') index += 2;
                else if (value == '`') return index + 1;
                else if (value == '$' && index + 1 < text.length() && text.charAt(index + 1) == '{') index = scan(index + 2, true);
                else index++;
            }
            throw invalid("Unterminated JavaScript template");
        }
        private int regularExpression(int start) {
            boolean characterClass = false;
            for (int index = start + 1; index < text.length(); index++) {
                char value = text.charAt(index);
                if (value == '\\') index++;
                else if (value == '[') characterClass = true;
                else if (value == ']') characterClass = false;
                else if (value == '/' && !characterClass) {
                    index++; while (index < text.length() && Character.isJavaIdentifierPart(text.charAt(index))) index++;
                    return index;
                } else if (value == '\n' || value == '\r') break;
            }
            throw invalid("Unsupported JavaScript regular expression syntax");
        }
    }

    private static void reference(String value, String owner, Map<String, byte[]> files, ArrayDeque<String> pending, boolean dataAllowed) {
        if (dataAllowed && (value.startsWith("data:") || value.startsWith("#"))) return;
        if (value.isEmpty() || value.contains("${") || value.contains("\\") || value.contains("%") || value.contains("?")
                || value.contains(":") || value.startsWith("//") || value.contains("#")) throw invalid("Resource references must be literal local build paths");
        String key;
        if (value.startsWith("/")) key = value.substring(1);
        else {
            int slash = owner.lastIndexOf('/');
            key = (slash < 0 ? "" : owner.substring(0, slash + 1)) + value;
            while (key.contains("/./")) key = key.replace("/./", "/");
            if (key.startsWith("./")) key = key.substring(2);
        }
        validMember(key);
        if (!files.containsKey(key)) throw invalid("A referenced resource is missing");
        pending.addLast(key);
    }

    private static void validMember(String key) {
        if (key.equals("index.html") || key.equals("favicon.svg")) return;
        if (!key.startsWith("assets/") || key.length() > 512 || key.split("/").length > 9) throw invalid("Only index, favicon and the assets tree may be packaged");
        for (String part : key.split("/", -1)) {
            if (!part.matches("[A-Za-z0-9][A-Za-z0-9_.-]*") || part.equals("..") || part.contains("..")
                    || Set.of("api", "internal", "src", "node_modules").contains(part.toLowerCase(Locale.ROOT))) throw invalid("Unsafe resource path");
        }
        int dot = key.lastIndexOf('.');
        if (dot < 0 || !EXTENSIONS.contains(key.substring(dot + 1))) throw invalid("Unsupported distribution file type");
        if (key.endsWith(".js") && !HASHED_JS.matcher(key).matches() || key.endsWith(".css") && !HASHED_CSS.matcher(key).matches()) throw invalid("JavaScript and CSS must have build hash names");
    }

    private static void validDirectory(String key) {
        if ((!key.equals("assets") && !key.startsWith("assets/")) || key.length() > 512 || key.split("/").length > 8) throw invalid("Only the bounded assets directory may be packaged");
        for (String part : key.split("/", -1)) {
            if (!part.matches("[A-Za-z0-9][A-Za-z0-9_.-]*") || part.contains("..")
                    || Set.of("api", "internal", "src", "node_modules").contains(part.toLowerCase(Locale.ROOT))) throw invalid("Unsafe resource directory");
        }
    }

    private static String utf8(byte[] bytes) {
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException invalidText) { throw invalid("Text resources must be valid UTF-8"); }
    }

    private static void noAliases(Path path) throws IOException {
        Path current = path.getRoot();
        for (Path part : path) {
            current = current.resolve(part);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) break;
            if (Files.isSymbolicLink(current) || !current.toRealPath().equals(current.toAbsolutePath().normalize())) throw invalid("Linked or aliased input/output paths are not permitted");
        }
    }
    private static boolean overlaps(Path left, Path right) { return left.startsWith(right) || right.startsWith(left); }
    private static IllegalArgumentException invalid(String detail) { return new IllegalArgumentException("WORKBENCH_DIST_INVALID: " + detail); }
}
