package dev.simplified.log.editor;

import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiType;
import com.intellij.psi.impl.light.LightFieldBuilder;
import com.intellij.psi.impl.source.PsiExtensibleClass;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import dev.simplified.log.inspect.LogConstants;
import dev.simplified.shared.psi.AbstractRecursionSafeAugmentProvider;
import dev.simplified.shared.psi.GeneratedMemberMarker;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;

/**
 * Surfaces the {@code private static final Logger log} field {@code @Log}
 * generates to the PSI layer, so every {@code log.info(...)} resolves and
 * autocompletes before the first javac round rather than reading red against
 * source that builds cleanly.
 *
 * <p>Two constraints are not obvious. The field type is resolved by
 * fully-qualified name against the target's own resolve scope, and the
 * unresolved type is contributed as-is when log4j2 is absent from the module -
 * a fallback to {@code Object} or a skip would paint the editor green over
 * source javac rejects, which is the failure mode this provider exists to
 * prevent. And declared fields are read through
 * {@link PsiExtensibleClass#getOwnFields()}: {@link PsiClass#getFields()} routes
 * back through the platform's augment chain and re-enters this provider.
 */
public final class LogAugmentProvider extends AbstractRecursionSafeAugmentProvider {

    @Override
    protected @NotNull <Psi extends PsiElement> List<Psi> getAugments(@NotNull PsiElement element,
                                                                     @NotNull Class<Psi> type,
                                                                     @Nullable String nameHint) {
        if (!(element instanceof PsiClass target)) return Collections.emptyList();
        if (!PsiField.class.isAssignableFrom(type)) return Collections.emptyList();
        if (!LogConstants.isLegalTarget(target)) return Collections.emptyList();
        if (LogConstants.findLog(target) == null) return Collections.emptyList();

        @SuppressWarnings("unchecked")
        List<Psi> fields = (List<Psi>) cachedFields(target);
        return fields;
    }

    // ------------------------------------------------------------------
    // Synthesis - cached on the target's PSI modification count.
    // ------------------------------------------------------------------

    private static List<PsiField> cachedFields(PsiClass target) {
        return CachedValuesManager.getCachedValue(target, () -> {
            List<PsiField> fields = buildFields(target);
            return CachedValueProvider.Result.create(fields, PsiModificationTracker.MODIFICATION_COUNT);
        });
    }

    private static List<PsiField> buildFields(PsiClass target) {
        PsiAnnotation annotation = LogConstants.findLog(target);
        String fieldName = LogConstants.resolvedFieldName(annotation, target);
        if (fieldName == null) return Collections.emptyList();
        if (hasOwnField(target, fieldName)) return Collections.emptyList();

        Project project = target.getProject();
        PsiManager manager = PsiManager.getInstance(project);
        PsiType loggerType = JavaPsiFacade.getElementFactory(project)
            .createTypeByFQClassName(LogConstants.LOGGER_FQN, target.getResolveScope());
        return List.of(buildField(manager, target, fieldName, loggerType));
    }

    private static PsiField buildField(PsiManager manager, PsiClass target, String name, PsiType type) {
        LightFieldBuilder field = new LightFieldBuilder(manager, name, type);
        field.setModifiers(PsiModifier.PRIVATE, PsiModifier.STATIC, PsiModifier.FINAL);
        field.setContainingClass(target);
        field.setNavigationElement(target);
        GeneratedMemberMarker.mark(field);
        return field;
    }

    private static boolean hasOwnField(PsiClass target, String name) {
        Iterable<PsiField> fields = target instanceof PsiExtensibleClass ext
            ? ext.getOwnFields()
            : List.of(target.getFields());
        for (PsiField f : fields) {
            if (name.equals(f.getName())) return true;
        }
        return false;
    }
}
