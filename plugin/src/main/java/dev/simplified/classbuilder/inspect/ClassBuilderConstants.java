package dev.simplified.classbuilder.inspect;

import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.CommonClassNames;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiCompiledElement;
import com.intellij.psi.PsiConstantEvaluationHelper;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiImportList;
import com.intellij.psi.PsiImportStaticStatement;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiReferenceList;
import com.intellij.psi.PsiReferenceParameterList;
import com.intellij.psi.PsiTypeElement;
import com.intellij.psi.PsiTypeParameter;
import com.intellij.psi.impl.source.PsiExtensibleClass;
import com.intellij.psi.util.PsiTreeUtil;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.annotations.SetterNames;
import dev.simplified.args.apt.ArgsMode;
import dev.simplified.args.inspect.ArgsConstants;
import dev.simplified.classbuilder.apt.BuilderScheme;
import dev.simplified.classbuilder.apt.ChainRole;
import dev.simplified.classbuilder.apt.DeclaredBuildMethod;
import dev.simplified.classbuilder.apt.DeclaredBuilderFacts;
import dev.simplified.classbuilder.apt.DeclaredBuilderRejection;
import dev.simplified.classbuilder.apt.DeclaredBuilderShape;
import dev.simplified.classbuilder.apt.RoleExpectation;
import dev.simplified.classbuilder.apt.SetterScheme;
import dev.simplified.classbuilder.editor.MergedSlotStorage;
import dev.simplified.shared.psi.AbstractRecursionSafeAugmentProvider;
import dev.simplified.shared.psi.WrittenAnnotations;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Shared FQNs and attribute-reading helpers for the {@code @ClassBuilder}
 * IDE support. Mirrors the layout of {@code ResourcePathConstants}.
 *
 * <p>Every reader here asks for the <b>declared</b> attribute value and supplies
 * the annotation's own default itself, which is what the {@code fallback}
 * argument on each of them is. That is not a shortcut: reading the value the
 * platform fills in resolves the annotation type, and a resolve started from an
 * annotation written inside a class body walks that class's nested types, which
 * is augment-aware and re-enters the provider that asked. The defaults are
 * stated once at each call site instead.
 */
public final class ClassBuilderConstants {

    public static final @NotNull String ANNOTATION_FQN = "dev.simplified.annotations.ClassBuilder";
    public static final @NotNull String ANNOTATION_SHORT_NAME = "ClassBuilder";
    public static final @NotNull String XCONTRACT_FQN = "dev.simplified.annotations.XContract";

    public static final @NotNull String BUILDER_DEFAULT_FQN = "dev.simplified.annotations.BuilderDefault";
    public static final @NotNull String BUILDER_IGNORE_FQN = "dev.simplified.annotations.BuilderIgnore";
    public static final @NotNull String BUILDER_SEED_FQN = "dev.simplified.annotations.BuilderSeed";
    public static final @NotNull String SETTER_NAMES_FQN = "dev.simplified.annotations.SetterNames";
    public static final @NotNull String BUILD_FLAG_FQN = "dev.simplified.annotations.BuildFlag";
    public static final @NotNull String OBTAIN_VIA_FQN = "dev.simplified.annotations.ObtainVia";
    public static final @NotNull String ASSIGN_VIA_FQN = "dev.simplified.annotations.AssignVia";

    /**
     * The container javac wraps a repeated {@code @AssignVia} in. Only ever seen
     * on a slot read out of a class file - a source declaration presents each one
     * separately - but read for the same reason the processor reads it.
     */
    public static final @NotNull String ASSIGN_VIA_LIST_FQN = ASSIGN_VIA_FQN + ".List";
    public static final @NotNull String COLLECTOR_FQN = "dev.simplified.annotations.Collector";
    public static final @NotNull String NEGATE_FQN = "dev.simplified.annotations.Negate";
    public static final @NotNull String FORMATTABLE_FQN = "dev.simplified.annotations.Formattable";
    public static final @NotNull String LAZY_FQN = "dev.simplified.annotations.Lazy";

    /**
     * FQNs of every annotation whose PSI changes should invalidate the editor-
     * side synthesis. Consumed by {@code ClassBuilderChangeService} to decide
     * whether a tree-change event warrants a {@code DaemonCodeAnalyzer.restart()}.
     */
    public static final @NotNull Set<String> TRACKED_ANNOTATION_FQNS = Set.of(
        ANNOTATION_FQN,
        BUILDER_DEFAULT_FQN,
        BUILDER_IGNORE_FQN,
        BUILDER_SEED_FQN,
        SETTER_NAMES_FQN,
        BUILD_FLAG_FQN,
        OBTAIN_VIA_FQN,
        ASSIGN_VIA_FQN,
        COLLECTOR_FQN,
        NEGATE_FQN,
        FORMATTABLE_FQN,
        LAZY_FQN
    );

    /**
     * Short-name fallback for the tracked set - used when the PSI is in dumb
     * mode and {@link PsiAnnotation#getQualifiedName()} returns the unqualified
     * name. A short-name false positive here just triggers a harmless extra
     * daemon restart.
     */
    public static final @NotNull Set<String> TRACKED_ANNOTATION_SHORT_NAMES = Set.of(
        ANNOTATION_SHORT_NAME,
        "BuilderDefault",
        "BuilderIgnore",
        "BuilderSeed",
        "SetterNames",
        "BuildFlag",
        "ObtainVia",
        "AssignVia",
        "Collector",
        "Negate",
        "Formattable",
        "Lazy"
    );

    public static final @NotNull String ATTR_STYLE = "style";
    public static final @NotNull String ATTR_SETTERS = "setters";
    public static final @NotNull String ATTR_BUILDER = "builder";
    public static final @NotNull String ATTR_EMIT_CONTRACTS = "emitContracts";
    public static final @NotNull String ATTR_ACCESS = "access";
    public static final @NotNull String ATTR_CONSTRUCTOR_ACCESS = "constructorAccess";
    public static final @NotNull String ATTR_BUILDER_CONSTRUCTOR_ACCESS = "builderConstructorAccess";
    public static final @NotNull String ATTR_FACTORY_METHOD = "factoryMethod";
    public static final @NotNull String ATTR_GENERATE_COPY_CONSTRUCTOR = "generateCopyConstructor";
    public static final @NotNull String ATTR_RETAIN_INIT = "retainInit";

    /** Attribute names of {@code @SetterNames}, in declaration order. */
    public static final @NotNull String[] SETTER_ROLES =
        {"set", "flag", "add", "put", "compute", "clear", "remove"};

    /** Attribute names of {@code @BuilderNames}, in declaration order. */
    public static final @NotNull String[] BUILDER_ROLES = {"type", "builder", "build", "from", "toBuilder"};

    /** The two naming annotations by their fully qualified names, which is how a static import spells them. */
    private static final @NotNull Set<String> NAMING_CLASSES_QUALIFIED =
        Set.of(SETTER_NAMES_FQN, "dev.simplified.annotations.BuilderNames");

    /** Every spelling a qualified {@code NONE} or {@code INHERIT} reference gives the class it is declared on. */
    private static final @NotNull Set<String> NAMING_CLASSES =
        Set.of("SetterNames", "BuilderNames", SETTER_NAMES_FQN, "dev.simplified.annotations.BuilderNames");

    private ClassBuilderConstants() {}

    public static @NotNull String stringAttr(@Nullable PsiAnnotation annotation, @NotNull String attr, @NotNull String fallback) {
        if (annotation == null) return fallback;
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue(attr);
        if (value instanceof PsiLiteralExpression literal && literal.getValue() instanceof String s && !s.isEmpty()) return s;
        return fallback;
    }

    /**
     * Reads a naming attribute of {@code @BuilderNames} or {@code @SetterNames}
     * only when it is written at the annotation, mirroring the processor's
     * {@code getElementValues()} view rather than {@code findAttributeValue}'s
     * defaults-included one. Returning {@code null} for an unwritten attribute
     * is what lets the schemes tell "inherit from the style" from an explicit
     * value, empty ones included.
     *
     * <p>javac hands the processor the value a constant holds, so a written
     * constant is read as that value rather than as unwritten. The two the
     * annotations declare, {@code NONE} and {@code INHERIT}, are recognised by
     * name without resolving - qualified by either annotation's simple or fully
     * qualified name, or unqualified where the file statically imports them
     * from one of the two. Any other expression goes to the platform's constant
     * evaluator under the owning class's re-entry guard, since resolving it can
     * reach the augment pass that is reading this attribute; one the evaluator
     * cannot answer stays unwritten.
     *
     * @param annotation the annotation to read, or {@code null}
     * @param attr the attribute name
     * @return the written value, or {@code null}
     */
    public static @Nullable String writtenStringAttr(@Nullable PsiAnnotation annotation, @NotNull String attr) {
        if (annotation == null) return null;
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue(attr);
        if (value == null) return null;
        if (value instanceof PsiLiteralExpression literal)
            return literal.getValue() instanceof String s ? s : null;
        String named = namingConstant(value);
        return named != null ? named : evaluatedString(value);
    }

    /**
     * The value of {@code NONE} or {@code INHERIT} when the expression names one
     * of the two as declared on {@code @BuilderNames} or {@code @SetterNames},
     * read from the reference and the file's static imports alone.
     *
     * @param value the written attribute value
     * @return the constant's value, or {@code null} when the expression names neither
     */
    private static @Nullable String namingConstant(@NotNull PsiAnnotationMemberValue value) {
        if (!(value instanceof PsiReferenceExpression reference)) return null;
        String name = reference.getReferenceName();
        String constant = namingConstantValue(name);
        if (constant == null) return null;
        PsiExpression qualifier = reference.getQualifierExpression();
        if (qualifier != null)
            return NAMING_CLASSES.contains(qualifier.getText().replaceAll("\\s", "")) ? constant : null;
        return staticallyImported(reference, name) ? constant : null;
    }

    /**
     * What a constant of that name holds on both naming annotations.
     *
     * @param name the referenced name, or {@code null}
     * @return the value, or {@code null} when the name is neither constant
     */
    private static @Nullable String namingConstantValue(@Nullable String name) {
        if ("NONE".equals(name)) return SetterNames.NONE;
        if ("INHERIT".equals(name)) return SetterNames.INHERIT;
        return null;
    }

    /**
     * Whether the file statically imports the name from one of the naming
     * annotations, by a single import of it or an import on demand.
     *
     * @param reference the unqualified reference
     * @param name its name
     * @return whether an import brings it in
     */
    private static boolean staticallyImported(@NotNull PsiElement reference, @NotNull String name) {
        if (!(reference.getContainingFile() instanceof PsiJavaFile file)) return false;
        PsiImportList imports = file.getImportList();
        if (imports == null) return false;
        for (PsiImportStaticStatement statement : imports.getImportStaticStatements()) {
            PsiJavaCodeReferenceElement imported = statement.getImportReference();
            if (imported == null) continue;
            String text = imported.getText().replaceAll("\\s", "");
            String declaring;
            if (statement.isOnDemand()) declaring = text;
            else if (text.endsWith("." + name)) declaring = text.substring(0, text.length() - name.length() - 1);
            else continue;
            if (NAMING_CLASSES_QUALIFIED.contains(declaring)) return true;
        }
        return false;
    }

    /**
     * Evaluates a written constant expression to the {@code String} javac
     * would read from it.
     *
     * @param value the written attribute value
     * @return the string it evaluates to, or {@code null} when it evaluates to none
     */
    private static @Nullable String evaluatedString(@NotNull PsiAnnotationMemberValue value) {
        PsiConstantEvaluationHelper evaluator =
            JavaPsiFacade.getInstance(value.getProject()).getConstantEvaluationHelper();
        PsiClass owner = PsiTreeUtil.getParentOfType(value, PsiClass.class);
        Object result = owner == null
            ? constantValue(evaluator, value, new HashSet<>())
            : AbstractRecursionSafeAugmentProvider.withInProgress(owner,
                () -> constantValue(evaluator, value, new HashSet<>()));
        return result instanceof String s ? s : null;
    }

    /**
     * Evaluates an expression through the platform's constant evaluator, with
     * every reference to a {@code final} field in it replaced by the literal of
     * the value that field holds, followed through its initializer rather than
     * asked of the field.
     *
     * <p>The field's own answer reads its declared type first, and this runs
     * inside the augment pass a resolve of that very type can have started -
     * a {@code String} constant declared on the target is the ordinary case -
     * so asking it re-enters the resolve in progress. The initializer is what
     * the field's value is computed from, so following it gives the same
     * answer without the type, and the evaluator is then handed an expression
     * of literals and operators only. A compiled field has no source type to
     * resolve and answers for itself.
     *
     * @param evaluator the platform's constant evaluator
     * @param expression the expression to evaluate
     * @param following the fields whose initializers are being followed, which ends a cycle
     * @return the constant value, or {@code null} when the expression is not a constant
     */
    private static @Nullable Object constantValue(@NotNull PsiConstantEvaluationHelper evaluator,
                                                  @NotNull PsiElement expression,
                                                  @NotNull Set<PsiField> following) {
        PsiField field = finalField(expression);
        if (field != null) return fieldValue(evaluator, field, following);
        List<PsiReferenceExpression> references = finalFieldReferences(expression);
        if (references.isEmpty()) return evaluator.computeConstantExpression(expression);

        int start = expression.getTextRange().getStartOffset();
        StringBuilder text = new StringBuilder(expression.getText());
        for (int i = references.size() - 1; i >= 0; i--) {
            PsiReferenceExpression reference = references.get(i);
            String literal = literalText(constantValue(evaluator, reference, following));
            if (literal == null) return null;
            TextRange range = reference.getTextRange().shiftLeft(start);
            text.replace(range.getStartOffset(), range.getEndOffset(), literal);
        }
        PsiExpression literals = JavaPsiFacade.getElementFactory(expression.getProject())
            .createExpressionFromText(text.toString(), expression);
        return evaluator.computeConstantExpression(literals);
    }

    /**
     * The value a {@code final} field holds, from its initializer.
     *
     * @param evaluator the platform's constant evaluator
     * @param field the field to read
     * @param following the fields whose initializers are being followed
     * @return the constant value, or {@code null} when it holds none
     */
    private static @Nullable Object fieldValue(@NotNull PsiConstantEvaluationHelper evaluator,
                                               @NotNull PsiField field,
                                               @NotNull Set<PsiField> following) {
        if (field instanceof PsiCompiledElement) return field.computeConstantValue();
        PsiExpression initializer = field.getInitializer();
        if (initializer == null || !following.add(field)) return null;
        try {
            return constantValue(evaluator, initializer, following);
        } finally {
            following.remove(field);
        }
    }

    /**
     * The {@code final} field an expression is a reference to.
     *
     * @param expression the expression to read
     * @return the field, or {@code null} when the expression names none
     */
    private static @Nullable PsiField finalField(@NotNull PsiElement expression) {
        return expression instanceof PsiReferenceExpression reference
            && reference.resolve() instanceof PsiField field
            && field.hasModifierProperty(PsiModifier.FINAL) ? field : null;
    }

    /**
     * The outermost references to a {@code final} field inside an expression,
     * in source order.
     *
     * @param expression the expression to read
     * @return the references, none nested in another
     */
    private static @NotNull List<PsiReferenceExpression> finalFieldReferences(@NotNull PsiElement expression) {
        List<PsiReferenceExpression> out = new ArrayList<>();
        for (PsiReferenceExpression reference : PsiTreeUtil.findChildrenOfType(expression,
            PsiReferenceExpression.class)) {
            if (!out.isEmpty() && out.get(out.size() - 1).getTextRange().contains(reference.getTextRange()))
                continue;
            if (finalField(reference) != null) out.add(reference);
        }
        return out;
    }

    /**
     * A constant value spelled as the Java literal that evaluates to it.
     *
     * @param value the value, or {@code null}
     * @return the literal, or {@code null} when the value has none
     */
    private static @Nullable String literalText(@Nullable Object value) {
        if (value instanceof String s) return "\"" + StringUtil.escapeStringCharacters(s) + "\"";
        if (value instanceof Character c) return "'" + StringUtil.escapeCharCharacters(String.valueOf(c)) + "'";
        if (value instanceof Boolean || value instanceof Integer) return value.toString();
        if (value instanceof Long l) return l + "L";
        if (value instanceof Short || value instanceof Byte) return "((" + primitiveName(value) + ") " + value + ")";
        if (value instanceof Double d) return Double.isFinite(d) ? d.toString() : null;
        if (value instanceof Float f) return Float.isFinite(f) ? f + "f" : null;
        return null;
    }

    /**
     * The primitive a boxed {@code short} or {@code byte} unboxes to.
     *
     * @param value the boxed value
     * @return the primitive's keyword
     */
    private static @NotNull String primitiveName(@NotNull Object value) {
        return value instanceof Short ? "short" : "byte";
    }

    /** Reads the {@code style} attribute, defaulting to {@link NamingStyle#SIMPLIFIED}. */
    public static @NotNull NamingStyle namingStyle(@Nullable PsiAnnotation annotation) {
        if (annotation == null) return NamingStyle.SIMPLIFIED;
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue(ATTR_STYLE);
        if (value instanceof PsiReferenceExpression ref) {
            String name = ref.getReferenceName();
            if (name != null) {
                try {
                    return NamingStyle.valueOf(name);
                } catch (IllegalArgumentException ignored) {
                    // Unresolvable or mid-typing reference - fall through.
                }
            }
        }
        return NamingStyle.SIMPLIFIED;
    }

    /**
     * Resolves the per-field setter patterns from the nested {@code setters}
     * attribute over the given style. An unwritten attribute leaves every role
     * inheriting.
     *
     * @param annotation the annotation to read, or {@code null}
     * @param style the style supplying every unwritten role
     * @return the resolved scheme
     */
    public static @NotNull SetterScheme setterScheme(@Nullable PsiAnnotation annotation,
                                                     @NotNull NamingStyle style) {
        PsiAnnotation setters = nestedAnnotation(annotation, ATTR_SETTERS);
        if (setters == null) return SetterScheme.of(style);
        return SetterScheme.resolve(style,
            writtenStringAttr(setters, "set"),
            writtenStringAttr(setters, "flag"),
            writtenStringAttr(setters, "add"),
            writtenStringAttr(setters, "put"),
            writtenStringAttr(setters, "compute"),
            writtenStringAttr(setters, "clear"),
            writtenStringAttr(setters, "remove"));
    }

    /**
     * Resolves one slot's patterns over the target's, from a
     * {@code @SetterNames} written on the field, record component or parameter
     * itself.
     *
     * @param written the annotation on the slot, or {@code null}
     * @param base the target's resolved scheme
     * @return the scheme that slot's members are named from
     */
    public static @NotNull SetterScheme setterOverride(@Nullable PsiAnnotation written,
                                                       @NotNull SetterScheme base) {
        if (written == null) return base;
        return SetterScheme.override(base,
            writtenStringAttr(written, "set"),
            writtenStringAttr(written, "flag"),
            writtenStringAttr(written, "add"),
            writtenStringAttr(written, "put"),
            writtenStringAttr(written, "compute"),
            writtenStringAttr(written, "clear"),
            writtenStringAttr(written, "remove"));
    }

    /**
     * Resolves the once-per-target names from the nested {@code builder}
     * attribute over the given style.
     *
     * @param annotation the annotation to read, or {@code null}
     * @param style the style supplying every unwritten name
     * @param targetSimpleName simple name of the annotated type
     * @return the resolved scheme
     */
    public static @NotNull BuilderScheme builderScheme(@Nullable PsiAnnotation annotation,
                                                       @NotNull NamingStyle style,
                                                       @NotNull String targetSimpleName) {
        PsiAnnotation names = nestedAnnotation(annotation, ATTR_BUILDER);
        if (names == null) return BuilderScheme.of(style, targetSimpleName);
        return BuilderScheme.resolve(style, targetSimpleName,
            writtenStringAttr(names, "type"),
            writtenStringAttr(names, "builder"),
            writtenStringAttr(names, "build"),
            writtenStringAttr(names, "from"),
            writtenStringAttr(names, "toBuilder"));
    }

    /**
     * Reads a written nested-annotation attribute.
     *
     * @param annotation the enclosing annotation, or {@code null}
     * @param attr the attribute name
     * @return the nested annotation, or {@code null} when it is not written
     */
    public static @Nullable PsiAnnotation nestedAnnotation(@Nullable PsiAnnotation annotation,
                                                           @NotNull String attr) {
        if (annotation == null) return null;
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue(attr);
        return value instanceof PsiAnnotation nested ? nested : null;
    }

    public static boolean booleanAttr(@Nullable PsiAnnotation annotation, @NotNull String attr, boolean fallback) {
        if (annotation == null) return fallback;
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue(attr);
        if (value instanceof PsiLiteralExpression literal && literal.getValue() instanceof Boolean b) return b;
        return fallback;
    }

    /**
     * Reads the {@code access} attribute as a Java modifier keyword, defaulting
     * to {@code public}.
     */
    public static @NotNull String accessKeyword(@Nullable PsiAnnotation annotation) {
        return accessKeyword(annotation, ATTR_ACCESS, "public");
    }

    /**
     * Reads an {@code AccessLevel.X} enum reference from an annotation attribute
     * as a Java modifier keyword. {@code PACKAGE} maps to the empty string,
     * which is how package-private is spelled in source.
     *
     * @param annotation the annotation to read, or {@code null}
     * @param attr the attribute name holding the {@code AccessLevel}
     * @param fallback keyword to return when the attribute is absent or unreadable
     * @return the modifier keyword, or the empty string for package-private
     */
    public static @NotNull String accessKeyword(@Nullable PsiAnnotation annotation,
                                                @NotNull String attr,
                                                @NotNull String fallback) {
        if (annotation == null) return fallback;
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue(attr);
        if (value instanceof PsiReferenceExpression ref) {
            String name = ref.getReferenceName();
            if (name != null) {
                return switch (name) {
                    case "PUBLIC" -> "public";
                    case "PROTECTED" -> "protected";
                    case "PACKAGE" -> "";
                    case "PRIVATE" -> "private";
                    default -> fallback;
                };
            }
        }
        return fallback;
    }

    // ------------------------------------------------------------------
    // Chain role and the declared builder
    // ------------------------------------------------------------------

    /**
     * Classifies a target's position in a SuperBuilder chain.
     *
     * <p>The two questions are the ones {@code BuilderMutator.mutate} dispatches
     * on, asked of PSI here and of the element model there, so the editor's model
     * of a chain is the shape javac will emit.
     *
     * @param target the annotated type
     * @return its position in a chain, never null
     */
    public static @NotNull ChainRole chainRoleOf(@NotNull PsiClass target) {
        boolean isAbstract = target.hasModifierProperty(PsiModifier.ABSTRACT) && !target.isInterface();
        return ChainRole.of(isAbstract, annotatedSuperOf(target) != null);
    }

    /**
     * The direct superclass when it also carries {@code @ClassBuilder}. Only the
     * immediate parent is consulted, matching the processor's
     * {@code findAnnotatedDirectSuper} - an unannotated class in between breaks
     * the chain rather than being skipped over.
     *
     * @param target the annotated type
     * @return the annotated superclass, or {@code null}
     */
    public static @Nullable PsiClass annotatedSuperOf(@NotNull PsiClass target) {
        if (target.isInterface() || target.isRecord() || target.isEnum()) return null;
        PsiClass superClass = target.getSuperClass();
        if (superClass == null) return null;
        if (CommonClassNames.JAVA_LANG_OBJECT.equals(superClass.getQualifiedName())) return null;
        return WrittenAnnotations.has(superClass, ANNOTATION_FQN) ? superClass : null;
    }

    /**
     * The nested type the target declares under the configured builder name.
     *
     * <p>Read through {@link PsiExtensibleClass#getOwnInnerClasses()} rather than
     * {@code getChildren()} or {@code getInnerClasses()}: the first forces a full
     * AST load, which is illegal for a file not open in the editor and throws
     * during cross-file highlighting, and the second is augment-aware and would
     * re-enter the provider that asked.
     *
     * @param target the annotated type
     * @param builderName the configured builder class name
     * @return the declared class, or {@code null} when the target declares none
     */
    public static @Nullable PsiClass declaredBuilderOf(@NotNull PsiClass target,
                                                       @NotNull String builderName) {
        if (!(target instanceof PsiExtensibleClass extensible)) return null;
        for (PsiClass nested : extensible.getOwnInnerClasses()) {
            if (builderName.equals(nested.getName())) return nested;
        }
        return null;
    }

    /**
     * Whether the entry points alone are withheld, the builder itself still
     * being generated.
     *
     * <p>Every entry point instantiates the builder with the seeds, in parameter
     * order, and a declared builder's constructors are the author's and the ones
     * a constructor annotation written on it appends, so one where none of those
     * is a constructor javac would call with the seeds leaves the entry points
     * with nothing to call and the processor skips them with a note, which
     * {@link DeclaredBuilderSkipsEntryPointsInspection} reports in the editor in
     * the same words. Everything else still runs - the
     * merge appends every setter, a class target still gets the all-args
     * constructor {@code build()} calls, and a chain link still gets its copy
     * constructor. Withholding the whole member list here would take that
     * constructor with it, and put a same-package {@code new Target(...)} red
     * over source that builds.
     *
     * <p>The rule is {@link DeclaredBuilderShape#instantiable}, which the
     * processor asks of the same parameter types - a constructor whose throws
     * clause {@link DeclaredBuilderShape#throwsNothingChecked} does not accept
     * counted as none the entry points can call. On a class or
     * record target, a chain link among them, there is no seed, so the
     * constructor that serves is a no-argument one; on a constructor or factory
     * target it is the one javac selects for the seeds {@code builder(..)}
     * passes, as far as names can tell. An
     * interface type target's entry points call its sibling builder, never a
     * class nested in the interface body; a constructor or factory inside an
     * interface merges into that class as it does anywhere else.
     *
     * @param target the type the builder nests in
     * @param builderName the configured builder class name
     * @param executable whether the annotation sits on a constructor or factory method
     * @param seedTypes the type of each seed the entry points pass, in parameter order
     * @return whether the entry points are skipped
     */
    public static boolean withholdsEntryPointsOnly(@NotNull PsiClass target,
                                                   @NotNull String builderName,
                                                   boolean executable,
                                                   @NotNull List<String> seedTypes) {
        if (target.isInterface() && !executable) return false;
        PsiClass declared = declaredBuilderOf(target, builderName);
        return declared != null
            && !DeclaredBuilderShape.instantiable(constructorSignatures(declared, false),
                constructorSignatures(declared, true), seedTypes, typeParameterNames(declared));
    }

    /**
     * The names of the type parameters a declared builder declares, which the
     * seed match reads a parameter spelling one of as able to take the seed.
     *
     * @param declared the builder the author wrote
     * @return the names, in declaration order
     */
    private static @NotNull List<String> typeParameterNames(@NotNull PsiClass declared) {
        List<String> out = new ArrayList<>();
        for (PsiTypeParameter parameter : declared.getTypeParameters()) out.add(parameter.getName());
        return out;
    }

    /**
     * Whether the entry points are skipped only because the constructor javac
     * selects for what they pass declares a throws clause that may name a
     * checked exception, which decides the wording of the note, as
     * {@link DeclaredBuilderShape#skippedForAThrowsClause} decides it for the
     * processor.
     *
     * @param declared the builder the author wrote
     * @param seedTypes the type of each seed the entry points pass, in parameter order
     * @return whether a constructor is selected and it declares such a throws clause
     */
    public static boolean skippedForAThrowsClause(@NotNull PsiClass declared, @NotNull List<String> seedTypes) {
        return DeclaredBuilderShape.skippedForAThrowsClause(constructorSignatures(declared, false),
            constructorSignatures(declared, true), seedTypes, typeParameterNames(declared));
    }

    /**
     * The parameter types of each constructor the processor finds on the
     * declared builder when it counts the entry points' constructors: the
     * author's, and each one a constructor annotation written on the builder
     * appends.
     *
     * <p>The constructor pass runs before the merge, so what those annotations
     * append is in the builder by then, and the processor counts it beside the
     * author's. Here it is the args provider's light constructor, which no own
     * read returns, so it is derived as that pass derives it - from the written
     * annotations and the builder's own fields, through
     * {@link ArgsConstants#appendedConstructors} - and a constructor it appends
     * declares no throws clause.
     *
     * @param declared the builder the author wrote
     * @param callableOnly whether to read only the constructors whose throws clause
     *     {@link DeclaredBuilderShape#throwsNothingChecked} accepts
     * @return each constructor's parameter types, the author's first
     */
    private static @NotNull List<List<String>> constructorSignatures(@NotNull PsiClass declared,
                                                                    boolean callableOnly) {
        List<List<String>> out = declaredConstructorSignatures(declared, callableOnly);
        for (List<PsiField> parameters : ArgsConstants.appendedConstructors(declared)) {
            List<String> types = new ArrayList<>(parameters.size());
            for (PsiField field : parameters) {
                String written = MergedSlotStorage.writtenTypeText(field);
                types.add(written == null ? "" : written);
            }
            out.add(types);
        }
        return out;
    }

    /**
     * The parameter types of each constructor the author declared, as written.
     *
     * <p>Read through {@link PsiExtensibleClass#getOwnMethods()} rather than
     * {@code getConstructors()}, the latter being augment-aware, and read as
     * text rather than resolved. The implicit default of a class declaring none
     * is not in the list, which is what {@link DeclaredBuilderShape#instantiable}
     * expects.
     *
     * @param declared the builder the author wrote
     * @param callableOnly whether to read only the constructors whose throws clause
     *     {@link DeclaredBuilderShape#throwsNothingChecked} accepts
     * @return each constructor's parameter types, in declaration order
     */
    private static @NotNull List<List<String>> declaredConstructorSignatures(@NotNull PsiClass declared,
                                                                            boolean callableOnly) {
        List<List<String>> out = new ArrayList<>();
        if (!(declared instanceof PsiExtensibleClass extensible)) return out;
        for (PsiMethod own : extensible.getOwnMethods()) {
            if (!own.isConstructor()) continue;
            if (callableOnly && !DeclaredBuilderShape.throwsNothingChecked(thrownTypes(own))) continue;
            List<String> types = new ArrayList<>();
            for (PsiParameter parameter : own.getParameterList().getParameters()) {
                String written = MergedSlotStorage.writtenTypeText(parameter);
                types.add(written == null ? "" : written);
            }
            out.add(types);
        }
        return out;
    }

    /**
     * Each type a constructor's throws clause names, as written - read off the
     * reference elements rather than resolved.
     *
     * @param constructor the author's constructor
     * @return the thrown types' texts, in order
     */
    private static @NotNull List<String> thrownTypes(@NotNull PsiMethod constructor) {
        List<String> out = new ArrayList<>();
        for (PsiJavaCodeReferenceElement thrown : constructor.getThrowsList().getReferenceElements())
            out.add(thrown.getText());
        return out;
    }

    /**
     * Whether the author wrote the declared builder a constructor, beside which
     * {@code builderConstructorAccess} changes nothing.
     *
     * <p>Read through {@link PsiExtensibleClass#getOwnMethods()}, so a light
     * constructor this plugin contributes is never taken for the author's.
     *
     * @param declared the builder the author wrote
     * @return whether it declares any constructor
     */
    public static boolean declaresConstructor(@NotNull PsiClass declared) {
        return !declaredConstructorSignatures(declared, false).isEmpty();
    }

    /**
     * Whether the declared builder is left with javac's default constructor and
     * no other, which is the constructor the processor retypes to
     * {@code builderConstructorAccess}.
     *
     * <p>The constructor pass runs before the merge, so a constructor annotation
     * written on the declared builder has appended its constructor by the time
     * the processor looks, and the retype declines beside that one as beside the
     * author's. Here that constructor is the args provider's light one, which
     * {@link #declaresConstructor} does not see, so the annotations are read as
     * written; one at {@code AccessLevel.NONE} appends nothing.
     *
     * @param declared the builder the author wrote
     * @return whether no constructor but javac's default is there to call
     */
    public static boolean keepsOnlyTheDefaultConstructor(@NotNull PsiClass declared) {
        if (declaresConstructor(declared)) return false;
        for (PsiAnnotation annotation : ArgsConstants.written(declared)) {
            ArgsMode mode = ArgsConstants.modeOf(annotation);
            if (mode == null || mode == ArgsMode.BUILDER) continue;
            if (ArgsConstants.accessKeyword(annotation, mode) != null) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // The declared builder's shape, as the shared decision states it
    // ------------------------------------------------------------------

    /**
     * Why the merge cannot run into the builder this target declares, worded as
     * the processor words it.
     *
     * <p>One entry point for the two callers that have to agree: the augment
     * provider withholds where this answers and the inspection reports what it
     * answers, so the editor cannot populate a builder it also marks red or stay
     * silent about one it refuses to populate. The text comes back rather than
     * the constant, because rendering it is where the two halves would otherwise
     * pick different operands.
     *
     * <p>Every role merges, so every role is judged. A chain role is measured
     * with the pair of self-type names the declaration spells and, on a linked
     * role, against the annotated superclass's builder and the arguments the
     * target passes that superclass - the same names the processor reads off its
     * tree.
     *
     * <p>A constructor or factory target is standalone whatever its enclosing
     * type is, a constructor having no chain to find, and its builder re-declares
     * the type parameters of {@link #typeParameterSource} - a static factory's
     * own - which is what the processor measures the declaration against.
     *
     * @param target the type the builder nests in
     * @param executable the annotated constructor or static factory, or {@code null} when the
     *     annotation is on the type
     * @param declared the builder it declares
     * @param names the resolved builder-member names
     * @return the diagnostic, or {@code null} when the shape is usable
     */
    public static @Nullable String mergeRejection(@NotNull PsiClass target,
                                                  @Nullable PsiMethod executable,
                                                  @NotNull PsiClass declared,
                                                  @NotNull BuilderScheme names) {
        ChainRole role = executable != null ? ChainRole.STANDALONE : chainRoleOf(target);
        String declaredName = declared.getName();
        String targetName = target.getName();
        if (declaredName == null || targetName == null) return null;
        DeclaredBuilderFacts facts = declaredBuilderFacts(declared, names.build());
        PsiClass ancestor = role.hasAnnotatedSuper() ? annotatedSuperOf(target) : null;
        RoleExpectation expectation = roleExpectation(target,
            typeParameterSource(target, executable), role, names.type(), facts.typeParameterNames(),
            ancestor == null ? null : ancestor.getName(), superTypeArgumentTexts(target));
        DeclaredBuilderRejection rejection = DeclaredBuilderShape.check(role, facts, expectation);
        return rejection == null
            ? null
            : DeclaredBuilderShape.describe(rejection, role, declaredName, targetName,
                names.builder(), facts, expectation);
    }

    /**
     * The annotated supertype whose own declared builder leaves this target with
     * no builder to generate.
     *
     * <p>A link's builder extends the ancestor's, passing it the ancestor's own
     * arguments plus the self-typed pair. Where the ancestor's author wrote that
     * class themselves it takes whatever they declared - usually none - and the
     * clause cannot be formed. The processor's answer is to generate nothing and
     * say so; this is the same test, so the editor withholds the same builder
     * rather than leaving it unrooted in silence.
     *
     * <p>An ancestor declaring nothing is not blocking: the builder it gets is
     * the generated one, in the shape the clause expects. A target declaring its
     * own builder is asked too, ahead of its own shape, as the processor asks
     * it: that declaration's extends clause has to name the ancestor's builder
     * just as a generated one does.
     *
     * @param target the annotated type
     * @param builderName the builder class name the chain is written in
     * @return the blocking supertype, or {@code null} when the chain can be formed
     */
    public static @Nullable PsiClass ancestorBlockingGeneration(@NotNull PsiClass target,
                                                                @NotNull String builderName,
                                                                boolean executable) {
        // An executable target is never in a chain - a constructor has no chain
        // to find, and the processor's third path never looks for an annotated
        // super. Asking anyway reads the enclosing class's own supertype and
        // withholds a builder that is emitted.
        if (executable) return null;
        PsiClass parent = annotatedSuperOf(target);
        if (parent == null) return null;
        PsiClass declared = declaredBuilderOf(parent, builderName);
        if (declared == null) return null;
        return declared.getTypeParameters().length == superTypeArgumentTexts(target).size() + 2
            ? null
            : parent;
    }

    /**
     * The type arguments the target passes to its superclass, as written.
     *
     * @param target the annotated type
     * @return the argument texts, read off the extends clause, in order
     */
    private static @NotNull List<String> superTypeArgumentTexts(@NotNull PsiClass target) {
        PsiReferenceList extendsList = target.getExtendsList();
        return extendsList == null ? List.of() : firstReferenceArguments(extendsList);
    }

    /**
     * The type arguments of a reference list's first entry, each as written.
     *
     * @param list the extends list to read
     * @return the argument texts, empty when the list is empty or its first entry is raw
     */
    private static @NotNull List<String> firstReferenceArguments(@NotNull PsiReferenceList list) {
        PsiJavaCodeReferenceElement[] references = list.getReferenceElements();
        if (references.length == 0) return List.of();
        PsiReferenceParameterList parameters = references[0].getParameterList();
        if (parameters == null) return List.of();
        List<String> out = new ArrayList<>();
        for (PsiTypeElement argument : parameters.getTypeParameterElements()) out.add(argument.getText());
        return out;
    }

    /**
     * Reads a declared builder as written, for
     * {@link DeclaredBuilderShape#check}.
     *
     * <p>Every read here is a declared read and a textual one. The reference
     * elements are asked for their text rather than for the types they resolve
     * to, and the methods come from {@link PsiExtensibleClass#getOwnMethods()}
     * rather than {@code getAllMethods()} or {@code findMethodsByName} - both of
     * which are augment-aware, so a provider asking them while it runs would see
     * whatever it contributed last and never settle.
     *
     * @param declared the builder the author wrote
     * @param buildMethodName the configured name of the terminal method
     * @return the facts the shape decision measures
     */
    public static @NotNull DeclaredBuilderFacts declaredBuilderFacts(@NotNull PsiClass declared,
                                                                     @NotNull String buildMethodName) {
        List<String> parameterNames = new ArrayList<>();
        List<String> parameterBounds = new ArrayList<>();
        for (PsiTypeParameter parameter : declared.getTypeParameters()) {
            parameterNames.add(parameter.getName() == null ? "" : parameter.getName());
            parameterBounds.add(boundsText(parameter));
        }
        PsiReferenceList extendsList = declared.getExtendsList();
        String writtenSuper = firstReferenceText(extendsList);
        return new DeclaredBuilderFacts(
            declared.hasModifierProperty(PsiModifier.STATIC),
            declared.hasModifierProperty(PsiModifier.ABSTRACT),
            parameterNames, parameterBounds,
            writtenSuper == null ? null : DeclaredBuilderShape.rawType(writtenSuper),
            extendsList == null ? List.of() : firstReferenceArguments(extendsList),
            declaredBuildMethod(declared, buildMethodName),
            kindOf(declared));
    }

    /**
     * The keyword a declared type is written with, as the processor reads it
     * off the parser's flags.
     *
     * @param declared the type the author wrote
     * @return one of the {@link DeclaredBuilderFacts} kind constants
     */
    private static @NotNull String kindOf(@NotNull PsiClass declared) {
        if (declared.isAnnotationType()) return DeclaredBuilderFacts.ANNOTATION;
        if (declared.isInterface()) return DeclaredBuilderFacts.INTERFACE;
        if (declared.isEnum()) return DeclaredBuilderFacts.ENUM;
        if (declared.isRecord()) return DeclaredBuilderFacts.RECORD;
        return DeclaredBuilderFacts.CLASS;
    }

    /**
     * The bounds written on a type parameter, read rather than resolved and
     * joined as {@link DeclaredBuilderFacts#typeParameterBounds} holds them.
     *
     * @param parameter the parameter to read
     * @return the bounds, or {@code null} when none is written
     */
    private static @Nullable String boundsText(@NotNull PsiTypeParameter parameter) {
        PsiJavaCodeReferenceElement[] references = parameter.getExtendsList().getReferenceElements();
        if (references.length == 0) return null;
        List<String> out = new ArrayList<>(references.length);
        for (PsiJavaCodeReferenceElement reference : references) out.add(reference.getText());
        return String.join(" & ", out);
    }

    /**
     * The type parameters a builder for this site re-declares.
     *
     * <p>A {@code static} factory's own, since it cannot name the enclosing
     * type's; the enclosing type's everywhere else, a constructor running under
     * exactly those. The processor makes the same choice when it reads the
     * annotated member.
     *
     * @param owner the type the builder nests in
     * @param executable the annotated constructor or static factory, or {@code null} when the
     *     annotation is on the type
     * @return the parameters, in declaration order
     */
    public static PsiTypeParameter[] typeParameterSource(@NotNull PsiClass owner,
                                                                  @Nullable PsiMethod executable) {
        return executable != null && !executable.isConstructor()
            ? executable.getTypeParameters()
            : owner.getTypeParameters();
    }

    /**
     * What the role requires of a declared builder, derived by
     * {@link DeclaredBuilderShape#expectation} from the names PSI holds.
     *
     * @param target the type the builder nests in
     * @param typeParameters the parameters the builder re-declares, from {@link #typeParameterSource}
     * @param role its position in a chain
     * @param builderName the builder class name
     * @param declaredTypeParameters the declared builder's type parameter names, in declaration order
     * @param ancestorName the annotated superclass's simple name, or null when there is none
     * @param superArguments the type arguments the target passes to its superclass, as written
     * @return the expectation to measure the declaration against
     */
    public static @NotNull RoleExpectation roleExpectation(@NotNull PsiClass target,
                                                           PsiTypeParameter[] typeParameters,
                                                           @NotNull ChainRole role,
                                                           @NotNull String builderName,
                                                           @NotNull List<String> declaredTypeParameters,
                                                           @Nullable String ancestorName,
                                                           @NotNull List<String> superArguments) {
        List<String> targetParameters = new ArrayList<>();
        List<String> targetBounds = new ArrayList<>();
        for (PsiTypeParameter parameter : typeParameters) {
            targetParameters.add(parameter.getName() == null ? "" : parameter.getName());
            targetBounds.add(boundsText(parameter));
        }
        String targetName = target.getName() == null ? "" : target.getName();
        return DeclaredBuilderShape.expectation(role, targetName, builderName, targetParameters,
            targetBounds, declaredTypeParameters, ancestorName, superArguments);
    }

    /**
     * The no-argument build method the author wrote, by the configured name.
     *
     * @param declared the builder the author wrote
     * @param buildMethodName the configured name of the terminal method
     * @return the method as written, or {@code null} when the class declares none
     */
    private static @Nullable DeclaredBuildMethod declaredBuildMethod(@NotNull PsiClass declared,
                                                                     @NotNull String buildMethodName) {
        List<PsiMethod> own = declared instanceof PsiExtensibleClass extensible
            ? extensible.getOwnMethods()
            : List.of(declared.getMethods());
        for (PsiMethod method : own) {
            if (!buildMethodName.equals(method.getName())) continue;
            if (!method.getParameterList().isEmpty()) continue;
            PsiTypeElement returnType = method.getReturnTypeElement();
            return new DeclaredBuildMethod(
                returnType == null ? "" : DeclaredBuilderShape.erasedName(returnType.getText()),
                method.hasModifierProperty(PsiModifier.ABSTRACT));
        }
        return null;
    }

    /**
     * The text of a reference list's first entry, read rather than resolved.
     *
     * @param list the extends or bounds list, or {@code null}
     * @return the first reference as written, or {@code null} when the list is empty
     */
    private static @Nullable String firstReferenceText(@Nullable PsiReferenceList list) {
        if (list == null) return null;
        PsiJavaCodeReferenceElement[] references = list.getReferenceElements();
        return references.length == 0 ? null : references[0].getText();
    }

}
