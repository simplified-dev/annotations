package dev.simplified.classbuilder.apt;

import dev.simplified.annotations.AccessLevel;

import javax.annotation.processing.Messager;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.type.TypeMirror;
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

    enum TargetKind { CLASS_OR_RECORD, INTERFACE }

    private final TypeElement target;
    private final String targetSimpleName;
    private final String packageName;
    private final String builderName;
    private final BuilderConfig config;
    private final List<FieldSpec> fields;
    private final Set<String> imports = new TreeSet<>();
    private final Messager messager;
    private final boolean isRecord;
    private final TargetKind targetKind;
    private String interfaceImplName;

    /** {@code ""} or the declaration form {@code "<T extends Comparable<T>>"}. */
    private final String typeParamDecl;
    /** {@code ""} or the reference form {@code "<T>"}. */
    private final String typeArgs;
    /** The target with its type arguments applied - {@code "Repo"} or {@code "Repo<T>"}. */
    private final String targetRef;
    /** The builder with its type arguments applied - the setter return type. */
    private final String builderRef;

    private final StringBuilder body = new StringBuilder(2048);

    BuilderEmitter(TypeElement target, String packageName, BuilderConfig config, List<FieldSpec> fields, Messager messager) {
        this(target, packageName, config, fields, messager, false, TargetKind.CLASS_OR_RECORD);
    }

    BuilderEmitter(TypeElement target, String packageName, BuilderConfig config, List<FieldSpec> fields,
                   Messager messager, boolean isRecord, TargetKind targetKind) {
        this.target = target;
        this.targetSimpleName = target.getSimpleName().toString();
        this.packageName = packageName;
        this.config = config;
        this.builderName = targetSimpleName + "Builder";
        this.fields = fields;
        this.messager = messager;
        this.isRecord = isRecord;
        this.targetKind = targetKind;
        // A generic target propagates its parameters onto the sibling builder,
        // which is a separate top-level class and so cannot see the target's.
        this.typeParamDecl = buildTypeParamDecl();
        this.typeArgs = buildTypeArgs();
        this.targetRef = targetSimpleName + typeArgs;
        this.builderRef = builderName + typeArgs;
    }

    /**
     * The target's type parameters in declaration form, bounds included -
     * {@code "<K, V extends Comparable<V>>"}. Empty when the target is not
     * generic. {@code java.lang.Object} bounds are dropped, being what an
     * unbounded parameter means anyway.
     */
    private String buildTypeParamDecl() {
        if (target.getTypeParameters().isEmpty()) return "";
        StringBuilder sb = new StringBuilder("<");
        boolean firstParam = true;
        for (TypeParameterElement tp : target.getTypeParameters()) {
            if (!firstParam) sb.append(", ");
            firstParam = false;
            sb.append(tp.getSimpleName());
            boolean firstBound = true;
            for (TypeMirror bound : tp.getBounds()) {
                if ("java.lang.Object".equals(bound.toString())) continue;
                sb.append(firstBound ? " extends " : " & ").append(simplifyType(bound.toString()));
                firstBound = false;
            }
        }
        return sb.append('>').toString();
    }

    /** The target's type parameters in reference form - {@code "<K, V>"}. */
    private String buildTypeArgs() {
        if (target.getTypeParameters().isEmpty()) return "";
        StringBuilder sb = new StringBuilder("<");
        boolean first = true;
        for (TypeParameterElement tp : target.getTypeParameters()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(tp.getSimpleName());
        }
        return sb.append('>').toString();
    }

    /** {@code "<>"} on a generic target, for diamond instantiation. */
    private String diamond() {
        return typeArgs.isEmpty() ? "" : "<>";
    }

    void setInterfaceImplName(String implName) {
        this.interfaceImplName = implName;
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
        body.append(accessKeyword()).append("class ").append(builderName).append(typeParamDecl).append(" {\n\n");
    }

    private void emitClassFooter() {
        body.append("}\n");
    }

    private void emitFields() {
        for (FieldSpec f : fields) {
            // No @BuilderDefault check here: this emitter only ever runs for
            // INTERFACE targets, whose FieldSpecs come from
            // fromInterfaceAccessor and never carry a builder default. The
            // equivalent diagnostic for classes and records lives on the
            // AST-mutation path, in RetainedInitFactory.
            body.append("    private ");
            body.append(typeName(f.typeDisplay));
            body.append(' ').append(f.name);
            String initializer = defaultInitializer(f);
            if (initializer != null) body.append(" = ").append(initializer);
            body.append(";\n");
        }
        body.append('\n');
    }

    private String defaultInitializer(FieldSpec f) {
        if (f.builderDefault && f.sourceInitializer != null) {
            // Import the type references harvested from the source initializer so its simple
            // names resolve in the generated builder. The expression text itself is emitted
            // verbatim - it is general expression syntax, not the FQN-typed form that
            // simplifyType() is built for.
            for (String fqn : f.initializerImports) considerImport(fqn);
            return f.sourceInitializer;
        }
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
        // An array is a container like the rest, so an unset slot is empty
        // rather than null - matching the AST-mutation path. The zero-length
        // dimension has to come first, so a multi-dimensional component's own
        // brackets are moved after it: String[] yields new String[0][].
        if (f.isArray) {
            String component = typeName(f.collectionElement);
            int nested = 0;
            while (component.endsWith("[]")) {
                component = component.substring(0, component.length() - 2);
                nested++;
            }
            return "new " + component + "[0]" + "[]".repeat(nested);
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
        if ((f.isListLike || f.isMap) && f.collector) {
            emitCollectorSetters(f);
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
        body.append("    ").append(accessKeyword()).append("@NotNull ").append(builderRef).append(' ')
            .append(config.naming().setName(f.name)).append('(').append(nullabilityPrefix(f)).append(typeName(f.typeDisplay)).append(' ').append(f.name).append(") {\n");
        body.append("        this.").append(f.name).append(" = ").append(f.name).append(";\n");
        body.append("        return this;\n    }\n\n");
    }

    private void emitStringSetter(FieldSpec f) {
        emitPlainSetter(f);
        if (f.formattable) emitFormattableOverload(f);
    }

    private void emitFormattableOverload(FieldSpec f) {
        imports.add("org.intellij.lang.annotations.PrintFormat");
        boolean nullable = f.nullable;
        emitContract("_, _ -> this", false, "this");
        body.append("    ").append(accessKeyword()).append("@NotNull ").append(builderRef).append(' ')
            .append(config.naming().setName(f.name))
            .append("(@PrintFormat ").append(nullable ? "@Nullable " : "@NotNull ").append("String ").append(f.name)
            .append(", @Nullable Object... args) {\n");
        if (nullable) {
            imports.add("dev.simplified.classbuilder.validate.Strings");
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
        // The typed setter is the ordinary `set` role, so a boolean is named
        // like every other field; the zero-arg form is the separate `flag` role
        // and drops out entirely when a style suppresses it.
        if (config.naming().emitsFlag()) {
            emitContract("-> this", false, "this");
            body.append("    ").append(accessKeyword()).append("@NotNull ").append(builderRef).append(' ')
                .append(config.naming().flagName(methodBase)).append("() {\n");
            body.append("        this.").append(f.name).append(" = ").append(inverse ? "false" : "true").append(";\n");
            body.append("        return this;\n    }\n\n");
        }

        emitContract("_ -> this", false, "this");
        body.append("    ").append(accessKeyword()).append("@NotNull ").append(builderRef).append(' ')
            .append(config.naming().setName(methodBase)).append("(boolean ").append(methodBase).append(") {\n");
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
        String setterName = config.naming().setName(f.name);

        // (@Nullable T) wrapper - for Optional<String> with @Formattable, this is the raw-nullable variant
        emitContract("_ -> this", false, "this");
        body.append("    ").append(accessKeyword()).append("@NotNull ").append(builderRef).append(' ')
            .append(setterName).append("(@Nullable ").append(inner).append(' ').append(f.name).append(") {\n");
        body.append("        return this.").append(setterName).append("(Optional.ofNullable(").append(f.name).append("));\n");
        body.append("    }\n\n");

        // (Optional<T>) wrapped variant
        emitContract("_ -> this", false, "this");
        body.append("    ").append(accessKeyword()).append("@NotNull ").append(builderRef).append(' ')
            .append(setterName).append("(@NotNull Optional<").append(inner).append("> ").append(f.name).append(") {\n");
        body.append("        this.").append(f.name).append(" = ").append(f.name).append(";\n");
        body.append("        return this;\n    }\n\n");

        // @Formattable + Optional<String>: add format-nullable overload that assigns formatNullable(...) directly
        if (f.formattable && "java.lang.String".equals(f.optionalInner)) {
            imports.add("org.intellij.lang.annotations.PrintFormat");
            imports.add("dev.simplified.classbuilder.validate.Strings");
            emitContract("_, _ -> this", false, "this");
            body.append("    ").append(accessKeyword()).append("@NotNull ").append(builderRef).append(' ').append(setterName)
                .append("(@PrintFormat @Nullable String ").append(f.name).append(", @Nullable Object... args) {\n");
            body.append("        this.").append(f.name).append(" = Strings.formatNullable(").append(f.name).append(", args);\n");
            body.append("        return this;\n    }\n\n");
        }
    }

    private void emitCollectorSetters(FieldSpec f) {
        String whole = config.naming().setName(f.name);
        String clear = config.naming().clearName(f.name);

        if (f.isMap) {
            imports.add("java.util.LinkedHashMap");
            imports.add("java.util.Map");
            String k = typeName(f.mapKey);
            String v = typeName(f.mapValue);

            // whole: withFoo(Map<K,V>) - replace (always)
            emitContract("_ -> this", false, "this");
            body.append("    ").append(accessKeyword()).append("@NotNull ").append(builderRef).append(' ').append(whole)
                .append("(@NotNull Map<").append(k).append(", ").append(v).append("> ").append(f.name).append(") {\n");
            body.append("        this.").append(f.name).append(" = new LinkedHashMap<>(").append(f.name).append(");\n");
            body.append("        return this;\n    }\n\n");

            if (f.singular && config.naming().emitsPut()) {
                String putName = config.naming().putName(f.singularName);
                emitContract("_, _ -> this", false, "this");
                body.append("    ").append(accessKeyword()).append("@NotNull ").append(builderRef).append(' ').append(putName)
                    .append("(@NotNull ").append(k).append(" key, ").append(v).append(" value) {\n");
                body.append("        this.").append(f.name).append(".put(key, value);\n");
                body.append("        return this;\n    }\n\n");
            }

            if (f.compute && config.naming().emitsCompute()) {
                imports.add("java.util.function.Supplier");
                String putName = config.naming().computeName(f.singularName);
                emitContract("_, _ -> this", false, "this");
                body.append("    ").append(accessKeyword()).append("@NotNull ").append(builderRef).append(' ').append(putName)
                    .append("(@NotNull ").append(k).append(" key, @NotNull Supplier<").append(v).append("> valueSupplier) {\n");
                body.append("        if (!this.").append(f.name).append(".containsKey(key)) this.").append(f.name).append(".put(key, valueSupplier.get());\n");
                body.append("        return this;\n    }\n\n");
            }

            if (f.clearable && config.naming().emitsClear()) {
                emitContract("-> this", false, "this");
                body.append("    ").append(accessKeyword()).append("@NotNull ").append(builderRef).append(' ').append(clear).append("() {\n");
                body.append("        this.").append(f.name).append(".clear();\n");
                body.append("        return this;\n    }\n\n");
            }
            return;
        }

        String elem = typeName(f.collectionElement);
        String container = f.isSet ? "LinkedHashSet" : "ArrayList";
        imports.add(f.isSet ? "java.util.LinkedHashSet" : "java.util.ArrayList");

        // varargs replace (always)
        emitContract("_ -> this", false, "this");
        body.append("    ").append(accessKeyword()).append("@NotNull ").append(builderRef).append(' ').append(whole)
            .append("(@NotNull ").append(elem).append("... ").append(f.name).append(") {\n");
        body.append("        this.").append(f.name).append(" = new ").append(container).append("<>();\n");
        body.append("        for (").append(elem).append(" e : ").append(f.name).append(") this.").append(f.name).append(".add(e);\n");
        body.append("        return this;\n    }\n\n");

        // Iterable replace (always)
        emitContract("_ -> this", false, "this");
        body.append("    ").append(accessKeyword()).append("@NotNull ").append(builderRef).append(' ').append(whole)
            .append("(@NotNull Iterable<").append(elem).append("> ").append(f.name).append(") {\n");
        body.append("        this.").append(f.name).append(" = new ").append(container).append("<>();\n");
        body.append("        ").append(f.name).append(".forEach(this.").append(f.name).append("::add);\n");
        body.append("        return this;\n    }\n\n");

        if (f.singular && config.naming().emitsAdd()) {
            String single = config.naming().addName(f.singularName);
            emitContract("_ -> this", false, "this");
            body.append("    ").append(accessKeyword()).append("@NotNull ").append(builderRef).append(' ').append(single)
                .append("(@NotNull ").append(elem).append(' ').append(f.singularName).append(") {\n");
            body.append("        this.").append(f.name).append(".add(").append(f.singularName).append(");\n");
            body.append("        return this;\n    }\n\n");
        }

        if (f.clearable && config.naming().emitsClear()) {
            emitContract("-> this", false, "this");
            body.append("    ").append(accessKeyword()).append("@NotNull ").append(builderRef).append(' ').append(clear).append("() {\n");
            body.append("        this.").append(f.name).append(".clear();\n");
            body.append("        return this;\n    }\n\n");
        }
    }

    private void emitArraySetter(FieldSpec f) {
        String elem = typeName(f.collectionElement);
        String setter = config.naming().setName(f.name);
        emitContract("_ -> this", false, "this");
        body.append("    ").append(accessKeyword()).append("@NotNull ").append(builderRef).append(' ').append(setter)
            .append("(@NotNull ").append(elem).append("... ").append(f.name).append(") {\n");
        body.append("        this.").append(f.name).append(" = ").append(f.name).append(";\n");
        body.append("        return this;\n    }\n\n");
    }

    // ------------------------------------------------------------------
    // from(T) / build()
    // ------------------------------------------------------------------

    private void emitFromMethod() {
        if (!config.generateFrom()) return;
        if (config.fromMethodName().isEmpty()) return;

        emitContract("_ -> new", true, null);
        // Being static, from() cannot see the target's type parameters and
        // re-declares them; the caller infers them from the argument.
        body.append("    ").append(accessKeyword()).append("static ").append(typeParamDecl)
            .append(typeParamDecl.isEmpty() ? "" : " ")
            .append("@NotNull ").append(builderRef).append(' ').append(config.fromMethodName())
            .append("(@NotNull ").append(targetRef).append(" instance) {\n");
        body.append("        ").append(builderRef).append(" b = new ").append(builderName).append(diamond()).append("();\n");
        for (FieldSpec f : fields) body.append("        b.").append(f.name).append(" = ").append(readFromInstance(f)).append(";\n");
        body.append("        return b;\n    }\n\n");
    }

    private String readFromInstance(FieldSpec f) {
        String getter;
        if (f.obtainViaStatic && f.obtainViaMethod != null) {
            // Static helper: TargetType.method(instance)
            getter = targetSimpleName + "." + f.obtainViaMethod + "(instance)";
        } else if (f.obtainViaMethod != null) {
            getter = "instance." + f.obtainViaMethod + "()";
        } else if (f.obtainViaField != null) {
            getter = "instance." + f.obtainViaField;
        } else if (isRecord || targetKind == TargetKind.INTERFACE) {
            // Records expose component accessors as `name()`; interfaces use the abstract method name directly.
            getter = "instance." + f.name + "()";
        } else if (f.isBoolean) {
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
        body.append("    ").append(accessKeyword()).append("@NotNull ").append(targetRef).append(' ').append(config.buildMethodName()).append("() {\n");
        boolean useFactory = !config.factoryMethod().isEmpty();
        String constructorTarget;
        if (targetKind == TargetKind.INTERFACE && useFactory) {
            // generateImpl=false path (and any interface target that routes
            // construction through a static factory). factoryMethod must
            // return the interface type; the processor can't statically
            // verify this, so it's documented in ClassBuilder's Javadoc.
            constructorTarget = targetSimpleName + "." + config.factoryMethod();
        } else if (targetKind == TargetKind.INTERFACE) {
            // Diamond, not the explicit arguments: the impl's parameters mirror
            // the interface's, so the assignment or return target infers them.
            constructorTarget = "new "
                + (interfaceImplName == null ? targetSimpleName + "Impl" : interfaceImplName)
                + diamond();
        } else if (useFactory) {
            constructorTarget = targetSimpleName + "." + config.factoryMethod();
        } else {
            constructorTarget = "new " + targetSimpleName + diamond();
        }
        // Validator reads @BuildFlag annotations off the constructed target,
        // not the Builder (whose fields are synthesised without annotations),
        // so we capture the new instance first, validate, then return.
        boolean emitValidation = config.validate();
        if (emitValidation) {
            imports.add("dev.simplified.classbuilder.validate.BuildFlagValidator");
            body.append("        final ").append(targetRef).append(" $result = ").append(constructorTarget).append('(');
        } else {
            body.append("        return ").append(constructorTarget).append('(');
        }
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) body.append(", ");
            body.append(fields.get(i).name);
        }
        body.append(");\n");
        if (emitValidation) {
            body.append("        BuildFlagValidator.validate($result);\n");
            body.append("        return $result;\n");
        }
        body.append("    }\n\n");
    }

    // ------------------------------------------------------------------
    // Contracts, helpers
    // ------------------------------------------------------------------

    private void emitContract(String value, boolean pure, String mutates) {
        if (!config.emitContracts()) return;
        imports.add("dev.simplified.annotations.XContract");
        body.append("    @XContract(");
        boolean first = true;
        if (value != null) { body.append("value = \"").append(value).append('"'); first = false; }
        if (pure) { if (!first) body.append(", "); body.append("pure = true"); first = false; }
        if (mutates != null) { if (!first) body.append(", "); body.append("mutates = \"").append(mutates).append('"'); }
        body.append(")\n");
    }

    private String accessKeyword() {
        String k = config.access().toKeyword();
        return k.isEmpty() ? "" : k + " ";
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

}
