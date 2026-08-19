package dev.simplified.expand.apt;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.VariableTree;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.JCTree;
import dev.simplified.shared.javac.AstMarkers;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Renders a generated member as a declaration with an empty body.
 *
 * <p>The javadoc tool runs no flow analysis, so a body that returns nothing from
 * a method with a return type is still accepted. Nothing here therefore renders
 * a statement or an expression, which is what keeps the whole expander down to a
 * signature printer rather than a pretty-printer.
 *
 * <p>Types are printed from the trees the mutators built, and those are built
 * from resolved symbols, so every type name comes out fully qualified and
 * resolves without an import.
 */
final class MemberRenderer {

    private static final String STEP = "    ";

    private final Function<Tree, String> docs;
    private final Predicate<Tree> emits;

    /**
     * Constructs a new {@code MemberRenderer} reading authored documentation
     * through the given lookup.
     *
     * @param docs the authored doc comment of a tree, or {@code null} when it
     *             carries none
     * @param emits whether a member is one the copy still needs written into it
     */
    MemberRenderer(Function<Tree, String> docs, Predicate<Tree> emits) {
        this.docs = docs;
        this.emits = emits;
    }

    /**
     * Renders every generated member of a class, in declaration order.
     *
     * @param owner the class whose members to render
     * @param indent the indentation each member sits at
     * @return the rendered members, or an empty string when the class generated
     *         nothing
     */
    String renderGeneratedMembers(ClassTree owner, String indent) {
        StringBuilder out = new StringBuilder();
        for (Tree member : owner.getMembers()) {
            if (!this.emits.test(member)) continue;
            String text = render(member, owner, indent);
            if (!text.isEmpty()) out.append("\n\n").append(text);
        }
        return out.toString();
    }

    private String render(Tree member, ClassTree owner, String indent) {
        if (member instanceof MethodTree method) return renderMethod(method, owner, indent);
        if (member instanceof VariableTree field) return renderField(field, owner, indent);
        if (member instanceof ClassTree nested) return renderClass(nested, owner, indent);
        return "";
    }

    // ------------------------------------------------------------------
    // Declarations
    // ------------------------------------------------------------------

    private String renderMethod(MethodTree method, ClassTree owner, String indent) {
        boolean constructor = method.getReturnType() == null;
        String name = constructor ? owner.getSimpleName().toString() : method.getName().toString();

        List<String> params = new ArrayList<>();
        List<? extends VariableTree> declared = method.getParameters();
        for (int i = 0; i < declared.size(); i++) {
            VariableTree param = declared.get(i);
            boolean last = i == declared.size() - 1;
            params.add(parameterType(param, last) + " " + param.getName());
        }

        StringBuilder head = new StringBuilder();
        head.append(indent).append(modifiers(method.getModifiers().getFlags()));
        head.append(typeParameters(method.getTypeParameters()));
        if (!constructor) head.append(method.getReturnType()).append(' ');
        head.append(name).append('(').append(String.join(", ", params)).append(')');

        List<? extends ExpressionTree> thrown = method.getThrows();
        if (!thrown.isEmpty()) head.append(" throws ").append(join(thrown));

        head.append(bodyless(method, owner) ? ";" : " { }");
        return doc(method, owner, indent, method.getParameters(),
            constructor ? null : method.getReturnType()) + head;
    }

    private String renderField(VariableTree field, ClassTree owner, String indent) {
        return doc(field, owner, indent, List.of(), null)
            + indent + modifiers(field.getModifiers().getFlags())
            + field.getType() + " " + field.getName() + ";";
    }

    private String renderClass(ClassTree type, ClassTree owner, String indent) {
        StringBuilder head = new StringBuilder();
        head.append(indent).append(modifiers(type.getModifiers().getFlags()));
        head.append(keyword(type)).append(' ').append(type.getSimpleName());
        head.append(typeParameters(type.getTypeParameters()));

        if (type.getExtendsClause() != null) head.append(" extends ").append(type.getExtendsClause());
        if (!type.getImplementsClause().isEmpty())
            head.append(type.getKind() == Tree.Kind.INTERFACE ? " extends " : " implements ")
                .append(join(type.getImplementsClause()));

        head.append(" {");
        head.append(renderGeneratedMembers(type, indent + STEP));
        head.append('\n').append('\n').append(indent).append('}');
        return doc(type, owner, indent, List.of(), null) + head;
    }

    // ------------------------------------------------------------------
    // Pieces
    // ------------------------------------------------------------------

    /**
     * Whether the method is declared without a body. A varargs-style empty body
     * is legal everywhere the doclet looks, but an abstract member with one is
     * not a declaration the reader should be shown.
     */
    private static boolean bodyless(MethodTree method, ClassTree owner) {
        var flags = method.getModifiers().getFlags();
        if (flags.contains(Modifier.ABSTRACT) || flags.contains(Modifier.NATIVE)) return true;
        return owner.getKind() == Tree.Kind.INTERFACE
            && !flags.contains(Modifier.DEFAULT)
            && !flags.contains(Modifier.STATIC);
    }

    /** The parameter's type, spelled with an ellipsis when it is the varargs slot. */
    private static String parameterType(VariableTree param, boolean last) {
        String type = param.getType().toString();
        boolean varargs = param instanceof JCVariableDecl decl
            && (decl.mods.flags & Flags.VARARGS) != 0;
        if (!last || !varargs || !type.endsWith("[]")) return type;
        return type.substring(0, type.length() - 2) + "...";
    }

    /** The modifiers in canonical order, with a trailing space when any apply. */
    private static String modifiers(java.util.Set<Modifier> flags) {
        if (flags.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (Modifier modifier : EnumSet.copyOf(flags)) out.append(modifier).append(' ');
        return out.toString();
    }

    private static String typeParameters(List<? extends TypeParameterTree> parameters) {
        return parameters.isEmpty() ? "" : "<" + join(parameters) + "> ";
    }

    private static String keyword(ClassTree type) {
        return switch (type.getKind()) {
            case INTERFACE -> "interface";
            case ENUM -> "enum";
            case RECORD -> "record";
            case ANNOTATION_TYPE -> "@interface";
            default -> "class";
        };
    }

    private static String join(List<? extends Tree> trees) {
        List<String> parts = new ArrayList<>(trees.size());
        for (Tree tree : trees) parts.add(tree.toString());
        return String.join(", ", parts);
    }

    // ------------------------------------------------------------------
    // Documentation
    // ------------------------------------------------------------------

    /**
     * The doc comment for a generated member, carrying the prose of whatever
     * authored declaration it derives from.
     *
     * <p>Every tag the doclet asks for is emitted, because a member the author
     * never wrote should not produce a warning telling them to document it.
     */
    private String doc(Tree member, ClassTree owner, String indent,
                       List<? extends VariableTree> params, Tree returnType) {
        if (overridesObject(member)) return indent + "/** {@inheritDoc} */\n";

        Tree source = AstMarkers.docSourceOf((JCTree) member);
        String prose = describe(member, owner, source);

        StringBuilder out = new StringBuilder();
        out.append(indent).append("/**\n");
        out.append(indent).append(" * ").append(prose).append('\n');

        boolean voidReturn = returnType == null || "void".equals(returnType.toString());
        if (!params.isEmpty() || !voidReturn) out.append(indent).append(" *\n");

        for (VariableTree param : params)
            out.append(indent).append(" * @param ").append(param.getName())
                .append(' ').append(fragment(source, param)).append('\n');

        if (!voidReturn)
            out.append(indent).append(" * @return ").append(returned(member, owner, source)).append('\n');

        out.append(indent).append(" */\n");
        return out.toString();
    }

    /**
     * Whether the member is one of the three {@link Object} members every class
     * may override.
     *
     * <p>Those three carry a contract the reader already knows and the doclet can
     * already reach, so inheriting it says more than any sentence composed here
     * and needs no {@code @param} or {@code @return} of its own.
     */
    private static boolean overridesObject(Tree member) {
        if (!(member instanceof MethodTree method) || method.getReturnType() == null) return false;
        int arity = method.getParameters().size();
        String name = method.getName().toString();
        return (arity == 0 && ("toString".equals(name) || "hashCode".equals(name)))
            || (arity == 1 && "equals".equals(name));
    }

    /**
     * The member's description sentence, taken from the authored declaration it
     * derives from when there is one and read off its shape when there is not.
     */
    private String describe(Tree member, ClassTree owner, Tree source) {
        String core = firstSentence(source == null ? null : this.docs.apply(source));
        if (core != null)
            return member instanceof MethodTree method && !method.getParameters().isEmpty()
                ? "Sets " + decapitalise(core) + "."
                : core + ".";

        if (member instanceof ClassTree) return "Builds {@link " + owner.getSimpleName() + "} instances.";
        if (member instanceof VariableTree field) return "The " + spaced(field.getName().toString()) + ".";

        MethodTree method = (MethodTree) member;
        if (method.getReturnType() == null)
            return "Constructs a new {@code " + owner.getSimpleName() + "}.";
        if (declaredOnBuilder(owner)) {
            if (returnsOwner(method, owner)) return "Sets the value and returns this builder.";
            if (method.getParameters().isEmpty())
                return "Builds a new instance from the values set so far.";
        }
        if (returnsGeneratedTypeOf(method, owner)) return "Creates a builder.";
        return capitalise(spaced(method.getName().toString())) + ".";
    }

    /** The text of a {@code @param} tag. */
    private String fragment(Tree source, VariableTree param) {
        String core = firstSentence(source == null ? null : this.docs.apply(source));
        if (core != null && source instanceof VariableTree field
            && field.getName().contentEquals(param.getName())) return decapitalise(core);
        return "the " + spaced(param.getName().toString());
    }

    /** The text of a {@code @return} tag. */
    private String returned(Tree member, ClassTree owner, Tree source) {
        String core = firstSentence(source == null ? null : this.docs.apply(source));
        if (core != null) return decapitalise(core);
        MethodTree method = (MethodTree) member;
        if (declaredOnBuilder(owner)) {
            if (returnsOwner(method, owner)) return "this builder";
            if (method.getParameters().isEmpty()) return "a new instance";
        }
        if (returnsGeneratedTypeOf(method, owner)) return "a new builder";
        return "the " + spaced(method.getReturnType().toString()
            .replaceAll("[<>,?\\[\\]]", " ").replaceAll("[\\w.]+\\.", "").trim());
    }

    /**
     * Whether the method returns a type the pipeline generated inside the class
     * that declares it, which is what a builder entry point looks like from the
     * outside.
     */
    private boolean returnsGeneratedTypeOf(MethodTree method, ClassTree owner) {
        if (method.getReturnType() == null) return false;
        String returned = method.getReturnType().toString();
        for (Tree member : owner.getMembers())
            if (member instanceof ClassTree nested && this.emits.test(nested)
                && returned.endsWith(nested.getSimpleName().toString())) return true;
        return false;
    }

    /** Whether the method returns the very class that declares it. */
    private static boolean returnsOwner(MethodTree method, ClassTree owner) {
        return method.getReturnType() != null
            && method.getReturnType().toString().endsWith(owner.getSimpleName().toString());
    }

    /**
     * Whether the declaring class is itself generated, which is what separates a
     * builder's {@code build()} from the {@code builder()} and {@code mutate()}
     * entry points on the class it builds - all three take no arguments and
     * return a type other than their own.
     */
    private static boolean declaredOnBuilder(ClassTree owner) {
        return AstMarkers.isGenerated((JCTree) owner);
    }

    /**
     * The first sentence of a doc comment, stripped of block tags, inline markup
     * and its trailing period.
     */
    private static String firstSentence(String comment) {
        if (comment == null) return null;
        String text = comment.trim();
        int tag = text.indexOf("\n@");
        if (tag >= 0) text = text.substring(0, tag);
        text = text.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        int stop = text.indexOf(". ");
        if (stop >= 0) text = text.substring(0, stop);
        while (text.endsWith(".")) text = text.substring(0, text.length() - 1).trim();
        return text.isEmpty() ? null : text;
    }

    /** A camel-cased or underscored identifier as spaced lowercase words. */
    private static String spaced(String identifier) {
        return identifier.replaceAll("(?<=[a-z0-9])(?=[A-Z])", " ")
            .replace('_', ' ').replaceAll("\\s+", " ").trim().toLowerCase();
    }

    private static String capitalise(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static String decapitalise(String text) {
        if (text.isEmpty() || Character.isLowerCase(text.charAt(0))) return text;
        if (text.length() > 1 && Character.isUpperCase(text.charAt(1))) return text;
        return Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }

}
