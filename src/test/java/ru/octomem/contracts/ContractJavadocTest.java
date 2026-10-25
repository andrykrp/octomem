package ru.octomem.contracts;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.doctree.ParamTree;
import com.sun.source.doctree.ReturnTree;
import com.sun.source.doctree.ThrowsTree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePathScanner;
import java.nio.file.Path;
import java.util.List;
import javax.lang.model.element.Modifier;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

/** Structural Javadoc checks; semantic promises still require independent review/runtime tests. */
class ContractJavadocTest {
    private static final List<String> TYPES = List.of(
            "engine/CacheEngine", "engine/KeySequence", "engine/KeyVisitor", "engine/RetrievalSnapshot",
            "engine/GetResult", "engine/CounterResult", "engine/StoreResult", "engine/CacheCapacityException",
            "storage/Item", "storage/ItemLease", "storage/MemoryAllocator", "storage/SlabHandle",
            "stats/SlabClassStats", "protocol/Command", "protocol/CommandType", "protocol/Response",
            "protocol/ResponseKind", "protocol/ProtocolDecoder", "protocol/ProtocolEncoder",
            "protocol/ResponseWriter", "protocol/WriteStatus", "protocol/ChunkSink", "protocol/ProtocolException");

    @Test
    void publicContractDeclarationsHaveActualJavadocNotOrdinaryComments() throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Tests require the primary full OpenJDK 25");
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            var files = manager.getJavaFileObjectsFromPaths(TYPES.stream()
                    .map(name -> Path.of("src/main/java/ru/octomem", name + ".java")).toList());
            JavacTask task = (JavacTask) compiler.getTask(null, manager, null, List.of("-proc:none"), null, files);
            DocTrees docs = DocTrees.instance(task);
            int[] declarations = {0};
            for (CompilationUnitTree unit : task.parse()) {
                new TreePathScanner<Void, Boolean>() {
                    private void documented() {
                        var comment = docs.getDocCommentTree(getCurrentPath());
                        assertNotNull(comment, unit.getSourceFile().getName() + ": " + getCurrentPath().getLeaf());
                        assertFalse(comment.getFullBody().isEmpty(), "Javadoc must explain its contract");
                        declarations[0]++;
                    }
                    @Override
                    public Void visitClass(ClassTree node, Boolean inInterface) {
                        if (node.getModifiers().getFlags().contains(Modifier.PUBLIC) || Boolean.TRUE.equals(inInterface)) {
                            documented();
                        }
                        return super.visitClass(node, node.getKind() == com.sun.source.tree.Tree.Kind.INTERFACE);
                    }
                    @Override
                    public Void visitMethod(MethodTree node, Boolean inInterface) {
                        if (Boolean.TRUE.equals(inInterface) || node.getModifiers().getFlags().contains(Modifier.PUBLIC)
                                || node.getModifiers().getFlags().contains(Modifier.PROTECTED)) {
                            documented();
                            var comment = docs.getDocCommentTree(getCurrentPath());
                            for (VariableTree parameter : node.getParameters()) {
                                assertTrue(comment.getBlockTags().stream().anyMatch(tag -> tag instanceof ParamTree p
                                        && p.getName().getName().contentEquals(parameter.getName())),
                                        node.getName() + " needs actual @param " + parameter.getName());
                            }
                            if (node.getReturnType() != null && !node.getReturnType().toString().equals("void")) {
                                assertTrue(comment.getBlockTags().stream().anyMatch(ReturnTree.class::isInstance),
                                        node.getName() + " needs actual @return");
                            }
                            for (var thrown : node.getThrows()) {
                                assertTrue(comment.getBlockTags().stream().anyMatch(tag -> tag instanceof ThrowsTree t
                                        && t.getExceptionName().toString().equals(thrown.toString())),
                                        node.getName() + " needs actual @throws " + thrown);
                            }
                        }
                        return null;
                    }
                    @Override
                    public Void visitVariable(VariableTree node, Boolean inInterface) {
                        if (Boolean.TRUE.equals(inInterface) || node.getModifiers().getFlags().contains(Modifier.PUBLIC)) {
                            documented();
                        }
                        return null;
                    }
                }.scan(unit, false);
            }
            assertTrue(declarations[0] >= 90, "all public contract declarations must be visited");
        }
    }
}
