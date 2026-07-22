package dev.simplified.classbuilder.inspect;

import com.intellij.codeInsight.FileModificationService;
import com.intellij.codeInsight.hint.HintManager;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiEnumConstant;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodReferenceExpression;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.PsiNameValuePair;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiReturnStatement;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.PsiSuperExpression;
import com.intellij.psi.PsiThisExpression;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypes;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.impl.source.PsiExtensibleClass;
import com.intellij.psi.search.PsiSearchHelper;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.search.UsageSearchContext;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import dev.simplified.accessor.inspect.AccessorConstants;
import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.classbuilder.apt.AccessorScheme;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reports a generated read accessor whose visibility is wider than anything
 * reads, and a hand-written set of trivial accessors a single {@code @Getter}
 * would replace.
 *
 * <p>The two triggers face opposite directions - one narrows an annotation
 * already written, the other proposes one - but both answer the same question
 * about the same member, read the same {@link AccessorScheme}, and would
 * otherwise duplicate the whole "which accessor does this field generate"
 * resolution.
 *
 * <p>No fix ever rewrites a type-level annotation already written. Narrowing is
 * expressed as a field-level override, which is what keeps the fix local: the
 * type-level {@code @Getter} is a statement about the class, and rewriting it to
 * serve one field would silently move every sibling. The promotion fix does
 * write a type-level annotation, since proposing one for the whole class is
 * what it is for.
 */
public class GetterVisibilityInspection extends LocalInspectionTool {

    private static final String ACCESS_LEVEL_FQN = "dev.simplified.annotations.AccessLevel";
    private static final String NAMING_STYLE_FQN = "dev.simplified.annotations.NamingStyle";

    /** Balloon group for the one message that has nowhere else to go. */
    private static final String NOTIFICATION_GROUP = "Simplified Annotations";

    /**
     * Ceiling on the occurrences a single accessor name may have before the
     * trigger gives up. A name common enough to blow through this is one whose
     * usage nobody can review from a gutter icon anyway, and the scan runs on
     * every class in the file.
     */
    private static final int MAX_OCCURRENCES = 200;

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder,
                                                   boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitClass(@NotNull PsiClass target) {
                super.visitClass(target);
                if (!isSupportedKind(target)) return;
                if (target.getName() == null) return;
                checkWidth(holder, target);
                checkPromotable(holder, target);
            }
        };
    }

    /**
     * Records and interfaces generate no accessor - a record's components are
     * accessors already, and an interface declares no field to read.
     */
    private static boolean isSupportedKind(@NotNull PsiClass target) {
        return !target.isRecord() && !target.isInterface() && !target.isAnnotationType();
    }

    // Trigger A - a generated accessor wider than its readers.

    private static void checkWidth(@NotNull ProblemsHolder holder, @NotNull PsiClass target) {
        PsiAnnotation typeLevel = target.getAnnotation(AccessorConstants.GETTER_FQN);
        for (PsiField field : ownFields(target)) {
            // An enum's constants are fields of the enum type as far as the PSI
            // is concerned; the mutator skips them and generates nothing.
            if (field instanceof PsiEnumConstant) continue;
            PsiAnnotation effective = AccessorConstants.effectiveGetter(typeLevel, field);
            if (effective == null) continue;
            // Only a public accessor has anywhere to narrow to, and only a
            // public one costs anything to leave alone.
            if (!PsiModifier.PUBLIC.equals(AccessorConstants.accessKeyword(effective))) continue;
            // @Lazy mints its own getter, whose storage is the wrapper - not
            // this pipeline's to move.
            if (field.getAnnotation(AccessorConstants.LAZY_FQN) != null) continue;

            String name = accessorName(effective, field);
            if (declares(target, name)) continue;

            Reach reach = reachOf(target, name);
            if (reach == null) continue;
            report(holder, field, target, name, reach);
        }
    }

    private static void report(@NotNull ProblemsHolder holder, @NotNull PsiField field,
                               @NotNull PsiClass target, @NotNull String name,
                               @NotNull Reach reach) {
        PsiElement anchor = field.getNameIdentifier();
        String owner = target.getName();
        switch (reach) {
            case UNREAD -> holder.registerProblem(anchor,
                name + "() is generated public, but nothing outside " + owner + " reads it",
                ProblemHighlightType.WEAK_WARNING,
                new SetAccessLevelFix(AccessLevel.NONE), new SetAccessLevelFix(AccessLevel.PRIVATE));
            case PACKAGE -> holder.registerProblem(anchor,
                name + "() is generated public, but only " + owner + "'s own package reads it",
                ProblemHighlightType.WEAK_WARNING, new SetAccessLevelFix(AccessLevel.PACKAGE));
            case SUBCLASS -> holder.registerProblem(anchor,
                name + "() is generated public, but only subclasses of " + owner + " read it",
                ProblemHighlightType.WEAK_WARNING, new SetAccessLevelFix(AccessLevel.PROTECTED));
        }
    }

    /** How far outside its declaring class an accessor is actually read. */
    private enum Reach {
        /** Nothing outside the declaring class names it. */
        UNREAD,
        /** Every reader sits in the declaring package. */
        PACKAGE,
        /** Every reader sits in the declaring package or a subclass. */
        SUBCLASS
    }

    /**
     * Classifies where an accessor name is read from.
     *
     * <p>The search is by <b>name</b> rather than by resolving the synthesised
     * method. A generated accessor is a light element contributed by an augment
     * provider, and both its search scope and its identity across
     * reanalysis are the platform's to decide; a name search answers the
     * question without depending on either. The cost is that an unrelated
     * method of the same name counts as a reader, which can only ever suppress
     * a report - the direction a review aid should fail in.
     *
     * @param target the declaring class
     * @param name the accessor name
     * @return the reach, or {@code null} when the accessor is read widely
     *         enough that nothing can be narrowed
     */
    private static @Nullable Reach reachOf(@NotNull PsiClass target, @NotNull String name) {
        List<PsiReferenceExpression> hits = new ArrayList<>();
        boolean[] overflowed = {false};
        SearchScope scope = target.getUseScope();
        PsiSearchHelper.getInstance(target.getProject()).processElementsWithWord(
            (element, offsetInElement) -> {
                if (!(element instanceof PsiReferenceExpression ref)) return true;
                if (!name.equals(ref.getReferenceName())) return true;
                if (PsiTreeUtil.isAncestor(target, ref, true)) return true;
                hits.add(ref);
                if (hits.size() <= MAX_OCCURRENCES) return true;
                overflowed[0] = true;
                return false;
            }, scope, name, UsageSearchContext.IN_CODE, true);
        if (overflowed[0]) return null;
        if (hits.isEmpty()) return Reach.UNREAD;

        String home = packageOf(target);
        boolean allInPackage = true;
        for (PsiReferenceExpression hit : hits) {
            boolean samePackage = home.equals(packageOf(hit));
            if (!samePackage) allInPackage = false;
            if (samePackage) continue;
            PsiClass reader = PsiTreeUtil.getParentOfType(hit, PsiClass.class);
            if (reader == null || !InheritanceUtil.isInheritorOrSelf(reader, target, true)) return null;
            if (!reachableWhenProtected(hit, reader)) return null;
        }
        return allInPackage ? Reach.PACKAGE : Reach.SUBCLASS;
    }

    /**
     * Whether a cross-package subclass would still reach the accessor once it is
     * {@code protected}.
     *
     * <p>Being a subclass is not enough. JLS 6.6.2.1 lets a subclass in another
     * package touch a protected member only through its own type, so a reader
     * that qualifies the call with the superclass keeps compiling today and
     * stops the moment the accessor is narrowed.
     *
     * @param hit the reference naming the accessor
     * @param reader the class the reference sits in
     * @return whether the qualifier is the reader's own type, or absent
     */
    private static boolean reachableWhenProtected(@NotNull PsiReferenceExpression hit,
                                                  @NotNull PsiClass reader) {
        PsiExpression qualifier = hit.getQualifierExpression();
        if (qualifier == null) return true;
        if (qualifier instanceof PsiThisExpression || qualifier instanceof PsiSuperExpression) {
            return true;
        }
        PsiClass qualifierType = PsiUtil.resolveClassInClassTypeOnly(qualifier.getType());
        return qualifierType != null && InheritanceUtil.isInheritorOrSelf(qualifierType, reader, true);
    }

    private static @NotNull String packageOf(@NotNull PsiElement element) {
        return element.getContainingFile() instanceof PsiJavaFile java ? java.getPackageName() : "";
    }

    // Trigger B - hand-written accessors a @Getter subsumes.

    private static void checkPromotable(@NotNull ProblemsHolder holder, @NotNull PsiClass target) {
        if (target.getAnnotation(AccessorConstants.GETTER_FQN) != null) return;
        if (target.getModifierList() == null) return;

        List<PsiMethod> bean = trivialAccessors(target, false);
        List<PsiMethod> fluent = trivialAccessors(target, true);
        boolean useFluent = fluent.size() > bean.size();
        List<PsiMethod> matches = useFluent ? fluent : bean;
        if (matches.size() < 2) return;

        List<String> names = new ArrayList<>(matches.size());
        for (PsiMethod method : matches) names.add(method.getName() + "()");
        PsiElement anchor = target.getNameIdentifier();
        if (anchor == null) return;
        holder.registerProblem(anchor,
            String.join(", ", names) + " return their field and nothing else - one @Getter on "
                + target.getName() + " generates all of them",
            ProblemHighlightType.WARNING, new PromoteToGetterFix(useFluent));
    }

    /**
     * The methods of a class whose entire body returns a field of that class,
     * under the name the given style would mint.
     *
     * <p>The body test is exact on purpose. A null check, a defensive copy, a
     * cast, a widening return, a ternary, or a read of any other field is work
     * the author wrote, and the seeding this project does elsewhere deliberately
     * prefers such an accessor over a direct field read for that reason.
     * Replacing one with a generated accessor would change behaviour with
     * nothing to show for it.
     *
     * @param target the class to read
     * @param fluent whether to match the field-named spelling rather than the
     *        bean-shaped one
     * @return the matching methods, in declaration order
     */
    private static @NotNull List<PsiMethod> trivialAccessors(@NotNull PsiClass target,
                                                             boolean fluent) {
        AccessorScheme scheme = AccessorScheme.of(fluent ? NamingStyle.FLUENT : NamingStyle.SIMPLIFIED);
        List<PsiMethod> out = new ArrayList<>();
        for (PsiMethod method : ownMethods(target)) {
            PsiField field = returnedField(target, method);
            if (field == null) continue;
            boolean isBoolean = PsiTypes.booleanType().equals(field.getType());
            if (!method.getName().equals(scheme.readName(field.getName(), isBoolean))) continue;
            out.add(method);
        }
        return out;
    }

    /**
     * The field a method returns, when that is literally all it does.
     *
     * @param target the enclosing class
     * @param method the candidate accessor
     * @return the field, or {@code null} when the method is anything more
     */
    private static @Nullable PsiField returnedField(@NotNull PsiClass target,
                                                    @NotNull PsiMethod method) {
        if (method.isConstructor()) return null;
        if (!method.hasModifierProperty(PsiModifier.PUBLIC)) return null;
        if (method.hasModifierProperty(PsiModifier.STATIC)) return null;
        // Acquiring the monitor is work, and a generated accessor never carries
        // it - the swap would drop a memory-visibility guarantee with nothing to
        // show for it and nothing to fail on.
        if (method.hasModifierProperty(PsiModifier.SYNCHRONIZED)) return null;
        if (!method.getParameterList().isEmpty()) return null;
        if (method.getTypeParameters().length > 0) return null;
        if (method.getThrowsList().getReferencedTypes().length > 0) return null;
        // An annotation on the accessor is a contract with something else -
        // a serializer, a validator, an override check - that a generated
        // member cannot carry over.
        PsiModifierList modifiers = method.getModifierList();
        if (modifiers.getAnnotations().length > 0) return null;
        // A method an interface or superclass declares is not this class's to
        // withdraw, and no annotation can satisfy the inherited declaration.
        if (method.findSuperMethods().length > 0) return null;

        var body = method.getBody();
        if (body == null) return null;
        PsiStatement[] statements = body.getStatements();
        if (statements.length != 1) return null;
        if (!(statements[0] instanceof PsiReturnStatement returned)) return null;
        if (!(returned.getReturnValue() instanceof PsiReferenceExpression ref)) return null;
        if (ref instanceof PsiMethodReferenceExpression) return null;

        PsiElement qualifier = ref.getQualifier();
        if (qualifier != null && !(qualifier instanceof PsiThisExpression self
            && self.getQualifier() == null)) return null;
        if (!(ref.resolve() instanceof PsiField field)) return null;
        if (!target.equals(field.getContainingClass())) return null;
        if (field.hasModifierProperty(PsiModifier.STATIC)) return null;
        if (field.getAnnotation(AccessorConstants.LAZY_FQN) != null) return null;
        // A field-level @Getter replaces the promoted type-level one outright,
        // so the accessor it mints has to be the method being deleted - same
        // name, still public. A narrowed level, a different style or a written
        // name would turn the promotion into a rename or a visibility cut, and
        // AccessLevel.NONE into a deletion.
        PsiAnnotation fieldLevel = field.getAnnotation(AccessorConstants.GETTER_FQN);
        if (fieldLevel != null) {
            if (!PsiModifier.PUBLIC.equals(AccessorConstants.accessKeyword(fieldLevel))) return null;
            if (!method.getName().equals(accessorName(fieldLevel, field))) return null;
        }

        // A widening or boxing return is a conversion, which is work.
        PsiType returnType = method.getReturnType();
        return returnType != null && returnType.equals(field.getType()) ? field : null;
    }

    // Shared PSI reading.

    private static @NotNull String accessorName(@NotNull PsiAnnotation effective,
                                                @NotNull PsiField field) {
        AccessorScheme scheme = AccessorScheme.resolve(
            AccessorConstants.style(effective), AccessorConstants.name(effective));
        return scheme.readName(field.getName(), PsiTypes.booleanType().equals(field.getType()));
    }

    /** The {@code @Getter} the field's enclosing type carries, or {@code null}. */
    private static @Nullable PsiAnnotation governingType(@NotNull PsiField field) {
        PsiClass owner = field.getContainingClass();
        return owner == null ? null : owner.getAnnotation(AccessorConstants.GETTER_FQN);
    }

    private static boolean declares(@NotNull PsiClass target, @NotNull String name) {
        for (PsiMethod method : ownMethods(target)) {
            if (name.equals(method.getName()) && method.getParameterList().isEmpty()) return true;
        }
        return false;
    }

    /**
     * Fields the class itself declares. {@code getOwnFields()} rather than
     * {@code getFields()}, which is augment-aware and re-enters every provider
     * registered for the class.
     */
    private static @NotNull Iterable<PsiField> ownFields(@NotNull PsiClass target) {
        return target instanceof PsiExtensibleClass ext
            ? ext.getOwnFields()
            : List.of(target.getFields());
    }

    /** Methods the class itself declares, for the same reason as {@link #ownFields}. */
    private static @NotNull Iterable<PsiMethod> ownMethods(@NotNull PsiClass target) {
        return target instanceof PsiExtensibleClass ext
            ? ext.getOwnMethods()
            : List.of(target.getMethods());
    }

    // Quick fixes.

    /**
     * Writes a field-level {@code @Getter} carrying one access level, or
     * rewrites the one already there.
     *
     * <p>The type-level annotation is never touched. A field-level override is
     * the mechanism the annotation surface provides for exactly this, and it is
     * what keeps the edit to the one field the report was about.
     */
    private static final class SetAccessLevelFix implements LocalQuickFix {

        private final @NotNull AccessLevel level;

        SetAccessLevelFix(@NotNull AccessLevel level) {
            this.level = level;
        }

        @Override
        public @NotNull String getFamilyName() {
            return this.level == AccessLevel.NONE
                ? "Drop the accessor with @Getter(AccessLevel.NONE)"
                : "Narrow the accessor to " + this.level.name().toLowerCase(Locale.ROOT);
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiField field = PsiTreeUtil.getParentOfType(descriptor.getPsiElement(), PsiField.class, false);
            if (field == null) return;
            PsiModifierList modifiers = field.getModifierList();
            if (modifiers == null) return;

            PsiAnnotation written = field.getAnnotation(AccessorConstants.GETTER_FQN);
            // A field-level annotation replaces the type's outright, so the
            // naming the report was computed against has to be carried onto the
            // override however it was reached. Taking it only from an annotation
            // already on the field would turn a narrowing of a type-level
            // style = FLUENT into a rename back to the bean spelling.
            PsiAnnotation source = written != null ? written : governingType(field);
            List<String> attributes = new ArrayList<>();
            attributes.add(ACCESS_LEVEL_FQN + "." + this.level.name());
            if (source != null) {
                // Anything else the effective annotation said about the accessor
                // - a style, a name - is about naming rather than width, and
                // survives the narrowing untouched. 'exclude' does not: it names
                // the fields a type-level fan-out skips, and means nothing on
                // the one field this override is about.
                for (PsiNameValuePair pair : source.getParameterList().getAttributes()) {
                    String name = pair.getName();
                    if (name == null || "value".equals(name) || "exclude".equals(name)) continue;
                    PsiAnnotationMemberValue value = pair.getValue();
                    if (value != null) attributes.add(name + " = " + value.getText());
                }
            }
            write(project, modifiers, written,
                "@" + AccessorConstants.GETTER_FQN + "(" + String.join(", ", attributes) + ")");
        }
    }

    /**
     * Replaces or adds a {@code @Getter}, then shortens it back to simple names
     * and imports what that needs.
     *
     * @param project the open project
     * @param modifiers the modifier list receiving the annotation
     * @param existing the annotation already there, or {@code null}
     * @param text the replacement, written with fully-qualified names
     */
    private static void write(@NotNull Project project, @NotNull PsiModifierList modifiers,
                              @Nullable PsiAnnotation existing, @NotNull String text) {
        PsiAnnotation anchor = existing != null
            ? existing
            : modifiers.addAnnotation(AccessorConstants.GETTER_FQN);
        PsiElement written = anchor.replace(
            JavaPsiFacade.getElementFactory(project).createAnnotationFromText(text, modifiers));
        JavaCodeStyleManager.getInstance(project).shortenClassReferences(written);
    }

    /**
     * Writes a type-level {@code @Getter} and deletes the trivial accessors it
     * subsumes, leaving every other method alone.
     *
     * <p>Runs outside a write action so the reference search that decides
     * whether the deletion is safe at all happens before anything is modified.
     */
    private static final class PromoteToGetterFix implements LocalQuickFix {

        private final boolean fluent;

        PromoteToGetterFix(boolean fluent) {
            this.fluent = fluent;
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Replace the trivial accessors with @Getter";
        }

        @Override
        public boolean startInWriteAction() {
            return false;
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiClass target = PsiTreeUtil.getParentOfType(descriptor.getPsiElement(), PsiClass.class, false);
            if (target == null) return;
            List<PsiMethod> matches = trivialAccessors(target, this.fluent);
            if (matches.size() < 2) return;

            String blocker = blocker(matches);
            if (blocker != null) {
                warn(project, blocker);
                return;
            }
            // Declining the platform's write action also declines the writability
            // check it would have run, so the file has to be prepared here.
            if (!FileModificationService.getInstance().preparePsiElementForWrite(target)) return;
            WriteCommandAction.runWriteCommandAction(project, () -> promote(project, target, matches));
        }

        private void promote(@NotNull Project project, @NotNull PsiClass target,
                             @NotNull List<PsiMethod> matches) {
            PsiModifierList modifiers = target.getModifierList();
            if (modifiers == null) return;
            List<String> attributes = new ArrayList<>(2);
            if (this.fluent) attributes.add("style = " + NAMING_STYLE_FQN + ".FLUENT");
            List<String> unaccessed = unaccessedFields(target, matches);
            if (!unaccessed.isEmpty()) attributes.add("exclude = " + arrayLiteral(unaccessed));
            write(project, modifiers, null, attributes.isEmpty()
                ? "@" + AccessorConstants.GETTER_FQN
                : "@" + AccessorConstants.GETTER_FQN + "(" + String.join(", ", attributes) + ")");
            for (PsiMethod method : matches) method.delete();
        }

        /**
         * The fields the annotation would fan out over that no deleted method
         * accessed.
         *
         * <p>The fix replaces a hand-written set of accessors, so the set it
         * generates has to be the same one. Without this, a field the author
         * never published grows a public accessor as a side effect of tidying
         * up two that were already there.
         *
         * @param target the class receiving the annotation
         * @param matches the methods the fix will delete
         * @return the field names to exclude, in declaration order
         */
        private @NotNull List<String> unaccessedFields(@NotNull PsiClass target,
                                                       @NotNull List<PsiMethod> matches) {
            List<String> accessed = new ArrayList<>(matches.size());
            for (PsiMethod method : matches) {
                PsiField field = returnedField(target, method);
                if (field != null) accessed.add(field.getName());
            }
            List<String> out = new ArrayList<>();
            for (PsiField field : ownFields(target)) {
                if (field instanceof PsiEnumConstant) continue;
                String name = field.getName();
                if (name.startsWith("$") || accessed.contains(name)) continue;
                out.add(name);
            }
            return out;
        }

        private static @NotNull String arrayLiteral(@NotNull List<String> names) {
            List<String> quoted = new ArrayList<>(names.size());
            for (String name : names) quoted.add("\"" + name + "\"");
            // A single name is written bare, which is what the attribute reads
            // like everywhere else in this annotation surface.
            return quoted.size() == 1
                ? quoted.iterator().next()
                : "{" + String.join(", ", quoted) + "}";
        }

        /**
         * Why the deletion cannot go ahead, or {@code null} when it can.
         *
         * <p>A method reference names the method itself rather than calling it,
         * and a generated accessor is not a declaration the reference can bind
         * to before the next build round - so the file would stop compiling in
         * the editor the moment the fix ran.
         *
         * @param matches the methods the fix would delete
         * @return the reason to abort, or {@code null}
         */
        private static @Nullable String blocker(@NotNull List<PsiMethod> matches) {
            for (PsiMethod method : matches) {
                if (method.findSuperMethods().length > 0) {
                    return method.getName() + "() implements a method a supertype declares, which "
                        + "@Getter cannot satisfy";
                }
                for (PsiReference reference : ReferencesSearch.search(method).findAll()) {
                    if (reference.getElement() instanceof PsiMethodReferenceExpression) {
                        return method.getName() + "() is used as a method reference, which cannot "
                            + "bind to a generated accessor";
                    }
                }
            }
            return null;
        }

        /**
         * Explains the abort. Run from the results tool window there is no
         * editor to hang a hint on, and the fix would otherwise be exactly the
         * silent no-op it exists to avoid - so a balloon carries the reason
         * instead.
         *
         * @param project the open project
         * @param message why the deletion cannot go ahead
         */
        private static void warn(@NotNull Project project, @NotNull String message) {
            String full = "Cannot replace with @Getter - " + message;
            Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
            if (editor != null) {
                HintManager.getInstance().showErrorHint(editor, full);
                return;
            }
            NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification(full, NotificationType.ERROR)
                .notify(project);
        }
    }

}
