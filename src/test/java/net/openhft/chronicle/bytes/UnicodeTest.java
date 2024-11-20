/*
 * Copyright (c) 2016-2022 chronicle.software
 *
 *     https://chronicle.software
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
