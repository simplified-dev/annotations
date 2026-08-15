package dev.simplified.enumlookup.inspect;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiType;
import com.intellij.psi.impl.source.PsiExtensibleClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.lang.model.SourceVersion;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Flags misuse of {@code @EnumLookup} and {@code @KeyField} at edit time:
 *
 * <ul>
 *   <li>{@code @EnumLookup} on a non-enum type - ERROR.</li>
 *   <li>{@code @KeyField} on a static field - ERROR.</li>
 *   <li>{@code @KeyField} on a field whose enclosing type lacks {@code @EnumLookup}
 *       - WARNING (the annotation is a no-op).</li>
 *   <li>{@code @KeyField(strictNullKeys = true)} on a primitive-typed field
 *       - WARNING (attribute has no effect).</li>
 *   <li>{@code @KeyField(ignoreCase = true)} on a field that is not a
 *       {@code String} - WARNING (attribute has no effect).</li>
 *   <li>{@code @KeyField(methodName = "...")} that doesn't start with an
 *       uppercase letter or isn't a valid Java identifier - ERROR.</li>
 *   <li>Two {@code @KeyField}s on the same enum that produce a colliding
 *       generated method signature - ERROR.</li>
 *   <li>A hand-rolled field named {@code CACHED_VALUES} or
 *       {@code CACHED_KEYS_<field>} on an annotated enum - ERROR, matching the
 *       processor, which refuses the enum outright rather than emitting a
 *       second assignment to the author's own {@code final}.</li>
 * </ul>
 */
public final class EnumLookupInspection extends LocalInspectionTool {

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitClass(@NotNull PsiClass clazz) {
                super.visitClass(clazz);
                PsiAnnotation enumLookup = findAnnotation(clazz, EnumLookupConstants.ENUM_LOOKUP_FQN);
                if (enumLookup != null && !clazz.isEnum()) {
                    holder.registerProblem(enumLookup,
                        "@EnumLookup is only supported on enum types",
                        ProblemHighlightType.GENERIC_ERROR);
                }
                if (enumLookup != null && clazz.isEnum()) {
                    checkMethodSignatureCollisions(clazz);
                    checkCacheFieldCollisions(clazz);
                }
            }

            @Override
            public void visitField(@NotNull PsiField field) {
                super.visitField(field);
                PsiAnnotation keyField = findAnnotation(field, EnumLookupConstants.KEY_FIELD_FQN);
                if (keyField == null) return;

                if (field.hasModifierProperty(PsiModifier.STATIC)) {
                    holder.registerProblem(keyField,
                        "@KeyField is not allowed on static fields",
                        ProblemHighlightType.GENERIC_ERROR);
                    return;
                }

                PsiClass enclosing = field.getContainingClass();
                boolean enclosingIsAnnotatedEnum = enclosing != null
                    && enclosing.isEnum()
                    && findAnnotation(enclosing, EnumLookupConstants.ENUM_LOOKUP_FQN) != null;
                if (!enclosingIsAnnotatedEnum) {
                    holder.registerProblem(keyField,
                        "@KeyField has no effect - enclosing type is not an @EnumLookup enum",
                        ProblemHighlightType.WARNING);
                }

                String methodName = EnumLookupConstants.stringAttr(keyField,
                    EnumLookupConstants.ATTR_METHOD_NAME, "");
                if (!methodName.isEmpty() && !isValidMethodSuffix(methodName)) {
                    holder.registerProblem(keyField,
                        "@KeyField(methodName) must start with an uppercase letter and be a valid Java identifier",
                        ProblemHighlightType.GENERIC_ERROR);
                }

                boolean strictNullKeys = EnumLookupConstants.booleanAttr(keyField,
                    EnumLookupConstants.ATTR_STRICT_NULL_KEYS, false);
                if (strictNullKeys && isPrimitive(field.getType())) {
                    holder.registerProblem(keyField,
                        "@KeyField(strictNullKeys = true) has no effect on a primitive-typed field",
                        ProblemHighlightType.WARNING);
                }

                boolean ignoreCase = EnumLookupConstants.booleanAttr(keyField,
                    EnumLookupConstants.ATTR_IGNORE_CASE, false);
                if (ignoreCase && !isString(field.getType())) {
                    holder.registerProblem(keyField,
                        "@KeyField(ignoreCase = true) has no effect on a field that is not a String",
                        ProblemHighlightType.WARNING);
                }
            }

            /**
             * Flags a hand-rolled cache field the annotation would generate
             * under the same name.
             *
             * <p>The processor refuses the whole enum for this, so saying it
             * here is what keeps the editor from being quietly greener than the
             * build. A hand-rolled {@code values()} cache is also the usual
             * reason for adopting {@code @EnumLookup} in the first place, so the
             * field is nearly always meant to go.
             */
            private void checkCacheFieldCollisions(PsiClass enumClass) {
                Iterable<PsiField> fields = enumClass instanceof PsiExtensibleClass ext
                    ? ext.getOwnFields()
                    : java.util.List.of(enumClass.getFields());

                Set<String> owned = new HashSet<>();
                owned.add(EnumLookupConstants.CACHED_VALUES);
                for (PsiField field : enumClass.getFields()) {
                    if (field.hasModifierProperty(PsiModifier.STATIC)) continue;
                    if (findAnnotation(field, EnumLookupConstants.KEY_FIELD_FQN) == null) continue;
                    owned.add(EnumLookupConstants.CACHED_KEYS_PREFIX + field.getName());
                }

                for (PsiField field : fields) {
                    if (!owned.contains(field.getName())) continue;
                    PsiElement anchor = field.getNameIdentifier();
                    holder.registerProblem(anchor != null ? anchor : field,
                        "@EnumLookup generates a field named '" + field.getName()
                            + "' - delete this declaration and read the generated field, which is "
                            + "private static final and carries the same name",
                        ProblemHighlightType.GENERIC_ERROR);
                }
            }

            /**
             * Flags pairs of {@code @KeyField}s whose effective method suffix
             * AND parameter type match - those would produce duplicate
             * generated method signatures. Either the suffix differs or the
             * parameter type differs; both flagged with a hint at
             * {@code methodName}.
             */
            private void checkMethodSignatureCollisions(PsiClass enumClass) {
                Iterable<PsiField> fields = enumClass instanceof PsiExtensibleClass ext
                    ? ext.getOwnFields()
                    : java.util.List.of(enumClass.getFields());

                // suffix + paramTypeKey -> first field that emitted it
                Map<String, PsiField> seen = new HashMap<>();
                for (PsiField field : fields) {
                    if (field.hasModifierProperty(PsiModifier.STATIC)) continue;
                    PsiAnnotation keyField = findAnnotation(field, EnumLookupConstants.KEY_FIELD_FQN);
                    if (keyField == null) continue;
                    String fieldName = field.getName();
                    if (fieldName == null) continue;
                    String suffix = effectiveSuffix(keyField, fieldName);
                    String key = suffix + "|" + canonical(field.getType());
                    PsiField previous = seen.putIfAbsent(key, field);
                    if (previous != null) {
                        holder.registerProblem(keyField,
                            "@KeyField produces colliding signature 'of" + suffix
                                + "(" + canonical(field.getType()) + ")' with '"
                                + previous.getName() + "' - set methodName to disambiguate",
                            ProblemHighlightType.GENERIC_ERROR);
                    }
                }
            }
        };
    }

    private static @Nullable PsiAnnotation findAnnotation(PsiClass clazz, String fqn) {
        for (PsiAnnotation a : clazz.getAnnotations()) {
            if (fqn.equals(a.getQualifiedName())) return a;
        }
        return null;
    }

    private static @Nullable PsiAnnotation findAnnotation(PsiField field, String fqn) {
        for (PsiAnnotation a : field.getAnnotations()) {
            if (fqn.equals(a.getQualifiedName())) return a;
        }
        return null;
    }

    private static boolean isValidMethodSuffix(String s) {
        if (s.isEmpty()) return false;
        if (!Character.isUpperCase(s.charAt(0))) return false;
        return SourceVersion.isIdentifier(s) && !SourceVersion.isKeyword(s);
    }

    private static boolean isPrimitive(PsiType type) {
        return type instanceof com.intellij.psi.PsiPrimitiveType;
    }

    /**
     * Whether a key field's type is {@link String}.
     *
     * <p>Accepts the unqualified spelling as well as the canonical one. The
     * canonical text of a reference the index has not resolved is the text as
     * written, and this decides only whether to <b>warn</b> that an attribute is
     * inert - so a type that might be {@code String} is left alone rather than
     * flagged on a resolution accident.
     */
    private static boolean isString(PsiType type) {
        String text = type.getCanonicalText();
        return "java.lang.String".equals(text) || "String".equals(text);
    }

    private static String effectiveSuffix(PsiAnnotation keyField, String fieldName) {
        String override = EnumLookupConstants.stringAttr(keyField,
            EnumLookupConstants.ATTR_METHOD_NAME, "");
        if (!override.isEmpty()) return override;
        return Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
    }

    private static String canonical(PsiType type) {
        return type.getCanonicalText();
    }
}
