package dev.sbs.classbuilder.validate;

import org.junit.Test;

import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class StringsTest {

    @Test
    public void formatNullable_null_returnsEmpty() {
        assertTrue(Strings.formatNullable(null).isEmpty());
        assertTrue(Strings.formatNullable(null, "ignored", 42).isEmpty());
    }

    @Test
    public void formatNullable_plainString_returnsValue() {
        assertEquals(Optional.of("hello"), Strings.formatNullable("hello"));
    }

    @Test
    public void formatNullable_withArgs_formats() {
        assertEquals(Optional.of("hello world"), Strings.formatNullable("%s %s", "hello", "world"));
    }

    @Test
    public void formatNullable_emptyFormat_returnsEmptyString() {
        assertEquals(Optional.of(""), Strings.formatNullable(""));
    }

}
