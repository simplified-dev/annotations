package dev.sbs.classbuilder.apt;

import dev.sbs.annotation.AccessLevel;

import javax.annotation.processing.Messager;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Emits the full Java source of a generated builder class. One instance per
 * target type. Output is a single self-contained {@code .java} file; imports
 * are collected as the body is built.
 */
final class BuilderEmitter {

    private final TypeElement target;
    private final String targetSimpleName;
    private final String packageName;
    private final String builderName;
    private final BuilderConfig config;
    private final List<FieldSpec> fields;
    private final Set<String> imports = new TreeSet<>();
    private final Messager messager;

    private final StringBuilder body = new StringBuilder(2048);

    BuilderEmitter(TypeElement target, String packageName, BuilderConfig config, List<FieldSpec> fields, Messager messager) {
        this.target = target;
        this.targetSimpleName = target.getSimpleName().toString();
        this.packageName = packageName;
        this.config = config;
        this.builderName = targetSimpleName + "Builder";
        this.fields = fields;
        this.messager = messager;
    }

    String emit() {
        imports.add("org.jetbrains.annotations.NotNull");

        emitClassHeader();
        emitFields();
        for (FieldSpec f : fields) emitFieldSetters(f);
        emitFromMethod();
        emitBuildMethod();
        emitClassFooter();

        return assemble();
    }

    String builderClassName() {
        return builderName;
    }

    // ------------------------------------------------------------------
    // Structure
    // ------------------------------------------------------------------

    private void emitClassHeader() {
        body.append(accessKeyword()).append("class ").append(builderName).append(" {\n\n");
    }

    private void emitClassFooter() {
        body.append("}\n");
    }

    private void emitFields() {
        for (FieldSpec f : fields) {
            body.append("    private ");
            body.append(typeName(f.typeDisplay));
            body.append(' ').append(f.name);
            String initializer = defaultInitializer(f);
            if (initializer != null) body.append(" = ").append(initializer);
            body.append(";\n");
            if (f.builderDefault) {
                messager.printMessage(Diagnostic.Kind.WARNING,
                    "@BuilderDefault is not yet implemented - field will start at the JVM default",
                    f.element
                );
            }
            if (f.obtainViaMethod != null || f.obtainViaField != null) {
                messager.printMessage(Diagnostic.Kind.WARNING,
                    "@ObtainVia is not yet implemented - from() will call the standard getter",
                    f.element
                );
            }
        }
        body.append('\n');
    }

    private String defaultInitializer(FieldSpec f) {
        if (f.isOptional) {
            imports.add("java.util.Optional");
            return "Optional.empty()";
        }
        if (f.isListLike && !f.isSet) {
            imports.add("java.util.ArrayList");
            return "new ArrayList<>()";
        }
        if (f.isSet) {
            imports.add("java.util.LinkedHashSet");
            return "new LinkedHashSet<>()";
        }
        if (f.isMap) {
            imports.add("java.util.LinkedHashMap");
            return "new LinkedHashMap<>()";
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Per-field setters
    // ------------------------------------------------------------------

    private void emitFieldSetters(FieldSpec f) {
        if (f.isBoolean) {
            emitBooleanSetters(f);
            return;
        }
        if (f.isOptional) {
            emitOptionalSetters(f);
            return;
        }
        if ((f.isListLike || f.isMap) && f.singularName != null) {
            emitSingularSetters(f);
            return;
        }
        if (f.isArray) {
            emitArraySetter(f);
            return;
        }
        if (f.isString) {
            emitStringSetter(f);
            return;
        }
        emitPlainSetter(f);
    }

    private void emitPlainSetter(FieldSpec f) {
        emitContract("_ -> this", false, "this");
        body.append("    ").append(accessKeyword()).append("@NotNull ").append(builderName).append(' ')
            .append(methodName(f.name, false)).append('(').append(nullabilityPrefix(f)).append(typeName(f.typeDisplay)).append(' ').append(f.name).append(") {\n");
        body.append("        this.").append(f.name).append(" = ").append(f.name).append(";\n");
        body.append("        return this;\n    }\n\n");
    }

    private void emitStringSetter(FieldSpec f) {
        emitPlainSetter(f);
        if (f.formattable) emitFormattableOverload(f);
    }

    private void emitFormattableOverload(FieldSpec f) {
        imports.add("org.intellij.lang.annotations.PrintFormat");
        boolean nullable = f.formattableNullable || f.nullable;
        emitContract("_, _ -> this", false, "this");
        body.append("    ").append(accessKeyword()).append("@NotNull ").append(builderName).append(' ')
            .append(methodName(f.name, false))
            .append("(@PrintFormat ").append(nullable ? "@Nullable " : "@NotNull ").append("String ").append(f.name)
            .append(", @Nullable Object... args) {\n");
        if (nullable) {
            imports.add("dev.sbs.classbuilder.validate.Strings");
            imports.add("org.jetbrains.annotations.Nullable");
            body.append("        this.").append(f.name).append(" = Strings.formatNullable(").append(f.name).append(", args).orElse(null);\n");
        } else {
            imports.add("org.jetbrains.annotations.Nullable");
            body.append("        this.").append(f.name).append(" = String.format(").append(f.name).append(", args);\n");
        }
        body.append("        return this;\n    }\n\n");
    }

    private void emitBooleanSetters(FieldSpec f) {
        emitBooleanSetterPair(f, f.name, false);
        if (f.negateName != null && !f.negateName.isEmpty()) {
            emitBooleanSetterPair(f, f.negateName, true);
        }
    }

    private void emitBooleanSetterPair(FieldSpec f, String methodBase, boolean inverse) {
        String methodCap = "is" + capitalise(methodBase);
        emitContract("-> this", false, "this");
        body.append("    ").append(accessKeyword()).append("@NotNull ").append(builderName).append(' ').append(methodCap).append("() {\n");
        body.append("        this.").append(f.name).append(" = ").append(inverse ? "false" : "true").append(";\n");
        body.append("        return this;\n    }\n\n");

        emitContract("_ -> this", false, "this");
        body.append("    ").append(accessKeyword()).append("@NotNull ").append(builderName).append(' ')
            .append(methodCap).append("(boolean ").append(methodBase).append(") {\n");
        if (inverse) {
            body.append("        this.").append(f.name).append(" = !").append(methodBase).append(";\n");
        } else {
            body.append("        this.").append(f.name).append(" = ").append(methodBase).append(";\n");
        }
        body.append("        return this;\n    }\n\n");
    }

    private void emitOptionalSetters(FieldSpec f) {
        imports.add("java.util.Optional");
        imports.add("org.jetbrains.annotations.Nullable");

        String inner = typeName(f.optionalInner);
        String setterName = methodName(f.name, false);

        // (@Nullable T) wrapper - for Optional<String> with @Formattable, this is the raw-nullable variant
        emitContract("_ -> this", false, "this");
        body.append("    ").append(accessKeyword()).append("@NotNull ").append(builderName).append(' ')
            .append(setterName).append("(@Nullable ").append(inner).append(' ').append(f.name).append(") {\n");
        body.append("        return this.").append(setterName).append("(Optional.ofNullable(").append(f.name).append("));\n");
        body.append("    }\n\n");

        // (Optional<T>) wrapped variant
        emitContract("_ -> this", false, "this");
        body.append("    ").append(accessKeyword()).append("@NotNull ").append(builderName).append(' ')
            .append(setterName).append("(@NotNull Optional<").append(inner).append("> ").append(f.name).append(") {\n");
        body.append("        this.").append(f.name).append(" = ").append(f.name).append(";\n");
        body.append("        return this;\n    }\n\n");

        // @Formattable + Optional<String>: add format-nullable overload that assigns formatNullable(...) directly
        if (f.formattable && "java.lang.String".equals(f.optionalInner)) {
            imports.add("org.intellij.lang.annotations.PrintFormat");
            imports.add("dev.sbs.classbuilder.validate.Strings");
            emitContract("_, _ -> this", false, "this");
            body.append("    ").append(accessKeyword()).append("@NotNull ").append(builderName).append(' ').append(setterName)
                .append("(@PrintFormat @Nullable String ").append(f.name).append(", @Nullable Object... args) {\n");
            body.append("        this.").append(f.name).append(" = Strings.formatNullable(").append(f.name).append(", args);\n");
            body.append("        return this;\n    }\n\n");
        }
    }

    private void emitSingularSetters(FieldSpec f) {
        String whole = methodName(f.name, false);
        String single = (config.methodPrefix.isEmpty() ? "add" : config.methodPrefix) + capitalise(f.singularName);
        String clear = "clear" + capitalise(f.name);

        if (f.isMap) {
            imports.add("java.util.LinkedHashMap");
            imports.add("java.util.Map");
            String k = typeName(f.mapKey);
            String v = typeName(f.mapValue);

            // whole: withFoo(Map<K,V>) - replace
            emitContract("_ -> this", false, "this");
            body.append("    ").append(accessKeyword()).append("@NotNull ").append(builderName).append(' ').append(whole)
                .append("(@NotNull Map<").append(k).append(", ").append(v).append("> ").append(f.name).append(") {\n");
            body.append("        this.").append(f.name).append(" = new LinkedHashMap<>(").append(f.name).append(");\n");
            body.append("        return this;\n    }\n\n");

            // single: putEntry(K, V)
            String putName = "put" + capitalise(f.singularName);
            emitContract("_, _ -> this", false, "this");
            body.append("    ").append(accessKeyword()).append("@NotNull ").append(builderName).append(' ').append(putName)
                .append("(@NotNull ").append(k).append(" key, ").append(v).append(" value) {\n");
            body.append("        this.").append(f.name).append(".put(key, value);\n");
            body.append("        return this;\n    }\n\n");

            // clear
            emitContract("-> this", false, "this");
            body.append("    ").append(accessKeyword()).append("@NotNull ").append(builderName).append(' ').append(clear).append("() {\n");
            body.append("        this.").append(f.name).append(".clear();\n");
            body.append("        return this;\n    }\n\n");
            return;
        }

        String elem = typeName(f.collectionElement);
        String container = f.isSet ? "LinkedHashSet" : "ArrayList";
        imports.add(f.isSet ? "java.util.LinkedHashSet" : "java.util.ArrayList");

        // varargs replace
        emitContract("_ -> this", false, "this");
        body.append("    ").append(accessKeyword()).append("@NotNull ").append(builderName).append(' ').append(whole)
            .append("(@NotNull ").append(elem).append("... ").append(f.name).append(") {\n");
        body.append("        this.").append(f.name).append(" = new ").append(container).append("<>();\n");
        body.append("        for (").append(elem).append(" e : ").append(f.name).append(") this.").append(f.name).append(".add(e);\n");
        body.append("        return this;\n    }\n\n");

        // Iterable replace
        emitContract("_ -> this", false, "this");
        body.append("    ").append(accessKeyword()).append("@NotNull ").append(builderName).append(' ').append(whole)
            .append("(@NotNull Iterable<").append(elem).append("> ").append(f.name).append(") {\n");
        body.append("        this.").append(f.name).append(" = new ").append(container).append("<>();\n");
        body.append("        ").append(f.name).append(".forEach(this.").append(f.name).append("::add);\n");
        body.append("        return this;\n    }\n\n");

        // addEntry
        emitContract("_ -> this", false, "this");
        body.append("    ").append(accessKeyword()).append("@NotNull ").append(builderName).append(' ').append(single)
            .append("(@NotNull ").append(elem).append(' ').append(f.singularName).append(") {\n");
        body.append("        this.").append(f.name).append(".add(").append(f.singularName).append(");\n");
        body.append("        return this;\n    }\n\n");

        // clear
        emitContract("-> this", false, "this");
        body.append("    ").append(accessKeyword()).append("@NotNull ").append(builderName).append(' ').append(clear).append("() {\n");
        body.append("        this.").append(f.name).append(".clear();\n");
        body.append("        return this;\n    }\n\n");
    }

    private void emitArraySetter(FieldSpec f) {
        String elem = typeName(f.collectionElement);
        String setter = methodName(f.name, false);
        emitContract("_ -> this", false, "this");
        body.append("    ").append(accessKeyword()).append("@NotNull ").append(builderName).append(' ').append(setter)
            .append("(@NotNull ").append(elem).append("... ").append(f.name).append(") {\n");
        body.append("        this.").append(f.name).append(" = ").append(f.name).append(";\n");
        body.append("        return this;\n    }\n\n");
    }

    // ------------------------------------------------------------------
    // from(T) / build()
    // ------------------------------------------------------------------

    private void emitFromMethod() {
        if (!config.generateFrom) return;
        if (config.fromMethodName.isEmpty()) return;

        emitContract("_ -> new", true, null);
        body.append("    ").append(accessKeyword()).append("static @NotNull ").append(builderName).append(' ').append(config.fromMethodName)
            .append("(@NotNull ").append(targetSimpleName).append(" instance) {\n");
        body.append("        ").append(builderName).append(" b = new ").append(builderName).append("();\n");
        for (FieldSpec f : fields) body.append("        b.").append(f.name).append(" = ").append(readFromInstance(f)).append(";\n");
        body.append("        return b;\n    }\n\n");
    }

    private String readFromInstance(FieldSpec f) {
        String getter;
        if (f.isBoolean) {
            getter = "instance.is" + capitalise(f.name) + "()";
        } else {
            getter = "instance.get" + capitalise(f.name) + "()";
        }
        // Defensive copy for mutable collection types so builder mutations do not affect the source.
        if (f.isListLike && !f.isSet) return "new java.util.ArrayList<>(" + getter + ")";
        if (f.isSet) return "new java.util.LinkedHashSet<>(" + getter + ")";
        if (f.isMap) return "new java.util.LinkedHashMap<>(" + getter + ")";
        return getter;
    }

    private void emitBuildMethod() {
        emitContract("-> new", false, null);
        body.append("    ").append(accessKeyword()).append("@NotNull ").append(targetSimpleName).append(' ').append(config.buildMethodName).append("() {\n");
        if (config.validate) {
            imports.add("dev.sbs.classbuilder.validate.BuildFlagValidator");
            body.append("        BuildFlagValidator.validate(this);\n");
        }
        boolean useFactory = !config.factoryMethod.isEmpty();
        String head = useFactory ? (targetSimpleName + "." + config.factoryMethod) : ("new " + targetSimpleName);
        body.append("        return ").append(head).append('(');
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) body.append(", ");
            body.append(fields.get(i).name);
        }
        body.append(");\n    }\n\n");
    }

    // ------------------------------------------------------------------
    // Contracts, helpers
    // ------------------------------------------------------------------

    private void emitContract(String value, boolean pure, String mutates) {
        if (!config.emitContracts) return;
        imports.add("dev.sbs.annotation.XContract");
        body.append("    @XContract(");
        boolean first = true;
        if (value != null) { body.append("value = \"").append(value).append('"'); first = false; }
        if (pure) { if (!first) body.append(", "); body.append("pure = true"); first = false; }
        if (mutates != null) { if (!first) body.append(", "); body.append("mutates = \"").append(mutates).append('"'); }
        body.append(")\n");
    }

    private String accessKeyword() {
        String k = config.access.toKeyword();
        return k.isEmpty() ? "" : k + " ";
    }

    private String methodName(String fieldName, boolean forceBoolean) {
        String prefix = forceBoolean ? "is" : config.methodPrefix;
        if (prefix.isEmpty()) return fieldName;
        return prefix + capitalise(fieldName);
    }

    private static String capitalise(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private String nullabilityPrefix(FieldSpec f) {
        if (f.isPrimitive) return "";
        if (f.notNull) { imports.add("org.jetbrains.annotations.NotNull"); return "@NotNull "; }
        if (f.nullable) { imports.add("org.jetbrains.annotations.Nullable"); return "@Nullable "; }
        return "";
    }

    /** Converts a full type display (e.g. "java.util.List<java.lang.String>") to a simpler form, adding imports. */
    private String typeName(String fullDisplay) {
        return simplifyType(fullDisplay);
    }

    private String simplifyType(String typeStr) {
        StringBuilder out = new StringBuilder(typeStr.length());
        int i = 0;
        int n = typeStr.length();
        while (i < n) {
            int tokenStart = i;
            while (i < n && (Character.isJavaIdentifierPart(typeStr.charAt(i)) || typeStr.charAt(i) == '.')) i++;
            String token = typeStr.substring(tokenStart, i);
            if (!token.isEmpty() && token.indexOf('.') >= 0 && Character.isJavaIdentifierStart(token.charAt(0))) {
                out.append(considerImport(token));
            } else {
                out.append(token);
            }
            if (i < n) {
                out.append(typeStr.charAt(i));
                i++;
            }
        }
        return out.toString();
    }

    private String considerImport(String fqn) {
        if (fqn.startsWith("java.lang.") && fqn.lastIndexOf('.') == "java.lang".length()) {
            return fqn.substring("java.lang.".length());
        }
        int lastDot = fqn.lastIndexOf('.');
        if (lastDot < 0) return fqn;
        String pkg = fqn.substring(0, lastDot);
        String simple = fqn.substring(lastDot + 1);
        if (pkg.equals(packageName)) return simple;
        imports.add(fqn);
        return simple;
    }

    // ------------------------------------------------------------------
    // Final assembly
    // ------------------------------------------------------------------

    private String assemble() {
        StringBuilder sb = new StringBuilder(body.length() + 1024);
        sb.append("// Generated by @ClassBuilder - do not edit\n");
        if (!packageName.isEmpty()) sb.append("package ").append(packageName).append(";\n\n");
        for (String imp : imports) sb.append("import ").append(imp).append(";\n");
        if (!imports.isEmpty()) sb.append('\n');
        sb.append(body);
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Config record (constructor args)
    // ------------------------------------------------------------------

    record BuilderConfig(
        String builderName,
        String builderMethodName,
        String buildMethodName,
        String fromMethodName,
        String toBuilderMethodName,
        String methodPrefix,
        AccessLevel access,
        boolean generateBuilder,
        boolean generateFrom,
        boolean generateMutate,
        boolean validate,
        boolean emitContracts,
        String factoryMethod,
        Set<String> excludeSet
    ) {}

}
