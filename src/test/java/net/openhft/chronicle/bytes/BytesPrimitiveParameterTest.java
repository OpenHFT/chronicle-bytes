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

import net.openhft.chronicle.core.io.IORuntimeException;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static net.openhft.chronicle.bytes.BytesFactoryUtil.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests if certain methods with constraints on primitive method parameters work as expected.
 */
@SuppressWarnings("deprecation")
final class BytesPrimitiveParameterTest { // too hard to ensure resources are released

    private static final String SILLY_NAME = "Tryggve";

    /**
     * Checks that methods throws IllegalArgumentException if negative parameters are provided for @NonNegative
     */
    @TestFactory
    Stream<DynamicTest> negativeParameters() {
        final AtomicReference<BytesInitialInfo> initialInfo = new AtomicReference<>();
        return cartesianProductTest(BytesFactoryUtil::provideBytesObjects,
                BytesPrimitiveParameterTest::provideNegativeNonNegativeOperations,
                (args, bytes, nc) -> {
                    if (UncheckedBytes.class.isInstance(bytes))
                        // UncheckedBytes is... Well, unchecked
                        return;
                    final String name = createCommand(args) + "->" + bytes(args).getClass().getSimpleName() + "." + nc.name();
                    if (bytes.writePosition() == 0) {
                        if (isReadWrite(args)) {
                            System.out.println("Initializing: " + name + " " + bytes.getClass().getSimpleName());
                            bytes.write(SILLY_NAME);
                        }
                    }
                    initialInfo.set(new BytesInitialInfo(bytes));

                    try {
                        nc.accept(bytes);
                    } catch (IllegalArgumentException |
                             BufferOverflowException |
                             BufferUnderflowException |
                             AssertionError |
                             UnsupportedOperationException |
                             StringIndexOutOfBoundsException |
                             IORuntimeException accepted) {
                        // Although strictly not correct, we accept these Exceptions/Errors for neg-args
                    } catch (Throwable t) {
                        if (!(t instanceof IOException))
                            fail(name, t);
                    }

                    // Unable to check actual size for released MappedBytes
                    if (!MappedBytes.class.isInstance(bytes)) {
                        final BytesInitialInfo info = new BytesInitialInfo(bytes);
                        assertEquals(initialInfo.get(), info, name);
                    }
                    if (isReadWrite(args)) {
                        // Make sure nothing changes in the contents of the Bytes object
                        if (!SILLY_NAME.equals(bytes.toString())) {
                            System.out.println(bytes.getClass().getSimpleName() + ", b: " + Arrays.toString(bytes.toString().getBytes(StandardCharsets.UTF_8)));
                        }
                        assertEquals(SILLY_NAME, bytes.toString(), name);
                    }
                }
        );
    }

    private static Stream<NamedConsumer<Bytes<Object>>> provideNegativeNonNegativeOperations() {
        final OutputStream os = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new UnsupportedEncodingException();
            }
        };
        final BytesStore<?, ?> bs = BytesStore.from(SILLY_NAME);
        final Bytes<?> bytes = Bytes.from(SILLY_NAME);
        final ByteBuffer bb = ByteBuffer.allocate(10);
        return Stream.of(

                NamedConsumer.of(b -> b.write(-1, new byte[1]), "write(-1, new byte[1])"),
                NamedConsumer.of(b -> b.write(new byte[1], -1, 1), "write(new byte[1], -1, 1)"),
                NamedConsumer.of(b -> b.write(new byte[1], 1, -1), "write(new byte[1], 1, -1)"),
                NamedConsumer.of(b -> b.write(-1, new byte[1], 1, 1), "write(-1, new byte[1], 1, -1)"),
                NamedConsumer.of(b -> b.write(1, new byte[1], -1, 1), "write(1, new byte[1], -1, 1)"),
                NamedConsumer.of(b -> b.write(1, new byte[1], 1, -1), "write(1, new byte[1], 1, -1)"),
                NamedConsumer.of(b -> b.write(-1, bs), "write(-1, bs)"),
                NamedConsumer.of(b -> b.write(bs, -1L, 1L), "write(bs, -1L, 1L)"),
                NamedConsumer.of(b -> b.write(bs, 1L, -1L), "write(bs, 1L, -1L)"),
                NamedConsumer.of(b -> b.write(-1, bs, 1, 1), "write(-1, bs, 1, -1)"),
                NamedConsumer.of(b -> b.write(1, bs, -1, 1), "write(1, bs, -1, 1)"),
                NamedConsumer.of(b -> b.write(1, bs, 1, -1), "write(1, bs, 1, -1)"),
                NamedConsumer.of(b -> b.write(1, bs, 1, -1), "write(1, bs, 1, -1)"),
                NamedConsumer.of(b -> b.write(SILLY_NAME, -1, 1), "write(cs, -1, 1)"),
                NamedConsumer.of(b -> b.write(SILLY_NAME, 1, -1), "write(cs, -1, 1)"),
                NamedConsumer.of(b -> b.writeUtf8(-1, SILLY_NAME), "writeUtf8(-1, cs)"),
                NamedConsumer.of(b -> b.writeUtf8Limited(-1, SILLY_NAME, SILLY_NAME.length()), "writeUtf8Limited(-1, cs, 1)"),
                NamedConsumer.of(b -> b.writeUtf8Limited(1, SILLY_NAME, -1), "writeUtf8Limited(1, cs, -1)"),
                NamedConsumer.of(b -> b.write8bit(-1, bs), "write8bit(-1, bs)"),
                NamedConsumer.of(b -> b.write8bit(-1, SILLY_NAME, 1, 1), "write8bit(-1, cs, 1, 1)"),
                NamedConsumer.of(b -> b.write8bit(1, SILLY_NAME, -1, 1), "write8bit(1, cs, -1, 1)"),
                NamedConsumer.of(b -> b.write8bit(1, SILLY_NAME, 1, -1), "write8bit(1, cs, 1, -1)"),
                NamedConsumer.of(b -> b.write8bit(SILLY_NAME, -1, 1), "write8bit(1, cs, -1, 1)"),
                NamedConsumer.of(b -> b.write8bit(SILLY_NAME, 1, -1), "write8bit(1, cs, 1, -1)"),

                NamedConsumer.of(b -> b.writeByte(-1L, 42), "writeByte(-1, int)"),
                NamedConsumer.of(b -> b.writeByte(-1L, (byte) 42), "writeByte(-1, byte)"),
                NamedConsumer.of(b -> b.writeVolatileByte(-1L, (byte) 42), "writeVolatileByte(-1, byte)"),
                NamedConsumer.of(b -> b.writeUnsignedByte(-1L, (byte) 42), "writeUnsignedByte(-1, byte)"),

                NamedConsumer.of(b -> b.writeShort(-1L, (short) 42), "writeShort(-1, byte)"),
                NamedConsumer.of(b -> b.writeUnsignedShort(-1, 42), "writeUnsignedShort(-1, 42)"),
                NamedConsumer.of(b -> b.writeVolatileShort(-1, (short) 42), "writeVolatileShort(-1, 42)"),

                NamedConsumer.of(b -> b.writeInt(-1L, 42), "writeInt(-1, byte)"),
                NamedConsumer.of(b -> b.writeInt24(-1L, 42), "writeInt24(-1, byte)"),
                NamedConsumer.of(b -> b.writeIntAdv(42, -1), "writeInt24(42, -1)"),
                NamedConsumer.of(b -> b.writeOrderedInt(-1, 42), "writeOrderedInt(-1, 42)"),
                NamedConsumer.of(b -> b.writeUnsignedInt(-1, 42), "writeUnsignedInt(-1, 42)"),
                NamedConsumer.of(b -> b.writeVolatileInt(-1, 42), "writeVolatileInt(-1, 42)"),

                NamedConsumer.of(b -> b.writeLong(-1L, 42), "writeLong(-1, byte)"),
                NamedConsumer.of(b -> b.writeLongAdv(42, -1), "writeLongAdv(42, -1)"),
                NamedConsumer.of(b -> b.writeOrderedLong(-1, 42), "writeOrderedLong(-1, 42)"),
                NamedConsumer.of(b -> b.writeMaxLong(-1, 42), "writeMaxLong(-1, 42)"),
                NamedConsumer.of(b -> b.writeVolatileLong(-1, 42), "writeVolatileLong(-1, 42)"),

                NamedConsumer.of(b -> b.writeFloat(-1L, 42), "writeFloat(-1, byte)"),
                NamedConsumer.of(b -> b.writeOrderedFloat(-1, 42), "writeOrderedFloat(-1, 42)"),
                NamedConsumer.of(b -> b.writeVolatileFloat(-1, 42), "writeVolatileFloat(-1, 42)"),

                NamedConsumer.of(b -> b.writeDouble(-1L, 42), "writeDouble(-1, byte)"),
                NamedConsumer.of(b -> b.writeOrderedDouble(-1, 42), "writeOrderedDouble(-1, 42)"),
                NamedConsumer.of(b -> b.writeVolatileDouble(-1, 42), "writeVolatileDouble(-1, 42)"),

                NamedConsumer.of(b -> b.writeLimit(-1), "writeLimit(-1)"),
                NamedConsumer.of(b -> b.writePositionRemaining(-1, 1), "writePositionRemaining(-1)"),
                NamedConsumer.of(b -> b.writePositionRemaining(1, -1), "writePositionRemaining(-1)"),

                NamedConsumer.of(b -> b.addressForWrite(-1), "addressForWrite(-1)"),

                NamedConsumer.of(b -> b.writePosition(-1), "writePosition(-1)")

        );
    }

    // The stream below represents operations that are not checked for reasons specified
    private static Stream<NamedConsumer<Bytes<Object>>> provideNegativeNonNegativeOperationsOtherException() {
        final OutputStream os = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new UnsupportedEncodingException();
            }
        };
        final BytesStore<?, ?> bs = BytesStore.from(SILLY_NAME);
        final Bytes<?> bytes = Bytes.from(SILLY_NAME);
        final ByteBuffer bb = ByteBuffer.allocate(10);
        return Stream.of(
                // Acceptable: This will produce an Exception but not an IllegalArgumentException.
                NamedConsumer.of(b -> b.writePosition(-1), "writePosition(-1)")

        );
    }
}
