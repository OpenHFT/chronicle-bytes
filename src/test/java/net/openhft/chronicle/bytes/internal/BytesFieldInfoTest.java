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
package net.openhft.chronicle.bytes.internal;

import net.openhft.chronicle.bytes.BytesTestCommon;
import net.openhft.chronicle.bytes.FieldGroup;
import net.openhft.chronicle.core.Jvm;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeFalse;

public class BytesFieldInfoTest extends BytesTestCommon {

    @Test
    public void lookup() {
        assumeFalse(Jvm.isArm() || Jvm.isAzulZing());
        final BytesFieldInfo lookup = BytesFieldInfo.lookup(Groups1.class);
        assertEquals("type: BytesFieldInfo, groups: { header: 12 to 16, pad: 16 to 48, other: 64 to 96, hi: 96 to 100, add: 104 to 120 }", lookup.dump());
        assertEquals("a070000", Integer.toHexString(lookup.description()));
        final BytesFieldInfo lookup2 = BytesFieldInfo.lookup(Groups2.class);
        assertEquals("type: BytesFieldInfo, groups: { hi: 12 to 16, pad: 16 to 48, add: 48 to 64 }", lookup2.dump());
        assertEquals("4050000", Integer.toHexString(lookup2.description()));
        final BytesFieldInfo lookup3 = BytesFieldInfo.lookup(Groups3.class);
        // field layout changed with Java 15 - https://bugs.openjdk.java.net/browse/JDK-8237767
        final String groups3 = Jvm.isJava15Plus() ?
                "type: BytesFieldInfo, groups: { hi: 12 to 16, pad: 16 to 48, add: 48 to 64 }" :
                "type: BytesFieldInfo, groups: { pad: 16 to 48, hi: 48 to 52, add: 52 to 68 }";
        assertEquals(groups3, lookup3.dump());
        assertEquals("4050000", Integer.toHexString(lookup3.description()));
    }

    static class Groups1 {
        @FieldGroup("header")
        int header;
        @FieldGroup("pad")
        long pad0, pad1, pad3, pad2;
        double d1, d2;
        @FieldGroup("other")
        long pad10, pad11, pad13, pad12;
        @FieldGroup("hi")
        int hi99;
        float f;
        @FieldGroup("add")
        int add1, add2, add3, add4;
    }

    static class Groups2 {
        @FieldGroup("pad")
        long pad0, pad1, pad3, pad2;
        @FieldGroup("hi")
        int hi99;
        @FieldGroup("add")
        int add1, add2, add3, add4;
    }

    static class GroupsBase {
        @FieldGroup("pad")
        long pad0, pad1, pad3, pad2;
    }

    static class Groups3 extends GroupsBase {
        @FieldGroup("hi")
        int hi99;
        @FieldGroup("add")
        int add1, add2, add3, add4;
    }
}
