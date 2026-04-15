package dev.sbs.classbuilder.validate;

import dev.sbs.annotation.BuildFlag;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

public class BuildFlagValidatorTest {

    // --- nonNull ---------------------------------------------------------

    static class NonNullRequired {
        @BuildFlag(nonNull = true) String name;
    }

    @Test
    public void nonNull_null_throws() {
        NonNullRequired obj = new NonNullRequired();
        BuilderValidationException ex = assertThrows(
            BuilderValidationException.class,
            () -> BuildFlagValidator.validate(obj)
        );
        assertTrue(ex.getMessage(), ex.getMessage().contains("'name'"));
        assertTrue(ex.getMessage(), ex.getMessage().contains("required"));
    }

    @Test
    public void nonNull_nonNull_passes() {
        NonNullRequired obj = new NonNullRequired();
        obj.name = "hello";
        BuildFlagValidator.validate(obj);
    }

    // --- notEmpty across shapes ------------------------------------------

    static class NotEmptyShapes {
        @BuildFlag(notEmpty = true) String s;
        @BuildFlag(notEmpty = true) Optional<String> opt = Optional.empty();
        @BuildFlag(notEmpty = true) List<String> list = new ArrayList<>();
        @BuildFlag(notEmpty = true) Map<String, String> map = new HashMap<>();
        @BuildFlag(notEmpty = true) Object[] array = new Object[0];
    }

    @Test
    public void notEmpty_firstEmpty_throws() {
        NotEmptyShapes obj = new NotEmptyShapes();
        obj.s = "";
        obj.opt = Optional.of("x");
        obj.list.add("x");
        obj.map.put("k", "v");
        obj.array = new Object[] {"x"};
        BuilderValidationException ex = assertThrows(
            BuilderValidationException.class,
            () -> BuildFlagValidator.validate(obj)
        );
        assertTrue(ex.getMessage(), ex.getMessage().contains("'s'"));
    }

    @Test
    public void notEmpty_optionalEmpty_throws() {
        NotEmptyShapes obj = new NotEmptyShapes();
        obj.s = "x";
        assertThrows(BuilderValidationException.class, () -> BuildFlagValidator.validate(obj));
    }

    @Test
    public void notEmpty_collectionEmpty_throws() {
        NotEmptyShapes obj = new NotEmptyShapes();
        obj.s = "x";
        obj.opt = Optional.of("x");
        assertThrows(BuilderValidationException.class, () -> BuildFlagValidator.validate(obj));
    }

    @Test
    public void notEmpty_mapEmpty_throws() {
        NotEmptyShapes obj = new NotEmptyShapes();
        obj.s = "x";
        obj.opt = Optional.of("x");
        obj.list.add("x");
        assertThrows(BuilderValidationException.class, () -> BuildFlagValidator.validate(obj));
    }

    @Test
    public void notEmpty_arrayEmpty_throws() {
        NotEmptyShapes obj = new NotEmptyShapes();
        obj.s = "x";
        obj.opt = Optional.of("x");
        obj.list.add("x");
        obj.map.put("k", "v");
        assertThrows(BuilderValidationException.class, () -> BuildFlagValidator.validate(obj));
    }

    @Test
    public void notEmpty_allPopulated_passes() {
        NotEmptyShapes obj = new NotEmptyShapes();
        obj.s = "x";
        obj.opt = Optional.of("x");
        obj.list.add("x");
        obj.map.put("k", "v");
        obj.array = new Object[] {"x"};
        BuildFlagValidator.validate(obj);
    }

    // --- group (at-least-one-of) -----------------------------------------

    static class FaceGroup {
        @BuildFlag(nonNull = true, group = "face") String label;
        @BuildFlag(nonNull = true, group = "face") String emoji;
    }

    @Test
    public void group_allMissing_throws() {
        FaceGroup obj = new FaceGroup();
        BuilderValidationException ex = assertThrows(
            BuilderValidationException.class,
            () -> BuildFlagValidator.validate(obj)
        );
        assertTrue(ex.getMessage(), ex.getMessage().contains("'face'"));
        assertTrue(ex.getMessage(), ex.getMessage().contains("label"));
        assertTrue(ex.getMessage(), ex.getMessage().contains("emoji"));
    }

    @Test
    public void group_onePresent_passes() {
        FaceGroup obj = new FaceGroup();
        obj.label = "OK";
        BuildFlagValidator.validate(obj);
    }

    @Test
    public void group_otherPresent_passes() {
        FaceGroup obj = new FaceGroup();
        obj.emoji = ":)";
        BuildFlagValidator.validate(obj);
    }

    static class MultiGroup {
        @BuildFlag(nonNull = true, group = {"a", "b"}) String x;
        @BuildFlag(nonNull = true, group = "a") String y;
        @BuildFlag(nonNull = true, group = "b") String z;
    }

    @Test
    public void multiGroup_oneMemberSatisfiesAllItsGroups() {
        MultiGroup obj = new MultiGroup();
        obj.x = "value";
        BuildFlagValidator.validate(obj);
    }

    @Test
    public void multiGroup_missingOneGroupEntirely_throws() {
        MultiGroup obj = new MultiGroup();
        obj.y = "a-only"; // group a satisfied, group b has only z missing
        assertThrows(BuilderValidationException.class, () -> BuildFlagValidator.validate(obj));
    }

    // --- pattern ---------------------------------------------------------

    static class PatternRules {
        @BuildFlag(pattern = "[a-z0-9_]+") String identifier;
        @BuildFlag(pattern = "\\d{3}") Optional<String> code = Optional.empty();
    }

    @Test
    public void pattern_match_passes() {
        PatternRules obj = new PatternRules();
        obj.identifier = "hello_world_42";
        obj.code = Optional.of("123");
        BuildFlagValidator.validate(obj);
    }

    @Test
    public void pattern_mismatch_throws() {
        PatternRules obj = new PatternRules();
        obj.identifier = "Has Spaces";
        BuilderValidationException ex = assertThrows(
            BuilderValidationException.class,
            () -> BuildFlagValidator.validate(obj)
        );
        assertTrue(ex.getMessage(), ex.getMessage().contains("pattern"));
    }

    @Test
    public void pattern_null_skipsCheck() {
        PatternRules obj = new PatternRules();
        BuildFlagValidator.validate(obj);
    }

    @Test
    public void pattern_optionalMismatch_throws() {
        PatternRules obj = new PatternRules();
        obj.identifier = "ok";
        obj.code = Optional.of("ABC");
        assertThrows(BuilderValidationException.class, () -> BuildFlagValidator.validate(obj));
    }

    // --- limit -----------------------------------------------------------

    static class LimitRules {
        @BuildFlag(limit = 5) String text;
        @BuildFlag(limit = 3) List<String> items = new ArrayList<>();
        @BuildFlag(limit = 2) Map<String, String> entries = new LinkedHashMap<>();
        @BuildFlag(limit = 4) Object[] arr = new Object[0];
        @BuildFlag(limit = 10) Optional<String> optStr = Optional.empty();
        @BuildFlag(limit = 100) Optional<Integer> optNum = Optional.empty();
    }

    @Test
    public void limit_stringOver_throws() {
        LimitRules obj = new LimitRules();
        obj.text = "toolong";
        BuilderValidationException ex = assertThrows(
            BuilderValidationException.class,
            () -> BuildFlagValidator.validate(obj)
        );
        assertTrue(ex.getMessage(), ex.getMessage().contains("length"));
        assertTrue(ex.getMessage(), ex.getMessage().contains("limit"));
    }

    @Test
    public void limit_collectionOver_throws() {
        LimitRules obj = new LimitRules();
        obj.items.add("a");
        obj.items.add("b");
        obj.items.add("c");
        obj.items.add("d");
        assertThrows(BuilderValidationException.class, () -> BuildFlagValidator.validate(obj));
    }

    @Test
    public void limit_mapOver_throws() {
        LimitRules obj = new LimitRules();
        obj.entries.put("a", "1");
        obj.entries.put("b", "2");
        obj.entries.put("c", "3");
        assertThrows(BuilderValidationException.class, () -> BuildFlagValidator.validate(obj));
    }

    @Test
    public void limit_arrayOver_throws() {
        LimitRules obj = new LimitRules();
        obj.arr = new Object[] {1, 2, 3, 4, 5};
        assertThrows(BuilderValidationException.class, () -> BuildFlagValidator.validate(obj));
    }

    @Test
    public void limit_optionalStringOver_throws() {
        LimitRules obj = new LimitRules();
        obj.optStr = Optional.of("this is far too long");
        assertThrows(BuilderValidationException.class, () -> BuildFlagValidator.validate(obj));
    }

    @Test
    public void limit_optionalNumberOver_throws() {
        LimitRules obj = new LimitRules();
        obj.optNum = Optional.of(500);
        assertThrows(BuilderValidationException.class, () -> BuildFlagValidator.validate(obj));
    }

    @Test
    public void limit_underBoundary_passes() {
        LimitRules obj = new LimitRules();
        obj.text = "tiny";
        obj.items.add("one");
        obj.entries.put("a", "1");
        obj.arr = new Object[] {1};
        obj.optStr = Optional.of("short");
        obj.optNum = Optional.of(50);
        BuildFlagValidator.validate(obj);
    }

    @Test
    public void limit_exactBoundary_passes() {
        LimitRules obj = new LimitRules();
        obj.text = "12345";
        obj.items.add("a");
        obj.items.add("b");
        obj.items.add("c");
        obj.entries.put("a", "1");
        obj.entries.put("b", "2");
        obj.arr = new Object[] {1, 2, 3, 4};
        obj.optStr = Optional.of("1234567890");
        obj.optNum = Optional.of(100);
        BuildFlagValidator.validate(obj);
    }

    // --- inheritance -----------------------------------------------------

    static class ParentShape {
        @BuildFlag(nonNull = true) String parentName;
    }

    static class ChildShape extends ParentShape {
        @BuildFlag(nonNull = true) String childName;
    }

    @Test
    public void inheritance_missingParentField_throws() {
        ChildShape obj = new ChildShape();
        obj.childName = "child";
        BuilderValidationException ex = assertThrows(
            BuilderValidationException.class,
            () -> BuildFlagValidator.validate(obj)
        );
        assertTrue(ex.getMessage(), ex.getMessage().contains("parentName"));
    }

    @Test
    public void inheritance_missingChildField_throws() {
        ChildShape obj = new ChildShape();
        obj.parentName = "p";
        assertThrows(BuilderValidationException.class, () -> BuildFlagValidator.validate(obj));
    }

    @Test
    public void inheritance_bothPresent_passes() {
        ChildShape obj = new ChildShape();
        obj.parentName = "p";
        obj.childName = "c";
        BuildFlagValidator.validate(obj);
    }

    // --- caching ---------------------------------------------------------

    static class Cached {
        @BuildFlag(nonNull = true) String x;
    }

    @Test
    public void cache_secondCall_sameResultShape() {
        Cached a = new Cached();
        a.x = "1";
        BuildFlagValidator.validate(a);
        Cached b = new Cached();
        assertThrows(BuilderValidationException.class, () -> BuildFlagValidator.validate(b));
    }

    // --- no flags -------------------------------------------------------

    static class NoFlags {
        String x;
        int y;
    }

    @Test
    public void noFlags_passesSilently() {
        BuildFlagValidator.validate(new NoFlags());
    }

    // --- combinations ---------------------------------------------------

    static class Composite {
        @BuildFlag(nonNull = true, notEmpty = true, limit = 10, pattern = "[a-z]+") String name;
    }

    @Test
    public void composite_allSatisfied() {
        Composite obj = new Composite();
        obj.name = "hello";
        BuildFlagValidator.validate(obj);
    }

    @Test
    public void composite_failsFirstFailure() {
        Composite obj = new Composite();
        obj.name = null;
        try {
            BuildFlagValidator.validate(obj);
            fail("expected validation failure");
        } catch (BuilderValidationException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("required"));
        }
    }

    @Test
    public void composite_patternFailsAfterLength() {
        Composite obj = new Composite();
        obj.name = "OK"; // passes non-null / non-empty / limit; fails pattern (uppercase)
        try {
            BuildFlagValidator.validate(obj);
            fail("expected pattern failure");
        } catch (BuilderValidationException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("pattern"));
        }
    }

    @Test
    public void composite_limitFails() {
        Composite obj = new Composite();
        obj.name = "abcdefghijklmnop";
        try {
            BuildFlagValidator.validate(obj);
            fail("expected limit failure");
        } catch (BuilderValidationException e) {
            assertEquals("Field 'name' in 'Composite' has length 16, exceeds limit of 10", e.getMessage());
        }
    }

}
