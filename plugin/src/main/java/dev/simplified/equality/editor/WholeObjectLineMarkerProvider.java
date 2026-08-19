package dev.simplified.equality.editor;

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo;
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider;
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder;
import com.intellij.openapi.util.IconLoader;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.util.PsiTreeUtil;
import dev.simplified.equality.apt.EqualityConfig;
import dev.simplified.equality.inspect.WholeObjectConstants.Selected;
import dev.simplified.equality.inspect.WholeObjectConstants.Signature;
import dev.simplified.equality.inspect.WholeObjectConstants;
import dev.simplified.tostring.apt.ToStringConfig;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;
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
 * <p><b>Every decision the tooltip describes is taken by
 * {@link WholeObjectConstants}</b> - which members are selected, what each is
 * printed as, what order they arrive in, whether the superclass is called. A
 * second implementation of any of them is the one defect this feature cannot
 * signal: nothing resolves through the gutter and nothing turns red when it is
 * wrong, so a rule that drifts from the processor's produces a confidently
 * wrong description that no other check and no compiler contradicts.
 *
 * <p>The icon lives at {@code /icons/generated.svg}, shared with the builder's
 * marker so one glance says "this member is synthesised" wherever it appears.
 */
public final class WholeObjectLineMarkerProvider extends RelatedItemLineMarkerProvider {

    private static final Icon ICON = IconLoader.getIcon(
        "/icons/generated.svg",
        WholeObjectLineMarkerProvider.class
    );

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

        List<Selected> members = WholeObjectConstants.selected(target, annotation,
            WholeObjectConstants.EQUALITY_POLICY);
        out.append("<br>").append(members.isEmpty()
            ? "Compares no members - every instance compares equal to every other"
            : "Compares: " + labels(members));

        if (callsSuper(target, annotation, true)) {
            out.append("<br>Calls super.equals and super.hashCode");
        }
        if (WholeObjectConstants.booleanAttr(annotation,
            WholeObjectConstants.ATTR_CACHE_HASH_CODE, false)) {
            out.append("<br>Memoizes the hash in a transient $hashCode field");
        }
        if (readsAccessors(annotation)) {
            out.append("<br>Reads each member through its declared accessor");
        }
        return out.toString();
    }

    /** The gutter text for {@code @ToString} - the line the member returns. */
    private static @NotNull String toStringTooltip(PsiClass target, PsiAnnotation annotation) {
        StringBuilder out = new StringBuilder("Generated by ").append(ToStringConfig.LABEL);
        out.append("<br>Renders: ").append(rendered(target, annotation));
        if (readsAccessors(annotation)) {
            out.append("<br>Reads each member through its declared accessor");
        }
        return out.toString();
    }

    private static boolean readsAccessors(PsiAnnotation annotation) {
        return WholeObjectConstants.booleanAttr(annotation,
            WholeObjectConstants.ATTR_USE_ACCESSORS, false);
    }

    private static String identity(PsiClass target, PsiAnnotation annotation) {
        String written = WholeObjectConstants.enumAttr(annotation,
            WholeObjectConstants.ATTR_IDENTITY, WholeObjectConstants.EXACT_CLASS);
        String name = target.getName() == null ? "the type" : target.getName();
        if ("INSTANCE_OF".equals(written)) return "o instanceof " + name;
        if (WholeObjectConstants.INSTANCE_OF_CANEQUAL.equals(written)) {
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
        boolean lombok = "LOMBOK".equals(WholeObjectConstants.enumAttr(annotation,
            WholeObjectConstants.ATTR_STYLE, "SIMPLIFIED"));
        boolean fieldNames = WholeObjectConstants.booleanAttr(annotation,
            WholeObjectConstants.ATTR_INCLUDE_FIELD_NAMES, true);
        List<Selected> members = WholeObjectConstants.selected(target, annotation,
            WholeObjectConstants.TO_STRING_POLICY);

        StringBuilder out = new StringBuilder(target.getName() == null ? "" : target.getName());
        out.append(lombok ? '(' : '[');
        boolean first = true;
        if (callsSuper(target, annotation, false)) {
            out.append("super=...");
            first = false;
        }
        for (Selected member : members) {
            if (!first) out.append(", ");
            if (fieldNames) out.append(member.label()).append('=');
            out.append("...");
            first = false;
        }
        return out.append(lombok ? ')' : ']').toString();
    }

    private static String labels(List<Selected> members) {
        StringBuilder out = new StringBuilder();
        for (Selected member : members) {
            if (!out.isEmpty()) out.append(", ");
            out.append(member.label());
        }
        return out.toString();
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
        String resolved = WholeObjectConstants.enumAttr(annotation,
            WholeObjectConstants.ATTR_CALL_SUPER, "AUTO");
        return equality
            ? WholeObjectConstants.callSuper(target, resolved, EqualityConfig.FQN,
                Signature.EQUALS, Signature.HASH_CODE)
            : WholeObjectConstants.callSuper(target, resolved, ToStringConfig.FQN,
                Signature.TO_STRING);
    }

}
