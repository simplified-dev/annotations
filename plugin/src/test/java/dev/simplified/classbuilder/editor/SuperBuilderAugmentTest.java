package dev.simplified.classbuilder.editor;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.application.AccessToken;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import dev.simplified.testutil.JSvgErrorSuppressor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Editor-side modelling of a SuperBuilder chain. The synthesised Builder for a
 * link has to extend its parent's synthesised Builder, or the platform's
 * inherited-member lookup has nothing to walk and every setter declared further
 * up reads as unresolved - while the same code compiles perfectly.
 *
 * <p>The root's Builder additionally has to be self-typed
 * ({@code <T extends Root, B extends Builder<T, B>>}) with setters returning
 * {@code B}. Without that a subclass builder degrades to the parent's type
 * partway along a chained call, so {@code .parentSetter(...).childSetter(...)}
 * resolves in one order and not the other.
 */
public class SuperBuilderAugmentTest extends LightJavaCodeInsightFixtureTestCase {

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
                boolean validate() default true;
                boolean emitContracts() default true;
                boolean emitGenerated() default true;
                boolean generateImpl() default true;
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
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            if (jsvgSuppressor != null) jsvgSuppressor.close();
        } finally {
            super.tearDown();
        }
    }

    private void assertNoErrors() {
        List<String> errors = new ArrayList<>();
        for (HighlightInfo info : myFixture.doHighlighting()) {
            if (info.getSeverity() == HighlightSeverity.ERROR) errors.add(info.getDescription());
        }
        assertTrue("expected no editor errors, got: " + errors, errors.isEmpty());
    }

    /** Adds a plain non-generic root + concrete link. */
    private void addPlainChain() {
        myFixture.addFileToProject("b/P.java",
            """
            package b;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public abstract class P {
                String t;
                public String getT() { return t; }
            }
            """);
        myFixture.addFileToProject("b/K.java",
            """
            package b;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class K extends P {
                int n;
                public int getN() { return n; }
            }
            """);
    }

    // ------------------------------------------------------------------
    // Shape
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // The chain's copy constructor
    //
    // The processor emits protected Target(Builder) on every chain role and the
    // editor synthesised none, so an author writing super(builder) in a
    // constructor of their own was red over source that builds. It is
    // contributed under both of the processor's gates rather than on the role,
    // because gating on the role alone produces the inverse divergence wherever
    // the attribute is written false or the author declared their own.
    // ------------------------------------------------------------------

    /** Every constructor the editor offers on the target, by parameter type. */
    private List<String> constructorParameterTypesOf(PsiClass target) {
        List<String> out = new ArrayList<>();
        for (PsiMethod method : target.getMethods()) {
            if (!method.isConstructor()) continue;
            PsiParameter[] parameters = method.getParameterList().getParameters();
            out.add(parameters.length == 1 ? parameters[0].getType().getPresentableText() : "");
        }
        return out;
    }

    public void testChainCopyConstructor_isOffered() {
        addPlainChain();
        myFixture.configureByText("Use.java", "package b;\npublic class Use { }\n");
        List<String> types = constructorParameterTypesOf(myFixture.findClass("b.K"));
        assertTrue("a concrete link takes its own builder plainly: " + types,
            types.contains("Builder"));
    }

    /** A root's builder carries the self-typed pair, so its constructor takes the wildcard form. */
    public void testChainCopyConstructorOnARoot_takesTheWildcardBuilder() {
        addPlainChain();
        myFixture.configureByText("Use.java", "package b;\npublic class Use { }\n");
        List<String> types = constructorParameterTypesOf(myFixture.findClass("b.P"));
        assertTrue("a root accepts any subclass builder: " + types,
            types.contains("Builder<?, ?>"));
    }

    public void testWithGenerateCopyConstructorFalse_isNotOffered() {
        myFixture.addFileToProject("c/P.java",
            """
            package c;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(generateCopyConstructor = false)
            public abstract class P { String t; }
            """);
        myFixture.configureByText("Use.java", "package c;\npublic class Use { }\n");
        List<String> types = constructorParameterTypesOf(myFixture.findClass("c.P"));
        assertFalse("the attribute is read, so nothing is contributed: " + types,
            types.contains("Builder<?, ?>"));
    }

    public void testWhereTheTargetDeclaresItsOwn_isNotOfferedTwice() {
        myFixture.addFileToProject("d/P.java",
            """
            package d;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public abstract class P {
                String t;
                protected P(Builder<?, ?> b) { }
            }
            """);
        myFixture.configureByText("Use.java", "package d;\npublic class Use { }\n");
        List<String> types = constructorParameterTypesOf(myFixture.findClass("d.P"));
        assertEquals("the author's version wins and nothing lands beside it: " + types,
            1, types.stream().filter(t -> t.startsWith("Builder")).count());
    }

    /** An unrelated one-parameter constructor is not the author's copy constructor. */
    public void testWhereTheTargetDeclaresAnUnrelatedConstructor_isStillOffered() {
        myFixture.addFileToProject("e/P.java",
            """
            package e;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public abstract class P {
                String t;
                protected P(String t) { this.t = t; }
            }
            """);
        myFixture.configureByText("Use.java", "package e;\npublic class Use { }\n");
        List<String> types = constructorParameterTypesOf(myFixture.findClass("e.P"));
        assertTrue("the builder-taking one is still missing without this: " + types,
            types.contains("Builder<?, ?>"));
        assertTrue("and the author's own is untouched: " + types, types.contains("String"));
    }

    public void testRootBuilder_isAbstractAndSelfTyped() {
        myFixture.configureByText("P.java",
            """
            package b;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder public abstract class P { String t; }
            """);
        PsiClass root = ((PsiJavaFile) myFixture.getFile()).getClasses()[0];
        PsiClass builder = root.getInnerClasses()[0];

        assertTrue("root Builder must be abstract",
            builder.hasModifierProperty(PsiModifier.ABSTRACT));
        assertEquals("root Builder must be self-typed <T, B>",
            2, builder.getTypeParameters().length);
        assertEquals("T", builder.getTypeParameters()[0].getName());
        assertEquals("B", builder.getTypeParameters()[1].getName());
    }

    public void testLinkBuilder_extendsTheParentsBuilder() {
        addPlainChain();
        myFixture.configureByText("Use.java",
            """
            package b;
            public class Use { }
            """);
        PsiClass link = myFixture.findClass("b.K");
        PsiClass parent = myFixture.findClass("b.P");
        PsiClass linkBuilder = link.getInnerClasses()[0];
        PsiClass parentBuilder = parent.getInnerClasses()[0];

        assertEquals("link Builder must extend the parent's synthesised Builder",
            parentBuilder, linkBuilder.getSuperClass());
        assertFalse("a concrete link's Builder must not be abstract",
            linkBuilder.hasModifierProperty(PsiModifier.ABSTRACT));
    }

    public void testLinkBuilder_seesInheritedSetterThroughTheSupertype() {
        addPlainChain();
        myFixture.configureByText("Use.java",
            """
            package b;
            public class Use { }
            """);
        PsiClass linkBuilder = myFixture.findClass("b.K").getInnerClasses()[0];

        assertEquals("inherited setter must resolve on the link's Builder",
            1, linkBuilder.findMethodsByName("t", true).length);
        assertEquals("own setter must still resolve",
            1, linkBuilder.findMethodsByName("n", true).length);
        assertEquals("the inherited setter must not be duplicated onto the link",
            0, linkBuilder.findMethodsByName("t", false).length);
    }

    public void testStandaloneBuilder_unchanged() {
        myFixture.configureByText("Solo.java",
            """
            package b;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder public class Solo { String a; }
            """);
        PsiClass builder = ((PsiJavaFile) myFixture.getFile()).getClasses()[0].getInnerClasses()[0];

        assertFalse("a standalone Builder must not become abstract",
            builder.hasModifierProperty(PsiModifier.ABSTRACT));
        assertEquals("a standalone Builder gains no self-types",
            0, builder.getTypeParameters().length);
        assertEquals("a standalone Builder needs no self()",
            0, builder.findMethodsByName("self", false).length);
    }

    // ------------------------------------------------------------------
    // Consumption - what the user actually sees
    // ------------------------------------------------------------------

    /** The case that was previously red: an inherited setter in a chained call. */
    public void testChain_inheritedSetterResolves() {
        addPlainChain();
        myFixture.configureByText("Use.java",
            """
            import b.K;
            public class Use {
                public static String go() {
                    return K.builder().t("x").n(1).build().getT();
                }
            }
            """);
        assertNoErrors();
    }

    /**
     * Either call order has to work. This is what the self-typed {@code B}
     * buys - without it the inherited setter returns the parent's Builder and
     * the following child setter is unresolved.
     */
    public void testChain_setterOrderIsInterchangeable() {
        addPlainChain();
        myFixture.configureByText("Use.java",
            """
            import b.K;
            public class Use {
                public static int parentFirst() {
                    return K.builder().t("x").n(1).build().getN();
                }
                public static int childFirst() {
                    return K.builder().n(1).t("x").build().getN();
                }
            }
            """);
        assertNoErrors();
    }

    public void testChain_fromAndMutateCarryInheritedSetters() {
        addPlainChain();
        myFixture.configureByText("Use.java",
            """
            import b.K;
            public class Use {
                public static String go(K k) {
                    return K.from(k).t("x").n(2).build().getT()
                        + k.mutate().t("y").build().getT();
                }
            }
            """);
        assertNoErrors();
    }

    /** Three levels, so the chained-abstract shape is exercised. */
    public void testThreeLevelChain_resolvesEveryLevelsSetters() {
        myFixture.addFileToProject("c/R.java",
            """
            package c;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public abstract class R {
                String a;
                public String getA() { return a; }
            }
            """);
        myFixture.addFileToProject("c/M.java",
            """
            package c;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public abstract class M extends R {
                String b;
                public String getB() { return b; }
            }
            """);
        myFixture.addFileToProject("c/L.java",
            """
            package c;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class L extends M {
                int c;
                public int getC() { return c; }
            }
            """);
        myFixture.configureByText("Use.java",
            """
            import c.L;
            public class Use {
                public static String go() {
                    return L.builder().a("A").c(3).b("B").build().getA();
                }
            }
            """);
        assertNoErrors();
    }

    // ------------------------------------------------------------------
    // Generic chains
    // ------------------------------------------------------------------

    /** Generic root, concrete link binding the parameter to String. */
    public void testGenericChain_boundLinkResolvesInheritedSetter() {
        myFixture.addFileToProject("d/Box.java",
            """
            package d;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public abstract class Box<V> {
                V item;
                public V getItem() { return item; }
            }
            """);
        myFixture.addFileToProject("d/SBox.java",
            """
            package d;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class SBox extends Box<String> {
                int n;
                public int getN() { return n; }
            }
            """);
        myFixture.configureByText("Use.java",
            """
            import d.SBox;
            public class Use {
                public static String go() {
                    // item(V) must arrive bound to String through the link.
                    return SBox.builder().item("x").n(1).build().getItem();
                }
            }
            """);
        assertNoErrors();
    }

    /** Generic root with a generic link forwarding the parameter. */
    public void testGenericChain_genericLinkForwardsTheParameter() {
        myFixture.addFileToProject("e/Base.java",
            """
            package e;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public abstract class Base<V> {
                V item;
                public V getItem() { return item; }
            }
            """);
        myFixture.addFileToProject("e/Impl.java",
            """
            package e;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Impl<V> extends Base<V> {
                int n;
                public int getN() { return n; }
            }
            """);
        myFixture.configureByText("Use.java",
            """
            import e.Impl;
            public class Use {
                public static String go() {
                    return Impl.<String>builder().item("x").n(1).build().getItem();
                }
            }
            """);
        assertNoErrors();
    }

    /** A generic target naming a parameter T collides with the self-types. */
    public void testGenericChain_targetParameterNamedT() {
        myFixture.addFileToProject("f/Holder.java",
            """
            package f;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public abstract class Holder<T> {
                T value;
                public T getValue() { return value; }
            }
            """);
        myFixture.addFileToProject("f/SHolder.java",
            """
            package f;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class SHolder extends Holder<String> {
                int n;
                public int getN() { return n; }
            }
            """);
        myFixture.configureByText("Use.java",
            """
            import f.SHolder;
            public class Use {
                public static String go() {
                    return SHolder.builder().value("v").n(1).build().getValue();
                }
            }
            """);
        assertNoErrors();
    }

    /**
     * An unannotated class between two annotated ones breaks the chain, matching
     * the APT side's direct-superclass-only rule. The link is then a standalone
     * builder, and the grandparent's setter is genuinely absent.
     */
    public void testUnannotatedClassInBetween_doesNotChain() {
        myFixture.addFileToProject("g/A.java",
            """
            package g;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public abstract class A { String t; }
            """);
        myFixture.addFileToProject("g/B.java",
            """
            package g;
            public abstract class B extends A { }
            """);
        myFixture.addFileToProject("g/C.java",
            """
            package g;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class C extends B { int n; }
            """);
        myFixture.configureByText("Use.java",
            """
            package g;
            public class Use { }
            """);
        PsiClass cBuilder = myFixture.findClass("g.C").getInnerClasses()[0];
        assertEquals("an unannotated class in between must break the chain",
            0, cBuilder.findMethodsByName("t", true).length);
        assertEquals(1, cBuilder.findMethodsByName("n", true).length);
    }

    /**
     * A chained abstract inherits the root's {@code self()} and {@code build()},
     * and the processor declares neither on its builder. The editor declared
     * both again, abstract, on every chained-abstract builder it synthesised.
     */
    public void testChainedAbstractBuilder_declaresNoSelfOrBuild() {
        myFixture.addFileToProject("h/R.java",
            """
            package h;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public abstract class R { String a; }
            """);
        myFixture.configureByText("M.java",
            """
            package h;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public abstract class M extends R { String b; }
            """);
        PsiClass builder = ((PsiJavaFile) myFixture.getFile()).getClasses()[0].getInnerClasses()[0];
        List<String> names = new ArrayList<>();
        for (PsiMethod method : builder.getMethods()) names.add(method.getName());
        assertTrue("the setter is its own: " + names, names.contains("b"));
        assertFalse("and the pair is inherited, not redeclared: " + names,
            names.contains("self") || names.contains("build"));
    }

    // ------------------------------------------------------------------
    // The chain merge
    //
    // A root, a link or a chained abstract declaring its own builder has the
    // role's members merged into it. The editor left each declaration exactly
    // as written, the processor having aborted on it.
    // ------------------------------------------------------------------

    /** A root's author verb calls a merged setter and returns the declared self type. */
    public void testMergeOnARoot_offersSelfTypedSettersBesideTheAuthorsVerbs() {
        myFixture.addFileToProject("m/Shape.java",
            """
            package m;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public abstract class Shape {
                String name;
                public String getName() { return name; }
                public abstract static class Builder<T extends Shape, B extends Builder<T, B>> {
                    public B named(String first, String last) { return name(first + " " + last); }
                }
            }
            """);
        myFixture.addFileToProject("m/Circle.java",
            """
            package m;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Circle extends Shape {
                int radius;
                public int getRadius() { return radius; }
            }
            """);
        myFixture.configureByText("Use.java",
            """
            import m.Circle;
            public class Use {
                public static String go() {
                    return Circle.builder().named("a", "b").radius(2).build().getName();
                }
            }
            """);
        assertNoErrors();
        myFixture.configureFromTempProjectFile("m/Shape.java");
        assertNoErrors();
    }

    /**
     * A link's author verb reads a merged slot field, and the entry points
     * return the declared class, so a chain through the verb and the inherited
     * setter resolves from every entry point.
     */
    public void testMergeOnALink_offersInheritedAndOwnSetters() {
        myFixture.addFileToProject("n/Base.java",
            """
            package n;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public abstract class Base {
                String label;
                public String getLabel() { return label; }
            }
            """);
        myFixture.configureByText("Link.java",
            """
            package n;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Link extends Base {
                String extra;
                public String getExtra() { return extra; }
                public static class Builder extends Base.Builder<Link, Builder> {
                    public Builder shout() { this.extra = this.extra.toUpperCase(); return this; }
                }
            }
            class Use {
                String go(Link link) {
                    return Link.builder().extra("x").shout().label("l").build().getLabel()
                        + Link.from(link).shout().build().getExtra()
                        + link.mutate().shout().label("m").build().getExtra();
                }
            }
            """);
        assertNoErrors();
    }

    /**
     * A chained abstract's declared builder keeps its verbs and is given its own
     * setters, which every level below resolves through; it declares neither of
     * the root's pair, as the processor leaves it.
     */
    public void testMergeOnAThreeLevelChain_everyLevelsSettersResolve() {
        myFixture.addFileToProject("o/R.java",
            """
            package o;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public abstract class R {
                String a;
                public String getA() { return a; }
            }
            """);
        myFixture.addFileToProject("o/M.java",
            """
            package o;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public abstract class M extends R {
                String b;
                public abstract static class Builder<T extends M, B extends Builder<T, B>>
                        extends R.Builder<T, B> {
                    public B shapeless() { return b("none"); }
                }
            }
            """);
        myFixture.addFileToProject("o/L.java",
            """
            package o;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class L extends M {
                int c;
            }
            """);
        myFixture.configureByText("Use.java",
            """
            import o.L;
            public class Use {
                public static String go() {
                    return L.builder().a("A").shapeless().c(3).b("B").build().getA();
                }
            }
            """);
        assertNoErrors();
        PsiClass declared = myFixture.findClass("o.M").getInnerClasses()[0];
        List<String> names = new ArrayList<>();
        for (PsiMethod method : declared.getMethods()) names.add(method.getName());
        assertTrue("the setter is merged in: " + names, names.contains("b"));
        assertFalse("and neither of the root's pair: " + names,
            names.contains("self") || names.contains("build"));
    }

    /** A generic root's declared builder forwards its parameter to a generic link. */
    public void testMergeOnAGenericChain_forwardsTheParameter() {
        myFixture.addFileToProject("p/Base.java",
            """
            package p;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public abstract class Base<V> {
                V item;
                public V getItem() { return item; }
                public abstract static class Builder<V, T extends Base<V>, B extends Builder<V, T, B>> {
                    public B cleared() { return item(null); }
                }
            }
            """);
        myFixture.addFileToProject("p/Impl.java",
            """
            package p;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Impl<V> extends Base<V> {
                int n;
            }
            """);
        myFixture.configureByText("Use.java",
            """
            import p.Impl;
            public class Use {
                public static String go() {
                    return Impl.<String>builder().cleared().item("x").n(1).build().getItem();
                }
            }
            """);
        assertNoErrors();
    }

    /**
     * No chain builder carries a declared constructor: the processor writes none
     * on any role, so javac's default at the builder class's access - public
     * here - is what a caller in another package constructs a link's builder
     * through, and PSI's implicit default is the same constructor. The editor
     * used to contribute a package-private one at builderConstructorAccess on
     * every role, red over source that builds.
     */
    public void testAChainBuilder_keepsTheImplicitDefault() {
        myFixture.addFileToProject("r/Doc.java",
            """
            package r;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public abstract class Doc {
                String title;
                public String getTitle() { return title; }
            }
            """);
        myFixture.addFileToProject("r/Article.java",
            """
            package r;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Article extends Doc {
                int words;
                public int getWords() { return words; }
            }
            """);
        myFixture.configureByText("UseArticle.java",
            """
            import r.Article;
            public class UseArticle {
                String go() { return new Article.Builder().title("t").words(3).build().getTitle(); }
            }
            """);
        assertNoErrors();
        PsiClass article = myFixture.findClass("r.Article");
        PsiClass doc = myFixture.findClass("r.Doc");
        assertEquals("the link's builder", 0, builderOf(article).getConstructors().length);
        assertEquals("the root's builder", 0, builderOf(doc).getConstructors().length);
    }

    /** The builder the editor lists on a target. */
    private static PsiClass builderOf(PsiClass target) {
        for (PsiClass nested : target.getInnerClasses()) {
            if ("Builder".equals(nested.getName())) return nested;
        }
        throw new AssertionError("expected a Builder on " + target.getName());
    }

}
