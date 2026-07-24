package dev.simplified.classbuilder.showcase;

import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.BuildFlag;
import dev.simplified.annotations.BuilderIgnore;
import dev.simplified.annotations.BuilderNames;
import dev.simplified.annotations.ClassBuilder;
import dev.simplified.annotations.Collector;
import dev.simplified.annotations.EqualsAndHashCode;
import dev.simplified.annotations.Formattable;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.annotations.Negate;
import dev.simplified.annotations.ObtainVia;
import dev.simplified.annotations.SetterNames;
import dev.simplified.annotations.ToString;
import dev.simplified.classbuilder.validate.BuilderValidationException;
import lombok.Getter;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runtime showcase exercising every configuration of the {@code @ClassBuilder}
 * + companion annotations against the real APT-generated builders. Each nested
 * type is its own @ClassBuilder target; {@link #main} instantiates each, runs
 * the intended builder pattern, and records SUCCESS/FAILURE through
 * {@link ShowcaseReport}.
 *
 * <p>Each @ClassBuilder-annotated class carries an explicit all-args
 * constructor matching the non-ignored, non-excluded fields in declaration
 * order - the generated {@code build()} invokes that constructor
 * positionally. Fields carrying {@code @BuilderIgnore} or listed
 * in {@code @ClassBuilder(exclude = ...)} are skipped in the constructor
 * signature; their initializers supply the field value.
 *
 * <p>The whole-object annotations - {@link EqualsAndHashCode},
 * {@link ToString} - carry no builder, so their nested types declare only the
 * constructor their cases build through.
 *
 * <p>Case IDs are stable and mirrored in the integration test's expected
 * set. Add new cases by (a) introducing a new nested annotated class with a
 * matching constructor, (b) appending a {@code report.expect(...)} block in
 * main(), and (c) adding the id to the test's EXPECTED_IDS.
 */
public final class ClassBuilderShowcase {

    private ClassBuilderShowcase() {}

    // ==================================================================
    // @ClassBuilder base cases
    // ==================================================================

    @ClassBuilder
    public static final class Plain {
        private final String name;
        private final int age;
        public Plain(String name, int age) { this.name = name; this.age = age; }
        public String getName() { return name; }
        public int getAge() { return age; }
        @Override public String toString() { return "Plain[name=" + name + ", age=" + age + "]"; }
    }

    @ClassBuilder(
        builder = @BuilderNames(builder = "newBuilder", build = "construct"),
        setters = @SetterNames(set = "set{}")
    )
    public static final class Renamed {
        private final String value;
        public Renamed(String value) { this.value = value; }
        public String getValue() { return value; }
        @Override public String toString() { return "Renamed[value=" + value + "]"; }
    }

    @ClassBuilder(exclude = {"secret"})
    public static final class Excluded {
        private final String visible;
        private final String secret;
        public Excluded(String visible) { this.visible = visible; this.secret = "default-secret"; }
        public String getVisible() { return visible; }
        public String getSecret() { return secret; }
    }

    @ClassBuilder(access = AccessLevel.PACKAGE)
    public static final class PackageAccess {
        private final String value;
        public PackageAccess(String value) { this.value = value; }
        public String getValue() { return value; }
    }

    @ClassBuilder(validate = false)
    public static final class ValidationDisabled {
        @BuildFlag(nonNull = true) private final String required;
        public ValidationDisabled(String required) { this.required = required; }
        public String getRequired() { return required; }
        @Override public String toString() { return "ValidationDisabled[required=" + required + "]"; }
    }

    // Suppressing from(T) leaves mutate() standing: mutate() seeds a fresh
    // Builder inline off `this` rather than delegating to from(this), so the
    // instance-seed method no longer dangles when the factory is off.
    @ClassBuilder(builder = @BuilderNames(from = BuilderNames.NONE))
    public static final class NoFrom {
        private final String label;
        private final int size;
        public NoFrom(String label, int size) { this.label = label; this.size = size; }
        public String getLabel() { return label; }
        public int getSize() { return size; }
        @Override public String toString() { return "NoFrom[label=" + label + ", size=" + size + "]"; }
    }

    // ==================================================================
    // retained initializers / @BuilderIgnore
    // ==================================================================

    @ClassBuilder
    public static final class RetainedInit {
        private String greeting = "hello-from-init";
        public RetainedInit(String greeting) { this.greeting = greeting; }
        public String getGreeting() { return greeting; }
        @Override public String toString() { return "RetainedInit[greeting=" + greeting + "]"; }
    }

    @ClassBuilder
    public static final class RetainedInitNumeric {
        private int threshold = 42;
        public RetainedInitNumeric(int threshold) { this.threshold = threshold; }
        public int getThreshold() { return threshold; }
    }

    @ClassBuilder
    public static final class RetainedInitObject {
        private Object blob = new Object();
        public RetainedInitObject(Object blob) { this.blob = blob; }
        public Object getBlob() { return blob; }
    }

    @ClassBuilder
    public static final class RetainedInitFresh {
        // The headline retainInit use case: an expression that must evaluate
        // FRESH on every build() - UUID.randomUUID() re-runs for each builder.
        private java.util.UUID id = java.util.UUID.randomUUID();
        public RetainedInitFresh(java.util.UUID id) { this.id = id; }
        public java.util.UUID getId() { return id; }
    }

    @ClassBuilder
    public static final class RetainedInitCollection {
        private java.util.ArrayList<String> tags = new java.util.ArrayList<>();
        public RetainedInitCollection(java.util.ArrayList<String> tags) { this.tags = tags; }
        public java.util.List<String> getTags() { return tags; }
    }

    @ClassBuilder
    public static final class RetainedInitFactoryCall {
        private java.util.List<String> roles = java.util.List.of("guest", "user");
        public RetainedInitFactoryCall(java.util.List<String> roles) { this.roles = roles; }
        public java.util.List<String> getRoles() { return roles; }
    }

    // BUG-2 / F3: `final` retainInit fields. The blank-final lift strips the
    // field initializer (preserved by $default$) so the builder-called
    // constructor can assign it, while the field stays `final` in source and
    // bytecode. Covers a literal (no forced attribution) and an identifier
    // initializer (List.of - the case that forced attribution and broke).
    @ClassBuilder
    public static final class RetainedInitFinal {
        private final int tileSize = 128;
        private final List<String> layers = List.of("base");
        public RetainedInitFinal(int tileSize, List<String> layers) {
            this.tileSize = tileSize;
            this.layers = layers;
        }
        public int getTileSize() { return tileSize; }
        public List<String> getLayers() { return layers; }
    }

    // The same `final` retainInit shape with Lombok @Getter co-resident - the
    // multi-round processing configuration that surfaced BUG-2 in asset-renderer.
    @Getter
    @ClassBuilder
    public static final class RetainedInitFinalLombok {
        private final int tileSize = 128;
        private final List<String> layers = List.of("base");
        public RetainedInitFinalLombok(int tileSize, List<String> layers) {
            this.tileSize = tileSize;
            this.layers = layers;
        }
    }

    @ClassBuilder
    public static final class IgnoredField {
        private final String visible;
        @BuilderIgnore private final String hidden;
        public IgnoredField(String visible) { this.visible = visible; this.hidden = "hidden-default"; }
        public String getVisible() { return visible; }
        public String getHidden() { return hidden; }
    }

    // ==================================================================
    // @BuildFlag - nonNull / notEmpty / pattern / limit / group
    // ==================================================================

    @ClassBuilder
    public static final class NullRequired {
        @BuildFlag(nonNull = true) private final String name;
        public NullRequired(String name) { this.name = name; }
        public String getName() { return name; }
    }

    @ClassBuilder
    public static final class EmptyStringRequired {
        @BuildFlag(notEmpty = true) private final String s;
        public EmptyStringRequired(String s) { this.s = s; }
        public String getS() { return s; }
    }

    @ClassBuilder
    public static final class EmptyOptionalRequired {
        @BuildFlag(notEmpty = true) private final Optional<String> opt;
        public EmptyOptionalRequired(Optional<String> opt) { this.opt = opt; }
        public Optional<String> getOpt() { return opt; }
    }

    @ClassBuilder
    public static final class EmptyCollectionRequired {
        @BuildFlag(notEmpty = true) private final List<String> items;
        public EmptyCollectionRequired(List<String> items) { this.items = items; }
        public List<String> getItems() { return items; }
    }

    @ClassBuilder
    public static final class EmptyMapRequired {
        @BuildFlag(notEmpty = true) private final Map<String, String> entries;
        public EmptyMapRequired(Map<String, String> entries) { this.entries = entries; }
        public Map<String, String> getEntries() { return entries; }
    }

    @ClassBuilder
    public static final class EmptyArrayRequired {
        @BuildFlag(notEmpty = true) private final Object[] arr;
        public EmptyArrayRequired(Object[] arr) { this.arr = arr; }
        public Object[] getArr() { return arr; }
    }

    @ClassBuilder
    public static final class PatternConstrained {
        @BuildFlag(pattern = "[a-z]+") private final String ident;
        public PatternConstrained(String ident) { this.ident = ident; }
        public String getIdent() { return ident; }
    }

    @ClassBuilder
    public static final class LimitedString {
        @BuildFlag(limit = 5) private final String text;
        public LimitedString(String text) { this.text = text; }
        public String getText() { return text; }
    }

    @ClassBuilder
    public static final class LimitedCollection {
        @BuildFlag(limit = 2) private final List<String> tags;
        public LimitedCollection(List<String> tags) { this.tags = tags; }
        public List<String> getTags() { return tags; }
    }

    @ClassBuilder
    public static final class LimitedOptionalNumber {
        @BuildFlag(limit = 100) private final Optional<Integer> amount;
        public LimitedOptionalNumber(Optional<Integer> amount) { this.amount = amount; }
        public Optional<Integer> getAmount() { return amount; }
    }

    @ClassBuilder
    public static final class FaceGroup {
        @BuildFlag(nonNull = true, group = "face") private final String label;
        @BuildFlag(nonNull = true, group = "face") private final String emoji;
        public FaceGroup(String label, String emoji) { this.label = label; this.emoji = emoji; }
        public String getLabel() { return label; }
        public String getEmoji() { return emoji; }
    }

    // ==================================================================
    // @ObtainVia - from(T) accessor redirection
    // ==================================================================

    @ClassBuilder
    public static final class ViaMethod {
        @ObtainVia(method = "customAccessor") private final String custom;
        public ViaMethod(String custom) { this.custom = custom; }
        public String customAccessor() { return "method-derived-" + custom; }
        public String getCustom() { return custom; }
        @Override public String toString() { return "ViaMethod[custom=" + custom + "]"; }
    }

    @ClassBuilder
    public static final class ViaField {
        @ObtainVia(field = "realValue") private final String alias;
        // Regular field that @ObtainVia redirects to during from(T); marked
        // ignored so the APT keeps it out of the Builder/constructor surface.
        @BuilderIgnore public String realValue = "from-real-field";
        public ViaField(String alias) { this.alias = alias; }
        public String getAlias() { return alias; }
        @Override public String toString() { return "ViaField[alias=" + alias + "]"; }
    }

    @ClassBuilder
    public static final class ViaStatic {
        @ObtainVia(method = "extract", isStatic = true) private final String value;
        public ViaStatic(String value) { this.value = value; }
        public static String extract(ViaStatic target) { return "static-helper-result"; }
        public String getValue() { return value; }
        @Override public String toString() { return "ViaStatic[value=" + value + "]"; }
    }

    // ==================================================================
    // @Collector
    // ==================================================================

    @ClassBuilder
    public static final class CollectorList {
        @Collector(singular = true, clearable = true) private final List<String> items;
        public CollectorList(List<String> items) { this.items = items; }
        public List<String> getItems() { return items; }
        @Override public String toString() { return "CollectorList[items=" + items + "]"; }
    }

    @ClassBuilder
    public static final class CollectorMap {
        @Collector(singular = true, singularMethodName = "entry", compute = true)
        private final Map<String, Integer> counts;
        public CollectorMap(Map<String, Integer> counts) { this.counts = counts; }
        public Map<String, Integer> getCounts() { return counts; }
        @Override public String toString() { return "CollectorMap[counts=" + counts + "]"; }
    }

    // ==================================================================
    // @Negate
    // ==================================================================

    @ClassBuilder
    public static final class Negated {
        @Negate("disabled") private final boolean enabled;
        public Negated(boolean enabled) { this.enabled = enabled; }
        public boolean isEnabled() { return enabled; }
        @Override public String toString() { return "Negated[enabled=" + enabled + "]"; }
    }

    // ==================================================================
    // @Formattable
    // ==================================================================

    // ==================================================================
    // Naming: style + per-role patterns
    // ==================================================================

    /**
     * Default naming. The typed boolean setter is the ordinary {@code set}
     * role, so it takes the bare field name; the zero-arg convenience is the
     * separate {@code flag} role and keeps the {@code is} prefix.
     */
    @ClassBuilder
    public static final class Sprite {
        private final boolean animated;
        public Sprite(boolean animated) { this.animated = animated; }
        public boolean isAnimated() { return animated; }
    }

    /** Lombok's exact surface: {@code CardBuilder}, {@code toBuilder()}, bare-name everything. */
    @ClassBuilder(style = NamingStyle.LOMBOK)
    public static final class Card {
        private final boolean shiny;
        @Collector(singular = true, clearable = true) private final List<String> tags;
        public Card(boolean shiny, List<String> tags) { this.shiny = shiny; this.tags = tags; }
        public boolean isShiny() { return shiny; }
        public List<String> getTags() { return tags; }
    }

    /** Per-role overrides on the collector's add and clear. */
    @ClassBuilder(setters = @SetterNames(add = "append{}", clear = "reset{}"))
    public static final class Basket {
        @Collector(singular = true, clearable = true) private final List<String> items;
        public Basket(List<String> items) { this.items = items; }
        public List<String> getItems() { return items; }
    }

    @ClassBuilder
    public static final class FormattedString {
        @Formattable private final String message;
        public FormattedString(String message) { this.message = message; }
        public String getMessage() { return message; }
        @Override public String toString() { return "FormattedString[message=" + message + "]"; }
    }

    // ==================================================================
    // @EqualsAndHashCode / @ToString
    //
    // The two array-carrying shapes are the ones a divergence bites on: an
    // array member compared by reference gives a type that is unequal to its
    // own copy, and a hash that disagrees with whatever equals decided.
    // ==================================================================

    /** The ordinary accumulator path - no array, no partitioned primitive. */
    @EqualsAndHashCode
    public static final class Measurement {
        private final String name;
        private final int size;
        public Measurement(String name, int size) { this.name = name; this.size = size; }
    }

    /**
     * A primitive array and a reference array, which take the flat and the deep
     * comparison respectively. {@code style = LOMBOK} so the rendering can be
     * held against {@link LombokPalette}'s field for field.
     */
    @EqualsAndHashCode
    @ToString(style = ToString.Style.LOMBOK)
    public static final class Palette {
        private final String name;
        private final int[] swatches;
        private final String[] labels;
        public Palette(String name, int[] swatches, String[] labels) {
            this.name = name;
            this.swatches = swatches;
            this.labels = labels;
        }
    }

    /**
     * The same shape under Lombok, generated in the same javac round - the only
     * place the two generators are held against each other on one processor
     * path. The pair stays on a separate type from {@link Palette}: both
     * annotations inject the members, and the two on one target is the
     * already-declares-it error.
     */
    @lombok.EqualsAndHashCode
    @lombok.ToString
    public static final class LombokPalette {
        private final String name;
        private final int[] swatches;
        private final String[] labels;
        public LombokPalette(String name, int[] swatches, String[] labels) {
            this.name = name;
            this.swatches = swatches;
            this.labels = labels;
        }
    }

    /**
     * The headline shape, and the one with no Lombok column: Lombok rejects a
     * record outright - "@EqualsAndHashCode is only supported on a class" - so
     * a record whose array component compares by content is what the implicit
     * pair and Lombok both leave to the author.
     */
    @EqualsAndHashCode
    @ToString
    public record Frame(float[] values, int width) {}

    // ==================================================================
    // main - runs every case
    // ==================================================================

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: java -jar <showcase.jar> <output-file>");
            System.exit(2);
            return;
        }
        Path output = Paths.get(args[0]);
        ShowcaseReport report = new ShowcaseReport(output);

        // --- @ClassBuilder base ------------------------------------------

        report.expect("classBuilder.plain")
            .run(() -> Plain.builder().name("alice").age(30).build())
            .asSuccess();

        report.expect("classBuilder.plain.from")
            .run(() -> {
                Plain source = Plain.builder().name("source").age(42).build();
                return Plain.from(source).build();
            })
            .asSuccess("round-tripped name/age");

        report.expect("classBuilder.plain.mutate")
            .run(() -> {
                Plain source = Plain.builder().name("a").age(1).build();
                return source.mutate().age(2).build();
            })
            .asSuccess("mutate() returned new instance with age=2");

        report.expect("classBuilder.suppressedFrom.mutate")
            .runVoid(() -> {
                // mutate() round-trips even though from(T) is suppressed - it
                // seeds inline off `this`, not via from(this).
                NoFrom base = NoFrom.builder().label("a").size(1).build();
                NoFrom mutated = base.mutate().size(2).build();
                if (!"a".equals(mutated.getLabel()) || mutated.getSize() != 2)
                    throw new AssertionError("mutate() round-trip failed: " + mutated);
                // A suppressed from must leave no static from(T) factory.
                for (Method m : NoFrom.class.getDeclaredMethods()) {
                    if (m.getName().equals("from"))
                        throw new AssertionError("from() must not exist when from is suppressed");
                }
            })
            .asSuccess("mutate() round-trips inline; no static from() emitted");

        report.expect("classBuilder.renamed")
            .runVoid(() -> {
                Renamed built = Renamed.newBuilder().setValue("renamed").construct();
                if (!"renamed".equals(built.getValue()))
                    throw new AssertionError("expected value 'renamed', got " + built.getValue());
            })
            .asSuccess("newBuilder().setValue().construct() chain works");

        report.expect("classBuilder.exclude")
            .runVoid(() -> {
                for (Method m : Excluded.Builder.class.getDeclaredMethods()) {
                    if (m.getName().equals("secret"))
                        throw new AssertionError("'secret' field was excluded but secret setter exists");
                }
                Excluded built = Excluded.builder().visible("v").build();
                if (!"default-secret".equals(built.getSecret()))
                    throw new AssertionError("excluded field lost its default initializer");
            })
            .asSuccess("no secret on Builder; default initializer preserved");

        report.expect("classBuilder.access.package")
            .runVoid(() -> {
                int modifiers = PackageAccess.Builder.class.getModifiers();
                if (Modifier.isPublic(modifiers))
                    throw new AssertionError("Builder expected package-private but is public");
            })
            .asSuccess("Builder class is package-private");

        report.expect("classBuilder.validate.disabled")
            .run(() -> ValidationDisabled.builder().build())
            .asSuccess("build() returned even though nonNull field is null");

        // --- retained initializers / @BuilderIgnore ----------------------

        report.expect("builderDefault.literal")
            .runVoid(() -> {
                RetainedInit built = RetainedInit.builder().build();
                if (!"hello-from-init".equals(built.getGreeting()))
                    throw new AssertionError("expected retained initializer, got " + built.getGreeting());
            })
            .asSuccess("string literal initializer materialised as builder default");

        report.expect("builderDefault.numeric")
            .runVoid(() -> {
                RetainedInitNumeric built = RetainedInitNumeric.builder().build();
                if (built.getThreshold() != 42)
                    throw new AssertionError("expected threshold=42, got " + built.getThreshold());
            })
            .asSuccess("int literal initializer materialised as builder default");

        report.expect("builderDefault.object")
            .runVoid(() -> {
                RetainedInitObject a = RetainedInitObject.builder().build();
                RetainedInitObject b = RetainedInitObject.builder().build();
                if (a.getBlob() == null || b.getBlob() == null)
                    throw new AssertionError("expected non-null blobs");
                if (a.getBlob() == b.getBlob())
                    throw new AssertionError("each build() should produce a fresh Object");
            })
            .asSuccess("new Object() re-evaluated on every build()");

        report.expect("builderDefault.fresh")
            .runVoid(() -> {
                // Every build() evaluates UUID.randomUUID() again - two
                // sequential builds must yield distinct IDs.
                RetainedInitFresh a = RetainedInitFresh.builder().build();
                RetainedInitFresh b = RetainedInitFresh.builder().build();
                if (a.getId() == null || b.getId() == null)
                    throw new AssertionError("expected non-null UUIDs");
                if (a.getId().equals(b.getId()))
                    throw new AssertionError("UUIDs must differ, got " + a.getId() + " / " + b.getId());
            })
            .asSuccess("UUID.randomUUID() re-evaluated on every build()");

        report.expect("builderDefault.collection")
            .runVoid(() -> {
                // Each builder gets its OWN fresh ArrayList.
                RetainedInitCollection first = RetainedInitCollection.builder().build();
                first.getTags().add("mutated");
                RetainedInitCollection second = RetainedInitCollection.builder().build();
                if (!second.getTags().isEmpty())
                    throw new AssertionError("fresh builder should start with empty list, got " + second.getTags());
            })
            .asSuccess("new ArrayList<>() returns a fresh instance per build()");

        report.expect("builderDefault.factory")
            .runVoid(() -> {
                RetainedInitFactoryCall built = RetainedInitFactoryCall.builder().build();
                if (!java.util.List.of("guest", "user").equals(built.getRoles()))
                    throw new AssertionError("expected [guest, user], got " + built.getRoles());
            })
            .asSuccess("List.of(...) factory call preserved as builder default");

        report.expect("builderDefault.override")
            .runVoid(() -> {
                RetainedInit built = RetainedInit.builder().greeting("explicit").build();
                if (!"explicit".equals(built.getGreeting()))
                    throw new AssertionError("expected explicit override, got " + built.getGreeting());
            })
            .asSuccess("explicit setter overrides retained initializer");

        // --- BUG-2 / F3: final retainInit (blank-final lift) -------------

        report.expect("builderDefault.final.literal")
            .runVoid(() -> {
                RetainedInitFinal built = RetainedInitFinal.builder().build();
                if (built.getTileSize() != 128)
                    throw new AssertionError("expected tileSize=128, got " + built.getTileSize());
                if (!List.of("base").equals(built.getLayers()))
                    throw new AssertionError("expected layers=[base], got " + built.getLayers());
            })
            .asSuccess("final retainInit (int literal + List.of identifier) materialised as defaults");

        report.expect("builderDefault.final.override")
            .runVoid(() -> {
                RetainedInitFinal built = RetainedInitFinal.builder().tileSize(256).build();
                if (built.getTileSize() != 256)
                    throw new AssertionError("expected override tileSize=256, got " + built.getTileSize());
            })
            .asSuccess("explicit setter overrides the retained final default");

        report.expect("builderDefault.final.isFinal")
            .runVoid(() -> {
                boolean found = false;
                for (java.lang.reflect.Field f : RetainedInitFinal.class.getDeclaredFields()) {
                    if (!f.getName().equals("tileSize")) continue;
                    found = true;
                    if (!Modifier.isFinal(f.getModifiers()))
                        throw new AssertionError("tileSize must stay final in bytecode (ACC_FINAL)");
                }
                if (!found) throw new AssertionError("tileSize field not found");
            })
            .asSuccess("source final preserved as ACC_FINAL - immutability intact");

        report.expect("builderDefault.final.lombok")
            .runVoid(() -> {
                RetainedInitFinalLombok built = RetainedInitFinalLombok.builder().build();
                if (built.getTileSize() != 128 || !List.of("base").equals(built.getLayers()))
                    throw new AssertionError("Lombok co-resident final retainInit failed: "
                        + built.getTileSize() + " / " + built.getLayers());
            })
            .asSuccess("final retainInit compiles + round-trips under Lombok @Getter co-residence");

        report.expect("builderIgnore")
            .runVoid(() -> {
                for (Method m : IgnoredField.Builder.class.getDeclaredMethods()) {
                    if (m.getName().equals("hidden"))
                        throw new AssertionError("ignored field leaked as hidden setter");
                }
                IgnoredField built = IgnoredField.builder().visible("v").build();
                if (!"hidden-default".equals(built.getHidden()))
                    throw new AssertionError("ignored field lost its default initializer");
            })
            .asSuccess("no hidden on Builder; default initializer preserved");

        // --- @BuildFlag --------------------------------------------------

        report.expect("buildFlag.nonNull.null")
            .runExpectingThrow(() -> NullRequired.builder().build())
            .asFailure(BuilderValidationException.class)
            .messageEquals("Field 'name' in 'NullRequired' is required and is null/empty");

        report.expect("buildFlag.nonNull.value")
            .run(() -> NullRequired.builder().name("ok").build())
            .asSuccess("built NullRequired with name=ok");

        report.expect("buildFlag.notEmpty.string.empty")
            .runExpectingThrow(() -> EmptyStringRequired.builder().s("").build())
            .asFailure(BuilderValidationException.class)
            .messageEquals("Field 's' in 'EmptyStringRequired' is required and is null/empty");

        report.expect("buildFlag.notEmpty.string.value")
            .run(() -> EmptyStringRequired.builder().s("x").build())
            .asSuccess("built with non-empty string");

        report.expect("buildFlag.notEmpty.optional.empty")
            .runExpectingThrow(() -> EmptyOptionalRequired.builder().opt(Optional.empty()).build())
            .asFailure(BuilderValidationException.class)
            .messageContains("'opt'");

        report.expect("buildFlag.notEmpty.collection.empty")
            .runExpectingThrow(() -> EmptyCollectionRequired.builder().build())
            .asFailure(BuilderValidationException.class)
            .messageContains("'items'");

        report.expect("buildFlag.notEmpty.map.empty")
            .runExpectingThrow(() -> EmptyMapRequired.builder().build())
            .asFailure(BuilderValidationException.class)
            .messageContains("'entries'");

        report.expect("buildFlag.notEmpty.array.empty")
            .runExpectingThrow(() -> EmptyArrayRequired.builder().build())
            .asFailure(BuilderValidationException.class)
            .messageContains("'arr'");

        report.expect("buildFlag.pattern.match")
            .run(() -> PatternConstrained.builder().ident("hello").build())
            .asSuccess("pattern matched");

        report.expect("buildFlag.pattern.mismatch")
            .runExpectingThrow(() -> PatternConstrained.builder().ident("Has Spaces").build())
            .asFailure(BuilderValidationException.class)
            .messageEquals("Field 'ident' in 'PatternConstrained' does not match pattern '[a-z]+' (value: 'Has Spaces')");

        report.expect("buildFlag.pattern.nullSkipped")
            .run(() -> PatternConstrained.builder().build())
            .asSuccess("null value bypasses pattern check");

        report.expect("buildFlag.limit.string.under")
            .run(() -> LimitedString.builder().text("abc").build())
            .asSuccess("under limit");

        report.expect("buildFlag.limit.string.over")
            .runExpectingThrow(() -> LimitedString.builder().text("toolong").build())
            .asFailure(BuilderValidationException.class)
            .messageEquals("Field 'text' in 'LimitedString' has length 7, exceeds limit of 5");

        report.expect("buildFlag.limit.collection.over")
            .runExpectingThrow(() -> LimitedCollection.builder().tags(List.of("a", "b", "c")).build())
            .asFailure(BuilderValidationException.class)
            .messageContains("exceeds limit of 2");

        report.expect("buildFlag.limit.optionalNumber.over")
            .runExpectingThrow(() -> LimitedOptionalNumber.builder().amount(Optional.of(500)).build())
            .asFailure(BuilderValidationException.class)
            .messageContains("exceeds limit of 100");

        report.expect("buildFlag.group.allMissing")
            .runExpectingThrow(() -> FaceGroup.builder().build())
            .asFailure(BuilderValidationException.class)
            .messageContains("Field group 'face'");

        report.expect("buildFlag.group.onePresent")
            .run(() -> FaceGroup.builder().label("OK").build())
            .asSuccess("one group member satisfied");

        // --- @ObtainVia ---------------------------------------------------

        report.expect("obtainVia.method")
            .runVoid(() -> {
                ViaMethod source = ViaMethod.builder().custom("raw-value").build();
                ViaMethod copy = ViaMethod.from(source).build();
                if (!"method-derived-raw-value".equals(copy.getCustom()))
                    throw new AssertionError("expected method-derived accessor, got " + copy.getCustom());
            })
            .asSuccess("from() used customAccessor() instance method");

        report.expect("obtainVia.field")
            .runVoid(() -> {
                ViaField source = ViaField.builder().alias("ignored").build();
                ViaField copy = ViaField.from(source).build();
                if (!"from-real-field".equals(copy.getAlias()))
                    throw new AssertionError("expected real-field read, got " + copy.getAlias());
            })
            .asSuccess("from() read alternate field 'realValue'");

        report.expect("obtainVia.isStatic")
            .runVoid(() -> {
                ViaStatic source = ViaStatic.builder().value("ignored").build();
                ViaStatic copy = ViaStatic.from(source).build();
                if (!"static-helper-result".equals(copy.getValue()))
                    throw new AssertionError("expected static-helper-result, got " + copy.getValue());
            })
            .asSuccess("from() invoked static extract(instance)");

        // --- @Collector --------------------------------------------------

        report.expect("collector.list.bulk")
            .runVoid(() -> {
                CollectorList built = CollectorList.builder().items("a", "b", "c").build();
                if (built.getItems().size() != 3)
                    throw new AssertionError("expected 3 items, got " + built.getItems());
            })
            .asSuccess("varargs bulk setter populates list");

        report.expect("collector.list.singular")
            .runVoid(() -> {
                CollectorList built = CollectorList.builder()
                    .addItem("x")
                    .addItem("y")
                    .build();
                if (built.getItems().size() != 2)
                    throw new AssertionError("expected 2 items via singular add, got " + built.getItems());
            })
            .asSuccess("singular add appended both entries");

        report.expect("collector.list.clearable")
            .runVoid(() -> {
                CollectorList built = CollectorList.builder()
                    .items("a", "b", "c")
                    .clearItems()
                    .build();
                if (!built.getItems().isEmpty())
                    throw new AssertionError("expected empty list after clear, got " + built.getItems());
            })
            .asSuccess("clearItems() emptied the list");

        report.expect("collector.map.put")
            .runVoid(() -> {
                CollectorMap built = CollectorMap.builder()
                    .putEntry("a", 1)
                    .putEntry("b", 2)
                    .build();
                if (!Integer.valueOf(1).equals(built.getCounts().get("a")))
                    throw new AssertionError("expected a=1, got " + built.getCounts());
            })
            .asSuccess("singular putEntry populated map");

        report.expect("collector.map.compute")
            .runVoid(() -> {
                int[] supplierCalls = {0};
                CollectorMap built = CollectorMap.builder()
                    .putEntry("key", 7)
                    .putEntryIfAbsent("key", () -> {
                        supplierCalls[0]++;
                        return 99;
                    })
                    .putEntryIfAbsent("other", () -> {
                        supplierCalls[0]++;
                        return 42;
                    })
                    .build();
                if (supplierCalls[0] != 1)
                    throw new AssertionError("expected supplier called once, got " + supplierCalls[0]);
                if (!Integer.valueOf(7).equals(built.getCounts().get("key")))
                    throw new AssertionError("compute should not have overwritten 'key', got " + built.getCounts());
                if (!Integer.valueOf(42).equals(built.getCounts().get("other")))
                    throw new AssertionError("compute missed 'other' entry, got " + built.getCounts());
            })
            .asSuccess("putEntryIfAbsent invoked supplier only on missing key");

        // --- @Negate -----------------------------------------------------

        report.expect("negate.direct")
            .runVoid(() -> {
                Negated built = Negated.builder().isEnabled().build();
                if (!built.isEnabled())
                    throw new AssertionError("expected enabled=true via isEnabled()");
            })
            .asSuccess("isEnabled() zero-arg set true");

        report.expect("negate.inverse")
            .runVoid(() -> {
                Negated built = Negated.builder().isDisabled().build();
                if (built.isEnabled())
                    throw new AssertionError("expected enabled=false via isDisabled()");
            })
            .asSuccess("isDisabled() zero-arg set enabled=false");

        // --- @Formattable ------------------------------------------------

        report.expect("formattable.string")
            .runVoid(() -> {
                FormattedString built = FormattedString.builder()
                    .message("%s: %d", "n", 42)
                    .build();
                if (!"n: 42".equals(built.getMessage()))
                    throw new AssertionError("expected 'n: 42', got " + built.getMessage());
            })
            .asSuccess("format overload produced formatted string");

        // --- Naming ------------------------------------------------------

        report.expect("naming.boolean.bareTypedSetter")
            .runVoid(() -> {
                Sprite built = Sprite.builder().animated(true).build();
                if (!built.isAnimated())
                    throw new AssertionError("expected animated=true via animated(boolean)");
                // The zero-arg convenience survives alongside it.
                if (!Sprite.builder().isAnimated().build().isAnimated())
                    throw new AssertionError("expected animated=true via isAnimated()");
            })
            .asSuccess("animated(boolean) is the set role, isAnimated() the flag role");

        report.expect("naming.style.lombok")
            .runVoid(() -> {
                Card built = Card.builder().shiny(true).tag("holo").tag("rare").build();
                if (!built.isShiny())
                    throw new AssertionError("expected shiny=true via shiny(boolean)");
                if (!built.getTags().equals(List.of("holo", "rare")))
                    throw new AssertionError("expected [holo, rare], got " + built.getTags());
                Card round = built.toBuilder().clearTags().build();
                if (!round.getTags().isEmpty())
                    throw new AssertionError("expected clearTags() to empty the list, got " + round.getTags());
                String builderName = Card.builder().getClass().getSimpleName();
                if (!"CardBuilder".equals(builderName))
                    throw new AssertionError("expected builderName pattern {}Builder, got " + builderName);
            })
            .asSuccess("CardBuilder / shiny(boolean) / tag(T) / clearTags() / toBuilder()");

        report.expect("naming.roles.override")
            .runVoid(() -> {
                Basket built = Basket.builder().appendItem("a").appendItem("b").build();
                if (!built.getItems().equals(List.of("a", "b")))
                    throw new AssertionError("expected [a, b], got " + built.getItems());
                if (!built.mutate().resetItems().build().getItems().isEmpty())
                    throw new AssertionError("expected resetItems() to empty the list");
            })
            .asSuccess("appendItem(T) / resetItems() from per-role patterns");

        // --- @EqualsAndHashCode ------------------------------------------

        report.expect("equalsAndHashCode.scalar")
            .runVoid(() -> {
                Measurement a = new Measurement("width", 12);
                Measurement b = new Measurement("width", 12);
                if (!a.equals(b) || !b.equals(a))
                    throw new AssertionError("equal scalar state must compare equal");
                if (a.hashCode() != b.hashCode())
                    throw new AssertionError("equal instances must hash alike, got "
                        + a.hashCode() + " / " + b.hashCode());
                if (a.equals(new Measurement("width", 13)))
                    throw new AssertionError("a differing int must break equality");
                if (a.equals(new Measurement("height", 12)))
                    throw new AssertionError("a differing String must break equality");
            })
            .asSuccess("scalar state compares and hashes on value");

        report.expect("equalsAndHashCode.array.content")
            .runVoid(() -> {
                Palette a = new Palette("solar", new int[]{1, 2}, new String[]{"x", "y"});
                Palette b = new Palette("solar", new int[]{1, 2}, new String[]{"x", "y"});
                // Every array here is a distinct instance, so a reference
                // comparison answers false for both assertions below.
                if (!a.equals(b))
                    throw new AssertionError("distinct-but-equal arrays must compare equal");
                if (a.hashCode() != b.hashCode())
                    throw new AssertionError("equal arrays must hash alike, got "
                        + a.hashCode() + " / " + b.hashCode());
                if (a.equals(new Palette("solar", new int[]{1, 9}, new String[]{"x", "y"})))
                    throw new AssertionError("differing primitive-array content must break equality");
                if (a.equals(new Palette("solar", new int[]{1, 2}, new String[]{"x", "z"})))
                    throw new AssertionError("differing reference-array content must break equality");
            })
            .asSuccess("int[] and String[] compared by content, hash agrees");

        report.expect("equalsAndHashCode.array.lombokParity")
            .runVoid(() -> {
                LombokPalette a = new LombokPalette("solar", new int[]{1, 2}, new String[]{"x", "y"});
                LombokPalette b = new LombokPalette("solar", new int[]{1, 2}, new String[]{"x", "y"});
                if (!a.equals(b))
                    throw new AssertionError("Lombok must also compare the arrays by content");
                if (a.hashCode() != b.hashCode())
                    throw new AssertionError("Lombok hash disagrees with its own equals, got "
                        + a.hashCode() + " / " + b.hashCode());
                if (a.equals(new LombokPalette("solar", new int[]{1, 9}, new String[]{"x", "y"})))
                    throw new AssertionError("Lombok must reject differing array content");
            })
            .asSuccess("Lombok's pair agrees on the same shape, same javac round");

        report.expect("equalsAndHashCode.record.array")
            .runVoid(() -> {
                Frame a = new Frame(new float[]{1f, 2f}, 4);
                Frame b = new Frame(new float[]{1f, 2f}, 4);
                if (a.values() == b.values())
                    throw new AssertionError("the two components must be distinct arrays");
                // The implicit record pair compares a float[] component by
                // reference, so both assertions fail without the injection.
                if (!a.equals(b))
                    throw new AssertionError("the injected pair must beat the implicit one");
                if (a.hashCode() != b.hashCode())
                    throw new AssertionError("equal records must hash alike, got "
                        + a.hashCode() + " / " + b.hashCode());
                if (a.equals(new Frame(new float[]{1f, 3f}, 4)))
                    throw new AssertionError("differing component content must break equality");
                if (a.equals(new Frame(new float[]{1f, 2f}, 5)))
                    throw new AssertionError("a differing scalar component must break equality");
            })
            .asSuccess("record's implicit reference comparison replaced by content");

        // --- @ToString ----------------------------------------------------

        report.expect("toString.record.array")
            .runVoid(() -> {
                String rendered = new Frame(new float[]{1f, 2f}, 4).toString();
                if (!"Frame[values=[1.0, 2.0], width=4]".equals(rendered))
                    throw new AssertionError("expected Frame[values=[1.0, 2.0], width=4], got " + rendered);
            })
            .asSuccess("record renders Name[a=1, b=2] with array contents");

        report.expect("toString.lombokParity")
            .runVoid(() -> {
                String ours = new Palette("solar", new int[]{1, 2}, new String[]{"x", "y"}).toString();
                String theirs = new LombokPalette("solar", new int[]{1, 2}, new String[]{"x", "y"}).toString();
                // The type name itself diverges and is meant to: Lombok
                // qualifies a nested type with its outer name, this renders the
                // simple name. The member list is what LOMBOK style promises.
                if (ours.indexOf('(') < 0 || theirs.indexOf('(') < 0)
                    throw new AssertionError("no generated rendering: " + ours + " / " + theirs);
                String oursMembers = ours.substring(ours.indexOf('('));
                String theirsMembers = theirs.substring(theirs.indexOf('('));
                if (!oursMembers.equals(theirsMembers))
                    throw new AssertionError("LOMBOK style rendered " + oursMembers
                        + " where Lombok rendered " + theirsMembers);
                if (!"Palette".equals(ours.substring(0, ours.indexOf('('))))
                    throw new AssertionError("expected the simple name to lead, got " + ours);
            })
            .asSuccess("style = LOMBOK matches Lombok's member list byte for byte");

        report.finish();
    }

}
