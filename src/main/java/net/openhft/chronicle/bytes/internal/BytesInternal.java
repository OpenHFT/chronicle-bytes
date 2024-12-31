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

import net.openhft.chronicle.bytes.*;
import net.openhft.chronicle.bytes.util.DecoratedBufferOverflowException;
import net.openhft.chronicle.bytes.util.DecoratedBufferUnderflowException;
import net.openhft.chronicle.bytes.util.StringInternerBytes;
import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.Maths;
import net.openhft.chronicle.core.Memory;
import net.openhft.chronicle.core.UnsafeMemory;
import net.openhft.chronicle.core.annotation.NonNegative;
import net.openhft.chronicle.core.io.*;
import net.openhft.chronicle.core.pool.ClassAliasPool;
import net.openhft.chronicle.core.pool.EnumInterner;
import net.openhft.chronicle.core.pool.StringBuilderPool;
import net.openhft.chronicle.core.scoped.ScopedResource;
import net.openhft.chronicle.core.scoped.ScopedResourcePool;
import net.openhft.chronicle.core.scoped.ScopedThreadLocal;
import net.openhft.chronicle.core.util.Histogram;
import net.openhft.chronicle.core.util.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UTFDataFormatException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static net.openhft.chronicle.assertions.AssertUtil.SKIP_ASSERTIONS;
import static net.openhft.chronicle.bytes.StreamingDataOutput.JAVA9_STRING_CODER_LATIN;
import static net.openhft.chronicle.bytes.StreamingDataOutput.JAVA9_STRING_CODER_UTF16;
import static net.openhft.chronicle.bytes.internal.ReferenceCountedUtil.throwExceptionIfReleased;
import static net.openhft.chronicle.core.UnsafeMemory.MEMORY;
import static net.openhft.chronicle.core.io.ReferenceOwner.temporary;
import static net.openhft.chronicle.core.util.Longs.requireNonNegative;
import static net.openhft.chronicle.core.util.ObjectUtils.requireNonNull;
import static net.openhft.chronicle.core.util.StringUtils.*;

/**
 * Utility methods to support common functionality in this package. This is not intended to be
 * accessed directly.
 */
@SuppressWarnings("rawtypes")
public
enum BytesInternal {

    ; // none
    private static final int THREAD_LOCAL_BYTES_POOL_SIZE = Jvm.getInteger("bytesInternal.bytes.instancesPerThread", 2);
    public static final ThreadLocal<ByteBuffer> BYTE_BUFFER_TL = ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(0));
    public static final ThreadLocal<ByteBuffer> BYTE_BUFFER2_TL = ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(0));
    public static final ScopedThreadLocal<Bytes<?>> BYTES_SCOPED_THREAD_LOCAL = new ScopedThreadLocal<>(
            () -> {
                Bytes<?> bbb = Bytes.allocateElasticDirect(256);
                IOTools.unmonitor(bbb);
                return bbb;
            },
            Bytes::clear,
            THREAD_LOCAL_BYTES_POOL_SIZE);
    public static final StringInternerBytes SI;
    static final char[] HEXADECIMAL = "0123456789abcdef".toCharArray();
    private static final String INFINITY = "Infinity";
    private static final String NAN = "NaN";
    private static final String MALFORMED_INPUT_PARTIAL_CHARACTER_AT_END = "malformed input: partial character at end";
    private static final String MALFORMED_INPUT_AROUND_BYTE = "malformed input around byte ";
    private static final String WAS = " was ";
    private static final String CAN_T_PARSE_FLEXIBLE_LONG_WITHOUT_PRECISION_LOSS = "Can't parse flexible long without precision loss: ";
    private static final byte[] MIN_VALUE_TEXT = ("" + Long.MIN_VALUE).getBytes(ISO_8859_1);
    private static final ScopedResourcePool<StringBuilder> STRING_BUILDER_SCOPED_RESOURCE_POOL = StringBuilderPool.createThreadLocal();
    private static final byte[] INFINITY_BYTES = INFINITY.getBytes(ISO_8859_1);
    private static final byte[] NAN_BYTES = NAN.getBytes(ISO_8859_1);
    private static final long MAX_VALUE_DIVIDE_5 = Long.MAX_VALUE / 5;
    private static final long MAX_VALUE_DIVIDE_10 = Long.MAX_VALUE / 10;
    private static final ThreadLocal<DateCache> dateCacheTL = new ThreadLocal<>();
    private static final int MAX_STRING_LEN = Jvm.getInteger("bytes.max-string-len", 128 * 1024);
    private static final int NEG_ONE = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? 0x80 : 0x8000;

    private static final MethodHandle VECTORIZED_MISMATCH_METHOD_HANDLE;
    private static final ThreadLocal<byte[]> BYTE_ARRAY_TL = ThreadLocal.withInitial(() -> new byte[20]);

    static {
        try {
            SI = new StringInternerBytes(Jvm.getInteger("wire.string-interner.size", 4096));
            ClassAliasPool.CLASS_ALIASES.addAlias(BytesStore.class, "!binary");
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        MethodHandle vectorizedMismatchMethodHandle = null;
        try {
            if (Jvm.isJava9Plus() && !Jvm.getBoolean("disable.vectorized.content_equals")) {
                final Class<?> arraysSupportClass = Class.forName("jdk.internal.util.ArraysSupport");
                final Method vectorizedMismatch = Jvm.getMethod(arraysSupportClass, "vectorizedMismatch",
                        Object.class,
                        long.class,
                        Object.class,
                        long.class,
                        int.class,
                        int.class);

                vectorizedMismatch.setAccessible(true);
                vectorizedMismatchMethodHandle = MethodHandles.lookup().unreflect(vectorizedMismatch);
            }
        } catch (Exception e) {
            if (e.getClass().getName().equals("java.lang.reflect.InaccessibleObjectException"))
                Jvm.debug().on(BytesInternal.class, "Cannot get access to vectorizedMismatch. The following command line args are required: " +
                        "--illegal-access=permit --add-exports java.base/jdk.internal.ref=ALL-UNNAMED --add-exports java.base/jdk.internal.util=ALL-UNNAMED" +
                        ". exception: " + e);
            else
                Jvm.warn().on(BytesInternal.class, e);
        } finally {
            VECTORIZED_MISMATCH_METHOD_HANDLE = vectorizedMismatchMethodHandle;
        }
    }

    public static boolean contentEqual(@Nullable final BytesStore<?, ?> a,
                                       @Nullable final BytesStore<?, ?> b) throws ClosedIllegalStateException {
        if (a == null) return b == null;
        if (b == null) {
            // The contract stipulates that if either ByteStores are closed then we throw an Exception
            throwExceptionIfReleased(a);
            return false;
        }
        throwExceptionIfReleased(a);
        throwExceptionIfReleased(b);
        final long readRemaining = a.readRemaining();
        if (readRemaining != b.readRemaining())
            // The size is different so, we know that a and b cannot be equal
            return false;

        if (VECTORIZED_MISMATCH_METHOD_HANDLE != null
                && b.realReadRemaining() == a.realReadRemaining()
                && a.realReadRemaining() < Integer.MAX_VALUE
                && a.realReadRemaining() > 7
                && !(a instanceof HexDumpBytes) && !(b instanceof HexDumpBytes)) {

            // this will use AVX instructions, this is very fast; much faster than a handwritten loop.
            try {
                Boolean vectorizedResult = java11ContentEqualUsingVectorizedMismatch(a, b);
                if (vectorizedResult != null)
                    return vectorizedResult;
            } catch (UnsupportedOperationException e) {
                Jvm.warn().on(BytesInternal.class, e);
            }
        }

        return readRemaining <= Integer.MAX_VALUE
                ? contentEqualInt(a, b)
                : contentEqualsLong(a, b);
    }

    /**
     * returns true if the contents are equal using VectorizedMismatch*
     *
     * @param left  the byte on the left
     * @param right the byte on the right
     * @return true if the content are equal
     * see <a href="https://bugs.java.com/bugdatabase/view_bug.do?bug_id=8136924">...</a>
     * JDK-8033148 will add methods to Arrays for array equals, compare and mismatch.
     * The implementations of equals, compare and mismatch can be reimplemented using underlying mismatch methods that in turn defer to a single method, vectorizedMismatch, that accesses the memory contents of arrays using Unsafe.getLongUnaligned.
     * The vectorizedMismatch implementation can be optimized efficiently by C2 to obtain an approximate 8x speed up when performing a mismatch on byte[] arrays (of a suitable size to overcome fixed costs).
     * The contract of vectorizedMismatch is simple enough that it can be made an intrinsic (see JDK-8044082) and leverage SIMDs instructions to perform operations up to a width of say 512 bits on supported architectures. Thus even further performance improvements may be possible.
     */
    private static Boolean java11ContentEqualUsingVectorizedMismatch(@NotNull final BytesStore<?, ?> left,
                                                                     @NotNull final BytesStore<?, ?> right) {
        try {
            final Object leftObject;
            final long leftOffset;

            if (left.isDirectMemory()) {
                leftObject = null;
                leftOffset = left.addressForRead(left.readPosition());
            } else {
                BytesStore<?, ?> bytesStore = left.bytesStore();
                if (!(bytesStore instanceof HeapBytesStore))
                    return null;

                HeapBytesStore heapBytesStore = (HeapBytesStore) bytesStore;
                leftObject = heapBytesStore.realUnderlyingObject();
                leftOffset = heapBytesStore.dataOffset() + left.readPosition();
            }

            final Object rightObject;
            final long rightOffset;

            if (right.isDirectMemory()) {
                rightObject = null;
                rightOffset = right.addressForRead(right.readPosition());
            } else {
                BytesStore<?, ?> bytesStore = right.bytesStore();
                if (!(bytesStore instanceof HeapBytesStore))
                    return null;

                HeapBytesStore heapBytesStore = (HeapBytesStore) bytesStore;
                rightObject = heapBytesStore.realUnderlyingObject();
                rightOffset = heapBytesStore.dataOffset() + right.readPosition();
            }

            final int length = (int) left.realReadRemaining();
            final int invoke = (int) VECTORIZED_MISMATCH_METHOD_HANDLE.invoke(leftObject,
                    leftOffset,
                    rightObject,
                    rightOffset,
                    length,
                    0);

            if (invoke >= 0)
                return Boolean.FALSE;

            int remaining = length - ~invoke;

            for (; remaining < length; remaining++) {
                if (left.readByte(left.readPosition() + remaining) !=
                        right.readByte(right.readPosition() + remaining)) {
                    return Boolean.FALSE;
                }
            }

            return Boolean.TRUE;
        } catch (Throwable e) {
            Jvm.warn().on(BytesInternal.class, e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    // Optimise for the common case where the length is 31-bit.
    static <U extends BytesStore<?, ?> & HasUncheckedRandomDataInput>
    boolean contentEqualInt(@NotNull final BytesStore<?, ?> a,
                            @NotNull final BytesStore<?, ?> b) throws ClosedIllegalStateException {

        final int aLength = (int) a.realReadRemaining();
        final int bLength = (int) b.realReadRemaining();
        if (a instanceof HasUncheckedRandomDataInput && b instanceof HasUncheckedRandomDataInput) {
            // Make sure a >= b
            if (aLength < bLength)
                return contentEqualIntUnchecked((U) b, (U) a, bLength, aLength);
            else
                return contentEqualIntUnchecked((U) a, (U) b, aLength, bLength);
        } else {
            // Make sure a >= b
            if (aLength < bLength)
                return contentEqualInt(b, a, bLength, aLength);
            else
                return contentEqualInt(a, b, aLength, bLength);
        }
    }

    // a >= b here and we also know it is safe to read bLength
    static boolean contentEqualInt(@NotNull final BytesStore<?, ?> a,
                                   @NotNull final BytesStore<?, ?> b,
                                   @NonNegative final int aLength,
                                   @NonNegative final int bLength) throws ClosedIllegalStateException {
        assert SKIP_ASSERTIONS || aLength >= bLength;
        final long aPos = a.readPosition();
        final long bPos = b.readPosition();

        int i;
        for (i = 0; i < bLength - 7; i += 8) {
            if (a.readLong(aPos + i) != b.readLong(bPos + i))
                return false;
        }
        for (; i < bLength; i++) {
            if (a.readByte(aPos + i) != b.readByte(bPos + i))
                return false;
        }
        // check for zeros
        for (; i < aLength - 7; i += 8) {
            if (a.readLong(aPos + i) != 0L)
                return false;
        }
        for (; i < aLength; i++) {
            if (a.readByte(aPos + i) != 0)
                return false;
        }

        return true;
    }

    // a >= b here and we also know it is safe to read bLength
    static <U extends BytesStore<?, ?> & HasUncheckedRandomDataInput>
    boolean contentEqualIntUnchecked(@NotNull final U a,
                                     @NotNull final U b,
                                     @NonNegative final int aLength,
                                     @NonNegative final int bLength) throws ClosedIllegalStateException {
        assert SKIP_ASSERTIONS || aLength >= bLength;
        final UncheckedRandomDataInput ua = a.acquireUncheckedInput();
        final UncheckedRandomDataInput ub = b.acquireUncheckedInput();
        final long aPos = a.readPosition();
        final long bPos = b.readPosition();

        int i;
        for (i = 0; i < bLength - 7; i += 8) {
            if (ua.readLong(aPos + i) != ub.readLong(bPos + i))
                return false;
        }
        for (; i < bLength; i++) {
            if (ua.readByte(aPos + i) != ub.readByte(bPos + i))
                return false;
        }
        // check for zeros
        for (; i < aLength - 7; i += 8) {
            if (ua.readLong(aPos + i) != 0L)
                return false;
        }
        for (; i < aLength; i++) {
            if (ua.readByte(aPos + i) != 0)
                return false;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    static <U extends BytesStore<?, ?> & HasUncheckedRandomDataInput>
    boolean contentEqualsLong(@NotNull final BytesStore<?, ?> a,
                              @NotNull final BytesStore<?, ?> b) {
        final long aLength = a.realReadRemaining();
        final long bLength = b.realReadRemaining();
        if (a instanceof HasUncheckedRandomDataInput && b instanceof HasUncheckedRandomDataInput) {
            // Make sure a >= b
            if (a.realCapacity() < b.realCapacity())
                return contentEqualsLongUnchecked((U) b, (U) a, bLength, aLength);
            else
                return contentEqualsLongUnchecked((U) a, (U) b, aLength, bLength);
        } else {
            // Make sure a >= b
            if (a.realCapacity() < b.realCapacity())
                return contentEqualsLong(b, a, bLength, aLength);
            else
                return contentEqualsLong(a, b, aLength, bLength);
        }
    }

    // a >= b here and we also know it is safe to read bLength
    private static boolean contentEqualsLong(@NotNull final BytesStore<?, ?> a,
                                             @NotNull final BytesStore<?, ?> b,
                                             @NonNegative final long aLength,
                                             @NonNegative final long bLength) throws ClosedIllegalStateException {
        assert SKIP_ASSERTIONS || aLength >= bLength;
        // assume a >= b
        long aPos = a.readPosition();
        long bPos = b.readPosition();

        long i;
        for (i = 0; i < bLength - 7; i += 8) {
            if (a.readLong(aPos + i) != b.readLong(bPos + i))
                return false;
        }
        for (; i < bLength; i++) {
            if (a.readByte(aPos + i) != b.readByte(bPos + i))
                return false;
        }
        // check for zeros
        for (; i < aLength - 7; i += 8) {
            if (a.readLong(aPos + i) != 0L)
                return false;
        }
        for (; i < aLength; i++) {
            if (a.readByte(aPos + i) != 0)
                return false;
        }
        return true;
    }

    // a >= b here and we also know it is safe to read bLength
    private static <U extends BytesStore<?, ?> & HasUncheckedRandomDataInput>
    boolean contentEqualsLongUnchecked(@NotNull final U a,
                                       @NotNull final U b,
                                       @NonNegative final long aLength,
                                       @NonNegative final long bLength) {
        assert SKIP_ASSERTIONS || aLength >= bLength;

        final UncheckedRandomDataInput ua = a.acquireUncheckedInput();
        final UncheckedRandomDataInput ub = b.acquireUncheckedInput();

        // assume a >= b
        long aPos = a.readPosition();
        long bPos = b.readPosition();

        long i;
        for (i = 0; i < bLength - 7; i += 8) {
            if (ua.readLong(aPos + i) != ub.readLong(bPos + i))
                return false;
        }
        for (; i < bLength; i++) {
            if (ua.readByte(aPos + i) != ub.readByte(bPos + i))
                return false;
        }
        // check for zeros
        for (; i < aLength - 7; i += 8) {
            if (ua.readLong(aPos + i) != 0L)
                return false;
        }
        for (; i < aLength; i++) {
            if (ua.readByte(aPos + i) != 0)
                return false;
        }
        return true;
    }

    public static boolean startsWith(@NotNull BytesStore<?, ?> a, @NotNull BytesStore<?, ?> b)
            throws ClosedIllegalStateException {
        throwExceptionIfReleased(a);
        throwExceptionIfReleased(b);
        final long bRealReadRemaining = b.realReadRemaining();
        if (a.realReadRemaining() < bRealReadRemaining)
            return false;

        return startsWith(a, b, a.readPosition(), b.readPosition(), bRealReadRemaining);
    }

    public static <U extends BytesStore<?, ?> & HasUncheckedRandomDataInput>
    boolean startsWithUnchecked(@NotNull final U a,
                                @NotNull final BytesStore<?, ?> b) {
        throwExceptionIfReleased(a);
        throwExceptionIfReleased(b);
        final long bRealReadRemaining = b.realReadRemaining();
        if (a.realReadRemaining() < bRealReadRemaining) {
            return false;
        }

        if (b instanceof HasUncheckedRandomDataInput) {
            // We have hoisted out boundary checks in this path
            return startsWithUnchecked(a.acquireUncheckedInput(),
                    ((HasUncheckedRandomDataInput) b).acquireUncheckedInput(),
                    a.readPosition(),
                    b.readPosition(),
                    bRealReadRemaining);
        } else {
            return startsWith(a, b, a.readPosition(), b.readPosition(), bRealReadRemaining);
        }
    }

    private static boolean startsWithUnchecked(@NotNull final UncheckedRandomDataInput ua,
                                               @NotNull final UncheckedRandomDataInput ub,
                                               @NonNegative final long aPos,
                                               @NonNegative final long bPos,
                                               @NonNegative final long length) {
        int i;
        for (i = 0; i < length - 7; i += 8) {
            if (ua.readLong(aPos + i) != ub.readLong(bPos + i))
                return false;
        }
        if (i < length - 3) {
            if (ua.readInt(aPos + i) != ub.readInt(bPos + i))
                return false;
            i += 4;
        }
        if (i < length - 1) {
            if (ua.readShort(aPos + i) != ub.readShort(bPos + i))
                return false;
            i += 2;
        }
        if (i < length) {
            return ua.readByte(aPos + i) == ub.readByte(bPos + i);
        }
        return true;
    }

    private static boolean startsWith(@NotNull final BytesStore<?, ?> a,
                                      @NotNull final BytesStore<?, ?> b,
                                      @NonNegative final long aPos,
                                      @NonNegative final long bPos,
                                      @NonNegative final long length) {
        int i;
        for (i = 0; i < length - 7; i += 8) {
            if (a.readLong(aPos + i) != b.readLong(bPos + i))
                return false;
        }
        if (i < length - 3) {
            if (a.readInt(aPos + i) != b.readInt(bPos + i))
                return false;
            i += 4;
        }
        if (i < length - 1) {
            if (a.readShort(aPos + i) != b.readShort(bPos + i))
                return false;
            i += 2;
        }
        if (i < length) {
            return a.readByte(aPos + i) == b.readByte(bPos + i);
        }
        return true;
    }

    public static void parseUtf8(
            @NotNull StreamingDataInput bytes, Appendable appendable, boolean utf, @NonNegative int length)
            throws UTFDataFormatRuntimeException, BufferUnderflowException, ClosedIllegalStateException {
        throwExceptionIfReleased(bytes);
        // Because Bytes implements Appendable, we need to check if it might be closed
        throwExceptionIfReleased(appendable);
        if (appendable instanceof StringBuilder
                && bytes.isDirectMemory()
                && length < 1 << 20
                && utf) {
            // todo fix, a problem with very long sequences. #35
            parseUtf8_SB1((Bytes) bytes, (StringBuilder) appendable, utf, length);
        } else {
            parseUtf81(bytes, appendable, utf, length);
        }
    }

    public static void parseUtf8(
            @NotNull RandomDataInput input, @NonNegative long offset, Appendable appendable, boolean utf, @NonNegative int length)
            throws UTFDataFormatRuntimeException, BufferUnderflowException, ClosedIllegalStateException {

        assert utf;
        throwExceptionIfReleased(input);
        throwExceptionIfReleased(appendable);
        if (appendable instanceof StringBuilder) {
            if (input instanceof NativeBytesStore) {
                parseUtf8_SB1((NativeBytesStore) input, offset, (StringBuilder) appendable, length);
                return;
            } else if (input instanceof Bytes
                    && ((Bytes) input).bytesStore() instanceof NativeBytesStore) {
                @Nullable NativeBytesStore bs = (NativeBytesStore) ((Bytes) input).bytesStore();
                parseUtf8_SB1(bs, offset, (StringBuilder) appendable, length);
                return;
            }
        }
        parseUtf81(input, offset, appendable, length);
    }

    public static boolean compareUtf8(@NotNull RandomDataInput input, @NonNegative long offset, @Nullable CharSequence other)
            throws IORuntimeException, BufferUnderflowException, ClosedIllegalStateException {
        throwExceptionIfReleased(input);
        if (other != null)
            throwExceptionIfReleased(other);

        long utfLen;
        if ((utfLen = input.readByte(offset++)) < 0) {
            utfLen &= 0x7FL;
            long b;
            int count = 7;
            while ((b = input.readByte(offset++)) < 0) {
                utfLen |= (b & 0x7FL) << count;
                count += 7;
            }
            if (b != 0) {
                if (count > 56)
                    throw new IORuntimeException(
                            "Cannot read more than 9 stop bits of positive value");
                utfLen |= (b << count);
            } else {
                if (count > 63)
                    throw new IORuntimeException(
                            "Cannot read more than 10 stop bits of negative value");
                utfLen = ~utfLen;
            }
        }
        if (utfLen == -1)
            return other == null;
        return other != null && compareUtf8(input, offset, utfLen, other);
    }

    private static boolean compareUtf8(
            @NotNull RandomDataInput input, @NonNegative long offset, @NonNegative long utfLen, @NotNull CharSequence other)
            throws UTFDataFormatRuntimeException, BufferUnderflowException, IndexOutOfBoundsException, ClosedIllegalStateException {
        throwExceptionIfReleased(input);
        throwExceptionIfReleased(other);
        if (offset + utfLen > input.realCapacity())
            throw new BufferUnderflowException();
        int i = 0;
        while (i < utfLen && i < other.length()) {
            int c = input.readByte(offset + i);
            if (c < 0)
                break;
            if ((char) c != other.charAt(i))
                return false;
            i++;
        }
        if (i < utfLen && i < other.length())
            return compareUtf82(input, offset + i, i, utfLen, other);
        return utfLen == other.length();
    }

    private static boolean compareUtf82(
            @NotNull RandomDataInput input, @NonNegative long offset, int charI, @NonNegative long utfLen, @NotNull CharSequence other)
            throws UTFDataFormatRuntimeException, BufferUnderflowException, IndexOutOfBoundsException, ClosedIllegalStateException {
        throwExceptionIfReleased(input);
        throwExceptionIfReleased(other);
        long limit = offset + utfLen;
        while (offset < limit && charI < other.length()) {
            int c = input.readUnsignedByte(offset++);
            switch (c >> 4) {
                case 0:
                case 1:
                case 2:
                case 3:
                case 4:
                case 5:
                case 6:
                case 7:
                    /* 0xxxxxxx */
                    if ((char) c != other.charAt(charI))
                        return false;
                    break;

                case 12:
                case 13: {
                    /* 110x xxxx 10xx xxxx */
                    if (offset == limit)
                        throw new UTFDataFormatRuntimeException(
                                MALFORMED_INPUT_PARTIAL_CHARACTER_AT_END);
                    int char2 = input.readUnsignedByte(offset++);
                    if ((char2 & 0xC0) != 0x80)
                        throw newUTFDataFormatRuntimeException((offset - limit + utfLen), "was " + char2);
                    int c2 = (char) (((c & 0x1F) << 6) |
                            (char2 & 0x3F));
                    if ((char) c2 != other.charAt(charI))
                        return false;
                    break;
                }

                case 14: {
                    /* 1110 xxxx 10xx xxxx 10xx xxxx */
                    if (offset + 2 > limit)
                        throw new UTFDataFormatRuntimeException(
                                MALFORMED_INPUT_PARTIAL_CHARACTER_AT_END);
                    int char2 = input.readUnsignedByte(offset++);
                    int char3 = input.readUnsignedByte(offset++);

                    if (((char2 & 0xC0) != 0x80) || ((char3 & 0xC0) != 0x80))
                        throw new UTFDataFormatRuntimeException(
                                MALFORMED_INPUT_AROUND_BYTE + (offset - limit + utfLen - 1) +
                                        WAS + char2 + " " + char3);
                    int c3 = (char) (((c & 0x0F) << 12) |
                            ((char2 & 0x3F) << 6) |
                            (char3 & 0x3F));
                    if ((char) c3 != other.charAt(charI))
                        return false;
                    break;
                }
                // TODO add code point of characters > 0xFFFF support.
                default:
                    /* 10xx xxxx, 1111 xxxx */
                    throw newUTFDataFormatRuntimeException(offset - limit + utfLen, "");
            }
            charI++;
        }
        return offset == limit && charI == other.length();
    }

    public static void parse8bit(@NonNegative long offset, @NotNull RandomDataInput bytesStore, Appendable appendable, @NonNegative int utflen)
            throws BufferUnderflowException, IOException, ClosedIllegalStateException {
        throwExceptionIfReleased(bytesStore);
        throwExceptionIfReleased(appendable);
        if (bytesStore instanceof NativeBytesStore
                && appendable instanceof StringBuilder) {
            parse8bit_SB1(offset, (NativeBytesStore) bytesStore, (StringBuilder) appendable, utflen);
        } else {
            parse8bit1(offset, bytesStore, appendable, utflen);
        }
    }

    public static void parseUtf81(
            @NotNull StreamingDataInput bytes, @NotNull Appendable appendable, boolean utf, @NonNegative int length)
            throws UTFDataFormatRuntimeException, BufferUnderflowException, ClosedIllegalStateException {
        throwExceptionIfReleased(bytes);
        throwExceptionIfReleased(appendable);
        if (bytes.readRemaining() < length)
            throw new IllegalArgumentException();
        try {
            int count = 0;
            while (count < length) {
                int c = bytes.rawReadByte();
                if (c < 0) {
                    bytes.readSkip(-1);
                    break;
                }
                count++;
                appendable.append((char) c);
            }

            if (length > count)
                parseUtf82(bytes, appendable, utf, length, count);
        } catch (IOException e) {
            throw Jvm.rethrow(e);
        }
    }

    public static void parseUtf81(@NotNull RandomDataInput input, @NonNegative long offset,
                                  @NotNull Appendable appendable, int utflen)
            throws UTFDataFormatRuntimeException, BufferUnderflowException, ClosedIllegalStateException {
        throwExceptionIfReleased(input);
        throwExceptionIfReleased(appendable);
        try {
            assert input.realCapacity() >= offset + utflen;
            long limit = offset + utflen;
            while (offset < limit) {
                int c = input.readUnsignedByte(offset++);
                if (c >= 128) {
                    offset--;
                    break;

                } else if (c < 0) {
                    break;
                }
                appendable.append((char) c);
            }

            if (limit > offset)
                parseUtf82(input, offset, limit, appendable, utflen);
        } catch (IOException e) {
            throw Jvm.rethrow(e);
        }
    }

    public static void parse8bit1(@NotNull StreamingDataInput bytes, @NotNull StringBuilder sb, @NonNegative int utflen)
            throws ClosedIllegalStateException {
        throwExceptionIfReleased(bytes);
        if (bytes.readRemaining() < utflen)
            throw new IllegalArgumentException();
        sb.ensureCapacity(utflen);

        if (Jvm.isJava9Plus()) {
            byte[] sbBytes = extractBytes(sb);
            for (int count = 0; count < utflen; count++) {
                int c = bytes.readUnsignedByte();
                sbBytes[count] = (byte) c;
            }
        } else {
            char[] chars = StringUtils.extractChars(sb);
            for (int count = 0; count < utflen; count++) {
                int c = bytes.readUnsignedByte();
                chars[count] = (char) c;
            }
        }
        StringUtils.setLength(sb, utflen);
    }

    public static void parse8bit1(@NotNull StreamingDataInput bytes, @NotNull Appendable appendable, @NonNegative int utflen)
            throws ClosedIllegalStateException, IOException {
        throwExceptionIfReleased(bytes);
        throwExceptionIfReleased(appendable);
        assert bytes.readRemaining() >= utflen;
        for (int count = 0; count < utflen; count++) {
            int c = bytes.readUnsignedByte();
            appendable.append((char) c);
        }
    }

    public static void parse8bit1(@NonNegative long offset, @NotNull RandomDataInput bytes, @NotNull Appendable appendable, @NonNegative int utflen)
            throws BufferUnderflowException, ClosedIllegalStateException, IOException {
        throwExceptionIfReleased(bytes);
        throwExceptionIfReleased(appendable);
        if (bytes.realCapacity() < utflen + offset)
            throw new DecoratedBufferUnderflowException(bytes.realCapacity() + " < " + utflen + " +" + offset);
        for (int count = 0; count < utflen; count++) {
            int c = bytes.readUnsignedByte(offset + count);
            appendable.append((char) c);
        }
    }

    public static void parseUtf8_SB1(@NotNull Bytes<?> bytes, @NotNull StringBuilder sb, boolean utf, @NonNegative int utflen)
            throws UTFDataFormatRuntimeException, BufferUnderflowException {
        try {
            throwExceptionIfReleased(bytes);
            assert utf;

            if (utflen > bytes.readRemaining()) {
                @NotNull final BufferUnderflowException bue = new BufferUnderflowException();
                bue.initCause(new ClosedIllegalStateException("utflen: " + utflen + ", readRemaining: " + bytes.readRemaining()));
                throw bue;
            }
            long readPosition = bytes.readPosition();
            sb.ensureCapacity(utflen);

            int count = calculateCount(bytes, sb, utflen, readPosition);

            bytes.readSkip(count);
            setCount(sb, count);
            if (count < utflen) {
                final long rp0 = bytes.readPosition();
                parseUtf82Guarded(bytes, sb, utf, utflen, count, rp0);
            }
        } catch (IOException | ClosedIllegalStateException e) {
            throw Jvm.rethrow(e);
        }
    }

    private static void parseUtf82Guarded(@NotNull Bytes<?> bytes, @NotNull StringBuilder sb, boolean utf, @NonNegative int utflen, int count, long rp0) throws IOException {
        try {
            parseUtf82(bytes, sb, utf, utflen, count);
        } catch (UTFDataFormatRuntimeException e) {
            long rp = Math.max(rp0 - 128, 0);
            throw new UTFDataFormatRuntimeException(Long.toHexString(rp0) + "\n" + bytes.toHexString(rp, 200), e);
        }
    }

    private static int calculateCount(@NotNull Bytes<?> bytes, @NotNull StringBuilder sb, @NonNegative int utflen, @NonNegative long readPosition) throws ClosedIllegalStateException {
        int count = 0;
        if (Jvm.isJava9Plus()) {
            sb.setLength(utflen);
            while (count < utflen) {
                byte c = bytes.readByte(readPosition + count);
                if (c < 0)
                    break;
                sb.setCharAt(count++, (char) c); // This is not as fast as it could be.
            }
        } else {
            final char[] chars = extractChars(sb);
            while (count < utflen) {
                int c = bytes.readByte(readPosition + count);
                if (c < 0)
                    break;
                chars[count++] = (char) c;
            }
        }
        return count;
    }

    public static void parseUtf8_SB1(@NotNull NativeBytesStore bytes, @NonNegative long offset,
                                     @NotNull StringBuilder sb, @NonNegative int utflen)
            throws UTFDataFormatRuntimeException, BufferUnderflowException, ClosedIllegalStateException {
        throwExceptionIfReleased(bytes);
        requireNonNull(sb);
        try {
            if (offset + utflen > bytes.realCapacity())
                throw new BufferUnderflowException();
            long address = bytes.address + bytes.translate(offset);
            Memory memory = bytes.memory;
            sb.ensureCapacity(utflen);
            int count = 0;

            if (Jvm.isJava9Plus()) {
                sb.setLength(utflen);
                while (count < utflen) {
                    byte c = memory.readByte(address + count);
                    if (c < 0)
                        break;
                    sb.setCharAt(count++, (char) c);
                }
            } else {
                char[] chars = extractChars(sb);
                while (count < utflen) {
                    int c = memory.readByte(address + count);
                    if (c < 0)
                        break;
                    chars[count++] = (char) c;
                }
            }
            setCount(sb, count);
            if (count < utflen)
                parseUtf82(bytes, offset + count, offset + utflen, sb, utflen);
            assert bytes.memory != null;
        } catch (IOException e) {
            throw Jvm.rethrow(e);
        }
    }

    public static int parse8bit_SB1(@NonNegative long offset, @NotNull NativeBytesStore nbs, @NotNull StringBuilder sb, @NonNegative int length) {
        throwExceptionIfReleased(nbs);
        requireNonNull(sb);
        long address = nbs.address + nbs.translate(offset);
        @Nullable Memory memory = nbs.memory;
        sb.ensureCapacity(length);
        int count = 0;

        if (Jvm.isJava9Plus()) {
            byte coder = getStringCoder(sb);

            if (coder == JAVA9_STRING_CODER_LATIN) {
                byte[] bytes = extractBytes(sb);
                while (count < length) {
                    byte b = memory.readByte(address + count);
                    bytes[count++] = b;
                }
            } else {
                assert coder == JAVA9_STRING_CODER_UTF16;
                sb.setLength(length);
                while (count < length) {
                    byte b = memory.readByte(address + count);
                    sb.setCharAt(count++, (char) b);
                }
            }
        } else {
            char[] chars = extractChars(sb);
            while (count < length) {
                int c = memory.readByte(address + count) & 0xFF;
                chars[count++] = (char) c;
            }
        }
        setCount(sb, count);
        return count;
    }

    static void parseUtf82(@NotNull StreamingDataInput bytes, @NotNull Appendable appendable, boolean utf, @NonNegative int length, @NonNegative int count)
            throws IOException, UTFDataFormatRuntimeException, ClosedIllegalStateException {
        throwExceptionIfReleased(bytes);
        throwExceptionIfReleased(appendable);
        while (count < length) {
            int c = bytes.readUnsignedByte();
            if (c < 0)
                break;
            switch (c >> 4) {
                case 0:
                case 1:
                case 2:
                case 3:
                case 4:
                case 5:
                case 6:
                case 7:
                    /* 0xxxxxxx */
                    count++;
                    appendable.append((char) c);
                    break;

                case 12:
                case 13: {
                    /* 110x xxxx 10xx xxxx */
                    count += utf ? 2 : 1;
                    if (count > length)
                        throw new UTFDataFormatRuntimeException(
                                MALFORMED_INPUT_PARTIAL_CHARACTER_AT_END);
                    int char2 = bytes.readUnsignedByte();
                    if ((char2 & 0xC0) != 0x80)
                        throw new UTFDataFormatRuntimeException(
                                MALFORMED_INPUT_AROUND_BYTE + count + WAS + char2);
                    int c2 = (char) (((c & 0x1F) << 6) |
                            (char2 & 0x3F));
                    appendable.append((char) c2);
                    break;
                }

                case 14: {
                    /* 1110 xxxx 10xx xxxx 10xx xxxx */
                    count += utf ? 3 : 1;
                    if (count > length)
                        throw new UTFDataFormatRuntimeException(
                                MALFORMED_INPUT_PARTIAL_CHARACTER_AT_END);
                    int char2 = bytes.readUnsignedByte();
                    int char3 = bytes.readUnsignedByte();

                    if (((char2 & 0xC0) != 0x80) || ((char3 & 0xC0) != 0x80))
                        throw newUTFDataFormatRuntimeException(count - 1L, WAS + char2 + " " + char3);
                    int c3 = (char) (((c & 0x0F) << 12) |
                            ((char2 & 0x3F) << 6) |
                            (char3 & 0x3F));
                    appendable.append((char) c3);
                    break;
                }
                // TODO add code point of characters > 0xFFFF support.
                default:
                    /* 10xx xxxx, 1111 xxxx */
                    throw newUTFDataFormatRuntimeException(count, "");
            }
        }
    }

    static void parseUtf82(@NotNull RandomDataInput input, @NonNegative long offset, @NonNegative long limit,
                           @NotNull Appendable appendable, @NonNegative int utflen)
            throws IOException, UTFDataFormatRuntimeException, BufferUnderflowException, ClosedIllegalStateException {
        throwExceptionIfReleased(input);
        throwExceptionIfReleased(appendable);
        while (offset < limit) {
            int c = input.readUnsignedByte(offset++);
            switch (c >> 4) {
                case 0:
                case 1:
                case 2:
                case 3:
                case 4:
                case 5:
                case 6:
                case 7:
                    /* 0xxxxxxx */
                    appendable.append((char) c);
                    break;

                case 12:
                case 13: {
                    /* 110x xxxx 10xx xxxx */
                    if (offset == limit)
                        throw new UTFDataFormatRuntimeException(
                                MALFORMED_INPUT_PARTIAL_CHARACTER_AT_END);
                    int char2 = input.readUnsignedByte(offset++);
                    if ((char2 & 0xC0) != 0x80)
                        throw newUTFDataFormatRuntimeException(offset - limit + utflen, "was " + char2);
                    int c2 = (char) (((c & 0x1F) << 6) |
                            (char2 & 0x3F));
                    appendable.append((char) c2);
                    break;
                }

                case 14: {
                    /* 1110 xxxx 10xx xxxx 10xx xxxx */
                    if (offset + 2 > limit)
                        throw new UTFDataFormatRuntimeException(
                                MALFORMED_INPUT_PARTIAL_CHARACTER_AT_END);
                    int char2 = input.readUnsignedByte(offset++);
                    int char3 = input.readUnsignedByte(offset++);

                    if (((char2 & 0xC0) != 0x80) || ((char3 & 0xC0) != 0x80))
                        throw newUTFDataFormatRuntimeException(offset - limit + utflen - 1, WAS + char2 + " " + char3);
                    int c3 = (char) (((c & 0x0F) << 12) |
                            ((char2 & 0x3F) << 6) |
                            (char3 & 0x3F));
                    appendable.append((char) c3);
                    break;
                }
                // TODO add code point of characters > 0xFFFF support.
                default:
                    /* 10xx xxxx, 1111 xxxx */
                    throw newUTFDataFormatRuntimeException(offset - limit + utflen, "");
            }
        }
    }

    public static void writeUtf8(@NotNull StreamingDataOutput bytes, @Nullable String str)
            throws BufferOverflowException, ClosedIllegalStateException, IllegalArgumentException, BufferUnderflowException {
        throwExceptionIfReleased(bytes);
        if (str == null) {
            BytesInternal.writeStopBitNeg1(bytes);
            return;
        }

        if (Jvm.isJava9Plus()) {
            byte[] strBytes = extractBytes(str);
            byte coder = StringUtils.getStringCoder(str);
            long utfLength = AppendableUtil.findUtf8Length(strBytes, coder);
            bytes.writeStopBit(utfLength);
            bytes.appendUtf8(strBytes, 0, str.length(), coder);
        } else {
            char[] chars = extractChars(str);
            long utfLength = AppendableUtil.findUtf8Length(chars);
            bytes.writeStopBit(utfLength);
            bytes.appendUtf8(chars, 0, chars.length);
        }
    }

    public static void writeUtf8(@NotNull StreamingDataOutput bytes, @Nullable CharSequence str)
            throws BufferOverflowException, ClosedIllegalStateException, BufferUnderflowException, IllegalArgumentException {
        throwExceptionIfReleased(bytes);
        if (str instanceof String) {
            writeUtf8(bytes, (String) str);
            return;
        }
        if (str == null) {
            BytesInternal.writeStopBitNeg1(bytes);

        } else {
            long utfLength = AppendableUtil.findUtf8Length(str);
            bytes.writeStopBit(utfLength);
            appendUtf8(bytes, str, 0, str.length());
        }
    }

    public static long writeUtf8(@NotNull RandomDataOutput out,
                                 @NonNegative long writeOffset,
                                 @Nullable CharSequence str)
            throws BufferOverflowException, ClosedIllegalStateException, ArithmeticException {
        throwExceptionIfReleased(out);
        requireNonNegative(writeOffset);
        if (str == null) {
            writeOffset = writeStopBit(out, writeOffset, -1);

        } else {
            int strLength = str.length();
            if (strLength < 32) {
                long lenOffset = writeOffset;
                writeOffset = appendUtf8(out, writeOffset + 1, str, 0, strLength);
                long utfLength = writeOffset - lenOffset - 1;
                assert utfLength <= 127;
                writeStopBit(out, lenOffset, utfLength);
            } else {
                long utfLength = AppendableUtil.findUtf8Length(str);
                writeOffset = writeStopBit(out, writeOffset, utfLength);
                if (utfLength == strLength) {
                    append8bit(writeOffset, out, str, 0, strLength);
                    writeOffset += utfLength;
                } else {
                    writeOffset = appendUtf8(out, writeOffset, str, 0, strLength);
                }
            }
        }
        return writeOffset;
    }

    public static long writeUtf8(@NotNull final RandomDataOutput out,
                                 @NonNegative long offset,
                                 @Nullable final CharSequence str,
                                 @NonNegative int maxUtf8Len) throws BufferOverflowException, ClosedIllegalStateException, ArithmeticException {
        requireNonNegative(offset);
        throwExceptionIfReleased(out);
        if (str == null) {
            offset = writeStopBit(out, offset, -1);

        } else {
            int strLength = str.length();
            long utfLength = AppendableUtil.findUtf8Length(str);
            if (utfLength > maxUtf8Len) {
                throw new IllegalArgumentException("Attempted to write a char sequence of " +
                        "utf8 size " + utfLength + ": \"" + str +
                        "\", when only " + maxUtf8Len + " allowed");
            }
            offset = writeStopBit(out, offset, utfLength);
            if (utfLength == strLength) {
                append8bit(offset, out, str, 0, strLength);
                offset += utfLength;
            } else {
                offset = appendUtf8(out, offset, str, 0, strLength);
            }
        }
        return offset;
    }

    @NotNull
    public static Bytes<?> asBytes(@NotNull RandomDataOutput bytes, @NonNegative long position, @NonNegative long limit)
            throws ClosedIllegalStateException, BufferOverflowException, BufferUnderflowException {
        throwExceptionIfReleased(bytes);
        Bytes<?> sbytes = bytes.bytesForWrite();
        sbytes.writeLimit(limit);
        sbytes.readLimit(limit);
        sbytes.readPosition(position);
        return sbytes;
    }

    public static void appendUtf8(@NotNull StreamingDataOutput bytes,
                                  @NotNull CharSequence str, @NonNegative int offset, @NonNegative int length)
            throws IndexOutOfBoundsException, ClosedIllegalStateException, ThreadingIllegalStateException {
        throwExceptionIfReleased(bytes);
        throwExceptionIfReleased(str);
        try {
            int i;
            for (i = 0; i < length; i++) {
                char c = str.charAt(offset + i);
                if (c > 0x007F)
                    break;
                bytes.rawWriteByte((byte) c);
            }
            appendUtf82(bytes, str, offset, length, i);
        } catch (BufferOverflowException | ClosedIllegalStateException e) {
            throw Jvm.rethrow(e);
        }
    }

    private static void appendUtf82(@NotNull StreamingDataOutput bytes,
                                    @NotNull CharSequence str, @NonNegative int offset, @NonNegative int length, @NonNegative int i)
            throws IndexOutOfBoundsException, BufferOverflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        throwExceptionIfReleased(bytes);
        throwExceptionIfReleased(str);
        for (; i < length; i++) {
            char c = str.charAt(offset + i);
            appendUtf8Char(bytes, c);
        }
    }

    public static long appendUtf8(@NotNull RandomDataOutput out, @NonNegative long outOffset,
                                  @NotNull CharSequence str, @NonNegative int strOffset, @NonNegative int length)
            throws IndexOutOfBoundsException, BufferOverflowException, ClosedIllegalStateException {
        throwExceptionIfReleased(out);
        throwExceptionIfReleased(str);
        int i;
        for (i = 0; i < length; i++) {
            char c = str.charAt(strOffset + i);
            if (c > 0x007F)
                break;
            out.writeByte(outOffset++, (byte) c);
        }
        return appendUtf82(out, outOffset, str, strOffset, length, i);
    }

    private static long appendUtf82(@NotNull RandomDataOutput out, @NonNegative long outOffset,
                                    @NotNull CharSequence str, @NonNegative int strOffset, @NonNegative int length, @NonNegative int i)
            throws IndexOutOfBoundsException, BufferOverflowException, ClosedIllegalStateException {
        throwExceptionIfReleased(out);
        throwExceptionIfReleased(str);
        for (; i < length; i++) {
            char c = str.charAt(strOffset + i);
            outOffset = appendUtf8Char(out, outOffset, c);
        }
        return outOffset;
    }

    public static void append8bit(@NonNegative long offsetInRDO, RandomDataOutput bytes, @NotNull CharSequence str, @NonNegative int offset, @NonNegative int length)
            throws IllegalArgumentException, BufferOverflowException, BufferUnderflowException,
            IndexOutOfBoundsException, ClosedIllegalStateException, ArithmeticException {
        throwExceptionIfReleased(bytes);
        throwExceptionIfReleased(str);
        if (bytes instanceof VanillaBytes) {
            @NotNull VanillaBytes vb = (VanillaBytes) bytes;
            if (str instanceof RandomDataInput) {
                vb.write(offsetInRDO, (RandomDataInput) str, offset, length);
                return;
            }
            if (str instanceof String) {
                vb.write(offsetInRDO, str, offset, length);
                return;
            }
        }
        for (int i = 0; i < length; i++) {
            char c = str.charAt(offset + i);
            if (c > 255) c = '?';
            bytes.writeUnsignedByte(offsetInRDO + i, c);
        }
    }

    public static void appendUtf8Char(@NotNull StreamingDataOutput bytes, int c)
            throws BufferOverflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        if (c <= 0x007F) {
            bytes.rawWriteByte((byte) c);

        } else if (c <= 0x07FF) {
            bytes.rawWriteByte((byte) (0xC0 | ((c >> 6) & 0x1F)));
            bytes.rawWriteByte((byte) (0x80 | c & 0x3F));

        } else if (c <= 0xFFFF) {
            bytes.rawWriteByte((byte) (0xE0 | ((c >> 12) & 0x0F)));
            bytes.rawWriteByte((byte) (0x80 | ((c >> 6) & 0x3F)));
            bytes.rawWriteByte((byte) (0x80 | (c & 0x3F)));

        } else {
            bytes.rawWriteByte((byte) (0xF0 | ((c >> 18) & 0x07)));
            bytes.rawWriteByte((byte) (0x80 | ((c >> 12) & 0x3F)));
            bytes.rawWriteByte((byte) (0x80 | ((c >> 6) & 0x3F)));
            bytes.rawWriteByte((byte) (0x80 | (c & 0x3F)));
        }
    }

    public static long appendUtf8Char(@NotNull RandomDataOutput out, @NonNegative long offset, int c)
            throws BufferOverflowException, ClosedIllegalStateException {
        if (c <= 0x007F) {
            out.writeByte(offset++, (byte) c);

        } else if (c <= 0x07FF) {
            out.writeByte(offset++, (byte) (0xC0 | ((c >> 6) & 0x1F)));
            out.writeByte(offset++, (byte) (0x80 | c & 0x3F));

        } else if (c <= 0xFFFF) {
            out.writeByte(offset++, (byte) (0xE0 | ((c >> 12) & 0x0F)));
            out.writeByte(offset++, (byte) (0x80 | ((c >> 6) & 0x3F)));
            out.writeByte(offset++, (byte) (0x80 | (c & 0x3F)));

        } else {
            out.writeByte(offset++, (byte) (0xF0 | ((c >> 18) & 0x07)));
            out.writeByte(offset++, (byte) (0x80 | ((c >> 12) & 0x3F)));
            out.writeByte(offset++, (byte) (0x80 | ((c >> 6) & 0x3F)));
            out.writeByte(offset++, (byte) (0x80 | (c & 0x3F)));
        }
        return offset;
    }

    public static void writeStopBitNeg1(@NotNull StreamingDataOutput out) {
        out.writeUnsignedShort(NEG_ONE);
    }

    public static void writeStopBit(@NotNull StreamingDataOutput out, char n)
            throws BufferOverflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        if ((n & ~0x7F) == 0) {
            out.rawWriteByte((byte) (n & 0x7f));
            return;
        }
        if ((n & ~0x3FFF) == 0) {
            out.rawWriteByte((byte) (n & 0x7f | 0x80));
            out.rawWriteByte((byte) (n >> 7));
            return;
        }
        if ((n & 0xFF80) == 0xFF80) {
            out.rawWriteByte((byte) (~n & 0x7f | 0x80));
            out.rawWriteByte((byte) 0);
            return;
        }
        writeStopBit0(out, n);
    }

    public static long writeStopBit(final long addr, final long n)
            throws BufferOverflowException {
        if ((n & ~0x7F) == 0) {
            UnsafeMemory.INSTANCE.writeByte(addr, (byte) n);
            return addr + 1;
        }
        if ((n & ~0x3FFF) == 0) {
            final int lo = (int) ((n & 0x7f) | 0x80);
            final int hi = (int) (n >> 7);
            UnsafeMemory.INSTANCE.writeByte(addr, (byte) lo);
            UnsafeMemory.INSTANCE.writeByte(addr + 1, (byte) hi);
            // Note: Refrain from using writeShort as this assumes a certain endian
            return addr + 2;
        }
        return writeStopBit0(addr, n);
    }

    public static void writeStopBit(@NotNull StreamingDataOutput out, long n)
            throws BufferOverflowException, ClosedIllegalStateException {
        if ((n & ~0x7F) == 0) {
            out.rawWriteByte((byte) n);
            return;
        }
        if ((n & ~0x3FFF) == 0) {
            out.rawWriteByte((byte) ((n & 0x7f) | 0x80));
            out.rawWriteByte((byte) (n >> 7));
            return;
        }
        writeStopBit0(out, n);
    }

    public static long writeStopBit(@NotNull RandomDataOutput out, @NonNegative long offset, long n)
            throws BufferOverflowException, ClosedIllegalStateException {
        if ((n & ~0x7F) == 0) {
            out.writeByte(offset++, (byte) n);
            return offset;
        }
        if ((n & ~0x3FFF) == 0) {
            out.writeByte(offset++, (byte) ((n & 0x7f) | 0x80));
            out.writeByte(offset++, (byte) (n >> 7));
            return offset;
        }
        return writeStopBit0(out, offset, n);
    }

    @SuppressWarnings("ShiftOutOfRange")
    public static void writeStopBit(@NotNull StreamingDataOutput out, double d)
            throws BufferOverflowException, ClosedIllegalStateException {
        long n = Double.doubleToRawLongBits(d);
        while ((n & (~0L >>> 7)) != 0) {
            out.rawWriteByte((byte) (((n >>> -7) & 0x7F) | 0x80));
            n <<= 7;
        }
        out.rawWriteByte((byte) ((n >>> -7) & 0x7F));
    }

    public static double readStopBitDouble(@NotNull StreamingDataInput in)
            throws ClosedIllegalStateException {
        long n = 0;
        int shift = 64 - 7;
        int b;
        do {
            b = in.readByte();
            n |= shift > 0 ? (long) (b & 0x7F) << shift : b >> -shift;
            shift -= 7;
        } while (b < 0);
        return Double.longBitsToDouble(n);
    }

    public static void writeStopBit0(@NotNull StreamingDataOutput out, long n)
            throws BufferOverflowException, ClosedIllegalStateException {
        boolean neg = false;
        if (n < 0) {
            neg = true;
            n = ~n;
        }

        long n2;
        while ((n2 = n >>> 7) != 0) {
            out.rawWriteByte((byte) (0x80L | n));
            n = n2;
        }
        // final byte
        if (!neg) {
            out.rawWriteByte((byte) n);

        } else {
            out.rawWriteByte((byte) (0x80L | n));
            out.rawWriteByte((byte) 0);
        }
    }

    static long writeStopBit0(long addr, long n)
            throws BufferOverflowException {
        int i = 0;
        boolean neg = false;
        if (n < 0) {
            neg = true;
            n = ~n;
        }

        long n2;
        while ((n2 = n >>> 7) != 0) {
            MEMORY.writeByte(addr + i++, (byte) (0x80L | n));
            n = n2;
        }
        // final byte
        if (!neg) {
            MEMORY.writeByte(addr + i++, (byte) n);

        } else {
            MEMORY.writeByte(addr + i++, (byte) (0x80L | n));
            MEMORY.writeByte(addr + i++, (byte) 0);
        }
        return addr + i;
    }

    static long writeStopBit0(@NotNull RandomDataOutput out, @NonNegative long offset, long n)
            throws BufferOverflowException, ClosedIllegalStateException {
        boolean neg = false;
        if (n < 0) {
            neg = true;
            n = ~n;
        }

        long n2;
        while ((n2 = n >>> 7) != 0) {
            out.writeByte(offset++, (byte) (0x80L | n));
            n = n2;
        }
        // final byte
        if (!neg) {
            out.writeByte(offset++, (byte) n);

        } else {
            out.writeByte(offset++, (byte) (0x80L | n));
            out.writeByte(offset++, (byte) 0);
        }
        return offset;
    }

    public static int stopBitLength0(long n) {
        int len = 0;
        if (n < 0) {
            len = 1;
            n = ~n;
        }

        while ((n >>>= 7) != 0) len++;
        return len + 1;
    }

    public static String toDebugString(@NotNull RandomDataInput bytes, @NonNegative long maxLength)
            throws ClosedIllegalStateException, ArithmeticException, ThreadingIllegalStateException {
        if (bytes.refCount() < 1) {
            // Make sure not to access a released resource
            return "<released>";
        }
        ReferenceOwner toDebugString = temporary("toDebugString");
        bytes.reserve(toDebugString);
        try {
            int len = Maths.toUInt31(maxLength + 40);
            @NotNull StringBuilder sb = new StringBuilder(len);
            long readPosition = bytes.readPosition();
            long readLimit = bytes.readLimit();
            if (bytes instanceof HexDumpBytes) {
                readPosition = (int) readPosition;
                readLimit = (int) readLimit;
            }
            sb.append("[")
                    .append("pos: ").append(readPosition)
                    .append(", rlim: ").append(readLimit)
                    .append(", wlim: ").append(asSize(bytes.writeLimit()))
                    .append(", cap: ").append(asSize(bytes.capacity()))
                    .append(" ] ");
            appendContent(bytes, maxLength, sb, readPosition, readLimit);
            return sb.toString();

        } finally {
            bytes.release(toDebugString);
        }
    }

    private static void appendContent(@NotNull final RandomDataInput bytes,
                                      @NonNegative final long maxLength,
                                      @NotNull final StringBuilder sb,
                                      @NonNegative final long readPosition,
                                      @NonNegative final long readLimit) {
        try {
            final long start = Math.max(bytes.start(), readPosition - 64);
            long end = Math.min(readLimit + 64, start + maxLength);
            // should never try to read past the end of the buffer
            end = Math.min(end, bytes.realCapacity());
            try {
                for (; end >= start + 16 && end >= readLimit + 16; end -= 8) {
                    if (bytes.readLong(end - 8) != 0)
                        break;
                }
            } catch (@NotNull UnsupportedOperationException | BufferUnderflowException ignored) {
                // ignore
            }
            toString(bytes, sb, start, readPosition, readLimit, end);
            if (end < bytes.readLimit())
                sb.append("...");
        } catch (Exception e) {
            sb.append(' ').append(e);
        }
    }

    @NotNull
    public static Object asSize(@NonNegative long size) {
        return size == Bytes.MAX_CAPACITY ? "8EiB" : size;
    }

    public static String to8bitString(@NotNull BytesStore<?, ?> bytes) throws ClosedIllegalStateException {
        final long pos = bytes.readPosition();
        throwExceptionIfReleased(bytes);
        int len = (int) Math.min(Integer.MAX_VALUE, bytes.readRemaining());
        char[] chars = new char[len];
        if (bytes instanceof VanillaBytes) {
            try {
                ((VanillaBytes) bytes).read8Bit(chars, len);
            } catch (BufferUnderflowException | ClosedIllegalStateException e) {
                throw Jvm.rethrow(e);
            }
        } else {
            for (int i = 0; i < len; i++)
                try {
                    chars[i] = (char) bytes.readUnsignedByte(pos + i);
                } catch (Exception e) {
                    return new String(chars, 0, len) + ' ' + e;
                }
        }
        return StringUtils.newString(chars);
    }

    /**
     * Represent the bytes store as a UTF-8 string.
     * Heavily based on the approach used in {@link VanillaBytes#toString()} but with UTF-8 support.
     *
     * @return UTF-8 string representation of the bytes store.
     */
    public static String toUtf8String(BytesStore<?, ?> bytesStore) {
        try {
            try {
                return bytesStore instanceof NativeBytesStore
                        ? toUtf8StringNativeBytes((NativeBytesStore<?>) bytesStore)
                        : toUtf8StringBytesStore(bytesStore);
            } catch (IllegalStateException e) {
                throw Jvm.rethrow(e);
            }
        } catch (Exception e) {
            return e.toString();
        }
    }

    private static String toUtf8StringNativeBytes(NativeBytesStore<?> bytesStore) {
        final Memory memory = bytesStore.memory;
        int length = (int)
                Math.min(Bytes.MAX_HEAP_CAPACITY, bytesStore.realReadRemaining());
        byte[] bytes = new byte[length];
        final long address = bytesStore.address + bytesStore.translate(bytesStore.readPosition());
        for (int i = 0; i < length && i < bytesStore.realCapacity(); i++) {
            bytes[i] = memory.readByte(address + i);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String toUtf8StringBytesStore(BytesStore<?, ?> bytesStore)
            throws ClosedIllegalStateException {
        int length = (int) Math.min(Bytes.MAX_HEAP_CAPACITY, bytesStore.readRemaining());
        byte[] bytes = new byte[length];
        try {
            for (int i = 0; i < length; i++) {
                bytes[i] = (bytesStore.readByte(bytesStore.readPosition() + i));
            }
        } catch (BufferUnderflowException e) {
            // ignored
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @NotNull
    public static String toString(@NotNull RandomDataInput bytes) {
        try {
            throwExceptionIfReleased(bytes);
            // the output will be no larger than this
            final long available = bytes.realReadRemaining();
            final int size = (int) Math.min(available, MAX_STRING_LEN - 3L);
            @NotNull final StringBuilder sb = new StringBuilder(size);

            if (bytes.readRemaining() > size) {
                final Bytes<?> bytes1 = bytes.bytesForRead();
                try {
                    bytes1.readLimit(bytes1.readPosition() + size);
                    toString(bytes1, sb);
                    if (size < available)
                        sb.append("...");
                    return sb.toString();
                } finally {
                    bytes1.releaseLast();
                }
            } else {
                toString(bytes, sb);
                return sb.toString();
            }
        } catch (ClosedIllegalStateException | BufferUnderflowException e) {
            return e.toString();
        }
    }

    private static void toString(@NotNull RandomDataInput bytes,
                                 @NotNull Appendable sb,
                                 @NonNegative long start,
                                 @NonNegative long readPosition,
                                 @NonNegative long writePosition,
                                 @NonNegative long end)
            throws ClosedIllegalStateException {
        throwExceptionIfReleased(bytes);
        throwExceptionIfReleased(sb);
        try {
            // before
            if (start < bytes.start()) start = bytes.start();
            long realCapacity = bytes.realCapacity();
            if (end > realCapacity) end = realCapacity;
            boolean showStartEnd = bytes instanceof Bytes && writePosition < end;
            if (readPosition >= start && showStartEnd) {
                long last = Math.min(readPosition, end);
                toString(bytes, sb, start, last);
                sb.append('\u01C1');
            }
            toString(bytes, sb, Math.max(readPosition, start), Math.min(writePosition, end));
            if (writePosition <= end) {
                if (showStartEnd)
                    sb.append('\u2021');
                toString(bytes, sb, writePosition, end);
            }
        } catch (Exception e) {
            try {
                sb.append(' ').append(e.toString());
            } catch (IOException e1) {
                throw new AssertionError(e);
            }
        }
    }

    private static void toString(@NotNull RandomDataInput bytes, @NotNull Appendable sb, @NonNegative long start, @NonNegative long last)
            throws IOException, BufferUnderflowException, ClosedIllegalStateException {
        throwExceptionIfReleased(bytes);
        throwExceptionIfReleased(sb);
        for (long i = start; i < last; i++) {
            sb.append(bytes.printable(i));
        }
    }

    private static void toString(@NotNull RandomDataInput bytes, @NotNull StringBuilder sb)
            throws ClosedIllegalStateException, ThreadingIllegalStateException {
        throwExceptionIfReleased(bytes);
        requireNonNull(sb);
        ReferenceOwner toString = temporary("toString");
        bytes.reserve(toString);
        long start = bytes.readPosition();
        assert bytes.start() <= start;
        assert start <= bytes.readLimit();
        int length = Math.toIntExact(bytes.realReadRemaining());

        try {
            for (int i = 0; i < length; i++) {
                sb.append((char) bytes.readUnsignedByte(start + i));
            }
        } catch (BufferUnderflowException e) {
            sb.append(' ').append(e);

        } finally {
            bytes.release(toString);
        }
    }

    public static char readStopBitChar(@NotNull StreamingDataInput in)
            throws IORuntimeException, ClosedIllegalStateException {
        byte b;
        if ((b = in.rawReadByte()) >= 0)
            return (char) b;
        // see if it -1
        if (b == -128 && in.peekUnsignedByte() == 0) {
            in.readSkip(1);
            return Character.MAX_VALUE;
        }
        return (char) readStopBit0(in, b);
    }

    public static long readStopBit(@NotNull StreamingDataInput in)
            throws IORuntimeException, ClosedIllegalStateException {
        byte b;
        if ((b = in.rawReadByte()) >= 0)
            return b;
        // see if it -1
        if (b == -128 && in.peekUnsignedByte() == 0) {
            in.readSkip(1);
            return -1;
        }
        return readStopBit0(in, b);
    }

    public static long readStopBit0(@NotNull StreamingDataInput in, long l)
            throws IORuntimeException, ClosedIllegalStateException {
        l &= 0x7FL;
        long b;
        int count = 7;
        while ((b = in.rawReadByte()) < 0) {
            l |= (b & 0x7FL) << count;
            count += 7;
        }
        if (b != 0) {
            if (count > 56)
                throw new IORuntimeException(
                        "Cannot read more than 9 stop bits of positive value");
            return l | (b << count);

        } else {
            if (count > 63)
                throw new IORuntimeException(
                        "Cannot read more than 10 stop bits of negative value");
            return ~l;
        }
    }

    public static void append(@NotNull ByteStringAppender out, long num, int base)
            throws IllegalArgumentException, BufferOverflowException, ClosedIllegalStateException, IndexOutOfBoundsException, ThreadingIllegalStateException {
        if (num < 0) {
            if (num == Long.MIN_VALUE) {
                if (base == 10)
                    out.write(MIN_VALUE_TEXT);
                else
                    out.write(Long.toString(Long.MIN_VALUE, base));
                return;
            }
            out.rawWriteByte((byte) '-');
            num = -num;
        }
        if (num == 0) {
            out.rawWriteByte((byte) '0');

        } else {
            switch (base) {
                case 10:
                    appendBase10(out, num);
                    break;
                case 16:
                    appendBase16(out, num, 1);
                    break;
                default:
                    out.write(Long.toString(num, base));
                    break;
            }
        }
    }

    public static void appendBase10(@NotNull ByteStringAppender out, int num)
            throws BufferOverflowException, ClosedIllegalStateException {
        appendBase10(out, (long) num);
    }

    public static void appendBase10(@NotNull ByteStringAppender out, long num)
            throws BufferOverflowException, ClosedIllegalStateException {
        if (out.canWriteDirect(20)) {
            long address = out.addressForWrite(out.writePosition());
            long address2 = UnsafeText.appendFixed(address, num);
            out.writeSkip(address2 - address);
        } else {
            appendLong0(out, num);
        }
    }

    public static void appendBase16(@NotNull ByteStringAppender out, long num, int minDigits)
            throws IllegalArgumentException, BufferOverflowException, ClosedIllegalStateException {
        int digits = 16 - Long.numberOfLeadingZeros(num) / 4;
        digits = Math.max(minDigits, digits);
        int shift = Math.max(4, digits << 2);
        do {
            shift -= 4;
            int digit = (int) ((num >>> shift) & 0xF);

            out.rawWriteByte((byte) HEXADECIMAL[digit]);
        } while (shift > 0);
    }

    public static void appendDecimal(@NotNull ByteStringAppender out, long num, int decimalPlaces)
            throws BufferOverflowException, ClosedIllegalStateException, ArithmeticException, IllegalArgumentException, ThreadingIllegalStateException {
        if (decimalPlaces == 0) {
            appendBase10(out, num);
            return;
        }

        byte[] numberBuffer = BYTE_ARRAY_TL.get();
        int endIndex;
        if (num < 0) {
            if (num == Long.MIN_VALUE) {
                numberBuffer = MIN_VALUE_TEXT;
                endIndex = MIN_VALUE_TEXT.length;
            } else {
                out.rawWriteByte((byte) '-');
                num = -num;
                endIndex = appendLong1(numberBuffer, num);
            }
        } else {
            endIndex = appendLong1(numberBuffer, num);
        }
        int digits = numberBuffer.length - endIndex;
        if (decimalPlaces >= digits) {
            out.writeUnsignedByte('0');
            out.writeUnsignedByte('.');
            while (decimalPlaces-- > digits)
                out.writeUnsignedByte('0');
            out.write(numberBuffer, endIndex, digits);
            return;
        }

        int decimalLength = numberBuffer.length - endIndex - decimalPlaces;
        out.write(numberBuffer, endIndex, decimalLength);
        out.writeUnsignedByte('.');
        out.write(numberBuffer, endIndex + decimalLength, digits - decimalLength);
    }

    public static void prepend(@NotNull BytesPrepender out, long num)
            throws BufferOverflowException, ClosedIllegalStateException {
        boolean neg = false;
        if (num < 0) {
            if (num == Long.MIN_VALUE) {
                out.prewrite(MIN_VALUE_TEXT);
                return;
            }
            neg = true;
            num = -num;
        }
        do {
            out.prewriteByte((byte) (num % 10 + '0'));
            num /= 10;
        } while (num > 0);
        if (neg)
            out.prewriteByte((byte) '-');
    }

    /**
     * The length of the number must be fixed otherwise short numbers will not overwrite longer
     * numbers
     *
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way.
     */
    public static void append(@NotNull RandomDataOutput out, @NonNegative long offset, long num, int digits)
            throws BufferOverflowException, IllegalArgumentException, ClosedIllegalStateException, ThreadingIllegalStateException {
        boolean negative = num < 0;
        num = Math.abs(num);

        for (int i = digits - 1; i > 0; i--) {
            out.writeByte(offset + i, (byte) (num % 10 + '0'));
            num /= 10;
        }
        if (negative) {
            if (num != 0)
                numberTooLarge(digits);
            out.writeByte(offset, (byte) '-');

        } else {
            if (num > 9)
                numberTooLarge(digits);
            out.writeByte(offset, (byte) (num % 10 + '0'));
        }
    }

    /**
     * Appends given long value with given decimalPlaces to RandomDataOutput out
     *
     * @param width Maximum width. I will be padded with zeros to the left if necessary
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way.
     */
    public static void appendDecimal(@NotNull RandomDataOutput out, long num, @NonNegative long offset, int decimalPlaces, int width)
            throws IORuntimeException, IllegalArgumentException, BufferOverflowException, ArithmeticException, ClosedIllegalStateException, ThreadingIllegalStateException {
        if (decimalPlaces == 0) {
            append(out, offset, num, width);
            return;
        }

        byte[] numberBuffer = BYTE_ARRAY_TL.get();
        int endIndex;
        if (num < 0) {
            if (num == Long.MIN_VALUE) {
                numberBuffer = MIN_VALUE_TEXT;
                endIndex = MIN_VALUE_TEXT.length;
            } else {
                out.writeByte(offset++, (byte) '-');
                num = -num;
                endIndex = appendLong1(numberBuffer, num);
            }
        } else {
            endIndex = appendLong1(numberBuffer, num);
        }
        int digits = numberBuffer.length - endIndex;

        if (decimalPlaces >= digits) {
            int numDigitsRequired = 2 + decimalPlaces;
            if (numDigitsRequired > width)
                throw new IllegalArgumentException("Value do not fit in " + width + " digits");
            out.writeUnsignedByte(offset++, '0');
            out.writeUnsignedByte(offset++, '.');
            while (decimalPlaces-- > digits)
                out.writeUnsignedByte(offset++, '0');
            out.write(offset, numberBuffer, endIndex, digits);
            return;
        } else {
            int numDigitsRequired = digits + 1;
            if (numDigitsRequired > width)
                throw new IllegalArgumentException("Value do not fit in " + width + " digits");
        }

        while (width-- > (digits + 1)) {
            out.writeUnsignedByte(offset++, '0');
        }

        int decimalLength = numberBuffer.length - endIndex - decimalPlaces;
        out.write(offset, numberBuffer, endIndex, decimalLength);
        offset += decimalLength;
        out.writeUnsignedByte(offset++, '.');
        out.write(offset, numberBuffer, endIndex + decimalLength, digits - decimalLength);
    }

    private static void numberTooLarge(int digits)
            throws IllegalArgumentException {
        throw new IllegalArgumentException("Number too large for " + digits + "digits");
    }

    private static void appendLong0(@NotNull StreamingDataOutput out, long num)
            throws BufferOverflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        if (num < 0) {
            if (num == Long.MIN_VALUE) {
                out.write(MIN_VALUE_TEXT);
                return;
            }
            out.rawWriteByte((byte) '-');
            num = -num;
        }
        if (num < 10) {
            out.rawWriteByte((byte) ('0' + num));

        } else if (num < 100) {
            out.writeShort((short) (num / 10 + (num % 10 << 8) + '0' * 0x101));

        } else {
            byte[] numberBuffer = BYTE_ARRAY_TL.get();
            // Extract digits into the end of the numberBuffer
            int endIndex = appendLong1(numberBuffer, num);

            // Bulk copy the digits into the front of the buffer
            out.write(numberBuffer, endIndex, numberBuffer.length - endIndex);
        }
    }

    private static int appendLong1(byte[] numberBuffer, long num) {
        numberBuffer[19] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 19;
        numberBuffer[18] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 18;
        numberBuffer[17] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 17;
        numberBuffer[16] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 16;
        numberBuffer[15] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 15;
        numberBuffer[14] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 14;
        numberBuffer[13] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 13;
        numberBuffer[12] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 12;
        numberBuffer[11] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 11;
        numberBuffer[10] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 10;
        numberBuffer[9] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 9;
        numberBuffer[8] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 8;
        numberBuffer[7] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 7;
        numberBuffer[6] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 6;
        numberBuffer[5] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 5;
        numberBuffer[4] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 4;
        numberBuffer[3] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 3;
        numberBuffer[2] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 2;
        numberBuffer[1] = (byte) (num % 10L + '0');
        return 1;
    }

    public static void append(@NotNull StreamingDataOutput out, double d)
            throws BufferOverflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        long val = Double.doubleToRawLongBits(d);
        int sign = (int) (val >>> 63);
        int exp = (int) ((val >>> 52) & 2047);
        long mantissa = val & ((1L << 52) - 1);
        if (sign != 0) {
            out.rawWriteByte((byte) '-');
        }
        if (exp == 0 && mantissa == 0) {
            out.rawWriteByte((byte) '0');
            out.rawWriteByte((byte) '.');
            out.rawWriteByte((byte) '0');
            return;

        } else if (exp == 2047) {
            if (mantissa == 0) {
                out.write(INFINITY_BYTES);

            } else {
                out.write(NAN_BYTES);
            }
            return;

        } else if (exp > 0) {
            mantissa += 1L << 52;
        }
        final int shift = (1023 + 52) - exp;
        if (shift > 0) {
            // integer and faction
            if (shift < 53) {
                long intValue = mantissa >> shift;
                appendLong0(out, intValue);
                mantissa -= intValue << shift;
                if (mantissa > 0) {
                    out.rawWriteByte((byte) '.');
                    mantissa <<= 1;
                    mantissa++;
                    int precision = shift + 1;
                    long error = 1;

                    long value = intValue;
                    int decimalPlaces = 0;
                    while (mantissa > error) {
                        // times 5*2 = 10
                        mantissa *= 5;
                        error *= 5;
                        precision--;
                        long num = (mantissa >> precision);
                        value = value * 10 + num;
                        out.rawWriteByte((byte) ('0' + num));
                        mantissa -= num << precision;

                        int deci = ++decimalPlaces;
                        final double parsedValue = Maths.asDouble(value, 0, sign != 0, deci);
                        if (parsedValue == d)
                            break;
                    }
                } else {
                    out.rawWriteByte((byte) '.');
                    out.rawWriteByte((byte) '0');
                }
                return;

            } else {
                // faction.
                out.rawWriteByte((byte) '0');
                out.rawWriteByte((byte) '.');
                mantissa <<= 6;
                mantissa += (1 << 5);
                int precision = shift + 6;

                long error = (1 << 5);

                long value = 0;
                int decimalPlaces = 0;
                while (mantissa > error) {
                    while (mantissa > MAX_VALUE_DIVIDE_5) {
                        mantissa >>>= 1;
                        error = (error + 1) >>> 1;
                        precision--;
                    }
                    // times 5*2 = 10
                    mantissa *= 5;
                    error *= 5;
                    precision--;
                    if (precision >= 64) {
                        decimalPlaces++;
                        out.rawWriteByte((byte) '0');
                        continue;
                    }
                    long num = (mantissa >>> precision);
                    value = value * 10 + num;
                    final char c = (char) ('0' + num);
                    assert !(c < '0' || c > '9');
                    out.rawWriteByte((byte) c);
                    mantissa -= num << precision;
                    int deci = ++decimalPlaces;
                    final double parsedValue = Maths.asDouble(value, 0, sign != 0, deci);
                    if (parsedValue == d)
                        break;
                }
                return;
            }
        }
        // large number
        mantissa <<= 10;
        int precision = -10 - shift;
        int digits = 0;
        while ((precision > 53 || mantissa > Long.MAX_VALUE >> precision) && precision > 0) {
            digits++;
            precision--;
            long mod = mantissa % 5;
            mantissa /= 5;
            int modDiv = 1;
            while (mantissa < MAX_VALUE_DIVIDE_5 && precision > 1) {
                precision -= 1;
                mantissa <<= 1;
                modDiv <<= 1;
            }
            mantissa += modDiv * mod / 5;
        }
        long val2 = precision > 0 ? mantissa << precision : mantissa >>> -precision;

        appendLong0(out, val2);
        for (int i = 0; i < digits; i++)
            out.rawWriteByte((byte) '0');
    }

    /**
     * Appends a formatted double value to a {@link ByteStringAppender} with a specified number of decimal places.
     * This method provides a high-performance way to append double values, optimizing for cases with up to 18 decimal places and up to 15 significant digits.
     * For higher precision, it falls back to using {@link BigDecimal}.
     *
     * @param bytesStringAppender the {@link ByteStringAppender} to append the formatted double to.
     * @param d                   the double value to format and append.
     * @param decimalPlaces       the number of decimal places to use in the formatted output. Must be non-negative.
     * @throws BufferOverflowException     if there is not enough space left in the {@link ByteStringAppender}.
     * @throws IllegalArgumentException    if the number of decimal places is negative.
     * @throws ClosedIllegalStateException if the {@link ByteStringAppender} has been closed.
     * @throws ArithmeticException         if an error occurs during arithmetic operations.
     */
    public static void append(@NotNull ByteStringAppender bytesStringAppender, double d, int decimalPlaces)
            throws BufferOverflowException, IllegalArgumentException, ClosedIllegalStateException, ArithmeticException {
        if (decimalPlaces < 0)
            throw new IllegalArgumentException("Number of decimal places cannot be negative");

        // For up to 18 decimal places, use fast path
        if (decimalPlaces > 18) {
            bytesStringAppender.append(d);
            return;
        }
        long factor = Maths.tens(decimalPlaces);
        // convert to double
        double d1 = d;
        // check for negative
        boolean neg = d1 < 0;
        d1 = Math.abs(d1);
        // scale it by the factor
        final double df = d1 * factor;
        if (df < 1e15) { // if the result up to 15 significant digits
            long ldf = (long) df; // truncate as a long
            // estimate the error in truncating
            final double residual = df - ldf + Math.ulp(d1) * (factor * 0.983);
            // should it round up.
            if (residual >= 0.5)
                ldf++;

            // restore the sign
            if (neg)
                ldf = -ldf;
            long round = ldf;

            if (bytesStringAppender.canWriteDirect(20L + decimalPlaces)) {
                long address = bytesStringAppender.addressForWritePosition();
                long address2 = UnsafeText.appendBase10d(address, round, decimalPlaces);
                bytesStringAppender.writeSkip(address2 - address);
            } else {
                bytesStringAppender.appendDecimal(round, decimalPlaces);
            }
            return;
        }
        // Fallback to BigDecimal for high precision cases
        bytesStringAppender.append(BigDecimal.valueOf(d).setScale(decimalPlaces, RoundingMode.HALF_UP));
    }

    @Nullable
    public static String readUtf8(@NotNull StreamingDataInput in)
            throws BufferUnderflowException, IORuntimeException, ClosedIllegalStateException, ArithmeticException {
        throwExceptionIfReleased(in);
        if (in.peekUnsignedByte() == 0x80 && in instanceof RandomDataInput) {
            RandomDataInput rdi = (RandomDataInput) in;
            if (rdi.peekUnsignedByte(in.readPosition() + 1) == 0) {
                in.readSkip(2);
                return null;
            }
        }
        try (ScopedResource<StringBuilder> stlSb = acquireStringBuilderScoped()) {
            StringBuilder sb = stlSb.get();
            return in.readUtf8(sb) ? SI.intern(sb) : null;
        }
    }

    @Nullable
    public static String readUtf8(@NotNull RandomDataInput in, @NonNegative long offset, @NonNegative int maxUtf8Len)
            throws BufferUnderflowException, IllegalArgumentException,
            ClosedIllegalStateException, IORuntimeException {
        throwExceptionIfReleased(in);
        try (ScopedResource<StringBuilder> stlSb = acquireStringBuilderScoped()) {
            StringBuilder sb = stlSb.get();
            return in.readUtf8Limited(offset, sb, maxUtf8Len) > 0 ? SI.intern(sb) : null;
        }
    }

    public static ScopedResource<StringBuilder> acquireStringBuilderScoped() {
        return STRING_BUILDER_SCOPED_RESOURCE_POOL.get();
    }

    /**
     * Acquire a scoped, thread-local bytes instance
     */
    public static ScopedResource<Bytes<?>> acquireBytesScoped() {
        return BYTES_SCOPED_THREAD_LOCAL.get();
    }

    @SuppressWarnings("unchecked")
    @Nullable
    public static String read8bit(@NotNull StreamingDataInput in)
            throws BufferUnderflowException, IORuntimeException, ArithmeticException, ClosedIllegalStateException {
        throwExceptionIfReleased(in);
        if (in.peekUnsignedByte() == 0x80 && in instanceof RandomDataInput) {
            RandomDataInput rdi = (RandomDataInput) in;
            // checks if the string was null
            if (rdi.peekUnsignedByte(in.readPosition() + 1) == 0) {
                in.readSkip(2);
                return null;
            }
        }
        try (ScopedResource<Bytes<?>> stlBytes = BytesInternal.acquireBytesScoped()) {
            Bytes<?> bytes = stlBytes.get();
            return in.read8bit(bytes) ? SI.intern(bytes, (int) bytes.readRemaining()) : null;
        }
    }

    @Nullable
    public static String parseUtf8(@NotNull StreamingDataInput bytes, @NotNull StopCharTester tester)
            throws ClosedIllegalStateException, ArithmeticException {
        throwExceptionIfReleased(bytes);
        requireNonNull(tester);
        try (ScopedResource<StringBuilder> stlSb = acquireStringBuilderScoped()) {
            StringBuilder utfReader = stlSb.get();
            parseUtf8(bytes, utfReader, tester);
            return SI.intern(utfReader);
        }
    }

    public static void parseUtf8(@NotNull StreamingDataInput bytes, @NotNull Appendable builder,
                                 @NotNull StopCharTester tester)
            throws BufferUnderflowException, ClosedIllegalStateException, ArithmeticException {
        throwExceptionIfReleased(bytes);
        throwExceptionIfReleased(builder);
        requireNonNull(tester);
        try {
            if (builder instanceof StringBuilder
                    && bytes.isDirectMemory()) {
                @NotNull Bytes<?> vb = (Bytes) bytes;
                @NotNull StringBuilder sb = (StringBuilder) builder;
                sb.setLength(0);
                readUtf8_SB1(vb, sb, tester);
            } else {
                AppendableUtil.setLength(builder, 0);
                readUtf81(bytes, builder, tester);
            }
        } catch (UTFDataFormatException e) {
            @NotNull UTFDataFormatRuntimeException e2 = new UTFDataFormatRuntimeException("Unable to parse invalid UTF-8 code", e);
            throw e2;

        } catch (IOException | IllegalArgumentException e) {
            throw Jvm.rethrow(e);
        }
    }

    private static void readUtf8_SB1(
            @NotNull Bytes<?> bytes, @NotNull StringBuilder appendable, @NotNull StopCharTester tester)
            throws IOException, ClosedIllegalStateException {

        @Nullable final NativeBytesStore nb = (NativeBytesStore) bytes.bytesStore();
        int i = 0;
        final int len = (int) Math.min(bytes.realReadRemaining(), Integer.MAX_VALUE);
        final long address = nb.address + nb.translate(bytes.readPosition());
        @Nullable final Memory memory = nb.memory;

        if (Jvm.isJava9Plus()) {
            final int appendableLength = appendable.capacity();
            for (; i < len && i < appendableLength; i++) {
                int c = memory.readByte(address + i);
                if (c < 0) // we have hit a non-ASCII character.
                    break;
                if (tester.isStopChar(c)) {
                    bytes.readSkip(i + 1L);
                    StringUtils.setCount(appendable, i);
                    return;
                }
                appendable.append((char) c);
            }
        } else {
            final char[] chars = StringUtils.extractChars(appendable);
            for (; i < len && i < chars.length; i++) {
                int c = memory.readByte(address + i);
                if (c < 0) // we have hit a non-ASCII character.
                    break;
                if (tester.isStopChar(c)) {
                    bytes.readSkip(i + 1L);
                    StringUtils.setCount(appendable, i);
                    return;
                }
                chars[i] = (char) c;
            }
        }
        StringUtils.setCount(appendable, i);
        bytes.readSkip(i);
        if (i < len) {
            readUtf8_SB2(bytes, appendable, tester);
        }
    }

    private static void readUtf8_SB2(@NotNull StreamingDataInput bytes, @NotNull StringBuilder appendable, @NotNull StopCharTester tester)
            throws UTFDataFormatException, ClosedIllegalStateException {
        while (true) {
            int c = bytes.readUnsignedByte();
            switch (c >> 4) {
                case 0:
                case 1:
                case 2:
                case 3:
                case 4:
                case 5:
                case 6:
                case 7:
                    /* 0xxxxxxx */
                    if (tester.isStopChar(c))
                        return;
                    appendable.append((char) c);
                    break;

                case 12:
                case 13: {
                    /* 110x xxxx 10xx xxxx */
                    int char2 = bytes.readUnsignedByte();
                    if ((char2 & 0xC0) != 0x80)
                        throw newUTFDataFormatException(-1, "");
                    int c2 = (char) (((c & 0x1F) << 6) |
                            (char2 & 0x3F));
                    if (tester.isStopChar(c2))
                        return;
                    appendable.append((char) c2);
                    break;
                }

                case 14: {
                    /* 1110 xxxx 10xx xxxx 10xx xxxx */
                    int char2 = bytes.readUnsignedByte();
                    int char3 = bytes.readUnsignedByte();

                    if (((char2 & 0xC0) != 0x80) || ((char3 & 0xC0) != 0x80))
                        throw newUTFDataFormatException(-1, "");
                    int c3 = (char) (((c & 0x0F) << 12) |
                            ((char2 & 0x3F) << 6) |
                            (char3 & 0x3F));
                    if (tester.isStopChar(c3))
                        return;
                    appendable.append((char) c3);
                    break;
                }

                default:
                    /* 10xx xxxx, 1111 xxxx */
                    throw newUTFDataFormatException(-1, "");
            }
        }
    }

    private static UTFDataFormatException newUTFDataFormatException(@NonNegative final long offset, final String suffix) {
        return new UTFDataFormatException(MALFORMED_INPUT_AROUND_BYTE + offset + " " + suffix);
    }

    private static UTFDataFormatRuntimeException newUTFDataFormatRuntimeException(@NonNegative final long offset, final String suffix) {
        return new UTFDataFormatRuntimeException(MALFORMED_INPUT_AROUND_BYTE + offset + " " + suffix);
    }

    private static void readUtf81(@NotNull StreamingDataInput bytes, @NotNull Appendable appendable, @NotNull StopCharTester tester)
            throws IOException, BufferUnderflowException, ArithmeticException, ClosedIllegalStateException {
        int len = (int) Math.min(bytes.readRemaining(), Integer.MAX_VALUE);
        while (len-- > 0) {
            int c = bytes.rawReadByte() & 0xff;
            if (c >= 128) {
                bytes.readSkip(-1);
                break;
            }
            if (tester.isStopChar(c))
                return;
            appendable.append((char) c);
        }
        if (len <= 0)
            return;

        readUtf82(bytes, appendable, tester);
    }

    private static void readUtf82(@NotNull StreamingDataInput bytes, @NotNull Appendable appendable, @NotNull StopCharTester tester)
            throws IOException, ClosedIllegalStateException {
        while (true) {
            int c = bytes.readUnsignedByte();
            switch (c >> 4) {
                case 0:
                case 1:
                case 2:
                case 3:
                case 4:
                case 5:
                case 6:
                case 7:
                    /* 0xxxxxxx */
                    if (tester.isStopChar(c))
                        return;
                    appendable.append((char) c);
                    break;

                case 12:
                case 13: {
                    /* 110x xxxx 10xx xxxx */
                    int char2 = bytes.readUnsignedByte();
                    if ((char2 & 0xC0) != 0x80)
                        throw new UTFDataFormatException(
                                "malformed input around byte");
                    int c2 = (char) (((c & 0x1F) << 6) |
                            (char2 & 0x3F));
                    if (tester.isStopChar(c2))
                        return;
                    appendable.append((char) c2);
                    break;
                }

                case 14: {
                    /* 1110 xxxx 10xx xxxx 10xx xxxx */
                    int char2 = bytes.readUnsignedByte();
                    int char3 = bytes.readUnsignedByte();

                    if (((char2 & 0xC0) != 0x80) || ((char3 & 0xC0) != 0x80))
                        throw new UTFDataFormatException(
                                MALFORMED_INPUT_AROUND_BYTE);
                    int c3 = (char) (((c & 0x0F) << 12) |
                            ((char2 & 0x3F) << 6) |
                            (char3 & 0x3F));
                    if (tester.isStopChar(c3))
                        return;
                    appendable.append((char) c3);
                    break;
                }

                default:
                    /* 10xx xxxx, 1111 xxxx */
                    throw new UTFDataFormatException(
                            MALFORMED_INPUT_AROUND_BYTE);
            }
        }
    }

    public static void parseUtf8(@NotNull StreamingDataInput bytes, @NotNull Appendable builder, @NotNull StopCharsTester tester)
            throws BufferUnderflowException, IORuntimeException, ClosedIllegalStateException {
        try {
            AppendableUtil.setLength(builder, 0);
            AppendableUtil.readUtf8AndAppend(bytes, builder, tester);
        } catch (IOException | IllegalArgumentException e) {
            throw Jvm.rethrow(e);
        }
    }

    public static void parse8bit(@NotNull StreamingDataInput bytes, @NotNull StringBuilder builder, @NotNull StopCharsTester tester)
            throws ClosedIllegalStateException {
        throwExceptionIfReleased(bytes);
        requireNonNull(builder);
        requireNonNull(tester);
        builder.setLength(0);
        AppendableUtil.read8bitAndAppend(bytes, builder, tester);
    }

    public static void parse8bit(@NotNull StreamingDataInput bytes, @NotNull Bytes<?> builder, @NotNull StopCharsTester tester)
            throws BufferUnderflowException, BufferOverflowException, ClosedIllegalStateException, ArithmeticException, ThreadingIllegalStateException {
        throwExceptionIfReleased(bytes);
        throwExceptionIfReleased(builder);
        requireNonNull(tester);
        builder.readPosition(0);
        read8bitAndAppend(bytes, builder, tester);
    }

    public static void parse8bit(@NotNull StreamingDataInput bytes, @NotNull StringBuilder builder, @NotNull StopCharTester tester)
            throws ClosedIllegalStateException {
        throwExceptionIfReleased(bytes);
        requireNonNull(tester);
        builder.setLength(0);
        read8bitAndAppend(bytes, builder, tester);
    }

    public static void parse8bit(@NotNull StreamingDataInput bytes, @NotNull Bytes<?> builder, @NotNull StopCharTester tester)
            throws BufferUnderflowException, BufferOverflowException, ClosedIllegalStateException, ArithmeticException, ThreadingIllegalStateException {
        throwExceptionIfReleased(bytes);
        throwExceptionIfReleased(builder);
        requireNonNull(tester);
        builder.clear();

        read8bitAndAppend(bytes, builder, tester);
    }

    private static void read8bitAndAppend(@NotNull StreamingDataInput bytes, @NotNull StringBuilder appendable, @NotNull StopCharTester tester)
            throws ClosedIllegalStateException {
        throwExceptionIfReleased(bytes);
        throwExceptionIfReleased(appendable);
        requireNonNull(tester);
        while (true) {
            int c = bytes.readUnsignedByte();
            if (tester.isStopChar(c))
                return;
            appendable.append((char) c);
            if (bytes.readRemaining() == 0)
                return;
        }
    }

    private static void read8bitAndAppend(@NotNull StreamingDataInput bytes, @NotNull Bytes<?> bytes2, @NotNull StopCharTester tester)
            throws BufferOverflowException, ClosedIllegalStateException, ArithmeticException, ThreadingIllegalStateException {
        throwExceptionIfReleased(bytes);
        throwExceptionIfReleased(bytes2);
        requireNonNull(tester);
        while (true) {
            int c = bytes.readUnsignedByte();
            if (tester.isStopChar(c))
                return;
            bytes2.writeUnsignedByte(c);
            if (bytes.readRemaining() == 0)
                return;
        }
    }

    private static void read8bitAndAppend(@NotNull StreamingDataInput bytes, @NotNull Bytes<?> bytes2, @NotNull StopCharsTester tester)
            throws BufferUnderflowException, BufferOverflowException, ClosedIllegalStateException, ArithmeticException, ThreadingIllegalStateException {
        throwExceptionIfReleased(bytes);
        throwExceptionIfReleased(bytes2);
        requireNonNull(tester);
        int ch = bytes.readUnsignedByte();
        do {
            int next = bytes.readUnsignedByte();
            if (tester.isStopChar(ch, next)) {
                bytes.readSkip(-1);
                return;
            }
            bytes2.writeUnsignedByte(ch);
            ch = next;
        } while (bytes.readRemaining() > 0);

        if (tester.isStopChar(ch, -1)) {
            bytes.readSkip(-1);
            return;
        }
        bytes2.writeUnsignedByte(ch);
    }

    public static long parseFlexibleLong(@NotNull StreamingDataInput in)
            throws BufferUnderflowException, ClosedIllegalStateException, IORuntimeException {
        long absValue = 0; // Math.abs(absValue) == absValue
        int sign = 1;
        int decimalPlaces = Integer.MIN_VALUE;
        boolean digits = false;
        int ch;
        do {
            ch = in.rawReadByte() & 0xFF;
        } while (ch == ' ' && in.readRemaining() > 0);

        try {
            switch (ch) {
                case 'N':
                    if (compareRest(in, "aN"))
                        throw new IORuntimeException("Expected flexible long, but got: NaN");
                    in.readSkip(-1);

                    throw new IORuntimeException("Expected flexible long, but got: N");
                case 'I':
                    //noinspection SpellCheckingInspection
                    if (compareRest(in, "nfinity"))
                        throw new IORuntimeException("Expected flexible long, but got: Infinity");
                    in.readSkip(-1);
                    throw new IORuntimeException("Expected flexible long, but got: I");
                case '-':
                    if (compareRest(in, INFINITY))
                        throw new IORuntimeException("Expected flexible long, but got: -Infinity");
                    sign = -1;
                    ch = in.rawReadByte();
                    break;
                case '+':
                    if (compareRest(in, INFINITY))
                        throw new IORuntimeException("Expected flexible long, but got: +Infinity");
                    ch = in.rawReadByte();
                    break;
                default:
                    // Do nothing
            }

            int tens = 0;
            IORuntimeException parsingError = null;
            while (true) {
                if (ch >= '0' && ch <= '9') {
                    // -absValue is always negative!
                    if (-absValue < -MAX_VALUE_DIVIDE_10) {
                        if (ch == '0')
                            tens++;
                        else if (parsingError == null) {
                            parsingError = new IORuntimeException(CAN_T_PARSE_FLEXIBLE_LONG_WITHOUT_PRECISION_LOSS +
                                    (sign * absValue) + " <- " + ((char) ch));
                        }

                    } else if (absValue == MAX_VALUE_DIVIDE_10) {
                        if (ch <= '7' || (sign < 0 && ch == '8'))
                            absValue = absValue * 10 + (ch - '0');
                        else if (parsingError == null) {
                            parsingError = new IORuntimeException(CAN_T_PARSE_FLEXIBLE_LONG_WITHOUT_PRECISION_LOSS +
                                    (sign * absValue) + " <- " + ((char) ch));
                        }
                    } else {
                        absValue = absValue * 10 + (ch - '0');
                    }
                    decimalPlaces++;
                    digits = true;

                } else if (ch == '.') {
                    decimalPlaces = 0;

                } else if (ch == 'E' || ch == 'e') {
                    tens += (int) parseLong(in);
                    break;

                } else {
                    break;

                }
                if (in.readRemaining() == 0)
                    break;
                ch = in.rawReadByte();
            }

            if (parsingError != null)
                throw parsingError;

            if (!digits)
                return 0L;

            if (decimalPlaces < 0)
                decimalPlaces = 0;

            tens -= decimalPlaces;

            if (tens <= 0) {
                absValue *= sign;

                for (int i = 0; i < -tens; i++) {
                    int truncatingDigit = (int) Math.abs(absValue % 10);

                    if (truncatingDigit != 0) {
                        throw new IORuntimeException(CAN_T_PARSE_FLEXIBLE_LONG_WITHOUT_PRECISION_LOSS +
                                "division of " + absValue + " by 10");
                    }

                    absValue /= 10;
                }

                return absValue;
            } else {
                for (int i = 0; i < tens; i++) {
                    if (-absValue < -MAX_VALUE_DIVIDE_10) {
                        throw new IORuntimeException("Can't parse flexible long as it goes beyond the range: " +
                                "multiplication of " + absValue + " by 10");
                    } else
                        absValue *= 10;
                }

                return sign * absValue;
            }
        } finally {
            final ByteStringParser bsp = (ByteStringParser) in;
            bsp.lastDecimalPlaces(decimalPlaces);
            bsp.lastNumberHadDigits(digits);
        }
    }

    public static double parseDouble(@NotNull StreamingDataInput in)
            throws BufferUnderflowException, ClosedIllegalStateException {
        long value = 0;
        int exp = 0;
        boolean negative = false;
        int decimalPlaces = Integer.MIN_VALUE;
        boolean digits = false;
        int ch = ' ';
        while (in.readRemaining() > 0) {
            ch = in.peekUnsignedByte() & 0xFF;
            if (ch != ' ')
                break;
            else
                in.readSkip(1);
        }

        try {
            switch (ch) {
                case 'N':
                    if (compareRest(in, NAN))
                        return Double.NaN;

                    return Double.NaN;
                case 'I':
                    if (compareRest(in, INFINITY))
                        return Double.POSITIVE_INFINITY;

                    return Double.NaN;
                case '-':
                    negative = true;
                    in.readSkip(1);
                    if (compareRest(in, INFINITY))
                        return Double.NEGATIVE_INFINITY;
                    break;
                case '+':
                    in.readSkip(1);
                    if (compareRest(in, INFINITY))
                        return Double.POSITIVE_INFINITY;
                    break;
                default:
                    // do nothing
            }
            int tens = 0;
            while (in.readRemaining() > 0) {
                ch = in.readUnsignedByte() & 0xFF;
                if (ch >= '0' && ch <= '9') {
                    while (value >= MAX_VALUE_DIVIDE_10) {
                        value >>>= 1;
                        exp++;
                    }
                    value = value * 10 + (ch - '0');
                    decimalPlaces++;
                    digits = true;

                } else if (ch == '.') {
                    decimalPlaces = 0;

                } else if (ch == 'E' || ch == 'e') {
                    tens = (int) parseLong(in);
                    break;

                } else {
                    break;

                }
            }
            if (!digits)
                return -0.0;
            if (decimalPlaces < 0)
                decimalPlaces = 0;

            decimalPlaces = decimalPlaces - tens;

            return Maths.asDouble(value, exp, negative, decimalPlaces);
        } finally {
            final ByteStringParser bsp = (ByteStringParser) in;
            bsp.lastDecimalPlaces(decimalPlaces);
            bsp.lastNumberHadDigits(digits);
        }
    }

    static boolean compareRest(@NotNull StreamingDataInput in, @NotNull String s)
            throws BufferUnderflowException, ClosedIllegalStateException {
        if (s.length() > in.readRemaining())
            return false;
        long position = in.readPosition();
        for (int i = 0; i < s.length(); i++) {
            if (in.readUnsignedByte() != s.charAt(i)) {
                in.readPosition(position);
                return false;
            }
        }
        int ch = in.readUnsignedByte();
        if (Character.isLetterOrDigit(ch)) {
            in.readPosition(position);
            return false;
        }
        while (Character.isWhitespace(ch) && ch >= ' ')
            ch = in.readUnsignedByte();
        return true;
    }

    public static long parseLong(@NotNull StreamingDataInput in)
            throws BufferUnderflowException, ClosedIllegalStateException {
        long num = 0;
        boolean negative = false;
        int b = in.peekUnsignedByte();
        while (b > 0 && b <= ' ') {
            in.readSkip(1);
            b = in.peekUnsignedByte();
        }
        boolean digits = false;
        if (b == '0') {
            in.readSkip(1);
            b = in.peekUnsignedByte();
            digits = true;
            if (b == 'x' || b == 'X') {
                in.readSkip(1);
                return parseLongHexaDecimal(in);
            }
        }
        while (in.readRemaining() > 0) {
            b = in.rawReadByte();
            if ((b - ('0' + Integer.MIN_VALUE)) <= 9 + Integer.MIN_VALUE) {
                num = num * 10 + b - '0';
                digits = true;
            } else if (b == '-') {
                negative = true;
            } else if (b == ']' || b == '}') {
                in.readSkip(-1);
                break;
            } else if (b == '.') {
                consumeDecimals(in);
                break;
            } else if (b == '_' || b == '+') {
                // ignore
            } else {
                break;
            }
        }
        ((ByteStringParser) in).lastNumberHadDigits(digits);
        return negative ? -num : num;
    }

    private static long parseLongHexaDecimal(@NotNull StreamingDataInput in)
            throws ClosedIllegalStateException, BufferUnderflowException {
        long num = 0;
        while (in.readRemaining() > 0) {
            int b = in.readUnsignedByte();
            if ((b - ('0' + Integer.MIN_VALUE)) <= 9 + Integer.MIN_VALUE) {
                num = (num << 4) + b - '0';
            } else if ((b - ('A' + Integer.MIN_VALUE)) < 6 + Integer.MIN_VALUE) {
                num = (num << 4) + b - ('A' - 10);
            } else if ((b - ('a' + Integer.MIN_VALUE)) < 6 + Integer.MIN_VALUE) {
                num = (num << 4) + b - ('a' - 10);
            } else if (b == ']' || b == '}') {
                in.readSkip(-1);
                break;
            } else if (b == '.') {
                consumeDecimals(in);
                break;
            } else if (b == '_') {
                // ignore
            } else {
                break;
            }
        }
        return num;
    }

    static void consumeDecimals(@NotNull StreamingDataInput in)
            throws ClosedIllegalStateException {
        int b;
        while (in.readRemaining() > 0) {
            b = in.readUnsignedByte();
            if (b < '0' || b > '9') {
                break;
            }
        }
    }

    public static long parseLongDecimal(@NotNull StreamingDataInput in)
            throws BufferUnderflowException, ClosedIllegalStateException {
        long num = 0;
        boolean negative = false;
        int decimalPlaces = Integer.MIN_VALUE;
        boolean digits = false;
        boolean first = true;
        while (in.readRemaining() > 0) {
            int b = in.readUnsignedByte();
            if ((b - ('0' + Integer.MIN_VALUE)) <= 9 + Integer.MIN_VALUE) {
                num = num * 10 + b - '0';
                decimalPlaces++;
                digits = true;
                first = false;
            } else if (b == '.') {
                decimalPlaces = 0;
                first = false;
            } else if (b == '-') {
                negative = true;
                first = false;
            } else if (b == ']' || b == '}') {
                in.readSkip(-1);
                break;
            } else if (b == '_' || b == '+') {
                // ignore
                first = false;
            } else if (!first || b > ' ') {
                break;
            } else if (b == 0) {
                break;
            }
        }
        final ByteStringParser bsp = (ByteStringParser) in;
        bsp.lastDecimalPlaces(decimalPlaces);
        bsp.lastNumberHadDigits(digits);
        return negative ? -num : num;
    }

    public static long parseHexLong(@NotNull StreamingDataInput in)
            throws BufferUnderflowException, ClosedIllegalStateException {
        long num = 0;
        while (in.readRemaining() > 0) {
            int b = in.readUnsignedByte();
            if ((b - ('0' + Integer.MIN_VALUE)) <= 9 + Integer.MIN_VALUE) {
                num = num * 16 + b - '0';
            } else if ((b - ('A' + Integer.MIN_VALUE)) <= 6 + Integer.MIN_VALUE) {
                num = num * 16 + b - 'A' + 10;
            } else if ((b - ('a' + Integer.MIN_VALUE)) <= 6 + Integer.MIN_VALUE) {
                num = num * 16 + b - 'a' + 10;
            } else if (b == ']' || b == '}') {
                in.readSkip(-1);
                break;
            } else if (b == '_') {
                // ignore
            } else {
                break;
            }
        }
        return num;
    }

    public static long parseLong(@NotNull RandomDataInput in, @NonNegative long offset)
            throws BufferUnderflowException, ClosedIllegalStateException {
        long num = 0;
        boolean negative = false;
        while (true) {
            int b = in.peekUnsignedByte(offset++);
            if ((b - ('0' + Integer.MIN_VALUE)) <= 9 + Integer.MIN_VALUE)
                num = num * 10 + b - '0';
            else if (b == '-')
                negative = true;
            else if (b != '_' && b != '+')
                break;
        }
        return negative ? -num : num;
    }

    public static boolean skipTo(@NotNull ByteStringParser parser, @NotNull StopCharTester tester)
            throws ClosedIllegalStateException {
        while (parser.readRemaining() > 0) {
            int ch = parser.readUnsignedByte();
            if (tester.isStopChar(ch))
                return true;
        }
        return false;
    }

    public static float addAndGetFloat(@NotNull BytesStore<?, ?> in, @NonNegative long offset, float adding)
            throws BufferUnderflowException, ClosedIllegalStateException {
        for (; ; ) {
            int value = in.readVolatileInt(offset);
            float value1 = Float.intBitsToFloat(value) + adding;
            int value2 = Float.floatToRawIntBits(value1);
            if (in.compareAndSwapInt(offset, value, value2))
                return value1;
        }
    }

    public static double addAndGetDouble(@NotNull BytesStore<?, ?> in, @NonNegative long offset, double adding)
            throws BufferUnderflowException, ClosedIllegalStateException {
        for (; ; ) {
            long value = in.readVolatileLong(offset);
            double value1 = Double.longBitsToDouble(value) + adding;
            long value2 = Double.doubleToRawLongBits(value1);
            if (in.compareAndSwapLong(offset, value, value2))
                return value1;
        }
    }

    public static int addAndGetInt(@NotNull BytesStore<?, ?> in, @NonNegative long offset, int adding)
            throws BufferUnderflowException, ClosedIllegalStateException {
        for (; ; ) {
            int value = in.readVolatileInt(offset);
            int value2 = value + adding;
            if (in.compareAndSwapInt(offset, value, value2))
                return value2;
        }
    }

    public static long addAndGetLong(@NotNull BytesStore<?, ?> in, @NonNegative long offset, long adding)
            throws BufferUnderflowException, ClosedIllegalStateException {
        for (; ; ) {
            long value = in.readVolatileLong(offset);
            long value2 = value + adding;
            if (in.compareAndSwapLong(offset, value, value2))
                return value2;
        }
    }

    /**
     * Converts the content of the provided Bytes buffer to a hexadecimal string representation
     * along with comments describing the meaning of the bytes.
     * <p>
     * The method reads the data from the offset with maxLength of the given bytes. Each line of the
     * output string contains hexadecimal representation of 16 bytes, followed by comments describing
     * the meaning of those bytes.
     * <p>
     * For example, a buffer containing bytes representing the ASCII string "VMH" would produce an
     * output like this:
     * <pre>
     * c3 76 6d 68                                     # vmh:
     * b6 03 56 4d 48                                  # VMH
     * </pre>
     *
     * @param bytes     The buffer whose content is to be converted to a hexadecimal string.
     *                  Must not be null.
     * @param offset    The starting offset within the buffer to begin the hexadecimal conversion.
     * @param maxLength The number of bytes to convert from the buffer.
     * @return A hexadecimal representation of the buffer content with comments describing the bytes.
     * Each line of the string contains the hexadecimal representation of 16 bytes.
     * @throws BufferUnderflowException    If there is not enough data in the buffer.
     * @throws ClosedIllegalStateException If the resource has been released or closed.
     * @throws IllegalArgumentException    If the provided offset or maxLength are negative.
     */
    public static String toHexString(@NotNull final Bytes<?> bytes, @NonNegative long offset, @NonNegative long maxLength)
            throws BufferUnderflowException {
        try {
            throwExceptionIfReleased(bytes);
        } catch (ClosedIllegalStateException e) {
            return e.toString();
        }
        if (maxLength == 0)
            return "";

        int width = 16;
        int[] lastLine = new int[width];
        @NotNull String sep = "";
        long position = bytes.readPosition();
        long limit = bytes.readLimit();

        try {
            bytes.readPositionRemaining(offset, maxLength);

            @NotNull final StringBuilder builder = new StringBuilder();
            long start = offset / width * width;
            long end = (offset + maxLength + width - 1) / width * width;
            for (long i = start; i < end; i += width) {
                // check for duplicate rows
                if (i + width < end) {
                    boolean same = true;

                    for (int j = 0; j < width && i + j < offset + maxLength; j++) {
                        int ch = bytes.readUnsignedByte(i + j);
                        same &= (ch == lastLine[j]);
                        lastLine[j] = ch;
                    }
                    if (i > start && same) {
                        sep = "........\n";
                        continue;
                    }
                }
                builder.append(sep);
                sep = "";
                String str = Long.toHexString(i);
                for (int j = str.length(); j < 8; j++)
                    builder.append('0');
                builder.append(str);
                for (int j = 0; j < width; j++) {
                    if (j == width / 2)
                        builder.append(' ');
                    if (i + j < offset || i + j >= offset + maxLength) {
                        builder.append("   ");

                    } else {
                        builder.append(' ');
                        int ch = bytes.readUnsignedByte(i + j);
                        builder.append(HEXADECIMAL[ch >> 4]);
                        builder.append(HEXADECIMAL[ch & 15]);
                    }
                }
                builder.append(' ');
                for (int j = 0; j < width; j++) {
                    if (j == width / 2)
                        builder.append(' ');
                    if (i + j < offset || i + j >= offset + maxLength) {
                        builder.append(' ');

                    } else {
                        int ch = bytes.readUnsignedByte(i + j);
                        if (ch < ' ' || ch > 126)
                            ch = '\u00B7';
                        builder.append((char) ch);
                    }
                }
                builder.append("\n");
            }
            return builder.toString();
        } catch (ClosedIllegalStateException e) {
            return e.toString();
        } finally {
            bytes.readLimit(limit);
            bytes.readPosition(position);
        }
    }

    public static void appendTimeMillis(@NotNull ByteStringAppender b, long timeInMS)
            throws BufferOverflowException, ClosedIllegalStateException, IllegalArgumentException, ThreadingIllegalStateException {
        int hours = (int) (timeInMS / (60 * 60 * 1000));
        if (hours > 99) {
            b.append(hours); // can have over 24 hours.
        } else {
            b.rawWriteByte((byte) (hours / 10 + '0'));
            b.rawWriteByte((byte) (hours % 10 + '0'));
        }
        b.rawWriteByte((byte) ':');
        int minutes = (int) ((timeInMS / (60 * 1000)) % 60);
        b.rawWriteByte((byte) (minutes / 10 + '0'));
        b.rawWriteByte((byte) (minutes % 10 + '0'));
        b.rawWriteByte((byte) ':');
        int seconds = (int) ((timeInMS / 1000) % 60);
        b.rawWriteByte((byte) (seconds / 10 + '0'));
        b.rawWriteByte((byte) (seconds % 10 + '0'));
        b.rawWriteByte((byte) '.');
        int millis = (int) (timeInMS % 1000);
        b.rawWriteByte((byte) (millis / 100 + '0'));
        b.rawWriteByte((byte) (millis / 10 % 10 + '0'));
        b.rawWriteByte((byte) (millis % 10 + '0'));
    }

    public static boolean equalBytesAny(@NotNull BytesStore<?, ?> b1, @NotNull BytesStore<?, ?> b2, @NonNegative long readRemaining)
            throws BufferUnderflowException, ClosedIllegalStateException {

        if (Math.min(b1.readRemaining(), b2.readRemaining()) < readRemaining)
            return false;

        long i = 0;
        long rp1 = b1.readPosition();
        long rp2 = b2.readPosition();
        for (; i < readRemaining - 7 &&
                canReadBytesAt(b1, rp1 + i, 8) &&
                canReadBytesAt(b2, rp2 + i, 8); i += 8) {
            long l1 = b1.readLong(rp1 + i);
            long l2 = b2.readLong(rp2 + i);
            if (l1 != l2)
                return false;
        }
        for (; i < readRemaining &&
                canReadBytesAt(b1, rp1 + i, 1) &&
                canReadBytesAt(b2, rp2 + i, 1); i++) {
            byte i1 = b1.readByte(rp1 + i);
            byte i2 = b2.readByte(rp2 + i);
            if (i1 != i2)
                return false;
        }
        return true;
    }

    public static void appendDateMillis(@NotNull ByteStringAppender b, long timeInMS)
            throws BufferOverflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        DateCache dateCache = dateCacheTL.get();
        if (dateCache == null) {
            dateCache = new DateCache();
            dateCacheTL.set(dateCache);
        }
        final long date = timeInMS / 86400000;
        if (dateCache.lastDay != date) {
            dateCache.lastDateStr = dateCache.dateFormat.format(new Date(timeInMS)).getBytes(ISO_8859_1);
            dateCache.lastDay = date;

        } else {
            assert dateCache.lastDateStr != null;
        }
        b.write(dateCache.lastDateStr);
    }

    @SuppressWarnings("unchecked")
    @NotNull
    public static <E extends Enum<E>, S extends StreamingDataInput<S>> E readEnum(@NotNull StreamingDataInput input, @NotNull Class<E> eClass)
            throws BufferUnderflowException, IORuntimeException, BufferOverflowException, ClosedIllegalStateException, ArithmeticException {
        try (ScopedResource<Bytes<?>> stlBytes = BytesInternal.acquireBytesScoped()) {
            Bytes<?> bytes = stlBytes.get();
            input.read8bit(bytes);

            return (E) EnumInterner.ENUM_INTERNER.get(eClass).intern(bytes);
        }
    }

    public static void writeFully(@NotNull final RandomDataInput bytes,
                                  @NonNegative final long offset,
                                  @NonNegative final long length,
                                  @NotNull final StreamingDataOutput sdo)
            throws BufferUnderflowException, BufferOverflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        long i = 0;

        if (bytes instanceof HasUncheckedRandomDataInput) {
            // Do boundary checking outside the inner loop
            if (length + offset > bytes.capacity()) {
                throw new DecoratedBufferOverflowException("Cannot read " + length + " bytes as offset is " + offset + " and capacity is " + bytes.capacity());
            }
            final UncheckedRandomDataInput uBytes = ((HasUncheckedRandomDataInput) bytes).acquireUncheckedInput();
            for (; i < length - 7; i += 8)
                sdo.rawWriteLong(uBytes.readLong(offset + i));
            if (i < length - 3) {
                sdo.rawWriteInt(uBytes.readInt(offset + i));
                i += 4;
            }
            for (; i < length; i++)
                sdo.rawWriteByte(uBytes.readByte(offset + i));
        } else {
            for (; i < length - 7; i += 8)
                sdo.rawWriteLong(bytes.readLong(offset + i));
            if (i < length - 3) {
                sdo.rawWriteInt(bytes.readInt(offset + i));
                i += 4;
            }
            for (; i < length; i++)
                sdo.rawWriteByte(bytes.readByte(offset + i));
        }
    }

    public static void copyMemory(long from, long to, int length) {
        UnsafeMemory.copyMemory(from, to, length);
    }

    @NotNull
    public static byte[] toByteArray(@NotNull RandomDataInput in)
            throws ClosedIllegalStateException {
        final int len = (int) Math.min(Bytes.MAX_HEAP_CAPACITY, in.readRemaining());
        throwExceptionIfReleased(in);
        @NotNull byte[] bytes = new byte[len];
        in.read(in.readPosition(), bytes, 0, bytes.length);
        return bytes;
    }

    public static void copy(@NotNull final RandomDataInput input, @NotNull final OutputStream output)
            throws IOException, ClosedIllegalStateException {
        requireNonNull(input);
        requireNonNull(output);
        throwExceptionIfReleased(input);
        final byte[] bytes = new byte[512];
        final long start = input.readPosition();
        long i = 0;
        for (int len; (len = (int) input.read(start + i, bytes, 0, bytes.length)) > 0; i += len) {
            output.write(bytes, 0, len);
        }
    }

    public static void copy(@NotNull InputStream input, @NotNull StreamingDataOutput output)
            throws IOException, BufferOverflowException, ClosedIllegalStateException, ThreadingIllegalStateException {
        @NotNull byte[] bytes = new byte[512];
        for (int len; (len = input.read(bytes)) > 0; ) {
            output.write(bytes, 0, len);
        }
    }

    public static Boolean parseBoolean(@NotNull ByteStringParser parser, @NotNull StopCharTester tester)
            throws BufferUnderflowException, ClosedIllegalStateException, ArithmeticException {
        try (ScopedResource<Bytes<?>> stlBytes = BytesInternal.acquireBytesScoped()) {
            Bytes<?> sb = stlBytes.get();
            parseUtf8(parser, sb, tester);
            if (sb.length() == 0)
                return null;
            switch (sb.charAt(0)) {
                case 't':
                case 'T':
                    return sb.length() == 1 || StringUtils.equalsCaseIgnore(sb, "true") ? true : null;
                case 'y':
                case 'Y':
                    return sb.length() == 1 || StringUtils.equalsCaseIgnore(sb, "yes") ? true : null;
                case '0':
                    return sb.length() == 1 ? false : null;
                case '1':
                    return sb.length() == 1 ? true : null;
                case 'f':
                case 'F':
                    return sb.length() == 1 || StringUtils.equalsCaseIgnore(sb, "false") ? false : null;
                case 'n':
                case 'N':
                    return sb.length() == 1 || StringUtils.equalsCaseIgnore(sb, "no") ? false : null;
                default:
                    return null;
            }
        }
    }

    @NotNull
    public static BytesStore<?, ?> subBytes(RandomDataInput from, @NonNegative long start, @NonNegative long length)
            throws BufferUnderflowException, ClosedIllegalStateException {
        @NotNull BytesStore<?, ?> ret;
        if (from.isDirectMemory()) {
            ret = BytesStore.nativeStore(Math.max(0, length));
        } else {
            ret = BytesStore.wrap(new byte[Math.toIntExact(length)]);
        }
        ret.write(0L, from, start, length);
        return ret;
    }

    public static long findByte(@NotNull RandomDataInput bytes, byte stopByte)
            throws ClosedIllegalStateException {
        long start = bytes.readPosition();
        long remaining = bytes.readRemaining();
        for (long i = 0; i < remaining; i++) {
            if (bytes.readByte(start + i) == stopByte)
                return i;
        }
        return -1;
    }

    @NotNull
    public static Bytes<?> fromHexString(@NotNull String s) {
        Bytes<?> in = Bytes.from(s);
        try {
            Bytes<?> out = Bytes.allocateElasticOnHeap();
            OUTER:
            while (in.readRemaining() > 0) {
                in.parseHexLong();
                for (int i = 0; i < 16; i++) {
                    if (in.peekUnsignedByte() == ' ') {
                        in.readSkip(1);
                        if (in.peekUnsignedByte() == ' ')
                            break OUTER;
                    }
                    long value = in.parseHexLong();
                    out.rawWriteByte((byte) value);
                }
                if (in.readByte(in.readPosition() - 1) <= ' ')
                    in.readSkip(-1);
                in.skipTo(StopCharTesters.CONTROL_STOP);
            }
            return out;
        } finally {
            in.releaseLast();
        }
    }

    public static void readHistogram(@NotNull StreamingDataInput in, @NotNull Histogram histogram)
            throws ClosedIllegalStateException, BufferUnderflowException, ArithmeticException {
        requireNonNull(histogram);
        throwExceptionIfReleased(in);
        int powersOf2 = Maths.toUInt31(in.readStopBit());
        int fractionBits = Maths.toUInt31(in.readStopBit());
        long overRange = in.readStopBit();
        long totalCount = in.readStopBit();
        long floor = in.readStopBit();
        histogram.init(powersOf2, fractionBits, overRange, totalCount, floor);
        int length = Maths.toUInt31(in.readStopBit());
        int[] ints = histogram.sampleCount();
        for (int i = 0; i < length; i++)
            ints[i] = Maths.toUInt31(in.readStopBit());
    }

    public static void writeHistogram(@NotNull StreamingDataOutput out, @NotNull Histogram histogram)
            throws BufferOverflowException, ClosedIllegalStateException {
        requireNonNull(histogram);
        throwExceptionIfReleased(out);
        out.writeStopBit(histogram.powersOf2());
        out.writeStopBit(histogram.fractionBits());
        out.writeStopBit(histogram.overRange());
        out.writeStopBit(histogram.totalCount());
        out.writeStopBit(histogram.floor());
        int[] ints = histogram.sampleCount();
        out.writeStopBit(ints.length);
        for (int i : ints)
            out.writeStopBit(i);
    }

    public static ByteBuffer asByteBuffer(@NotNull BytesStore<?, ?> bytesStore)
            throws BufferUnderflowException, ClosedIllegalStateException {
        return asByteBuffer(BYTE_BUFFER_TL, bytesStore);
    }

    public static ByteBuffer asByteBuffer2(@NotNull BytesStore<?, ?> bytesStore)
            throws BufferUnderflowException, ClosedIllegalStateException {
        return asByteBuffer(BYTE_BUFFER2_TL, bytesStore);
    }

    private static ByteBuffer asByteBuffer(@NotNull ThreadLocal<ByteBuffer> byteBufferTL, @NotNull BytesStore<?, ?> bytesStore)
            throws BufferUnderflowException, ClosedIllegalStateException {
        ByteBuffer byteBuffer = byteBufferTL.get();
        assignBytesStoreToByteBuffer(bytesStore, byteBuffer);
        return byteBuffer;
    }

    public static void assignBytesStoreToByteBuffer(@NotNull BytesStore<?, ?> bytesStore, @NotNull ByteBuffer byteBuffer)
            throws BufferUnderflowException, ClosedIllegalStateException {
        long address = bytesStore.addressForRead(bytesStore.readPosition());
        long capacity = bytesStore.realReadRemaining();
        ByteBuffers.setAddressCapacity(byteBuffer, address, capacity);
        byteBuffer.clear();
    }

    private static boolean canReadBytesAt(
            final BytesStore<?, ?> bs, final long offset, final int length) {
        return bs.readLimit() - offset >= length;
    }

    public static String parse8bit(ByteStringParser bsp, StopCharTester stopCharTester)
            throws ClosedIllegalStateException {
        try (ScopedResource<StringBuilder> stlSb = acquireStringBuilderScoped()) {
            StringBuilder sb = stlSb.get();
            BytesInternal.parse8bit(bsp, sb, stopCharTester);
            return BytesInternal.SI.intern(sb);
        }
    }

    public static void copy8bit(BytesStore<?, ?> bs, long addressForWrite, @NonNegative long length) throws ClosedIllegalStateException {
        throwExceptionIfReleased(bs);
        int length0 = Math.toIntExact(length);
        int i = 0;
        for (; i < length0 - 7; i += 8)
            MEMORY.writeLong(addressForWrite + i, bs.readLong(i));
        for (; i < length0; i++)
            MEMORY.writeByte(addressForWrite + i, bs.readByte(i));
    }

    public static int digitsForExponent(int exponent) {
        return 21 + Math.abs(exponent);
    }

    static class DateCache {
        final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd");
        private long lastDay = Long.MIN_VALUE;
        private byte[] lastDateStr = null;

        DateCache() {
            dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
        }
    }

    public static <B extends BytesStore<B, U>, U> BytesStore<B, U> failIfBytesOnBytes(BytesStore<B, U> bytesStore) {
        // MappedBytes don't have a backing BytesStore so we have to allow them to be used this way
        if (bytesStore instanceof Bytes && !(bytesStore instanceof MappedBytes)) {
            throw new IllegalArgumentException("A BytesStore is required as backing but a Bytes has been provided: " +
                    bytesStore.getClass().getSimpleName());
        }

        return bytesStore;
    }

    @SuppressWarnings("unchecked")
    public static <T> T uncheckedCast(Object o) {
        return (T) o;
    }
}
