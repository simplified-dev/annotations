package dev.simplified.shared.javac;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ImportTree;

/**
 * Decides whether a written annotation name refers to a given annotation type.
 *
 * <p>Annotations are not attributed at the moment a mutation pass reads them - a
 * local variable's never are during annotation processing - so the decision has
 * to be made on what the author wrote. The fully-qualified spelling always
 * counts; the bare simple name counts only in a compilation unit where it
 * resolves to this annotation.
 *
 * <p>A single-type import settles the question on its own, in both directions.
 * JLS 6.4.1 gives it priority over every on-demand import and over the unit's own
 * package, so one naming another type of the same simple name means the bare name
 * is not this annotation however the unit is otherwise arranged.
 */
public final class AnnotationSpelling {

    private AnnotationSpelling() {
    }

    /**
     * Whether the written annotation name refers to the given type.
     *
     * @param written the annotation type exactly as spelled at the site
     * @param fqn the annotation's fully-qualified name
     * @param unit the compilation unit the site sits in
     * @return {@code true} when the spelling names that annotation
     */
    public static boolean names(String written, String fqn, CompilationUnitTree unit) {
        if (fqn.equals(written)) return true;
        return simpleNameOf(fqn).equals(written) && simpleNameResolves(unit, fqn);
    }

    /**
     * Whether the bare simple name of the given annotation resolves to it in the
     * compilation unit - by single-type import, by an on-demand import of its
     * package, or by the unit being in that package itself.
     *
     * @param unit the compilation unit to read
     * @param fqn the annotation's fully-qualified name
     * @return {@code true} when the bare name names that annotation there
     */
    public static boolean simpleNameResolves(CompilationUnitTree unit, String fqn) {
        if (unit == null) return false;
        String simple = simpleNameOf(fqn);
        String pkg = packageOf(fqn);
        boolean onDemand = false;
        for (ImportTree imported : unit.getImports()) {
            if (imported.isStatic()) continue;
            String qualified = imported.getQualifiedIdentifier().toString();
            if (fqn.equals(qualified)) return true;
            if (qualified.endsWith("." + simple)) return false;
            if ((pkg + ".*").equals(qualified)) onDemand = true;
        }
        if (onDemand) return true;
        ExpressionTree name = unit.getPackageName();
        return name != null && pkg.equals(name.toString());
    }

    private static String simpleNameOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }

    private static String packageOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? "" : fqn.substring(0, dot);
    }

}
