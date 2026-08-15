package dev.simplified.classbuilder.apt;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The plural inflection a {@code @Collector}'s single-element members are named
 * from.
 *
 * <p>It lives here rather than in either module because both halves call it: a
 * name minted one way by the processor and another by the editor is completion
 * offering a method the build does not emit.
 */
public class NamePatternSingularTest {

    /**
     * The rule that was wrong: an {@code -es} ending is only two letters of
     * plural when the stem ends in a sibilant, so a word that merely ends in
     * {@code e} keeps it.
     */
    @Test
    public void plainEEndingKeepsItsE() {
        assertEquals("frame", NamePattern.singularSubject("frames"));
        assertEquals("name", NamePattern.singularSubject("names"));
        assertEquals("type", NamePattern.singularSubject("types"));
        assertEquals("value", NamePattern.singularSubject("values"));
        assertEquals("phase", NamePattern.singularSubject("phases"));
        assertEquals("response", NamePattern.singularSubject("responses"));
    }

    @Test
    public void sibilantStemGivesUpBothLetters() {
        assertEquals("box", NamePattern.singularSubject("boxes"));
        assertEquals("class", NamePattern.singularSubject("classes"));
        assertEquals("address", NamePattern.singularSubject("addresses"));
        assertEquals("match", NamePattern.singularSubject("matches"));
        assertEquals("dish", NamePattern.singularSubject("dishes"));
        assertEquals("buzz", NamePattern.singularSubject("buzzes"));
        assertEquals("hero", NamePattern.singularSubject("heroes"));
    }

    /**
     * The sibilant has to be doubled to have taken the {@code -es}. A single
     * {@code s} or {@code z} before it is the word's own last letter, which is
     * the trap {@code sizes} sprang.
     */
    @Test
    public void singleSibilantIsPartOfTheWord() {
        assertEquals("size", NamePattern.singularSubject("sizes"));
        assertEquals("prize", NamePattern.singularSubject("prizes"));
        assertEquals("house", NamePattern.singularSubject("houses"));
        assertEquals("case", NamePattern.singularSubject("cases"));
    }

    /**
     * The boundary the {@code -ches} rule buys its wins at, pinned so that
     * moving it is a decision rather than an accident. {@code caches} is
     * {@code cache} plus an {@code s} and nothing in the spelling says so, the
     * same letters that make {@code matches} a genuine {@code -es} plural. A
     * field this misses names itself with
     * {@code @Collector(singularMethodName = "cache")}.
     */
    @Test
    public void aSilentEBeforeChIsTheKnownMiss() {
        assertEquals("cach", NamePattern.singularSubject("caches"));
    }

    @Test
    public void consonantYPluralComesBack() {
        assertEquals("entry", NamePattern.singularSubject("entries"));
        assertEquals("city", NamePattern.singularSubject("cities"));
        assertEquals("property", NamePattern.singularSubject("properties"));
    }

    /** Too short a stem before {@code -ies} means the {@code s} was the plural. */
    @Test
    public void shortIesStemFallsBackToThePlainRule() {
        assertEquals("tie", NamePattern.singularSubject("ties"));
    }

    @Test
    public void plainSPluralLosesOneLetter() {
        assertEquals("tag", NamePattern.singularSubject("tags"));
        assertEquals("item", NamePattern.singularSubject("items"));
        assertEquals("count", NamePattern.singularSubject("counts"));
        assertEquals("flavor", NamePattern.singularSubject("flavors"));
    }

    /** Not a plural at all - taking a letter off names the method after nothing. */
    @Test
    public void singularWordEndingInSIsLeftWhole() {
        assertEquals("address", NamePattern.singularSubject("address"));
        assertEquals("status", NamePattern.singularSubject("status"));
        assertEquals("radius", NamePattern.singularSubject("radius"));
    }

    @Test
    public void aNonPluralIsUnchanged() {
        assertEquals("frame", NamePattern.singularSubject("frame"));
        assertEquals("data", NamePattern.singularSubject("data"));
    }

    @Test
    public void degenerateNamesAreLeftAlone() {
        assertEquals(null, NamePattern.singularSubject(null));
        assertEquals("", NamePattern.singularSubject(""));
        assertEquals("s", NamePattern.singularSubject("s"));
    }

}
