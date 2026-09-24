package dev.simplified.classbuilder.mutate;

import com.sun.source.tree.Tree;
import com.sun.tools.javac.tree.JCTree.JCFieldAccess;
import com.sun.tools.javac.tree.JCTree.JCIdent;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeScanner;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.Lazy;
import dev.simplified.annotations.Setter;
import dev.simplified.classbuilder.apt.AccessorScheme;
import dev.simplified.classbuilder.apt.FieldSpec;
import dev.simplified.classbuilder.apt.InstanceDefaults;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.util.Elements;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Decides which retained initializers reach for the enclosing instance.
 *
 * <p>A default touching no instance state is hoisted into a {@code static}
 * {@code $default$} provider and evaluated when the builder is created. One
 * that does reach for the instance cannot be: no target exists at that point.
 * Those are computed in the generated constructor instead, where {@code this}
 * is available - the same place an ordinary field initializer runs.
 *
 * <p>Detection is syntactic. At annotation-processing time the initializer has
 * been parsed and entered but not attributed, so its identifiers carry no
 * resolved symbols. It is therefore deliberately over-eager: a lambda
 * parameter sharing a name with an instance field counts as a reference. That
 * costs nothing but a slightly later evaluation, because the constructor path
 * handles static-safe expressions correctly too - it is the more general of
 * two working choices, never the wrong one.
 *
 * <p>The rule itself is {@link InstanceDefaults#readsInstanceState}, which the
 * editor asks of the names it lists out of PSI; what lives here is listing them
 * from the tree and naming the target's instance members.
 */
final class InstanceDefaultDetector {

    private InstanceDefaultDetector() {
    }

    /**
     * Names of fields whose captured initializer reads instance state.
     *
     * @param target the annotated type
     * @param fields the builder-visible fields
     * @param elements element utilities, for walking inherited members
     * @return field names needing the constructor-computed path, never null
     */
    static Set<String> detect(TypeElement target, List<FieldSpec> fields, Elements elements) {
        Set<String> instanceMembers = instanceMemberNames(target, elements);
        Set<String> out = new LinkedHashSet<>();
        for (FieldSpec f : fields) {
            Tree captured = f.sourceInitializerTree;
            if (!(captured instanceof JCTree tree)) continue;
            if (readsInstanceState(tree, instanceMembers)) out.add(f.name);
        }
        return out;
    }

    /**
     * Every non-static field and method visible on the target, inherited ones
     * included, and every instance accessor another pass generates on it or on
     * a supertype. {@code getClass()} and friends arrive via
     * {@link Elements#getAllMembers}, which is why a bare {@code getClass()} in
     * an initializer is spotted rather than mistaken for a static call.
     *
     * <p>The generated accessors do not arrive that way: the accessor and
     * {@code @Lazy} passes append theirs after this pass reads the target, and
     * enter no symbol. They are named from the annotations written on each
     * type's fields through {@link InstanceDefaults#generatedAccessorNames},
     * which the editor asks of the same annotations read out of PSI.
     */
    private static Set<String> instanceMemberNames(TypeElement target, Elements elements) {
        Set<String> names = new HashSet<>();
        for (Element member : elements.getAllMembers(target)) {
            ElementKind kind = member.getKind();
            if (kind != ElementKind.FIELD && kind != ElementKind.METHOD) continue;
            if (member.getModifiers().contains(Modifier.STATIC)) continue;
            names.add(member.getSimpleName().toString());
        }
        for (TypeElement type = target; type != null; type = superclassOf(type)) {
            Getter typeGetter = type.getAnnotation(Getter.class);
            Setter typeSetter = type.getAnnotation(Setter.class);
            for (Element member : type.getEnclosedElements()) {
                if (member.getKind() != ElementKind.FIELD) continue;
                Getter getter = member.getAnnotation(Getter.class);
                Setter setter = member.getAnnotation(Setter.class);
                Lazy lazy = member.getAnnotation(Lazy.class);
                if (getter == null) getter = typeGetter;
                if (setter == null) setter = typeSetter;
                names.addAll(InstanceDefaults.generatedAccessorNames(
                    member.getSimpleName().toString(),
                    member.asType().getKind() == TypeKind.BOOLEAN,
                    member.getModifiers().contains(Modifier.STATIC),
                    getter == null ? null : AccessorScheme.resolve(getter.style(), getter.name()),
                    setter == null ? null : AccessorScheme.resolve(setter.style(), setter.name()),
                    lazy == null ? null : AccessorScheme.resolve(lazy.style(), lazy.name())));
            }
        }
        return names;
    }

    /** The type's superclass as a type element, or {@code null} at the top of the chain. */
    private static TypeElement superclassOf(TypeElement type) {
        return type.getSuperclass() instanceof DeclaredType declared
            && declared.asElement() instanceof TypeElement superclass
            ? superclass
            : null;
    }

    private static boolean readsInstanceState(JCTree tree, Set<String> instanceMembers) {
        SpelledNames spelled = new SpelledNames();
        tree.accept(spelled);
        return InstanceDefaults.readsInstanceState(spelled.names, instanceMembers);
    }

    /**
     * Lists every name an initializer spells without a qualifier, and
     * {@code this} for each qualified {@code Outer.this}, which is what
     * {@link InstanceDefaults#readsInstanceState} is asked of.
     */
    private static final class SpelledNames extends TreeScanner {

        private final List<String> names = new ArrayList<>();

        @Override
        public void visitIdent(JCIdent tree) {
            names.add(tree.name.toString());
            super.visitIdent(tree);
        }

        @Override
        public void visitSelect(JCFieldAccess tree) {
            // Qualified forms such as Outer.this.field. The selected expression
            // is scanned by super, which reaches any this/super ident, so only
            // the selected name itself needs checking here.
            if (tree.name.toString().equals("this")) names.add("this");
            super.visitSelect(tree);
        }
    }

}
