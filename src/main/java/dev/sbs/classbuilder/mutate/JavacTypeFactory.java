package dev.sbs.classbuilder.mutate;

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
     * Parses a javax.lang.model type display string into a javac type
     * expression. Handles primitives, arrays, and generic type arguments
     * recursively.
     */
    public JCExpression parseType(String display) {
        String s = display.trim();
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
