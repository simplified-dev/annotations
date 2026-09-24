package dev.simplified.classbuilder.editor;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.application.AccessToken;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import dev.simplified.testutil.JSvgErrorSuppressor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The members the editor synthesises for a target are kept across reads so the
 * platform's idempotence check sees the same instances, and are synthesised
 * again when an edit changes anything the synthesis reads.
 *
 * <p>Each edit case highlights the file, edits the document through a write
 * command and a PSI commit, and highlights again - the sequence an author
 * typing into an open editor produces, where the target's class survives the
 * edit as the same PSI instance. Opening the edited text fresh was always
 * correct; only the edit in place kept the members built for the text before
 * it.
 */
public class SynthesizedMembersCacheTest extends LightJavaCodeInsightFixtureTestCase {

    private AccessToken jsvgSuppressor;

    @Override
    protected @NotNull LightProjectDescriptor getProjectDescriptor() {
        return JAVA_17;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        jsvgSuppressor = JSvgErrorSuppressor.install();
        myFixture.addFileToProject("dev/simplified/annotations/ClassBuilder.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS)
            @Target({ElementType.TYPE, ElementType.CONSTRUCTOR, ElementType.METHOD})
            public @interface ClassBuilder {
                BuilderNames builder() default @BuilderNames;
                NamingStyle style() default NamingStyle.SIMPLIFIED;
                SetterNames setters() default @SetterNames;
                String factoryMethod() default "";
                boolean retainInit() default true;
                boolean generateCopyConstructor() default true;
                AccessLevel access() default AccessLevel.PUBLIC;
                AccessLevel constructorAccess() default AccessLevel.PACKAGE;
                AccessLevel builderConstructorAccess() default AccessLevel.PACKAGE;
                String[] exclude() default {};
            }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/NamingStyle.java",
            """
            package dev.simplified.annotations;
            public enum NamingStyle { SIMPLIFIED, LOMBOK, BEAN, FLUENT }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/SetterNames.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS)
            @Target({ElementType.FIELD, ElementType.PARAMETER})
            public @interface SetterNames {
                String INHERIT = "";
                String NONE = "-";
                String set() default INHERIT;
                String flag() default INHERIT;
                String add() default INHERIT;
                String put() default INHERIT;
                String compute() default INHERIT;
                String clear() default INHERIT;
                String remove() default INHERIT;
            }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/BuilderNames.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target({})
            public @interface BuilderNames {
                String INHERIT = "";
                String NONE = "-";
                String type() default INHERIT;
                String builder() default INHERIT;
                String build() default INHERIT;
                String from() default INHERIT;
                String toBuilder() default INHERIT;
            }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/AccessLevel.java",
            """
            package dev.simplified.annotations;
            public enum AccessLevel { PUBLIC, PROTECTED, PACKAGE, PRIVATE, NONE }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/BuilderSeed.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS)
            @Target(ElementType.PARAMETER)
            public @interface BuilderSeed { }
            """);
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            if (jsvgSuppressor != null) jsvgSuppressor.close();
        } finally {
            super.tearDown();
        }
    }

    private static final String ORDER = """
        import dev.simplified.annotations.BuilderSeed;
        import dev.simplified.annotations.ClassBuilder;
        public class Order {
            private final String origin;
            private final String item;
            @ClassBuilder
            Order(<caret>String origin, String item) { this.origin = origin; this.item = item; }
        }
        """;

    // ------------------------------------------------------------------
    // An edit to an input re-synthesises
    // ------------------------------------------------------------------

    /**
     * A seed written after the file was first highlighted moves onto
     * {@code builder(..)}: javac compiles {@code builder("o")} on the edited
     * text, and the editor kept the nullary entry point built before the edit.
     */
    public void testASeedWrittenAfterHighlighting_movesOntoTheEntryPoint() {
        myFixture.configureByText("Order.java",
            ORDER + "class Caller { Order make() { return Order.builder(\"o\").item(\"i\").build(); } }\n");
        errors();
        insertAtCaret("@BuilderSeed ");

        assertEquals("javac compiles the edited text", List.of(), errors());
        assertEquals("builder(String) after the edit", List.of("builder(String)"),
            signatures(target(), "builder"));
    }

    /** The bare entry point javac rejects once the seed is written goes red. */
    public void testASeedWrittenAfterHighlighting_redsTheBareEntryPoint() {
        myFixture.configureByText("Order.java",
            ORDER + "class Caller { Order make() { return Order.builder().item(\"i\").build(); } }\n");
        assertEquals("green before the edit", List.of(), errors());
        insertAtCaret("@BuilderSeed ");

        assertFalse("javac rejects Order.builder() on the edited text", errors().isEmpty());
    }

    /** A field added after highlighting widens the all-args constructor. */
    public void testAFieldAddedAfterHighlighting_widensTheConstructor() {
        myFixture.configureByText("Point.java", """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Point {
                private int x;
                <caret>
            }
            class Caller { Point make() { return new Point(1, 2); } }
            """);
        errors();
        insertAtCaret("private int y;");

        assertEquals("javac compiles the edited text", List.of(), errors());
        assertEquals("the constructor takes both fields", List.of("Point(int, int)"),
            signatures(target(), "Point"));
    }

    /** An exclude written after highlighting narrows the all-args constructor. */
    public void testAnExcludeWrittenAfterHighlighting_narrowsTheConstructor() {
        myFixture.configureByText("Point.java", """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(<caret>)
            public class Point {
                private int x;
                private int y;
            }
            class Caller { Point make() { return new Point(1); } }
            """);
        errors();
        insertAtCaret("exclude = \"y\"");

        assertEquals("javac compiles the edited text", List.of(), errors());
        assertEquals("the constructor takes x alone", List.of("Point(int)"),
            signatures(target(), "Point"));
    }

    // ------------------------------------------------------------------
    // An edit to the superclass re-synthesises the subclass
    // ------------------------------------------------------------------

    private static final String SHAPE = """
        public abstract class Shape {
            private String name;
        }
        """;

    private static final String CIRCLE = """
        import dev.simplified.annotations.ClassBuilder;
        @ClassBuilder
        public class Circle extends Shape {
            private double radius;
        }
        """;

    /**
     * {@code @ClassBuilder} written on the superclass in another file after the
     * subclass was highlighted makes the subclass a link: javac compiles a chain
     * reaching the superclass's setter and a copy constructor taking the link's
     * builder. The editor kept the standalone builder, entry points and all-args
     * constructor built before the edit, since nothing in the subclass's own
     * text changed.
     */
    public void testAnAnnotationWrittenOnTheSuperclass_makesTheSubclassALink() {
        PsiFile shape = myFixture.addFileToProject("Shape.java", SHAPE);
        myFixture.configureByText("Circle.java",
            CIRCLE + "class Caller { Circle make() { return Circle.builder().name(\"n\").radius(1).build(); } }\n");
        errors();
        assertEquals("standalone before the edit", List.of("Circle(double)"), signatures(target(), "Circle"));

        edit(shape, document -> document.insertString(0,
            "import dev.simplified.annotations.ClassBuilder;\n@ClassBuilder\n"));

        assertEquals("javac compiles the edited text", List.of(), errors());
        assertEquals("the link's copy constructor", List.of("Circle(Builder)"), signatures(target(), "Circle"));
        PsiClass builder = target().getInnerClasses()[0];
        assertEquals("the link's builder extends the superclass's", "Shape.Builder",
            builder.getSuperClass() == null ? null : builder.getSuperClass().getQualifiedName());
    }

    /**
     * {@code @ClassBuilder} removed from the superclass after the subclass was
     * highlighted makes the subclass standalone again: javac compiles the
     * all-args constructor, and the editor kept the link's copy constructor.
     */
    public void testAnAnnotationRemovedFromTheSuperclass_makesTheSubclassStandalone() {
        String annotated = "import dev.simplified.annotations.ClassBuilder;\n@ClassBuilder\n" + SHAPE;
        PsiFile shape = myFixture.addFileToProject("Shape.java", annotated);
        myFixture.configureByText("Circle.java",
            CIRCLE + "class Caller { Circle make() { return new Circle(1.0); } }\n");
        errors();
        assertEquals("a link before the edit", List.of("Circle(Builder)"), signatures(target(), "Circle"));

        int start = annotated.indexOf("@ClassBuilder");
        edit(shape, document -> document.deleteString(start, start + "@ClassBuilder\n".length()));

        assertEquals("javac compiles the edited text", List.of(), errors());
        assertEquals("the all-args constructor", List.of("Circle(double)"), signatures(target(), "Circle"));
        PsiClass builder = target().getInnerClasses()[0];
        assertEquals("a standalone builder extends nothing of the superclass's", "java.lang.Object",
            builder.getSuperClass() == null ? null : builder.getSuperClass().getQualifiedName());
    }

    // ------------------------------------------------------------------
    // Unchanged text keeps the instances
    // ------------------------------------------------------------------

    /**
     * Rereading the members after every cached value is dropped, the text
     * unchanged, hands back the same instances - which is what the platform's
     * idempotence check compares.
     */
    public void testUnchangedText_rereadsTheSameInstances() {
        myFixture.configureByText("Order.java", ORDER.replace("<caret>", "@BuilderSeed "));
        PsiClass target = target();
        PsiMethod entry = target.findMethodsByName("builder", false)[0];
        PsiClass builder = target.getInnerClasses()[0];

        long before = PsiModificationTracker.getInstance(getProject()).getModificationCount();
        PsiManager.getInstance(getProject()).dropPsiCaches();
        assertTrue("the cached values were dropped",
            PsiModificationTracker.getInstance(getProject()).getModificationCount() != before);

        assertSame("the same entry point", entry, target.findMethodsByName("builder", false)[0]);
        assertSame("the same builder class", builder, target.getInnerClasses()[0]);
    }

    /**
     * A link's members, whose role is read off the superclass's annotation,
     * are handed back as the same instances over unchanged text as well.
     */
    public void testUnchangedText_rereadsTheSameInstancesOfALink() {
        myFixture.addFileToProject("Shape.java",
            "import dev.simplified.annotations.ClassBuilder;\n@ClassBuilder\n" + SHAPE);
        myFixture.configureByText("Circle.java", CIRCLE);
        PsiClass target = target();
        PsiMethod entry = target.findMethodsByName("builder", false)[0];
        PsiMethod constructor = target.findMethodsByName("Circle", false)[0];
        PsiClass builder = target.getInnerClasses()[0];

        long before = PsiModificationTracker.getInstance(getProject()).getModificationCount();
        PsiManager.getInstance(getProject()).dropPsiCaches();
        assertTrue("the cached values were dropped",
            PsiModificationTracker.getInstance(getProject()).getModificationCount() != before);

        assertSame("the same entry point", entry, target.findMethodsByName("builder", false)[0]);
        assertSame("the same copy constructor", constructor, target.findMethodsByName("Circle", false)[0]);
        assertSame("the same builder class", builder, target.getInnerClasses()[0]);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private PsiClass target() {
        return ((PsiJavaFile) myFixture.getFile()).getClasses()[0];
    }

    private List<String> errors() {
        List<String> out = new ArrayList<>();
        for (HighlightInfo info : myFixture.doHighlighting()) {
            if (info.getSeverity() == HighlightSeverity.ERROR)
                out.add("[" + info.getText() + "] " + info.getDescription());
        }
        return out;
    }

    private void insertAtCaret(String text) {
        WriteCommandAction.runWriteCommandAction(getProject(), () ->
            myFixture.getEditor().getDocument().insertString(myFixture.getCaretOffset(), text));
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
    }

    /** Edits another file's document through a write command, then commits it. */
    private void edit(PsiFile file, Consumer<Document> change) {
        Document document = PsiDocumentManager.getInstance(getProject()).getDocument(file);
        assertNotNull("the file has a document", document);
        WriteCommandAction.runWriteCommandAction(getProject(), () -> change.accept(document));
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
    }

    /** Each method of that name the class offers, as {@code name(Type, ...)}. */
    private static List<String> signatures(PsiClass owner, String name) {
        List<String> out = new ArrayList<>();
        for (PsiMethod method : owner.findMethodsByName(name, false)) {
            List<String> types = new ArrayList<>();
            for (PsiParameter parameter : method.getParameterList().getParameters())
                types.add(parameter.getType().getPresentableText());
            out.add(name + "(" + String.join(", ", types) + ")");
        }
        return out;
    }

}
