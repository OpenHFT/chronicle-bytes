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

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.OS;
import net.openhft.chronicle.core.io.IOTools;
import net.openhft.chronicle.core.io.ReferenceOwner;
import org.jetbrains.annotations.NotNull;
import org.junit.After;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.BufferUnderflowException;
import java.nio.file.Files;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeFalse;

public class MappedFileTest extends BytesTestCommon {

    @Rule
    public final TemporaryFolder tmpDir = new TemporaryFolder();

    @org.junit.jupiter.api.Test
    void testWarmup() {
        try {
            MappedFile.warmup();
        } catch (Throwable t) {
            fail(t.getMessage());
        }
    }

    @Test
    public void shouldReleaseReferenceWhenNewStoreIsAcquired()
            throws IOException {
        final File file = tmpDir.newFile();
        // this is what it will end up as
        final long chunkSize = OS.mapAlign(64);
        final ReferenceOwner test = ReferenceOwner.temporary("test");
        try (final MappedFile mappedFile = MappedFile.mappedFile(file, 64, 0)) {
            final MappedBytesStore first = mappedFile.acquireByteStore(test, 1);

            final int expected = MappedFile.RETAIN ? 2 : 1;
            assertEquals(expected, first.refCount());

            final MappedBytesStore second = mappedFile.acquireByteStore(test, 1 + chunkSize);

            assertEquals(expected, first.refCount());
            assertEquals(expected, second.refCount());

            final MappedBytesStore third = mappedFile.acquireByteStore(test, 1 + chunkSize + chunkSize);

            assertEquals(expected, first.refCount());
            assertEquals(expected, second.refCount());
            assertEquals(expected, third.refCount());

            third.release(test);
            second.release(test);
            first.release(test);
        }
    }

    @Test
    public void testReferenceCounts()
            throws IOException {
        /*        assumeFalse(Jvm.isMacArm());*/

        final File tmp = IOTools.createTempFile("testReferenceCounts");
        int chunkSize;
        if (OS.isWindows()) {
            chunkSize = 64;
        } else if (Jvm.isMacArm()) {
            chunkSize = 16;
        } else
            chunkSize = 4;
        chunkSize = chunkSize / 4 * PageUtil.getPageSize(tmp.getAbsolutePath());

        try (MappedFile mf = MappedFile.mappedFile(tmp, chunkSize, 0)) {
            assertEquals("refCount: 1", mf.referenceCounts());

            final ReferenceOwner test = ReferenceOwner.temporary("test");
            final MappedBytesStore bs = mf.acquireByteStore(test, chunkSize + (1 << 10));

            try {
                assertEquals(chunkSize, bs.start());
                assertEquals(chunkSize * 2, bs.capacity());
                final Bytes<?> bytes = bs.bytesForRead();

                assertNotNull(bytes.toString()); // show it doesn't blow up.
                assertNotNull(bs.toString()); // show it doesn't blow up.
                assertEquals(chunkSize, bytes.start());
                assertEquals(0L, bs.readLong(chunkSize + (1 << 10)));
                assertEquals(0L, bytes.readLong(chunkSize + (1 << 10)));
                Assert.assertFalse(bs.inside(chunkSize - (1 << 10)));
                Assert.assertFalse(bs.inside(chunkSize - 1));
                Assert.assertTrue(bs.inside(chunkSize));
                Assert.assertTrue(bs.inside(chunkSize * 2L - 1));
                Assert.assertFalse(bs.inside(chunkSize * 2L));
                try {
                    bytes.readLong(chunkSize - (1 << 10));
                    Assert.fail();
                } catch (BufferUnderflowException e) {
                    // expected
                }
                try {
                    bytes.readLong(chunkSize * 2L + (1 << 10));
                    Assert.fail();
                } catch (BufferUnderflowException e) {
                    // expected
                }
                assertEquals(1, mf.refCount());
                final int expected = MappedFile.RETAIN ? 2 : 1;
                assertEquals(expected + 1, bs.refCount());
                assertEquals("refCount: 1, 0, " + (expected + 1), mf.referenceCounts());

                final BytesStore<?, ?> bs2 = mf.acquireByteStore(test, chunkSize + (1 << 10), bs);
                assertSame(bs, bs2);
                assertEquals(expected + 1, bs2.refCount());
                assertEquals("refCount: 1, 0, " + (expected + 1), mf.referenceCounts());
                bytes.releaseLast();
                assertEquals(expected, bs2.refCount());
                assertEquals("refCount: 1, 0, " + expected, mf.referenceCounts());
            } finally {
                bs.release(test);
            }
        }
    }

    @Test
    public void largeReadOnlyFile()
            throws IOException {
        assumeFalse(Runtime.getRuntime().maxMemory() < Integer.MAX_VALUE || OS.isWindows());

        final File file = Files.createTempFile("largeReadOnlyFile", "deleteme").toFile();
        file.deleteOnExit();
        try (MappedBytes bytes = MappedBytes.mappedBytes(file, 1 << 30, OS.pageSize())) {
            bytes.writeLong(3L << 30, 0x12345678); // make the file 3 GB.
        }

        try (MappedBytes bytes = MappedBytes.readOnly(file)) {
            Assert.assertEquals(0x12345678L, bytes.readLong(3L << 30));
        }
    }

    @Test
    public void largeReadOnlyFileSingle()
            throws IOException {
        assumeFalse(OS.isWindows());
        assumeFalse(Runtime.getRuntime().maxMemory() < Integer.MAX_VALUE);

        final File file = Files.createTempFile("largeReadOnlyFile", "deleteme").toFile();
        file.deleteOnExit();
        try (MappedBytes bytes = MappedBytes.singleMappedBytes(file, 4L << 30)) {
            bytes.writeLong(3L << 30, 0x12345678); // make the file 3 GB.
        }

        try (MappedBytes bytes = MappedBytes.singleMappedBytes(file, 4L << 30, false)) {
            Assert.assertEquals(0x12345678L, bytes.readLong(3L << 30));
        }
    }

    @Test
    public void interrupted() throws Exception {
        ignoreException("/proc/self/mountinfo");
        Thread.currentThread().interrupt();
        final String filename = IOTools.createTempFile("interrupted").getAbsolutePath();
        try (MappedFile mf = MappedFile.mappedFile(filename, 64 << 10, 0)) {
            mf.actualSize();
            assertTrue(Thread.currentThread().isInterrupted());
        }
    }

    @Test
    public void testCreateMappedFile() throws Exception {
        final File file = IOTools.createTempFile("mappedFile");

        final MappedFile mappedFile = MappedFile.mappedFile(file, 1024, 256, 256, false);
        try {
            final MappedFile mappedFile2 = MappedFile.mappedFile(file, 1024, 256, 256, false);
            mappedFile2.releaseLast();
            assertTrue(mappedFile2.isClosed());
        } finally {
            mappedFile.releaseLast();
        }
        assertTrue(mappedFile.isClosed());
    }

    @Test
    public void testReadOnlyOpen()
            throws IOException {
        assumeFalse(OS.isWindows());

        String text = "Some text to put in this file. yay!\n";

        @NotNull File file = Files.createTempFile("readOnlyOpenFile", "deleteme").toFile();

        // write some stuff to a file so it exits using stock java APIs
        @NotNull OutputStreamWriter outWrite = new OutputStreamWriter(new FileOutputStream(file));
        outWrite.append(text);
        outWrite.flush();
        outWrite.close();

        byte[] tmp = new byte[1024 * 16];

        // Open and read the file w/ a MappedBytes to show it's readable
        try (@NotNull MappedBytes mapBuf = MappedBytes.readOnly(file)) {
            mapBuf.readLimit(file.length());
            int readLen = mapBuf.read(tmp, 0, tmp.length);
            assertEquals(text, new String(tmp, 0, readLen));
        }

        // open up the same file via a mapped file
        try (@NotNull MappedFile mapFile = MappedFile.mappedFile(file, OS.pageSize() * 16L, OS.pageSize(), true)) {
            // this throws a exception as of v2.20.9. it shouldn't
            ReferenceOwner temp = ReferenceOwner.temporary("TEMP");
            @NotNull Bytes<?> buf = mapFile.acquireBytesForRead(temp, 0);
            buf.readLimit(file.length());
            int readLen = buf.read(tmp, 0, tmp.length);
            assertEquals(text, new String(tmp, 0, readLen));
            buf.releaseLast(temp);
        }

        file.deleteOnExit();
    }

    @Test
    public void testSlicePositions()
            throws IOException {

        assumeFalse(OS.isWindows());

        final long chunkSize = OS.pageSize() * 64;
        final long overlapSize = OS.pageSize();

        // in the first chunk.
        final Long firstPos = chunkSize / 2;

        // in the first overlap
        final Long secondPos = chunkSize + (overlapSize / 2);

        // span the overlap and next chunk
        final Long thirdPos = chunkSize + overlapSize - 4;

        // need a file to read
        final File inFile = Files.createTempFile("testSlicePositions-inFile", "deleteme").toFile();
        inFile.deleteOnExit();

        try (MappedBytes bytes = MappedBytes.mappedBytes(inFile, chunkSize)) {
            // write in the middle of two different chunks
            bytes.writeLong(firstPos, 12345);
            bytes.writeLong(secondPos, 54321);
            bytes.writeLong(thirdPos, 87654);
            // set the file length
            bytes.writeLong(chunkSize * 4, 1010101);
        }

        try (MappedBytes root = MappedBytes.mappedBytes(inFile, chunkSize, overlapSize, true)) {

            // BUG 1 : must read first, or exception
            if(false) {
                assertEquals(12345, root.readLong(firstPos));
            }
            Bytes<Void> slice = root.bytesForRead();

            slice.readPositionRemaining(secondPos, 8);
            // BUG 2 : readPosion fails, PositionRemaining works
            if (false) {
                root.readPositionRemaining(thirdPos, 8);
            } else {
                root.readPosition(thirdPos);
            }
            long rootPos = root.readPosition();

            Assert.assertEquals(54321, slice.readLong());

            Assert.assertEquals(rootPos, root.readPosition());
            assertEquals(87654, root.readLong());

            slice.releaseLast();
        }

    }

    @After
    public void clearInterrupt() {
        Thread.interrupted();
    }
}
