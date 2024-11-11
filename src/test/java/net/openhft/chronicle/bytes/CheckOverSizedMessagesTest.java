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

import net.openhft.chronicle.core.OS;
import org.junit.Before;
import org.junit.Test;

import java.io.*;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

public class CheckOverSizedMessagesTest extends BytesTestCommon {

    public static final byte[] BYTE6K = new byte[6000];

    public static MappedBytes mbNoOverlap() {
        File path = new File(OS.getTarget(), "oversized-" + System.nanoTime());
        path.deleteOnExit();
        try {
            return MappedBytes.mappedBytes(path, 4 << 10, 4 << 10);
        } catch (FileNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    @Before
    public void checkPageSize() {
        assumeTrue(OS.isLinux());
    }

    @Test
    public void writeRDI() {
        try (MappedBytes mb = mbNoOverlap()) {
            mb.writePosition(3 << 10);
            Bytes<?> rdi = Bytes.allocateDirect(BYTE6K);
            final BytesStore<?, Void> bs0 = mb.bytesStore();
            mb.write(rdi);
            final BytesStore<?, Void> bs2 = mb.bytesStore();
            assertNotSame(bs0, bs2);
            rdi.releaseLast();
        }
    }

    @Test
    public void writeByteArray2() {
        try (MappedBytes mb = mbNoOverlap()) {
            byte[] arr = new byte[6 << 10];
            final BytesStore<?, Void> bs0 = mb.bytesStore();
            mb.write(3 << 10, arr);
            final BytesStore<?, Void> bs2 = mb.bytesStore();
            assertNotSame(bs0, bs2);
        }
    }

    @Test
    public void writeByteArray4() {
        try (MappedBytes mb = mbNoOverlap()) {
            byte[] arr = new byte[6 << 10];
            final BytesStore<?, Void> bs0 = mb.bytesStore();
            mb.write(4000, arr, 128, 5900);
            final BytesStore<?, Void> bs2 = mb.bytesStore();
            assertNotSame(bs0, bs2);
        }
    }

    @Test
    public void writeBB4() {
        try (MappedBytes mb = mbNoOverlap()) {
            ByteBuffer bb = ByteBuffer.allocate(6000);
            final BytesStore<?, Void> bs0 = mb.bytesStore();
            mb.write(4000, bb, 128, 5800);
            final BytesStore<?, Void> bs2 = mb.bytesStore();
            assertNotSame(bs0, bs2);
        }
    }

    @Test
    public void writeBB4B() {
        try (MappedBytes mb = mbNoOverlap()) {
            assumeFalse(PageUtil.isHugePage(mb.mappedFile().file().getAbsolutePath()));
            ByteBuffer bb = ByteBuffer.allocate(6000);
            mb.writeLong(4000, -1);
            final BytesStore<?, Void> bs0 = mb.bytesStore();
            try {
                mb.write(4000, bb, 128, 5900);
                fail();
            } catch (IndexOutOfBoundsException expected) {
                // check untouched
                assertEquals(-1, mb.readLong(4000));
            }
            // didn't actually write
            final BytesStore<?, Void> bs2 = mb.bytesStore();
            assertSame(bs0, bs2);
        }
    }

    @Test
    public void writeRDI4() {
        try (MappedBytes mb = mbNoOverlap()) {
            RandomDataInput rdi = Bytes.allocateDirect(BYTE6K);
            final BytesStore<?, Void> bs0 = mb.bytesStore();
            mb.write(4000, rdi, 128, 5900);
            rdi.releaseLast();
            final BytesStore<?, Void> bs2 = mb.bytesStore();
            assertNotSame(bs0, bs2);
        }
    }

    @Test
    public void writeByteArray3() {
        try (MappedBytes mb = mbNoOverlap()) {
            mb.writePosition(4000);
            byte[] arr = new byte[6 << 10];
            final BytesStore<?, Void> bs0 = mb.bytesStore();
            mb.write(arr, 128, 5900);
            final BytesStore<?, Void> bs2 = mb.bytesStore();
            assertNotSame(bs0, bs2);
        }
    }

    @Test
    public void writeIS() throws IOException {
        try (MappedBytes mb = mbNoOverlap()) {
            mb.writePosition(3 << 10);
            InputStream is = new ByteArrayInputStream(BYTE6K);
            final BytesStore<?, Void> bs0 = mb.bytesStore();
            mb.write(is);
            final BytesStore<?, Void> bs2 = mb.bytesStore();
            assertNotSame(bs0, bs2);
        }
    }

    @Test
    public void writeBytes2() {
        try (MappedBytes mb = mbNoOverlap()) {
            final BytesStore<?, Void> bs0 = mb.bytesStore();
            mb.write(4000, Bytes.wrapForRead(BYTE6K));
            final BytesStore<?, Void> bs2 = mb.bytesStore();
            assertNotSame(bs0, bs2);
        }
    }

    @Test
    public void writeCS() {
        try (MappedBytes mb = mbNoOverlap()) {
            mb.writePosition(3 << 10);
            final BytesStore<?, Void> bs0 = mb.bytesStore();
            mb.write(new String(BYTE6K, StandardCharsets.US_ASCII));
            final BytesStore<?, Void> bs2 = mb.bytesStore();
            assertNotSame(bs0, bs2);
        }
    }

    @Test
    public void writeBytes() {
        try (MappedBytes mb = mbNoOverlap()) {
            mb.writePosition(4000);
            final BytesStore<?, Void> bs0 = mb.bytesStore();
            mb.write(Bytes.wrapForRead(BYTE6K));
            final BytesStore<?, Void> bs2 = mb.bytesStore();
            assertNotSame(bs0, bs2);
        }
    }

    @Test
    public void writeByteStore3() {
        try (MappedBytes mb = mbNoOverlap()) {
            mb.writePosition(3 << 10);
            final BytesStore<Bytes<byte[]>, byte[]> bytes = Bytes.wrapForRead(BYTE6K);
            final BytesStore<?, Void> bs0 = mb.bytesStore();
            mb.write(bytes, 128L, 5800L);
            final BytesStore<?, Void> bs2 = mb.bytesStore();
            assertNotSame(bs0, bs2);
        }
    }

    @Test
    public void writeByteArray() {
        try (MappedBytes mb = mbNoOverlap()) {
            mb.writePosition(3 << 10);
            final BytesStore<?, Void> bs0 = mb.bytesStore();
            mb.write(BYTE6K);
            final BytesStore<?, Void> bs2 = mb.bytesStore();
            assertNotSame(bs0, bs2);
        }
    }

    @Test
    public void appendCS() {
        try (MappedBytes mb = mbNoOverlap()) {
            mb.writePosition(3 << 10);
            final BytesStore<?, Void> bs0 = mb.bytesStore();
            mb.append(new String(BYTE6K, StandardCharsets.US_ASCII));
            final BytesStore<?, Void> bs2 = mb.bytesStore();
            assertNotSame(bs0, bs2);
        }
    }

    @Test
    public void appendCS3() {
        try (MappedBytes mb = mbNoOverlap()) {
            mb.writePosition(3 << 10);
            final BytesStore<?, Void> bs0 = mb.bytesStore();
            mb.append(new String(BYTE6K, StandardCharsets.US_ASCII), 128, 5800);
            final BytesStore<?, Void> bs2 = mb.bytesStore();
            assertNotSame(bs0, bs2);
        }
    }

    @Test
    public void appendBigDecimal() {
        try (MappedBytes mb = mbNoOverlap()) {
            mb.writePosition(3 << 10);
            final byte[] bytes = BYTE6K;
            Arrays.fill(bytes, (byte) '1');
            final String s = new String(bytes, StandardCharsets.US_ASCII);
            final BytesStore<?, Void> bs0 = mb.bytesStore();
            mb.append(new BigDecimal(s));
            final BytesStore<?, Void> bs2 = mb.bytesStore();
            assertNotSame(bs0, bs2);
        }
    }

    @Test
    public void read() {
        Bytes<?> in = Bytes.wrapForRead(BYTE6K);
        try (MappedBytes mb = mbNoOverlap()) {
            mb.writePosition(3 << 10);
            final BytesStore<?, Void> bs0 = mb.bytesStore();
            in.read(mb);
            final BytesStore<?, Void> bs2 = mb.bytesStore();
            assertNotSame(bs0, bs2);
        }
    }

    @Test
    public void read2() {
        Bytes<?> in = Bytes.wrapForRead(BYTE6K);
        try (MappedBytes mb = mbNoOverlap()) {
            mb.writePosition(3 << 10);
            final BytesStore<?, Void> bs0 = mb.bytesStore();
            in.read(mb, 5900);
            final BytesStore<?, Void> bs2 = mb.bytesStore();
            assertNotSame(bs0, bs2);
        }
    }

    @Test
    public void parse8bit() {
        Bytes<?> in = Bytes.wrapForRead(BYTE6K);
        try (MappedBytes mb = mbNoOverlap()) {
            mb.writePosition(3 << 10);
            final BytesStore<?, Void> bs0 = mb.bytesStore();
            in.parse8bit(mb, StopCharTesters.ALL);
            final BytesStore<?, Void> bs2 = mb.bytesStore();
            assertNotSame(bs0, bs2);
        }
    }

    @Test
    public void parseUTF8() {
        Bytes<?> in = Bytes.wrapForRead(BYTE6K);
        try (MappedBytes mb = mbNoOverlap()) {
            mb.writePosition(3 << 10);
            final BytesStore<?, Void> bs0 = mb.bytesStore();
            in.parseUtf8(mb, StopCharTesters.ALL);
            final BytesStore<?, Void> bs2 = mb.bytesStore();
            assertNotSame(bs0, bs2);
        }
    }
}
