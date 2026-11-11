package io.livecut.web;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the same standalone validator that Maven executes before copying any resources. */
class WorkbenchDistValidatorTests {
    @TempDir static Path compilerOutput;
    @TempDir Path root;
    private static URLClassLoader loader;
    private static Method validator;
    @BeforeAll static void compileBuildTool() throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Packaging requires the documented JDK, not a JRE");
        assertEquals(0, compiler.run(null, null, null, "--release", "21", "-d", compilerOutput.toString(), "tools/buildvalidate/WorkbenchDistValidator.java"));
        loader = new URLClassLoader(new java.net.URL[]{compilerOutput.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
        validator = loader.loadClass("WorkbenchDistValidator").getMethod("validate", Path.class, Path.class, Path.class, Path.class, Path.class);
    }
    @AfterAll static void closeLoader() throws Exception { if (loader != null) loader.close(); }

    private Path project() throws Exception { return Files.createDirectories(root.resolve("backend-java")); }
    private Path distribution() throws Exception {
        Path dist = Files.createDirectories(root.resolve("frontend/dist"));
        write(dist, "index.html", "<!doctype html><html><head><link rel=\"icon\" href=\"/favicon.svg\"><link rel=\"stylesheet\" href=\"/assets/index-12345678.css\"></head><body><div id=\"app\"></div><script type=\"module\" src=\"/assets/index-12345678.js\"></script></body></html>");
        write(dist, "favicon.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>");
        write(dist, "assets/index-12345678.js", "new Worker(new URL(`/assets/fileHash.worker-12345678.js`,``+import.meta.url));");
        write(dist, "assets/index-12345678.css", "body{color:#222}");
        write(dist, "assets/fileHash.worker-12345678.js", "self.onmessage=()=>{};");
        return dist;
    }
    private static void write(Path dist, String key, String text) throws Exception { Path path = dist.resolve(key); Files.createDirectories(path.getParent()); Files.writeString(path, text, StandardCharsets.UTF_8); }
    private int validate(Path dist, Path build, Path project) throws Exception {
        try { return (Integer) validator.invoke(null, dist, build, project, build.resolve("classes"), build.resolve("test-classes")); }
        catch (InvocationTargetException failure) { if (failure.getCause() instanceof Exception exception) throw exception; throw failure; }
    }
    private void rejected(Path dist, Path build, Path project) { assertThrows(IllegalArgumentException.class, () -> validate(dist, build, project)); }

    @Test void acceptsTheWorkerAndAdditionalReachableChunksFontsAndImages() throws Exception {
        Path dist = distribution(), project = project(), build = root.resolve("bundle-output");
        write(dist, "assets/index-12345678.js", Files.readString(dist.resolve("assets/index-12345678.js")) + "import {x} from './chunk-abcdefgh.js'; import('./lazy-ABCDEFGH.js');");
        write(dist, "assets/chunk-abcdefgh.js", "export const x=1;");
        write(dist, "assets/lazy-ABCDEFGH.js", "export default 2;");
        write(dist, "assets/index-12345678.css", "@import './more-abcdefgh.css';body{background:url(./picture-abcdefgh.svg)}");
        write(dist, "assets/more-abcdefgh.css", "@font-face{src:url('./font-abcdefgh.woff2')}body{background:url(data:image/png;base64,AA==)}");
        write(dist, "assets/picture-abcdefgh.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>");
        write(dist, "assets/font-abcdefgh.woff2", "synthetic font bytes");
        assertEquals(10, validate(dist, build, project)); assertFalse(Files.exists(build));
    }
    @Test void rejectsMissingIndexFaviconCssAndWorker() throws Exception {
        Path project = project();
        for (String key : new String[]{"index.html", "favicon.svg", "assets/index-12345678.css", "assets/fileHash.worker-12345678.js"}) {
            Path dist = distribution(); byte[] original = Files.readAllBytes(dist.resolve(key)); Files.delete(dist.resolve(key));
            rejected(dist, root.resolve("output"), project); Files.write(dist.resolve(key), original);
        }
    }
    @Test void rejectsAnUnreferencedWorkerInsteadOfMerelyCountingItsFilename() throws Exception {
        Path dist = distribution(); write(dist, "assets/index-12345678.js", "console.log('no Worker');"); rejected(dist, root.resolve("output"), project());
    }
    @Test void rejectsMissingReferencedChunks() throws Exception {
        Path dist = distribution(); write(dist, "assets/index-12345678.js", Files.readString(dist.resolve("assets/index-12345678.js")) + "import('./missing-abcdefgh.js');"); rejected(dist, root.resolve("output"), project());
    }
    @Test void rejectsExternalHtmlModuleWorkerAndCssResources() throws Exception {
        Path project = project();
        String[] files = {"index.html", "assets/index-12345678.js", "assets/index-12345678.js", "assets/index-12345678.css"};
        String[] additions = {"<script src=\"https://invalid.example/app.js\"></script>", "import('https://invalid.example/a.js');", "new Worker('https://invalid.example/w.js');", "body{background:url(//invalid.example/image.png)}"};
        for (int index = 0; index < files.length; index++) {
            Path dist = distribution(); String original = Files.readString(dist.resolve(files[index])); write(dist, files[index], original + additions[index]);
            rejected(dist, root.resolve("output"), project); write(dist, files[index], original);
        }
    }
    @Test void rejectsSecretMapsDevSourcesAndApiTreesIncludingEmptyDirectories() throws Exception {
        Path project = project();
        for (String key : new String[]{".env", "assets/source.js.map", "src/main.ts", "api/agent-capabilities/evidence", "internal/index.html", "node_modules/package/index.js"}) {
            Path dist = distribution(); write(dist, key, "must not ship"); rejected(dist, root.resolve("output"), project); Files.delete(dist.resolve(key));
            // Each iteration owns a new distribution, so forbidden empty parents are also exercised below.
            root = Files.createDirectories(root.resolve("next")); project = project();
        }
        Path dist = distribution(); Files.createDirectories(dist.resolve("api")); rejected(dist, root.resolve("output"), project);
    }
    @Test void rejectsNonemptyOutputBeforeStaleStaticFilesCouldLeak() throws Exception {
        Path dist = distribution(), project = project(), build = root.resolve("output"); write(build, "classes/static/.env", "stale secret"); rejected(dist, build, project);
    }
    @Test void rejectsOrdinaryTargetSourceAndDistributionOutputAliases() throws Exception {
        Path dist = distribution(), project = project();
        for (Path build : new Path[]{project.resolve("target"), project.resolve("target/classes"), project, project.resolve("src/anything"), dist, dist.resolve("output"), dist.getParent()}) rejected(dist, build, project);
        assertThrows(InvocationTargetException.class, () -> validator.invoke(null, dist, root.resolve("output"), project, project.resolve("target/classes"), root.resolve("output/test-classes")));
    }
    @Test void rejectsSymlinkedDistributionMembersAndOutputParents() throws Exception {
        Path dist = distribution(), project = project(), external = root.resolve("external.js"); Files.writeString(external, "untrusted");
        Path member = dist.resolve("assets/fileHash.worker-12345678.js"); Files.delete(member); Files.createSymbolicLink(member, external); rejected(dist, root.resolve("output"), project); Files.delete(member); Files.writeString(member, "self.onmessage=()=>{};");
        Path alias = root.resolve("alias"); Files.createSymbolicLink(alias, project.resolve("target")); rejected(dist, alias.resolve("bundle"), project);
    }
    @Test void rejectsTraversalReferencesAndMalformedUtf8() throws Exception {
        Path dist = distribution(), project = project(); write(dist, "assets/index-12345678.js", "import('../outside-12345678.js');"); rejected(dist, root.resolve("output"), project);
        distribution(); Files.write(dist.resolve("index.html"), new byte[]{(byte)0xc3, (byte)0x28}); rejected(dist, root.resolve("output"), project);
    }
    @Test void supportsUnquotedHtmlCommentedImportsAndInlineCssWhenTheirResourcesExist() throws Exception {
        Path dist = distribution(), project = project();
        write(dist, "index.html", Files.readString(dist.resolve("index.html")) + "<script src=/assets/chunk-abcdefgh.js></script><style>.hero{background:url(/assets/picture.svg)}</style>");
        write(dist, "assets/index-12345678.js", Files.readString(dist.resolve("assets/index-12345678.js")) + "import(/* build comment */ './chunk-abcdefgh.js');");
        write(dist, "assets/chunk-abcdefgh.js", "export const value=1;"); write(dist, "assets/picture.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>");
        assertEquals(7, validate(dist, root.resolve("output"), project));
    }
    @Test void rejectsMissingAndExternalUnquotedHtmlCommentedImportsAndInlineCss() throws Exception {
        Path project = project();
        for (boolean external : new boolean[]{false, true}) {
            String url = external ? "https://invalid.example/missing-abcdefgh.js" : "/assets/missing-abcdefgh.js";
            for (int form = 0; form < 3; form++) {
                Path dist = distribution(); String file = form == 1 ? "assets/index-12345678.js" : "index.html";
                String addition = form == 0 ? "<script src=" + url + "></script>" : form == 1 ? "import(/* build comment */ \"" + url + "\");" : "<style>.hero{background:url(" + url + ")}</style>";
                write(dist, file, Files.readString(dist.resolve(file)) + addition); rejected(dist, root.resolve("output"), project);
            }
        }
    }
    @Test void rejectsUnsupportedDynamicImportsAndWorkerReferences() throws Exception {
        Path project = project();
        for (String addition : new String[]{"import(moduleName);", "import('./chunk-'+name+'.js');", "new Worker(workerName);", "new Worker(new URL(workerName,import.meta.url));", "new Worker(new URL('/assets/fileHash.worker-12345678.js','https://invalid.example'));", "new Worker('/assets/fileHash.worker-12345678.js');", "new Worker('./fileHash.worker-12345678.js');", "new Worker('/assets/fileHash.worker-12345678.js'+suffix);", "new Worker(new URL('./fileHash.worker-12345678.js',import.meta.url));", "new Worker(new URL('/assets/fileHash.worker-12345678.js',import.meta.url)+suffix);"}) {
            Path dist = distribution(); write(dist, "assets/index-12345678.js", Files.readString(dist.resolve("assets/index-12345678.js")) + addition); rejected(dist, root.resolve("output"), project);
        }
    }
}
