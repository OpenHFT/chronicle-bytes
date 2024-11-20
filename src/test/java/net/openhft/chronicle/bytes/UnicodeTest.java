package net.openhft.chronicle.bytes;

import org.junit.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.Assert.assertEquals;

public class UnicodeTest {

    @ParameterizedTest
    @ValueSource(strings = {"£", "¥", "ó", "óaóó"})
    public void verifyFrom(String input) {
        assertEquals(input, Bytes.from(input).toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"£", "¥", "ó", "óaóó", "♺♺♺"}) // Note this can take some more complex characters!
    public void verifyAppend(String input) {
        Bytes<?> bytes = Bytes.allocateElasticOnHeap();
        bytes.append(input);
        assertEquals(input, bytes.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"£", "¥", "ó", "óaóó"})
    public void verifyAppend8Bit(String input) {
        Bytes<?> bytes = Bytes.allocateElasticOnHeap();
        bytes.append8bit(input);
        assertEquals(input, bytes.toString());
    }

}
