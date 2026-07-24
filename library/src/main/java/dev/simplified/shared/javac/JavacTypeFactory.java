package dev.simplified.shared.javac;

import com.sun.tools.javac.code.BoundKind;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Names;

import java.util.ArrayList;

/**
 * Builds {@link JCExpression} type references from textual type descriptions
 * produced by the {@link javax.lang.model} API (e.g.
 * {@code "java.util.Optional&lt;java.lang.String&gt;"}).
 *
 * <p>Emits fully-qualified {@code Select} chains for class references so that
 * the injected code compiles regardless of what imports the target's
 * compilation unit carries - we never touch import declarations, which is
 * what historically broke Lombok on new JDKs.
 */
public final class JavacTypeFactory {

    private final TreeMaker make;
    private final Names names;

    public JavacTypeFactory(TreeMaker make, Names names) {
        this.make = make;
        this.names = names;
    }

    /**
     * Builds a fully-qualified identifier chain for the given dotted name.
     * {@code "java.util.List"} becomes
     * {@code Select(Select(Ident("java"), "util"), "List")}.
     */
    public JCExpression qualIdent(String fqn) {
        String[] parts = fqn.split("\\.");
        JCExpression result = make.Ident(names.fromString(parts[0]));
        for (int i = 1; i < parts.length; i++) {
            result = make.Select(result, names.fromString(parts[i]));
        }
        return result;
    }

    /** Simple unqualified identifier - used for parameter names, local refs, etc. */
    public JCExpression ident(String name) {
        return make.Ident(names.fromString(name));
    }

    /**
     * Like {@link #parseType} but yields the wrapper for a primitive, so the
     * result is usable as a type argument - {@code Supplier<Boolean>}, never the
     * uncompilable {@code Supplier<boolean>}. Non-primitives are unchanged.
     *
     * @param display the type-display string
     * @return the type expression, boxed when primitive
     */
    public JCExpression parseBoxedType(String display) {
        String boxed = switch (stripTypeUseAnnotations(display.trim())) {
            case "boolean" -> "java.lang.Boolean";
            case "byte" -> "java.lang.Byte";
            case "short" -> "java.lang.Short";
            case "int" -> "java.lang.Integer";
            case "long" -> "java.lang.Long";
            case "char" -> "java.lang.Character";
            case "float" -> "java.lang.Float";
            case "double" -> "java.lang.Double";
            default -> null;
        };
        return boxed != null ? qualIdent(boxed) : parseType(display);
    }

    /**
     * Parses a javax.lang.model type display string into a javac type
     * expression. Handles primitives, arrays, and generic type arguments
     * recursively.
     *
     * <p>Type-use annotations are stripped first. {@link javax.lang.model}
     * renders a field whose type carries a {@code TYPE_USE} annotation (e.g.
     * {@code @NotNull}) into the display string with the annotation spliced
     * before the simple name - {@code "pkg.@org.jetbrains.annotations.NotNull
     * Name"}. Feeding that verbatim to {@link #qualIdent} would split on the
     * dots and treat {@code @org} as a name segment, producing uncompilable
     * {@code "package pkg.@org.jetbrains.annotations does not exist"} errors.
     * The generated builder members do not need the type-use annotation, so
     * it is dropped.
     */
    public JCExpression parseType(String display) {
        String s = stripTypeUseAnnotations(display.trim());
        // Wildcard type arguments: "?", "? extends X", "? super X". A
        // javax.lang.model DeclaredType with a wildcard argument renders it
        // verbatim into the display string; without this branch "?" falls
        // through to qualIdent and becomes an identifier named "?", which javac
        // reports as `cannot find symbol: class ?` at the enclosing declaration.
        if (s.equals("?")) {
            return make.Wildcard(make.TypeBoundKind(BoundKind.UNBOUND), null);
        }
        if (s.startsWith("? extends ")) {
            return make.Wildcard(make.TypeBoundKind(BoundKind.EXTENDS),
                parseType(s.substring("? extends ".length())));
        }
        if (s.startsWith("? super ")) {
            return make.Wildcard(make.TypeBoundKind(BoundKind.SUPER),
                parseType(s.substring("? super ".length())));
        }
        if (s.endsWith("[]")) {
            return make.TypeArray(parseType(s.substring(0, s.length() - 2)));
        }
        TypeTag tag = primitiveTag(s);
        if (tag != null) return make.TypeIdent(tag);
        int lt = s.indexOf('<');
        if (lt < 0) return qualIdent(s);

        String base = s.substring(0, lt);
        String args = s.substring(lt + 1, s.length() - 1);
        java.util.List<JCExpression> parsed = new ArrayList<>();
        for (String a : splitTopLevel(args)) parsed.add(parseType(a));
        return make.TypeApply(qualIdent(base), List.from(parsed));
    }

    /**
     * Removes every {@code @Annotation} token from a type-display string,
     * including a fully-qualified annotation name and any parenthesised
     * argument list, plus the whitespace that separates it from the type.
     * Applied position-independently so it copes with the annotation at the
     * head ({@code "@NotNull java.lang.String"}), before a simple name
     * ({@code "java.util.@NotNull Optional<...>"}), or nested inside a type
     * argument ({@code "List<@NotNull String>"}) - and with however the running
     * JDK's {@code Type.toString()} chooses to place it.
     */
    static String stripTypeUseAnnotations(String display) {
        if (display.indexOf('@') < 0) return display;
        StringBuilder out = new StringBuilder(display.length());
        int i = 0;
        int n = display.length();
        while (i < n) {
            char c = display.charAt(i);
            if (c != '@') {
                out.append(c);
                i++;
                continue;
            }
            i++; // consume '@'
            while (i < n && (Character.isJavaIdentifierPart(display.charAt(i)) || display.charAt(i) == '.')) i++;
            if (i < n && display.charAt(i) == '(') {
                int depth = 0;
                do {
                    char d = display.charAt(i++);
                    if (d == '(') depth++;
                    else if (d == ')') depth--;
                } while (i < n && depth > 0);
            }
            while (i < n && Character.isWhitespace(display.charAt(i))) i++;
        }
        return out.toString();
    }

    /** Splits {@code "K, V<A, B>, T"} at top-level commas, preserving nested generics. */
    private static java.util.List<String> splitTopLevel(String csv) {
        java.util.List<String> out = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            if (c == ',' && depth == 0) {
                out.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) out.add(cur.toString().trim());
        return out;
    }

    private static TypeTag primitiveTag(String s) {
        return switch (s) {
            case "boolean" -> TypeTag.BOOLEAN;
            case "byte" -> TypeTag.BYTE;
            case "short" -> TypeTag.SHORT;
            case "int" -> TypeTag.INT;
            case "long" -> TypeTag.LONG;
            case "char" -> TypeTag.CHAR;
            case "float" -> TypeTag.FLOAT;
            case "double" -> TypeTag.DOUBLE;
            case "void" -> TypeTag.VOID;
            default -> null;
        };
    }

}
