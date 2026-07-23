package dev.simplified.equality.editor;

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo;
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider;
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder;
import com.intellij.openapi.util.IconLoader;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiArrayInitializerMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiEnumConstant;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.PsiRecordComponent;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypes;
import com.intellij.psi.impl.source.PsiExtensibleClass;
import com.intellij.psi.util.PsiTreeUtil;
import dev.simplified.equality.apt.EqualityConfig;
import dev.simplified.equality.inspect.WholeObjectConstants.Signature;
import dev.simplified.equality.inspect.WholeObjectConstants;
import dev.simplified.lazy.inspect.LazyConstants;
import dev.simplified.tostring.apt.ToStringConfig;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Shows a gutter icon next to every {@code @EqualsAndHashCode} and
 * {@code @ToString} annotation, carrying the shape the annotation resolves to.
 *
 * <p>The tooltip is the point rather than the icon. Both annotations are decided
 * entirely by their attributes - which members are compared, whether the
 * relation is exact-class or {@code instanceof}, whether the superclass is
 * called, what the rendered line looks like - and <b>none of it is visible at
 * any call site</b>, since the members they generate are the ones every type
 * already inherits. Adding one {@code @EqualsExclude} silently narrows an
 * equality relation with nothing in the source to point at, exactly the way
 * adding one {@code @BuilderIgnore} silently shortens a constructor.
 *
 * <p>The icon lives at {@code /icons/classbuilder_generated.svg}, shared with
 * the builder's marker so one glance says "this member is synthesised" wherever
 * it appears.
 */
public final class WholeObjectLineMarkerProvider extends RelatedItemLineMarkerProvider {

    private static final Icon ICON = IconLoader.getIcon(
        "/icons/classbuilder_generated.svg",
        WholeObjectLineMarkerProvider.class
    );

    private static final String EQUALS_EXCLUDE = simpleName(EqualityConfig.EXCLUDE_FQN);
    private static final String EQUALS_INCLUDE = simpleName(EqualityConfig.INCLUDE_FQN);
    private static final String TO_STRING_EXCLUDE = simpleName(ToStringConfig.EXCLUDE_FQN);
    private static final String TO_STRING_INCLUDE = simpleName(ToStringConfig.INCLUDE_FQN);

    @Override
    protected void collectNavigationMarkers(@NotNull PsiElement element,
                                            @NotNull Collection<? super RelatedItemLineMarkerInfo<?>> result) {
        // Anchored on the annotation's simple-name identifier: the platform logs
        // a gutter-icon best-practice error for a compound anchor.
        if (!(element instanceof PsiIdentifier identifier)) return;
        if (!(identifier.getParent() instanceof PsiJavaCodeReferenceElement reference)) return;
        if (!(reference.getParent() instanceof PsiAnnotation annotation)) return;

        String fqn = annotation.getQualifiedName();
        boolean equality = EqualityConfig.FQN.equals(fqn);
        if (!equality && !ToStringConfig.FQN.equals(fqn)) return;

        PsiClass target = PsiTreeUtil.getParentOfType(annotation, PsiClass.class);
        if (target == null) return;
        // Every kind the processor refuses as a target. Both annotations are
        // @Target(TYPE), so all three are legal source that generates nothing,
        // and describing a member that will never exist puts the gutter's own
        // promise beside the red the inspection reports on the same line. A
        // record stays: it is a legal target, and the augment provider skips one
        // for the unrelated reason that the platform already models its implicit
        // members.
        if (target.isEnum() || target.isInterface() || target.isAnnotationType()) return;

        RelatedItemLineMarkerInfo<PsiElement> info = NavigationGutterIconBuilder
            .create(ICON)
            .setTargets(Collections.singleton(annotation))
            .setTooltipText(equality
                ? equalityTooltip(target, annotation)
                : toStringTooltip(target, annotation))
            .createLineMarkerInfo(identifier);
        result.add(info);
    }

    @Override
    public String getName() {
        return "@EqualsAndHashCode / @ToString generated";
    }

    @Override
    public Icon getIcon() {
        return ICON;
    }

    // ------------------------------------------------------------------
    // Tooltips
    // ------------------------------------------------------------------

    /**
     * The gutter text for {@code @EqualsAndHashCode} - the identity relation and
     * the members it compares.
     *
     * <p>Both halves are load bearing. The relation decides whether a subclass
     * instance can ever compare equal, and the member list is what a rename or a
     * new field silently changes.
     */
    private static @NotNull String equalityTooltip(PsiClass target, PsiAnnotation annotation) {
        StringBuilder out = new StringBuilder("Generated by ").append(EqualityConfig.LABEL);
        out.append("<br>Identity: ").append(identity(target, annotation));

        List<Member> members =
            members(target, annotation, EQUALS_EXCLUDE, EQUALS_INCLUDE, false, false);
        out.append("<br>").append(members.isEmpty()
            ? "Compares no members - every instance compares equal to every other"
            : "Compares: " + labels(members));

        if (callsSuper(target, annotation, true)) {
            out.append("<br>Calls super.equals and super.hashCode");
        }
        if (booleanAttr(annotation, "cacheHashCode", false)) {
            out.append("<br>Memoizes the hash in a transient $hashCode field");
        }
        if (booleanAttr(annotation, "useAccessors", false)) {
            out.append("<br>Reads each member through its declared accessor");
        }
        return out.toString();
    }

    /** The gutter text for {@code @ToString} - the line the member returns. */
    private static @NotNull String toStringTooltip(PsiClass target, PsiAnnotation annotation) {
        StringBuilder out = new StringBuilder("Generated by ").append(ToStringConfig.LABEL);
        out.append("<br>Renders: ").append(rendered(target, annotation));
        if (booleanAttr(annotation, "useAccessors", false)) {
            out.append("<br>Reads each member through its declared accessor");
        }
        return out.toString();
    }

    private static String identity(PsiClass target, PsiAnnotation annotation) {
        String written = enumConstant(annotation, "identity");
        String name = target.getName() == null ? "the type" : target.getName();
        if ("INSTANCE_OF".equals(written)) return "o instanceof " + name;
        if ("INSTANCE_OF_CANEQUAL".equals(written)) {
            boolean hook = !target.hasModifierProperty(PsiModifier.FINAL)
                || WholeObjectAugmentProvider.callableSuperclass(target) != null;
            return hook
                ? "o instanceof " + name + ", asked back through a protected canEqual hook"
                : "o instanceof " + name + " - no canEqual hook, since no subclass of a final "
                    + "root type can override it";
        }
        return "this.getClass() == o.getClass()";
    }

    /**
     * The rendered line, with each value elided.
     *
     * <p>The bracket pair, the member names, whether each is labelled and
     * whether the superclass leads are four separate attributes, and a call site
     * prints the result of all four without showing any of them.
     */
    private static String rendered(PsiClass target, PsiAnnotation annotation) {
        boolean lombok = "LOMBOK".equals(enumConstant(annotation, "style"));
        boolean fieldNames = booleanAttr(annotation, "includeFieldNames", true);
        List<Member> members =
            members(target, annotation, TO_STRING_EXCLUDE, TO_STRING_INCLUDE, true, true);

        StringBuilder out = new StringBuilder(target.getName() == null ? "" : target.getName());
        out.append(lombok ? '(' : '[');
        boolean first = true;
        if (callsSuper(target, annotation, false)) {
            out.append("super=...");
            first = false;
        }
        for (Member member : members) {
            if (!first) out.append(", ");
            if (fieldNames) out.append(member.label()).append('=');
            out.append("...");
            first = false;
        }
        return out.append(lombok ? ')' : ']').toString();
    }

    private static String labels(List<Member> members) {
        StringBuilder out = new StringBuilder();
        for (Member member : members) {
            if (!out.isEmpty()) out.append(", ");
            out.append(member.label());
        }
        return out.toString();
    }

    // ------------------------------------------------------------------
    // Member selection
    // ------------------------------------------------------------------

    /**
     * One selected member.
     *
     * @param name the member's own name, which is what {@code of} and {@code exclude} match
     * @param label the name printed for it, which {@code @ToStringInclude(name)} may rewrite
     * @param rank the print-order weight, higher first, and always zero for equality
     */
    private record Member(String name, String label, int rank) {
    }

    /**
     * The members the annotation reaches, in emission order.
     *
     * <p>The processor's own predicate restated against the PSI, since the
     * decision has to be the same one and there is no shared representation to
     * take it from: instance fields the class declares, in declaration order,
     * minus the synthesised and the marker-excluded, plus any zero-arg method
     * the include marker names. The two annotations differ on exactly two
     * inputs, both parameters here - {@code transient} is state a debug dump
     * wants and an equality relation must not depend on, and rank reorders a
     * printed line where reordering a hash would make it depend on a rule
     * invisible in the source.
     *
     * <p><b>A record is read through its components, not its fields.</b>
     * {@code getOwnFields()} answers with the stub's field children, and a
     * record's component-backed fields are not among them - they arrive through
     * the platform's own augment path, which the own-member read deliberately
     * bypasses. Walking fields there returns nothing, on the one target kind this
     * feature exists for, and the tooltip then states the opposite of what javac
     * emits rather than degrading. The component is also where the PSI keeps the
     * marker annotations, and neither {@code static} nor {@code transient} can be
     * written on one.
     */
    private static List<Member> members(PsiClass target, PsiAnnotation annotation,
                                        String excludeName, String includeName,
                                        boolean keepTransient, boolean honourRank) {
        List<Member> selected = new ArrayList<>();

        if (target.isRecord()) {
            for (PsiRecordComponent component : target.getRecordComponents()) {
                String name = component.getName();
                if (name == null || name.indexOf('$') >= 0) continue;
                if (annotationNamed(component, excludeName) != null) continue;
                if (annotationNamed(component, LazyConstants.LAZY_SHORT_NAME) != null) continue;
                selected.add(member(name, annotationNamed(component, includeName), honourRank));
            }
        } else {
            for (PsiField field : ownFields(target)) {
                // An enum's constants are fields of the enum type as far as the
                // PSI is concerned, and are not per-instance state.
                if (field instanceof PsiEnumConstant) continue;
                if (field.hasModifierProperty(PsiModifier.STATIC)) continue;
                String name = field.getName();
                // A synthesised slot - an outer-instance link, a switch map, this
                // feature's own hash memo. The compiler's own names put the '$'
                // in the middle, so the test is not on a prefix.
                if (name.indexOf('$') >= 0) continue;
                if (annotationNamed(field, excludeName) != null) continue;
                // @Lazy owns the field's storage, so neither the slot nor a
                // forced read is what the annotation would compare or print.
                if (annotationNamed(field, LazyConstants.LAZY_SHORT_NAME) != null) continue;
                PsiAnnotation include = annotationNamed(field, includeName);
                if (field.hasModifierProperty(PsiModifier.TRANSIENT) && !keepTransient
                    && include == null) continue;
                selected.add(member(name, include, honourRank));
            }
        }
        for (PsiMethod method : ownMethods(target)) {
            PsiAnnotation include = annotationNamed(method, includeName);
            if (include == null) continue;
            if (method.isConstructor()) continue;
            if (method.hasModifierProperty(PsiModifier.STATIC)) continue;
            if (method.getParameterList().getParametersCount() != 0) continue;
            PsiType returnType = method.getReturnType();
            if (returnType == null || PsiTypes.voidType().equals(returnType)) continue;
            selected.add(member(method.getName(), include, honourRank));
        }

        List<Member> narrowed = narrow(selected, annotation);
        if (honourRank) narrowed.sort((a, b) -> Integer.compare(b.rank(), a.rank()));
        return narrowed;
    }

    private static Member member(String name, @Nullable PsiAnnotation include, boolean honourRank) {
        if (include == null || !honourRank) return new Member(name, name, 0);
        String written = stringAttr(include, "name");
        return new Member(name, written == null || written.isEmpty() ? name : written,
            intAttr(include, "rank"));
    }

    /**
     * Applies {@code of} or {@code exclude}.
     *
     * <p>Both written at once is an error the processor reports and the
     * inspection mirrors; the narrowing spelling is shown here rather than
     * guessing at a combination that will not compile.
     */
    private static List<Member> narrow(List<Member> selected, PsiAnnotation annotation) {
        List<String> of = stringArrayAttr(annotation, "of");
        List<String> exclude = stringArrayAttr(annotation, "exclude");
        List<Member> out = new ArrayList<>(selected.size());
        for (Member member : selected) {
            if (!of.isEmpty() && !of.contains(member.name())) continue;
            if (of.isEmpty() && exclude.contains(member.name())) continue;
            out.add(member);
        }
        return out;
    }

    /**
     * Fields the class itself declares.
     *
     * <p>{@code getOwnFields()} rather than {@code getFields()}: the latter is
     * augment-aware and re-enters every provider, the one contributing this
     * feature's own members among them.
     */
    private static Iterable<PsiField> ownFields(PsiClass target) {
        return target instanceof PsiExtensibleClass ext
            ? ext.getOwnFields()
            : List.of(target.getFields());
    }

    /** Methods the class itself declares, for the same reason. */
    private static Iterable<PsiMethod> ownMethods(PsiClass target) {
        return target instanceof PsiExtensibleClass ext
            ? ext.getOwnMethods()
            : List.of(target.getMethods());
    }

    // ------------------------------------------------------------------
    // callSuper
    // ------------------------------------------------------------------

    /**
     * Whether the generated members call the superclass's own.
     *
     * <p>{@code AUTO} is resolved the way the processor resolves it - the
     * annotation on the superclass first, and only then a scan for the members
     * themselves, requiring <b>every</b> one of them. A superclass overriding
     * {@code equals} without {@code hashCode} is already inconsistent, and the
     * processor errors rather than guessing; the tooltip stays quiet and shows
     * no super call, since that is what gets emitted after the error.
     *
     * <p>The resolution itself is the inspection half's, not a fifth copy of it.
     * The tooltip and the reports have to answer this identically or the gutter
     * contradicts the squiggle beside it, and the shared walk is the one that is
     * guarded against the cyclic {@code extends} an incomplete file routinely
     * presents.
     *
     * @param target the annotated type
     * @param annotation the annotation being described
     * @param equality whether the caller is the equality pair rather than {@code toString}
     * @return whether the super call appears in the generated member
     */
    private static boolean callsSuper(PsiClass target, PsiAnnotation annotation, boolean equality) {
        String written = enumConstant(annotation, "callSuper");
        String resolved = written == null ? "AUTO" : written;
        return equality
            ? WholeObjectConstants.callSuper(target, resolved, EqualityConfig.FQN,
                Signature.EQUALS, Signature.HASH_CODE)
            : WholeObjectConstants.callSuper(target, resolved, ToStringConfig.FQN,
                Signature.TO_STRING);
    }

    // ------------------------------------------------------------------
    // Attribute readers
    // ------------------------------------------------------------------

    /**
     * The annotation of this simple name written on a member.
     *
     * <p>Matched on the reference text rather than by resolving it, which is not
     * an optimisation. Resolving an annotation written on a field re-enters
     * every augment provider registered for the class, and the platform answers
     * that cycle by disabling caching and logging an error. The cost is that a
     * marker of the same simple name from another package would be honoured
     * here; that only affects what the gutter predicts, and the processor
     * resolves properly and remains the authority.
     */
    private static @Nullable PsiAnnotation annotationNamed(PsiModifierListOwner owner,
                                                           String simpleName) {
        var modifiers = owner.getModifierList();
        if (modifiers == null) return null;
        for (PsiAnnotation annotation : modifiers.getAnnotations()) {
            PsiJavaCodeReferenceElement reference = annotation.getNameReferenceElement();
            if (reference != null && simpleName.equals(reference.getReferenceName())) {
                return annotation;
            }
        }
        return null;
    }

    private static @Nullable String enumConstant(PsiAnnotation annotation, String attribute) {
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue(attribute);
        if (value instanceof PsiReferenceExpression reference) return reference.getReferenceName();
        return null;
    }

    private static boolean booleanAttr(PsiAnnotation annotation, String attribute,
                                       boolean fallback) {
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue(attribute);
        if (value == null) return fallback;
        return "true".equals(value.getText());
    }

    private static int intAttr(PsiAnnotation annotation, String attribute) {
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue(attribute);
        if (value instanceof PsiLiteralExpression literal
            && literal.getValue() instanceof Integer written) return written;
        return 0;
    }

    private static @Nullable String stringAttr(PsiAnnotation annotation, String attribute) {
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue(attribute);
        if (value instanceof PsiLiteralExpression literal
            && literal.getValue() instanceof String written) return written;
        return null;
    }

    private static List<String> stringArrayAttr(PsiAnnotation annotation, String attribute) {
        List<String> out = new ArrayList<>();
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue(attribute);
        if (value instanceof PsiLiteralExpression literal
            && literal.getValue() instanceof String written) {
            out.add(written);
        } else if (value instanceof PsiArrayInitializerMemberValue array) {
            for (PsiAnnotationMemberValue entry : array.getInitializers()) {
                if (entry instanceof PsiLiteralExpression literal
                    && literal.getValue() instanceof String written) out.add(written);
            }
        }
        return out;
    }

    private static String simpleName(String fqn) {
        return fqn.substring(fqn.lastIndexOf('.') + 1);
    }

}
