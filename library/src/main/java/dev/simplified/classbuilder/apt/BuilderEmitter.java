package dev.simplified.classbuilder.apt;

import dev.simplified.annotations.AccessLevel;

import javax.annotation.processing.Messager;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.List;

/**
 * Emits the full Java source of a generated builder class. One instance per
 * target type. Output is a single self-contained {@code .java} file; imports
 * are collected as the body is built.
 */
final class BuilderEmitter {

    enum TargetKind { CLASS_OR_RECORD, INTERFACE }

    private static final String NOT_NULL_FQN = "org.jetbrains.annotations.NotNull";
    private static final String NULLABLE_FQN = "org.jetbrains.annotations.Nullable";
    private static final String PRINT_FORMAT_FQN = "org.intellij.lang.annotations.PrintFormat";
    private static final String X_CONTRACT_FQN = "dev.simplified.annotations.XContract";
    private static final String STRINGS_FQN = "dev.simplified.classbuilder.validate.Strings";
    private static final String VALIDATOR_FQN = "dev.simplified.classbuilder.validate.BuildFlagValidator";

    private final TypeElement target;
    private final String targetSimpleName;
    private final String packageName;
    private final String builderName;
    private final BuilderConfig config;
    private final List<FieldSpec> fields;
    private final ImportRegistry imports;
    private final Messager messager;
    private final boolean isRecord;
    private final TargetKind targetKind;
    private String interfaceImplName;

    // The four below are resolved in emit() rather than here, because resolving
    // a type-parameter bound spends simple names and the impl name this file
    // writes bare only arrives after construction, through
    // setInterfaceImplName. A claim made second loses.

    /** {@code ""} or the declaration form {@code "<T extends Comparable<T>>"}. */
    private String typeParamDecl = "";
    /** {@code ""} or the reference form {@code "<T>"}. */
    private String typeArgs = "";
    /** The target with its type arguments applied - {@code "Repo"} or {@code "Repo<T>"}. */
    private String targetRef;
    /** The builder with its type arguments applied - the setter return type. */
    private String builderRef;

    private final StringBuilder body = new StringBuilder(2048);

    BuilderEmitter(TypeElement target, String packageName, BuilderConfig config, List<FieldSpec> fields, Messager messager) {
        this(target, packageName, config, fields, messager, false, TargetKind.CLASS_OR_RECORD);
    }

    BuilderEmitter(TypeElement target, String packageName, BuilderConfig config, List<FieldSpec> fields,
                   Messager messager, boolean isRecord, TargetKind targetKind) {
        this.target = target;
        this.targetSimpleName = target.getSimpleName().toString();
        this.packageName = packageName;
        this.imports = new ImportRegistry(packageName);
        this.config = config;
        this.builderName = targetSimpleName + "Builder";
        this.fields = fields;
        this.messager = messager;
        this.isRecord = isRecord;
        this.targetKind = targetKind;
        // Nothing here may spend a simple name. The impl name is written bare
        // too and only arrives afterwards, through setInterfaceImplName, so
        // every claim is made together at the top of emit().
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
        // Every name this file writes bare, claimed before anything that can
        // spend one. The target, the builder and the impl build() instantiates
        // never pass through the registry at all, and each signature below
        // carries a bare `@NotNull`. A type-parameter bound is the first thing
        // that can take a spelling, so resolving it has to come after this
        // block - otherwise a bound whose simple name is one of these four wins
        // the claim and the generated file names the wrong type on a line no
        // author can edit.
        imports.declare(targetSimpleName);
        imports.declare(builderName);
        if (interfaceImplName != null) imports.declare(interfaceImplName);
        imports.use(NOT_NULL_FQN);

        // A generic target propagates its parameters onto the sibling builder,
        // which is a separate top-level class and so cannot see the target's.
        this.typeParamDecl = buildTypeParamDecl();
        this.typeArgs = buildTypeArgs();
        this.targetRef = targetSimpleName + typeArgs;
        this.builderRef = builderName + typeArgs;

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
        // Marked on the type rather than on each member: every member of this
        // file is generated, so one annotation takes the whole class out of a
        // coverage report. The per-member form matters only where generated
        // members sit on a class the author also wrote, which is the
        // AST-mutation path, not this one. Fully qualified so no import is
        // needed and a target using javax/jakarta @Generated cannot collide.
        if (config.emitGenerated()) body.append("@dev.simplified.annotations.Generated\n");
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
            for (String fqn : f.initializerImports) imports.use(fqn);
            return f.sourceInitializer;
        }
        if (f.isOptional) return imports.use("java.util.Optional") + ".empty()";
        if (f.isListLike && !f.isSet) return "new " + imports.use("java.util.ArrayList") + "<>()";
        if (f.isSet) return "new " + imports.use("java.util.LinkedHashSet") + "<>()";
        if (f.isMap) return "new " + imports.use("java.util.LinkedHashMap") + "<>()";
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
        body.append("    ").append(accessKeyword()).append(notNull()).append(builderRef).append(' ')
            .append(f.setters.setName(f.name, f.isBoolean)).append('(').append(nullabilityPrefix(f))
            .append(typeNameOwning(f.typeDisplay, nullabilityFqn(f))).append(' ').append(f.name).append(") {\n");
        body.append("        this.").append(f.name).append(" = ").append(f.name).append(";\n");
        body.append("        return this;\n    }\n\n");
    }

    private void emitStringSetter(FieldSpec f) {
        emitPlainSetter(f);
        if (f.formattable) emitFormattableOverload(f);
    }

    private void emitFormattableOverload(FieldSpec f) {
        boolean nullable = f.nullable;
        emitContract("_, _ -> this", false, "this");
        body.append("    ").append(accessKeyword()).append(notNull()).append(builderRef).append(' ')
            .append(f.setters.setName(f.name, f.isBoolean))
            .append('(').append(printFormat()).append(nullable ? nullable() : notNull()).append(string()).append(' ').append(f.name)
            .append(", ").append(nullable()).append(object()).append("... args) {\n");
        if (nullable) {
            body.append("        this.").append(f.name).append(" = ").append(imports.use(STRINGS_FQN))
                .append(".formatNullable(").append(f.name).append(", args).orElse(null);\n");
        } else {
            body.append("        this.").append(f.name).append(" = ").append(string()).append(".format(").append(f.name).append(", args);\n");
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
        if (f.setters.emitsFlag()) {
            emitContract("-> this", false, "this");
            body.append("    ").append(accessKeyword()).append(notNull()).append(builderRef).append(' ')
                .append(f.setters.flagName(methodBase)).append("() {\n");
            body.append("        this.").append(f.name).append(" = ").append(inverse ? "false" : "true").append(";\n");
            body.append("        return this;\n    }\n\n");
        }

        emitContract("_ -> this", false, "this");
        body.append("    ").append(accessKeyword()).append(notNull()).append(builderRef).append(' ')
            .append(f.setters.setName(methodBase, true)).append("(boolean ").append(methodBase).append(") {\n");
        if (inverse) {
            body.append("        this.").append(f.name).append(" = !").append(methodBase).append(";\n");
        } else {
            body.append("        this.").append(f.name).append(" = ").append(methodBase).append(";\n");
        }
        body.append("        return this;\n    }\n\n");
    }

    private void emitOptionalSetters(FieldSpec f) {
        String optional = imports.use("java.util.Optional");
        String inner = typeName(f.optionalInner);
        String setterName = f.setters.setName(f.name, f.isBoolean);

        // (@Nullable T) wrapper - for Optional<String> with @Formattable, this is the raw-nullable variant
        emitContract("_ -> this", false, "this");
        body.append("    ").append(accessKeyword()).append(notNull()).append(builderRef).append(' ')
            .append(setterName).append('(').append(nullable()).append(inner).append(' ').append(f.name).append(") {\n");
        body.append("        return this.").append(setterName).append('(').append(optional).append(".ofNullable(").append(f.name).append("));\n");
        body.append("    }\n\n");

        // (Optional<T>) wrapped variant
        emitContract("_ -> this", false, "this");
        body.append("    ").append(accessKeyword()).append(notNull()).append(builderRef).append(' ')
            .append(setterName).append('(').append(notNull()).append(optional).append('<').append(inner).append("> ").append(f.name).append(") {\n");
        body.append("        this.").append(f.name).append(" = ").append(f.name).append(";\n");
        body.append("        return this;\n    }\n\n");

        // @Formattable + Optional<String>: add format-nullable overload that assigns formatNullable(...) directly
        if (f.formattable && f.isOptionalString) {
            emitContract("_, _ -> this", false, "this");
            body.append("    ").append(accessKeyword()).append(notNull()).append(builderRef).append(' ').append(setterName)
                .append('(').append(printFormat()).append(nullable()).append(string()).append(' ').append(f.name)
                .append(", ").append(nullable()).append(object()).append("... args) {\n");
            body.append("        this.").append(f.name).append(" = ").append(imports.use(STRINGS_FQN))
                .append(".formatNullable(").append(f.name).append(", args);\n");
            body.append("        return this;\n    }\n\n");
        }
    }

    private void emitCollectorSetters(FieldSpec f) {
        String whole = f.setters.setName(f.name, f.isBoolean);
        String clear = f.setters.clearName(f.name);

        if (f.isMap) {
            String linkedHashMap = imports.use("java.util.LinkedHashMap");
            String map = imports.use("java.util.Map");
            String k = typeName(f.mapKey);
            String v = typeName(f.mapValue);

            // whole: withFoo(Map<K,V>) - replace (always)
            emitContract("_ -> this", false, "this");
            body.append("    ").append(accessKeyword()).append(notNull()).append(builderRef).append(' ').append(whole)
                .append('(').append(notNull()).append(map).append('<').append(k).append(", ").append(v).append("> ").append(f.name).append(") {\n");
            if (f.append) {
                body.append("        this.").append(f.name).append(".putAll(").append(f.name).append(");\n");
            } else {
                body.append("        this.").append(f.name).append(" = new ").append(linkedHashMap).append("<>(").append(f.name).append(");\n");
            }
            body.append("        return this;\n    }\n\n");

            if (f.singular && f.setters.emitsPut()) {
                String putName = f.setters.putName(f.singularName);
                emitContract("_, _ -> this", false, "this");
                body.append("    ").append(accessKeyword()).append(notNull()).append(builderRef).append(' ').append(putName)
                    .append('(').append(notNull()).append(k).append(" key, ").append(v).append(" value) {\n");
                body.append("        this.").append(f.name).append(".put(key, value);\n");
                body.append("        return this;\n    }\n\n");
            }

            if (f.compute && f.setters.emitsCompute()) {
                String supplier = imports.use("java.util.function.Supplier");
                String putName = f.setters.computeName(f.singularName);
                emitContract("_, _ -> this", false, "this");
                body.append("    ").append(accessKeyword()).append(notNull()).append(builderRef).append(' ').append(putName)
                    .append('(').append(notNull()).append(k).append(" key, ").append(notNull()).append(supplier).append('<').append(v).append("> valueSupplier) {\n");
                body.append("        if (!this.").append(f.name).append(".containsKey(key)) this.").append(f.name).append(".put(key, valueSupplier.get());\n");
                body.append("        return this;\n    }\n\n");
            }

            if (f.clearable && f.setters.emitsClear()) {
                emitContract("-> this", false, "this");
                body.append("    ").append(accessKeyword()).append(notNull()).append(builderRef).append(' ').append(clear).append("() {\n");
                body.append("        this.").append(f.name).append(".clear();\n");
                body.append("        return this;\n    }\n\n");
            }
            return;
        }

        String elem = typeName(f.collectionElement);
        String container = imports.use(f.isSet ? "java.util.LinkedHashSet" : "java.util.ArrayList");

        // varargs replace (always)
        emitContract("_ -> this", false, "this");
        body.append("    ").append(accessKeyword()).append(notNull()).append(builderRef).append(' ').append(whole)
            .append('(').append(notNull()).append(elem).append("... ").append(f.name).append(") {\n");
        if (!f.append) body.append("        this.").append(f.name).append(" = new ").append(container).append("<>();\n");
        body.append("        for (").append(elem).append(" e : ").append(f.name).append(") this.").append(f.name).append(".add(e);\n");
        body.append("        return this;\n    }\n\n");

        // Iterable replace (always)
        emitContract("_ -> this", false, "this");
        body.append("    ").append(accessKeyword()).append(notNull()).append(builderRef).append(' ').append(whole)
            .append('(').append(notNull()).append(imports.use("java.lang.Iterable")).append('<').append(elem).append("> ").append(f.name).append(") {\n");
        if (!f.append) body.append("        this.").append(f.name).append(" = new ").append(container).append("<>();\n");
        body.append("        ").append(f.name).append(".forEach(this.").append(f.name).append("::add);\n");
        body.append("        return this;\n    }\n\n");

        if (f.singular && f.setters.emitsAdd()) {
            String single = f.setters.addName(f.singularName);
            emitContract("_ -> this", false, "this");
            body.append("    ").append(accessKeyword()).append(notNull()).append(builderRef).append(' ').append(single)
                .append('(').append(notNull()).append(elem).append(' ').append(f.singularName).append(") {\n");
            body.append("        this.").append(f.name).append(".add(").append(f.singularName).append(");\n");
            body.append("        return this;\n    }\n\n");
        }

        if (f.clearable && f.setters.emitsClear()) {
            emitContract("-> this", false, "this");
            body.append("    ").append(accessKeyword()).append(notNull()).append(builderRef).append(' ').append(clear).append("() {\n");
            body.append("        this.").append(f.name).append(".clear();\n");
            body.append("        return this;\n    }\n\n");
        }
    }

    private void emitArraySetter(FieldSpec f) {
        // The varargs component sits directly under the @NotNull written below,
        // so the component's own copy of it would be the second one.
        String elem = typeNameOwning(f.collectionElement, NOT_NULL_FQN);
        String setter = f.setters.setName(f.name, f.isBoolean);
        emitContract("_ -> this", false, "this");
        body.append("    ").append(accessKeyword()).append(notNull()).append(builderRef).append(' ').append(setter)
            .append('(').append(notNull()).append(elem).append("... ").append(f.name).append(") {\n");
        body.append("        this.").append(f.name).append(" = ").append(f.name).append(";\n");
        body.append("        return this;\n    }\n\n");
    }

    // ------------------------------------------------------------------
    // from(T) / build()
    // ------------------------------------------------------------------

    private void emitFromMethod() {
        if (config.fromMethodName().isEmpty()) return;

        emitContract("_ -> new", true, null);
        // Being static, from() cannot see the target's type parameters and
        // re-declares them; the caller infers them from the argument.
        body.append("    ").append(accessKeyword()).append("static ").append(typeParamDecl)
            .append(typeParamDecl.isEmpty() ? "" : " ")
            .append(notNull()).append(builderRef).append(' ').append(config.fromMethodName())
            .append('(').append(notNull()).append(targetRef).append(" instance) {\n");
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
        body.append("    ").append(accessKeyword()).append(notNull()).append(targetRef).append(' ').append(config.buildMethodName()).append("() {\n");
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
        //
        // Only when there is something to read. The Impl this emits carries
        // whatever @BuildFlag the interface's accessors declared and nothing
        // else - it extends Object - so the accessors are the whole answer, and
        // calling the validator without one puts the annotations jar on every
        // consumer's runtime classpath to check nothing.
        boolean emitValidation = config.validate() && declaresBuildFlag();
        String validator = emitValidation ? imports.use(VALIDATOR_FQN) : null;
        if (emitValidation) {
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
            body.append("        ").append(validator).append(".validate($result);\n");
            body.append("        return $result;\n");
        }
        body.append("    }\n\n");
    }

    /**
     * Whether any accessor carried a {@code @BuildFlag} onto the generated
     * {@code Impl}, and therefore whether {@code build()} has anything to
     * validate.
     */
    private boolean declaresBuildFlag() {
        // A factory may return a subtype carrying constraints of its own, and
        // the validator reads the runtime class, so that case keeps the call.
        String factory = config.factoryMethod();
        if (factory != null && !factory.isEmpty()) return true;
        for (FieldSpec f : fields) {
            if (f.buildFlag != null) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Contracts, helpers
    // ------------------------------------------------------------------

    private void emitContract(String value, boolean pure, String mutates) {
        if (!config.emitContracts()) return;
        body.append("    @").append(imports.use(X_CONTRACT_FQN)).append('(');
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
        String fqn = nullabilityFqn(f);
        if (NOT_NULL_FQN.equals(fqn)) return notNull();
        if (NULLABLE_FQN.equals(fqn)) return nullable();
        return "";
    }

    /** The nullability annotation this emitter writes for {@code f}, or null for none. */
    private static String nullabilityFqn(FieldSpec f) {
        if (f.isPrimitive) return null;
        if (f.notNull) return NOT_NULL_FQN;
        if (f.nullable) return NULLABLE_FQN;
        return null;
    }

    /**
     * Renders a declared type for a position where this emitter writes
     * {@code fqn} itself, with the type's own leading copy of that annotation
     * removed.
     *
     * <p>A nullability annotation that targets {@code TYPE_USE} - which both of
     * the ones written here do - is recorded by javac on the type as well as on
     * the declaration it was written on, so the rendered type already carries
     * what the prefix is about to write. Emitting both gives a parameter
     * annotated twice, and neither annotation is repeatable, so the generated
     * file fails to compile on a line the consumer cannot edit.
     *
     * <p>Only the leading occurrence goes. One nested in a type argument is a
     * statement about the element rather than about this parameter - nothing
     * here duplicates it, and dropping it would silently weaken the signature.
     * A foreign type-use annotation is left alone for the same reason.
     */
    private String typeNameOwning(String typeDisplay, String fqn) {
        return typeName(fqn == null ? typeDisplay : withoutLeading(typeDisplay, fqn));
    }

    /**
     * Removes the first {@code @fqn} sitting outside any type argument list.
     *
     * @param typeDisplay the type as {@code javax.lang.model} renders it
     * @param fqn the annotation type to drop
     * @return the type with that one annotation removed, unchanged when absent
     */
    private static String withoutLeading(String typeDisplay, String fqn) {
        String token = "@" + fqn;
        int depth = 0;
        for (int i = 0; i < typeDisplay.length(); i++) {
            char c = typeDisplay.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == '@' && depth == 0 && typeDisplay.startsWith(token, i)) {
                int end = i + token.length();
                // Only a whole token: @Foo must not match a prefix of @FooBar.
                if (end < typeDisplay.length() && Character.isJavaIdentifierPart(typeDisplay.charAt(end))) continue;
                while (end < typeDisplay.length() && typeDisplay.charAt(end) == ' ') end++;
                return typeDisplay.substring(0, i) + typeDisplay.substring(end);
            }
        }
        return typeDisplay;
    }

    // Every fixed name this file writes goes through the registry, including
    // the ones that need no import: a target field typed acme.String otherwise
    // imports itself, and the java.lang.String this emitter meant is then
    // silently that class everywhere below.

    /** The {@code @NotNull} prefix, spelled as this file's import scope allows. */
    private String notNull() {
        return "@" + imports.use(NOT_NULL_FQN) + " ";
    }

    /** The {@code @Nullable} prefix, spelled as this file's import scope allows. */
    private String nullable() {
        return "@" + imports.use(NULLABLE_FQN) + " ";
    }

    /** The {@code @PrintFormat} prefix, spelled as this file's import scope allows. */
    private String printFormat() {
        return "@" + imports.use(PRINT_FORMAT_FQN) + " ";
    }

    /** {@code String}, spelled as this file's import scope allows. */
    private String string() {
        return imports.use("java.lang.String");
    }

    /** {@code Object}, spelled as this file's import scope allows. */
    private String object() {
        return imports.use("java.lang.Object");
    }

    /** Converts a full type display (e.g. "java.util.List<java.lang.String>") to a simpler form, adding imports. */
    private String typeName(String fullDisplay) {
        return simplifyType(fullDisplay);
    }

    /**
     * Rewrites a fully qualified type rendering into the spelling this file's
     * import scope allows, registering an import for every type name it
     * shortens.
     *
     * <p><b>A type-use annotation is rendered inside the qualified name</b> -
     * {@code java.util.@NotNull List<java.lang.String>} - so an identifier run
     * ends at the {@code '@'} holding {@code "java.util."}, which is a package
     * qualifier rather than a type. Left as a token it produces
     * {@code import java.util.;} and the generated file does not parse. The
     * qualifier is held back and rejoined onto the type name that follows the
     * annotation, which is the one spelling that both imports the right type
     * and leaves the annotation where javac put it.
     *
     * @param typeStr the type as {@code TypeMirror.toString} renders it
     * @return the rewritten type reference
     */
    private String simplifyType(String typeStr) {
        StringBuilder out = new StringBuilder(typeStr.length());
        String qualifier = "";
        int i = 0;
        int n = typeStr.length();
        while (i < n) {
            if (typeStr.charAt(i) == '@') {
                out.append('@');
                i++;
                int nameStart = i;
                while (i < n && (Character.isJavaIdentifierPart(typeStr.charAt(i)) || typeStr.charAt(i) == '.')) i++;
                String name = typeStr.substring(nameStart, i);
                out.append(name.indexOf('.') >= 0 ? imports.use(name) : name);
                continue;
            }
            int tokenStart = i;
            while (i < n && (Character.isJavaIdentifierPart(typeStr.charAt(i)) || typeStr.charAt(i) == '.')) i++;
            String token = typeStr.substring(tokenStart, i);
            // The split qualifier, recognised by the '@' that ended it. Held
            // rather than emitted; the loop re-enters on the annotation.
            if (token.endsWith(".") && i < n && typeStr.charAt(i) == '@') {
                qualifier = token;
                continue;
            }
            if (!token.isEmpty()) {
                token = qualifier + token;
                qualifier = "";
                if (token.indexOf('.') >= 0 && Character.isJavaIdentifierStart(token.charAt(0))) {
                    out.append(imports.use(token));
                } else {
                    out.append(token);
                }
            }
            if (i < n) {
                out.append(typeStr.charAt(i));
                i++;
            }
        }
        return out.append(qualifier).toString();
    }

    // ------------------------------------------------------------------
    // Final assembly
    // ------------------------------------------------------------------

    private String assemble() {
        StringBuilder sb = new StringBuilder(body.length() + 1024);
        sb.append("// Generated by @ClassBuilder - do not edit\n");
        if (!packageName.isEmpty()) sb.append("package ").append(packageName).append(";\n\n");
        sb.append(imports.block());
        sb.append(body);
        return sb.toString();
    }

}
