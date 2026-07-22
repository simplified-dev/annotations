package dev.simplified.args.editor;

import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.impl.light.LightMethodBuilder;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import dev.simplified.args.apt.ArgsMode;
import dev.simplified.args.inspect.ArgsConstants;
import dev.simplified.shared.psi.AbstractRecursionSafeAugmentProvider;
import dev.simplified.shared.psi.GeneratedMemberMarker;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Surfaces the constructors the constructor annotations synthesise at javac
 * time, so a {@code new Target(...)} resolves before the first build round.
 *
 * <p>Mandatory rather than convenient, for two reasons that pull in opposite
 * directions. Without it a {@code @AllArgsConstructor} target's every call site
 * is unresolved, and the IDE never runs javac, so "until the next build" is not
 * a mitigation. And a {@code @NoArgsConstructor(access = PRIVATE)} exists
 * precisely to <b>remove</b> the public default the platform would otherwise
 * assume - contributing it here is what makes an outside {@code new Target()}
 * report the error it will get from javac.
 *
 * <p>{@code @BuilderArgsConstructor} is deliberately not handled: the builder's
 * own provider already synthesises that constructor, including the parameter
 * reshaping the builder does for defaulted fields.
 */
public final class ArgsAugmentProvider extends AbstractRecursionSafeAugmentProvider {

    @Override
    protected @NotNull <Psi extends PsiElement> List<Psi> getAugments(@NotNull PsiElement element,
                                                                     @NotNull Class<Psi> type,
                                                                     @Nullable String nameHint) {
        if (!(element instanceof PsiClass target)) return Collections.emptyList();
        if (!PsiMethod.class.isAssignableFrom(type)) return Collections.emptyList();
        // Rejected by the processor too: a record's canonical constructor is its
        // contract, and an interface has no constructor at all.
        if (target.isRecord() || target.isInterface()) return Collections.emptyList();
        if (IN_PROGRESS.get().contains(target)) return Collections.emptyList();

        @SuppressWarnings("unchecked")
        List<Psi> methods = (List<Psi>) cachedConstructors(target);
        return methods;
    }

    private static List<PsiMethod> cachedConstructors(PsiClass target) {
        return CachedValuesManager.getCachedValue(target, () -> {
            List<PsiMethod> methods = synthesize(target);
            return CachedValueProvider.Result.create(methods,
                PsiModificationTracker.MODIFICATION_COUNT);
        });
    }

    private static List<PsiMethod> synthesize(PsiClass target) {
        List<PsiAnnotation> written = ArgsConstants.written(target);
        if (written.isEmpty()) return Collections.emptyList();
        String name = target.getName();
        if (name == null) return Collections.emptyList();

        PsiManager manager = target.getManager();
        List<PsiMethod> out = new ArrayList<>(written.size());
        // The same collision rule the processor applies: two annotations
        // resolving to one signature are an error there, so contributing both
        // here would only add a second, differently-worded complaint.
        Set<String> emitted = new LinkedHashSet<>();

        IN_PROGRESS.get().add(target);
        try {
            for (PsiAnnotation annotation : written) {
                ArgsMode mode = ArgsConstants.modeOf(annotation);
                if (mode == null || mode == ArgsMode.BUILDER) continue;
                String access = ArgsConstants.accessKeyword(annotation, mode);
                if (access == null) continue; // AccessLevel.NONE generates nothing

                List<PsiField> fields = ArgsConstants.select(target, mode, List.of());
                LightMethodBuilder ctor = new LightMethodBuilder(manager, name)
                    .setConstructor(true)
                    .setContainingClass(target);
                StringBuilder signature = new StringBuilder();
                for (PsiField field : fields) {
                    ctor.addParameter(field.getName(), field.getType());
                    signature.append(field.getType().getCanonicalText()).append(',');
                }
                if (!emitted.add(signature.toString())) continue;
                // An enum constructor is private whatever is written, and the
                // language permits nothing else.
                if (target.isEnum()) ctor.addModifier(com.intellij.psi.PsiModifier.PRIVATE);
                else if (!access.isEmpty()) ctor.addModifier(access);
                ctor.setNavigationElement(target);
                GeneratedMemberMarker.mark(ctor);
                out.add(ctor);
            }
        } finally {
            IN_PROGRESS.get().remove(target);
        }
        return out;
    }

}
