package ru.octomem.acceptance;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

/** Resolves JDK time calls through javac symbols, including static imports and method references. */
class ClockUsageTest {
    private static final Set<String> NOW_TYPES = Set.of("java.time.Instant", "java.time.LocalDate",
            "java.time.LocalDateTime", "java.time.LocalTime", "java.time.OffsetDateTime",
            "java.time.OffsetTime", "java.time.ZonedDateTime", "java.time.Year", "java.time.YearMonth",
            "java.time.MonthDay");

    @Test
    void productionUsesOnlyThePreciselyNamedSystemAdapter() throws Exception {
        Path root = Path.of("src/main/java").toAbsolutePath();
        List<Path> paths;
        try (var files = Files.walk(root)) {
            paths = files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
        assertFalse(paths.isEmpty(), "An empty source scan is not acceptance");
        List<JavaFileObject> sources = new ArrayList<>();
        for (Path path : paths) {
            sources.add(source(path.toUri(), Files.readString(path)));
        }
        URI adapter = root.resolve("ru/octomem/util/SystemClock.java").toUri();
        assertTrue(Files.isRegularFile(Path.of(adapter)), "Public SystemClock adapter must exist");
        assertEquals(List.of(), violations(sources, adapter));
    }

    @Test
    void detectorRejectsDirectImportedReferencedAndAlternativeTimeSources() throws Exception {
        String[] expressions = {"System.currentTimeMillis()", "java.lang.System.nanoTime()",
                "currentTimeMillis()", "((java.util.function.LongSupplier) System::nanoTime).getAsLong()",
                "java.time.Instant.now().getEpochSecond()", "java.time.LocalDate.now().toEpochDay()",
                "java.time.Clock.systemUTC().millis()", "new java.util.Date().getTime()",
                "java.time.InstantSource.system().millis()", "java.time.Clock.tickMillis(java.time.ZoneOffset.UTC).millis()",
                "java.util.Calendar.getInstance().getTimeInMillis()",
                "new java.util.GregorianCalendar().getTimeInMillis()"};
        for (String expression : expressions) {
            URI uri = URI.create("string:///ru/octomem/util/OtherClock.java");
            String body = "package ru.octomem.util; import static java.lang.System.currentTimeMillis; "
                    + "class OtherClock { long read() { return " + expression + "; } }";
            assertFalse(violations(List.of(source(uri, body)), null).isEmpty(), expression);
        }
        URI impostor = URI.create("string:///ru/octomem/engine/SystemClock.java");
        assertFalse(violations(List.of(source(impostor,
                "package ru.octomem.engine; class SystemClock { long read() { return System.nanoTime(); } }")),
                URI.create("string:///ru/octomem/util/SystemClock.java")).isEmpty());
    }

    @Test
    void detectorAcceptsExplicitTimeAndDoesNotConfuseCommentsStringsOrLocalMethods() throws Exception {
        URI uri = URI.create("string:///Probe.java");
        String body = "class Probe { long nanoTime() { return 3; } long read() { "
                + "// System.nanoTime() is forbidden only as executable code\n"
                + "String text = \"System.currentTimeMillis()\"; "
                + "return nanoTime() + new java.util.Date(0).getTime() "
                + "+ java.time.Instant.ofEpochSecond(2).getEpochSecond(); } }";
        assertEquals(List.of(), violations(List.of(source(uri, body)), null));
    }

    @Test
    void adapterExceptionDoesNotPermitOtherTimeApis() throws Exception {
        URI uri = URI.create("string:///ru/octomem/util/SystemClock.java");
        String prefix = "package ru.octomem.util; class SystemClock { long read() { return ";
        assertEquals(List.of(), violations(List.of(source(uri, prefix + "System.nanoTime(); } }")), uri));
        assertFalse(violations(List.of(source(uri, prefix + "java.time.Instant.now().getEpochSecond(); } }")), uri)
                .isEmpty());
    }

    private static List<String> violations(List<JavaFileObject> sources, URI adapter) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "A complete JDK is required for architecture acceptance");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var violations = new ArrayList<String>();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            JavacTask task = (JavacTask) compiler.getTask(null, manager, diagnostics,
                    List.of("--release", "25", "-proc:none"), null, sources);
            var units = task.parse();
            task.analyze();
            assertEquals(List.of(), diagnostics.getDiagnostics().stream()
                    .filter(d -> d.getKind() == Diagnostic.Kind.ERROR).map(Object::toString).toList());
            Trees trees = Trees.instance(task);
            for (var unit : units) {
                new TreePathScanner<Void, Void>() {
                    private void inspect() {
                        if (!(trees.getElement(getCurrentPath()) instanceof ExecutableElement method)) return;
                        String owner = ((TypeElement) method.getEnclosingElement()).getQualifiedName().toString();
                        String name = method.getSimpleName().toString();
                        boolean system = owner.equals("java.lang.System")
                                && Set.of("currentTimeMillis", "nanoTime").contains(name);
                        boolean implicit = (NOW_TYPES.contains(owner) && name.equals("now")
                                && method.getParameters().stream().noneMatch(p -> p.asType().toString().equals("java.time.Clock")))
                                || (owner.equals("java.time.Clock") && (name.startsWith("system")
                                    || Set.of("tickMillis", "tickSeconds", "tickMinutes").contains(name)))
                                || (owner.equals("java.time.InstantSource") && name.equals("system"))
                                || (owner.equals("java.util.Calendar") && name.equals("getInstance"))
                                || (owner.equals("java.util.Date") && name.equals("<init>") && method.getParameters().isEmpty())
                                || (owner.equals("java.util.GregorianCalendar") && name.equals("<init>")
                                    && method.getParameters().size() <= 2);
                        boolean permitted = system && unit.getSourceFile().toUri().equals(adapter);
                        if ((system || implicit) && !permitted) {
                            violations.add(unit.getSourceFile().getName() + ": " + owner + "." + name);
                        }
                    }

                    @Override
                    public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                        inspect();
                        return super.visitMethodInvocation(node, unused);
                    }

                    @Override
                    public Void visitMemberReference(MemberReferenceTree node, Void unused) {
                        inspect();
                        return super.visitMemberReference(node, unused);
                    }

                    @Override
                    public Void visitNewClass(NewClassTree node, Void unused) {
                        inspect();
                        return super.visitNewClass(node, unused);
                    }
                }.scan(unit, null);
            }
        }
        return violations;
    }

    private static JavaFileObject source(URI uri, String text) {
        return new SimpleJavaFileObject(uri, JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) { return text; }
        };
    }
}
