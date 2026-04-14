package dev.sbs.classbuilder.editor;

import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiRecordComponent;
import dev.sbs.classbuilder.inspect.ClassBuilderConstants;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Walks a {@code @ClassBuilder}-annotated PsiClass (or a record) and derives
 * the {@link PsiFieldShape} list the augment provider uses to synthesise
 * setters. Honours {@code @BuilderIgnore} and the annotation's
 * {@code exclude} attribute.
 */
final class PsiFieldShapeExtractor {

    private static final String BUILDER_IGNORE_FQN = "dev.sbs.annotation.BuilderIgnore";

    private PsiFieldShapeExtractor() {
    }

    /**
     * Extracts shapes from a concrete or abstract target class. Record
     * components are handled separately by {@link #fromRecord}.
     */
    static List<PsiFieldShape> fromClass(PsiClass target, Set<String> excluded) {
        List<PsiFieldShape> out = new ArrayList<>();
        for (PsiField field : target.getFields()) {
            if (field.hasModifierProperty(PsiModifier.STATIC)) continue;
            if (field.hasModifierProperty(PsiModifier.TRANSIENT)) continue;
            if (excluded.contains(field.getName())) continue;
            if (hasAnnotation(field, BUILDER_IGNORE_FQN)) continue;
            out.add(PsiFieldShape.of(field.getName(), field.getType()));
        }
        return out;
    }

    /** Record-component variant; records expose fields via {@link PsiRecordComponent}. */
    static List<PsiFieldShape> fromRecord(PsiClass record, Set<String> excluded) {
        List<PsiFieldShape> out = new ArrayList<>();
        for (PsiRecordComponent c : record.getRecordComponents()) {
            String name = c.getName();
            if (excluded.contains(name)) continue;
            if (hasAnnotation(c, BUILDER_IGNORE_FQN)) continue;
            out.add(PsiFieldShape.of(name, c.getType()));
        }
        return out;
    }

    /** True when the element carries the given FQN annotation. */
    static boolean hasAnnotation(com.intellij.psi.PsiModifierListOwner owner, String fqn) {
        for (PsiAnnotation a : owner.getAnnotations()) {
            if (fqn.equals(a.getQualifiedName())) return true;
        }
        return false;
    }

    /** Reads the {@code @ClassBuilder} annotation on {@code target}. */
    static PsiAnnotation classBuilderAnnotation(PsiClass target) {
        for (PsiAnnotation a : target.getAnnotations()) {
            if (ClassBuilderConstants.ANNOTATION_FQN.equals(a.getQualifiedName())) return a;
        }
        return null;
    }

}
