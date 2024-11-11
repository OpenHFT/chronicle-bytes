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

import net.openhft.chronicle.bytes.internal.ChunkedMappedBytes;
import net.openhft.chronicle.bytes.internal.EmbeddedBytes;
import net.openhft.chronicle.core.Jvm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static net.openhft.chronicle.bytes.BytesFactoryUtil.*;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("deprecation")
final class BytesJavaDocComplianceTest extends BytesTestCommon {

    private static final Map<String, BytesInitialInfo> INITIAL_INFO_MAP = new LinkedHashMap<>();

    /**
     * Builds a Map with info on the initial state allowing us asserting it did not change for
     * illegal operations.
     * This method is using @BeforeEach rather than @BeforeAll so that the base class test can run
     */
    @BeforeEach
    void beforeEach() {
        if (INITIAL_INFO_MAP.isEmpty()) {
            AtomicInteger count = new AtomicInteger(0);
            provideBytesObjects()
                    .forEach(args -> {
                        count.incrementAndGet();
                        final Bytes<Object> bytes = bytes(args);
                        INITIAL_INFO_MAP.put(createCommand(args), new BytesInitialInfo(bytes));
                        releaseAndAssertReleased(bytes);
                        Jvm.pause(100);
                    });
            // Make sure we have unique keys
            assertEquals(count.get(), INITIAL_INFO_MAP.size());
        }
    }

    /**
     * Checks the Bytes::unchecked method
     */
    @ParameterizedTest
    @MethodSource("net.openhft.chronicle.bytes.BytesFactoryUtil#provideBytesObjects")
    void unchecked(final Bytes<?> bytes) {
        assertEquals(bytes.getClass().getSimpleName().contains("Unchecked"), bytes.unchecked());
        releaseAndAssertReleased(bytes);
    }

    /**
     * Checks the Bytes::readWrite method.
     */
    @ParameterizedTest
    @MethodSource("net.openhft.chronicle.bytes.BytesFactoryUtil#provideBytesObjects")
    void readWrite(final Bytes<?> bytes,
                   final boolean readWrite) {

        boolean writeable = false;
        try {
            bytes.writeByte((byte) 1);
            writeable = true;
        } catch (Exception ignored) {
            // We cannot write
        }
        // Checks if the actual writing ability corresponds to the reality
        assertEquals(readWrite, writeable);

        // Checks that bytes reflects this
        if (writeable != bytes.readWrite())
            assertEquals(writeable, bytes.readWrite());
        releaseAndAssertReleased(bytes);
    }

    /**
     * Checks that ByteBuffers that are read only cannot be wrapped.
     */
    @Test
    void wrapForReadCannotTakeReadOnlyByteBuffers() {
        final ByteBuffer bb = ByteBuffer.allocate(10).asReadOnlyBuffer();
        assertThrows(ReadOnlyBufferException.class, () ->
                Bytes.wrapForRead(bb)
        );
    }

    /**
     * Checks that ByteBuffers that are read only cannot be wrapped
     */
    @Test
    void wrapForWriteCannotTakeReadOnlyByteBuffers() {
        final ByteBuffer bb = ByteBuffer.allocate(10).asReadOnlyBuffer();
        assertThrows(ReadOnlyBufferException.class, () ->
                Bytes.wrapForWrite(bb)
        );
    }

    // Todo: Do some write operations so that we know we have content then try operations

    /**
     * Checks that methods with objet references that are annotated with @NotNull throw a NullPointerException when null is provided
     * and that no modification of the Bytes object's internal state is made in such cases.
     */
    @TestFactory
    Stream<DynamicTest> nonNullableOperators() {
        return cartesianProductTest(BytesFactoryUtil::provideBytesObjects,
                BytesJavaDocComplianceTest::provideThrowsMullPointerExceptionOperations,
                (args, bytes, nc) -> {
                    final String name = createCommand(args) + "->" + bytes(args).getClass().getSimpleName() + "." + nc.name();

                    assertThrows(NullPointerException.class, () -> {
                        try {
                            nc.accept(bytes);
                        } catch (IllegalArgumentException e) {
                            // @NotNull annotation processing might have been applied.
                            if (e.getMessage().endsWith("must not be null"))
                                throw new NullPointerException(e.getMessage());
                            throw e;
                        }
                    }, name);

                    if (isReadWrite(args)) {
                        if (!(ChunkedMappedBytes.class.isInstance(bytes))) {
                            // Unfortunately, inspecting a ChunkedMappedBytes may change its actualSize(), so no check there

                            if (bytes instanceof EmbeddedBytes) {
                                int foo = 1;
                            }

                            assertNeverWrittenTo(bytes);
                        }
                    }
                    assertPropertiesNotChanged(createCommand(args), bytes);
                }
        );
    }

    /**
     * Checks that methods with objet references that are annotated with @Nullable *does not* throw a NullPointerException when null is provided
     * and that the Bytes object's internal state is indeed modified.
     */
    @TestFactory
    Stream<DynamicTest> nullableOperators() {
        return cartesianProductTest(BytesFactoryUtil::provideBytesObjects,
                BytesJavaDocComplianceTest::provideNullableOperations,
                (args, bytes, nc) -> {
                    // Any content from a previous operation is cleared
                    bytes.clear();
                    // System.out.println(bytes.getClass().getSimpleName() + " " + isReadWrite(args)+", writePosition()="+bytes.writePosition());

                    if (isReadWrite(args)) {
                        assertDoesNotThrow(() -> nc.accept(bytes));
                        // Make sure something was written
                        assertNotEquals(0, bytes.readShort(0));
                    } else {
                        assertPropertiesNotChanged(createCommand(args), bytes);
                    }
                }
        );
    }

    private static Stream<NamedConsumer<Bytes<Object>>> provideThrowsMullPointerExceptionOperations() {

        // In some of the operations, we could use 1 as a parameter meaning some optimized versions (like unchecked)
        // will work with no explicit requireNonNull() but this is not strictly correct from a testing perspective

        return Stream.of(
                // Write operations with reference parameters
                NamedConsumer.ofThrowing(bytes -> bytes.write((InputStream) null), "write(InputStream)"),
                NamedConsumer.of(bytes -> bytes.writeMarshallableLength16(null), "writeMarshallableLength16()"),
                NamedConsumer.of(bytes -> bytes.write(0L, (byte[]) null, 0, 0), "write(long, byte[], int, int)"),
                NamedConsumer.of(bytes -> bytes.write((byte[]) null, 0, 0), "write(byte[], int, int)"),
                NamedConsumer.of(bytes -> bytes.write((byte[]) null), "write(byte[])"),
                NamedConsumer.of(bytes -> bytes.write(1L, (byte[]) null), "write(long, byte[])"),
                NamedConsumer.of(bytes -> bytes.write((CharSequence) null, 0, 0), "write(CharSequence, int, int)"),
                NamedConsumer.of(bytes -> bytes.write((CharSequence) null), "write(CharSequence)"),
                NamedConsumer.of(bytes -> bytes.write(1L, null, 0L, 0L), "write(long, BytesStore, long, long)"),
                NamedConsumer.of(bytes -> bytes.write(null, 0L, 0L), "write(BytesStore, long, long)"),
                NamedConsumer.of(bytes -> bytes.write((BytesStore<?, ?>) null), "write(BytesStore)"),
                NamedConsumer.of(bytes -> bytes.write(0L, (BytesStore<?, ?>) null), "write(long, BytesStore)"),
                NamedConsumer.of(bytes -> bytes.write(0L, null, 0L, 0L), "write(long, RandomDataInput, long, long)"),
                NamedConsumer.of(bytes -> bytes.write8bit((CharSequence) null, 0, 0), "write8bit(CharSequence, int, int)"),
                NamedConsumer.of(bytes -> bytes.write8bit(null, 0, 0), "write8bit(String, int, int)"),
                NamedConsumer.of(bytes -> bytes.write8bit(0L, null), "write8bit(long, BytesStore)"),
                NamedConsumer.of(bytes -> bytes.write8bit(0L, null, 0, 0), "write8bit(long, String, int, int)"),
                NamedConsumer.of(bytes -> bytes.writeBigDecimal(null), "writeBigDecimal()"),
                NamedConsumer.of(bytes -> bytes.writeBigInteger(null), "writeBigInteger()"),
                NamedConsumer.of(bytes -> bytes.writeHistogram(null), "writeHistogram()"),
                NamedConsumer.of(bytes -> bytes.writeSome(null), "writeSome(ByteBuffer)"),
                NamedConsumer.of(bytes -> bytes.writeEnum((MyEnum) null), "writeEnum()"),
                // Read operations with reference parameters

                NamedConsumer.of(bytes -> bytes.read((ByteBuffer) null), "read(ByteBuffer)"),
                NamedConsumer.of(bytes -> bytes.read((Bytes<?>) null), "read(Bytes)"),
                NamedConsumer.of(bytes -> bytes.read(null, 0), "read(Bytes, int)"),
                NamedConsumer.of(bytes -> bytes.read((byte[]) null), "read(byte[], int)"),
                NamedConsumer.of(bytes -> bytes.read((byte[]) null, 0, 0), "read(byte[], int, int)"),
                NamedConsumer.of(bytes -> bytes.read((char[]) null, 0, 0), "read(char[], int, int)"),
                NamedConsumer.of(bytes -> bytes.read(0, null, 0, 0), "read(long, byte[], int, int)"),
                NamedConsumer.of(bytes -> bytes.readObject(null), "readObject(Class)"),
                NamedConsumer.of(bytes -> bytes.readMarshallableLength16(null, null), "readMarshallableLength16(Class, Object)"),
                NamedConsumer.of(bytes -> bytes.readUtf8Limited(0, null, 0), "readUtf8Limited(long, ACS, int)"),
                NamedConsumer.of(bytes -> bytes.readUtf8(0, null), "readUtf8Limited(long, ACS)"),
                NamedConsumer.of(bytes -> bytes.readUtf8((Appendable & CharSequence) null), "readUtf8(ACS)"),
                NamedConsumer.of(bytes -> bytes.readUtf8((StringBuilder) null), "readUtf8(StringBuilder)"),
                NamedConsumer.of(bytes -> bytes.readUtf8((Bytes<?>) null), "readUtf8(Bytes)")

                // readHistogram

                // Todo: add unsafe...
                // Todo: add read operations
                // Todo: add prewrite
        );
    }

    private static Stream<NamedConsumer<Bytes<Object>>> provideNullableOperations() {
        return Stream.of(
                NamedConsumer.of(bytes -> bytes.writeUtf8((CharSequence) null), "writeUtf8(CharSequence)"),
                NamedConsumer.of(bytes -> bytes.writeUtf8(null), "writeUtf8(String)"),
                NamedConsumer.of(bytes -> bytes.writeUtf8(0L, null), "writeUtf8(long, CharSequence)"),
                NamedConsumer.of(bytes -> bytes.writeUtf8Limited(0L, null, 0), "writeUtf8Limited(long, CharSequence, int)"),
                NamedConsumer.of(bytes -> bytes.write8bit(null), "write8bit(String)"),
                NamedConsumer.of(bytes -> bytes.write8bit((CharSequence) null), "write8bit(CharSequence)")
        );
    }

    enum MyEnum {INSTANCE}

    private void assertPropertiesNotChanged(String createCommand, Bytes<?> bytes) {
        // Make sure that there was no change to the target bytes
        final BytesInitialInfo expectedInfo = INITIAL_INFO_MAP.get(createCommand);
        final BytesInitialInfo actualInfo = new BytesInitialInfo(bytes);
        assertEquals(expectedInfo, actualInfo, createCommand);
    }
}
