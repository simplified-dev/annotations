package dev.simplified.classbuilder.inspect;

import com.intellij.psi.CommonClassNames;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiReferenceList;
import com.intellij.psi.PsiReferenceParameterList;
import com.intellij.psi.PsiTypeElement;
import com.intellij.psi.PsiTypeParameter;
import com.intellij.psi.impl.source.PsiExtensibleClass;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.classbuilder.apt.BuilderScheme;
import dev.simplified.classbuilder.apt.ChainRole;
import dev.simplified.classbuilder.apt.DeclaredBuildMethod;
import dev.simplified.classbuilder.apt.DeclaredBuilderFacts;
import dev.simplified.classbuilder.apt.DeclaredBuilderRejection;
import dev.simplified.classbuilder.apt.DeclaredBuilderShape;
import dev.simplified.classbuilder.apt.RoleExpectation;
import dev.simplified.classbuilder.apt.SetterScheme;
import dev.simplified.shared.psi.WrittenAnnotations;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
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

    /** Attribute names of {@code @SetterNames}, in declaration order. */
    public static final @NotNull String[] SETTER_ROLES =
        {"set", "flag", "add", "put", "compute", "clear", "remove"};

    /** Attribute names of {@code @BuilderNames}, in declaration order. */
    public static final @NotNull String[] BUILDER_ROLES = {"type", "builder", "build", "from", "toBuilder"};

    private ClassBuilderConstants() {}

    public static @NotNull String stringAttr(@Nullable PsiAnnotation annotation, @NotNull String attr, @NotNull String fallback) {
        if (annotation == null) return fallback;
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue(attr);
        if (value instanceof PsiLiteralExpression literal && literal.getValue() instanceof String s && !s.isEmpty()) return s;
        return fallback;
    }

    /**
     * Reads a string attribute only when it is written at the annotation,
     * mirroring the processor's {@code getElementValues()} view rather than
     * {@code findAttributeValue}'s defaults-included one. Returning
     * {@code null} for an unwritten attribute is what lets the schemes tell
     * "inherit from the style" from an explicit value, empty ones included.
     *
     * @param annotation the annotation to read, or {@code null}
     * @param attr the attribute name
     * @return the written value, or {@code null}
     */
    public static @Nullable String writtenStringAttr(@Nullable PsiAnnotation annotation, @NotNull String attr) {
        if (annotation == null) return null;
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue(attr);
        if (value instanceof PsiLiteralExpression literal && literal.getValue() instanceof String s) return s;
        return null;
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
     * <p>Every entry point instantiates the builder with one argument per seed,
     * and a declared builder's constructors are the author's throughout, so one
     * that declares no constructor of that arity leaves the entry points with
     * nothing to call and the processor skips them with a note. Everything else
     * still runs - the merge appends every setter, a class target still gets
     * the all-args constructor {@code build()} calls, and a chain link still
     * gets its copy constructor. Withholding the whole member list here would
     * take that constructor with it, and put a same-package
     * {@code new Target(...)} red over source that builds.
     *
     * <p>The arity rule is {@link DeclaredBuilderShape#instantiable}, which the
     * processor asks of the same counts. On a class or record target, a chain
     * link among them, the seed count is zero, so the constructor that serves is
     * a no-argument one; on a constructor or factory target it is one taking
     * exactly the seeds {@code builder(..)} passes. An interface's entry points
     * call its sibling builder, never a class nested in the interface body.
     *
     * @param target the type the builder nests in
     * @param builderName the configured builder class name
     * @param executable whether the annotation sits on a constructor or factory method
     * @param seeds how many arguments the entry points pass the builder's constructor
     * @return whether the entry points are skipped
     */
    public static boolean withholdsEntryPointsOnly(@NotNull PsiClass target,
                                                   @NotNull String builderName,
                                                   boolean executable,
                                                   int seeds) {
        if (target.isInterface()) return false;
        PsiClass declared = declaredBuilderOf(target, builderName);
        return declared != null
            && !DeclaredBuilderShape.instantiable(declaredConstructorArities(declared), seeds);
    }

    /**
     * The parameter count of each constructor the author declared.
     *
     * <p>Read through {@link PsiExtensibleClass#getOwnMethods()} rather than
     * {@code getConstructors()}, the latter being augment-aware. The implicit
     * default of a class declaring none is not in the list, which is what
     * {@link DeclaredBuilderShape#instantiable} expects.
     *
     * @param declared the builder the author wrote
     * @return the arities, in declaration order
     */
    private static @NotNull List<Integer> declaredConstructorArities(@NotNull PsiClass declared) {
        List<Integer> out = new ArrayList<>();
        if (!(declared instanceof PsiExtensibleClass extensible)) return out;
        for (PsiMethod own : extensible.getOwnMethods()) {
            if (own.isConstructor()) out.add(own.getParameterList().getParametersCount());
        }
        return out;
    }

    /**
     * Whether the author wrote the declared builder a constructor, which is what
     * decides whether {@code builderConstructorAccess} reaches it.
     *
     * <p>Read through {@link PsiExtensibleClass#getOwnMethods()}, so a light
     * constructor this plugin contributes is never taken for the author's.
     *
     * @param declared the builder the author wrote
     * @return whether it declares any constructor
     */
    public static boolean declaresConstructor(@NotNull PsiClass declared) {
        return !declaredConstructorArities(declared).isEmpty();
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
            parameterBounds.add(firstReferenceText(parameter.getExtendsList()));
        }
        PsiReferenceList extendsList = declared.getExtendsList();
        String writtenSuper = firstReferenceText(extendsList);
        return new DeclaredBuilderFacts(
            declared.hasModifierProperty(PsiModifier.STATIC),
            declared.hasModifierProperty(PsiModifier.ABSTRACT),
            parameterNames, parameterBounds,
            writtenSuper == null ? null : DeclaredBuilderShape.rawType(writtenSuper),
            extendsList == null ? List.of() : firstReferenceArguments(extendsList),
            declaredBuildMethod(declared, buildMethodName));
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
        for (PsiTypeParameter parameter : typeParameters) {
            targetParameters.add(parameter.getName() == null ? "" : parameter.getName());
        }
        String targetName = target.getName() == null ? "" : target.getName();
        return DeclaredBuilderShape.expectation(role, targetName, builderName, targetParameters,
            declaredTypeParameters, ancestorName, superArguments);
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
