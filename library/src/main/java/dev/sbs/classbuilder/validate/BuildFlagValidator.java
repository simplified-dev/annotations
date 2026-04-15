package dev.sbs.classbuilder.validate;

import dev.sbs.annotation.BuildFlag;
import dev.sbs.annotation.BuildRule;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Runtime helper that scans an object's fields for
 * {@link BuildRule @BuildRule(flag = @BuildFlag(...))} annotations and
 * enforces the nested flag's constraints. Invoked from the generated
 * {@code build()} method of every {@code @ClassBuilder}-annotated type.
 *
 * <p>The list of flagged fields per class is cached on first invocation,
 * so subsequent calls for the same type skip all reflective discovery and
 * only read field values. Fields with a no-op flag (every attribute at its
 * default) are elided at cache-build time and never checked.
 */
public final class BuildFlagValidator {

    private static final ConcurrentHashMap<Class<?>, List<FlaggedField>> CACHE = new ConcurrentHashMap<>();

    private BuildFlagValidator() {}

    /**
     * Validates the given object's {@link BuildFlag}-annotated fields.
     *
     * @param target the instance whose fields should be checked
     * @throws BuilderValidationException if any constraint is violated
     */
    public static void validate(@NotNull Object target) {
        List<FlaggedField> fields = CACHE.computeIfAbsent(target.getClass(), BuildFlagValidator::scan);
        if (fields.isEmpty()) return;

        Map<String, List<GroupMember>> groupResults = new HashMap<>();
        String typeName = target.getClass().getSimpleName();

        for (FlaggedField ff : fields) {
            Object value = readValue(ff.field(), target);
            BuildFlag flag = ff.flag();

            boolean nullInvalid = flag.nonNull() && value == null;
            boolean emptyInvalid = flag.notEmpty() && !nullInvalid && isEmpty(value);
            boolean requiredInvalid = nullInvalid || emptyInvalid;

            if (flag.nonNull() || flag.notEmpty()) {
                if (flag.group().length == 0) {
                    if (requiredInvalid)
                        throw new BuilderValidationException(
                            "Field '%s' in '%s' is required and is null/empty",
                            ff.field().getName(), typeName
                        );
                } else {
                    for (String group : flag.group())
                        groupResults.computeIfAbsent(group, g -> new ArrayList<>())
                            .add(new GroupMember(ff.field(), requiredInvalid));
                }
            }

            if (!flag.pattern().isEmpty() && value != null) {
                String s = patternTarget(value);
                if (s != null && !Pattern.matches(flag.pattern(), s))
                    throw new BuilderValidationException(
                        "Field '%s' in '%s' does not match pattern '%s' (value: '%s')",
                        ff.field().getName(), typeName, flag.pattern(), value
                    );
            }

            if (flag.limit() >= 0 && value != null) {
                int measured = measureLength(value, ff.field());
                if (measured > flag.limit())
                    throw new BuilderValidationException(
                        "Field '%s' in '%s' has length %d, exceeds limit of %d",
                        ff.field().getName(), typeName, measured, flag.limit()
                    );
            }
        }

        for (Map.Entry<String, List<GroupMember>> entry : groupResults.entrySet()) {
            List<GroupMember> members = entry.getValue();
            if (members.stream().allMatch(GroupMember::invalid)) {
                String missing = members.stream()
                    .map(m -> m.field().getName())
                    .collect(Collectors.joining(","));
                throw new BuilderValidationException(
                    "Field group '%s' in '%s' is required and [%s] is null/empty",
                    entry.getKey(), typeName, missing
                );
            }
        }
    }

    private static @NotNull List<FlaggedField> scan(@NotNull Class<?> cls) {
        List<FlaggedField> out = new ArrayList<>();
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                BuildRule rule = field.getAnnotation(BuildRule.class);
                if (rule == null) continue;
                BuildFlag flag = rule.flag();
                if (!hasAnyConstraint(flag)) continue;
                try {
                    field.setAccessible(true);
                } catch (RuntimeException ignored) {
                    // Module access denied - skip silently; validation falls back to whatever is public
                }
                out.add(new FlaggedField(field, flag));
            }
        }
        return List.copyOf(out);
    }

    /**
     * True iff the flag carries at least one meaningful constraint. A flag
     * with every attribute at its default value is a no-op and filtered out
     * at scan time, so the hot loop in {@link #validate} never touches it.
     */
    private static boolean hasAnyConstraint(@NotNull BuildFlag flag) {
        return flag.nonNull()
            || flag.notEmpty()
            || !flag.pattern().isEmpty()
            || flag.limit() >= 0
            || flag.group().length > 0;
    }

    private static @Nullable Object readValue(@NotNull Field field, @NotNull Object target) {
        try {
            return field.get(target);
        } catch (IllegalAccessException e) {
            throw new BuilderValidationException(
                e, "Unable to read field '%s' on '%s'",
                field.getName(), target.getClass().getSimpleName()
            );
        }
    }

    private static boolean isEmpty(@Nullable Object value) {
        if (value == null) return true;
        if (value instanceof CharSequence cs) return cs.length() == 0;
        if (value instanceof Optional<?> opt) return opt.isEmpty();
        if (value instanceof Collection<?> coll) return coll.isEmpty();
        if (value instanceof Map<?, ?> map) return map.isEmpty();
        if (value instanceof Object[] arr) return arr.length == 0;
        return false;
    }

    private static @Nullable String patternTarget(@NotNull Object value) {
        if (value instanceof CharSequence cs) return cs.toString();
        if (value instanceof Optional<?> opt) return opt.map(String::valueOf).orElse(null);
        return null;
    }

    private static int measureLength(@NotNull Object value, @NotNull Field field) {
        if (value instanceof CharSequence cs) return cs.length();
        if (value instanceof Collection<?> coll) return coll.size();
        if (value instanceof Map<?, ?> map) return map.size();
        if (value instanceof Object[] arr) return arr.length;
        if (value instanceof Optional<?> opt) {
            if (opt.isEmpty()) return 0;
            Object inner = opt.get();
            if (inner instanceof CharSequence cs) return cs.length();
            if (inner instanceof Number n) return n.intValue();
            Type generic = field.getGenericType();
            if (generic instanceof ParameterizedType pt) {
                Type arg = pt.getActualTypeArguments()[0];
                if (arg instanceof Class<?> argCls) {
                    if (Number.class.isAssignableFrom(argCls) && inner instanceof Number n) return n.intValue();
                    if (CharSequence.class.isAssignableFrom(argCls)) return String.valueOf(inner).length();
                }
            }
            return String.valueOf(inner).length();
        }
        return -1;
    }

    private record FlaggedField(@NotNull Field field, @NotNull BuildFlag flag) {}

    private record GroupMember(@NotNull Field field, boolean invalid) {}

}
