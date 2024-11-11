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

import net.openhft.chronicle.bytes.internal.BytesFieldInfo;
import net.openhft.chronicle.bytes.internal.BytesInternal;
import net.openhft.chronicle.bytes.internal.ReferenceCountedUtil;
import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.Maths;
import net.openhft.chronicle.core.Memory;
import net.openhft.chronicle.core.OS;
import net.openhft.chronicle.core.annotation.NonNegative;
import net.openhft.chronicle.core.io.ClosedIllegalStateException;
import net.openhft.chronicle.core.io.IORuntimeException;
import net.openhft.chronicle.core.io.InvalidMarshallableException;
import net.openhft.chronicle.core.io.ThreadingIllegalStateException;
import net.openhft.chronicle.core.util.ClassLocal;
import net.openhft.chronicle.core.util.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static net.openhft.chronicle.core.UnsafeMemory.MEMORY;
import static net.openhft.chronicle.core.io.IOTools.*;

/**
 * Utility class for performing operations on Bytes and related data types.
 *
 * <p>This class provides a collection of static methods to manipulate and
 * interact with Bytes objects. These methods include transformations, checks,
 * and complex calculations not provided in the Bytes class itself.
 *
 * <p>Some of the operations provided in this class include:
 * <ul>
 * <li>Converting Bytes objects to different representations, such as char arrays.</li>
 * <li>Reading and writing specific data formats from/to Bytes objects, such as stop bits.</li>
 * <li>Manipulating the content of Bytes objects, such as combining double newlines or reversing contents.</li>
 * <li>Performing complex mathematical calculations, like rounding up values based on byte alignment.</li>
 * <li>Providing utility methods for exceptions, booleans and byte manipulations.</li>
 * </ul>
 *
 * <p>All methods in this class throw {@link java.lang.NullPointerException} if the objects provided to them are {@code null}.
 *
 * @see Bytes
 */
@SuppressWarnings("rawtypes")
public enum BytesUtil {
    ; // none

    /**
     * An empty array of integers, for use in various methods.
     */
    private static final int[] NO_INTS = {};

    /**
     * Cache for results of {@link #isTriviallyCopyable0(Class)}.
     */
    private static final ClassLocal<int[]> TRIVIALLY_COPYABLE = ClassLocal.withInitial(BytesUtil::isTriviallyCopyable0);

    /**
     * The current user's name, retrieved from system properties, default is "unknown" if not set.
     */
    private static final String USER_NAME = Jvm.getProperty("user.name", "unknown");

    /**
     * The directory for timestamp, retrieved from system properties, defaults to OS temporary directory if not set.
     */
    private static final String TIME_STAMP_DIR = Jvm.getProperty("timestamp.dir", OS.TMP);

    /**
     * The path for timestamp file, defaults to a file named .time-stamp.username.dat in timestamp directory.
     */
    static final String TIME_STAMP_PATH = Jvm.getProperty("timestamp.path", new File(TIME_STAMP_DIR, ".time-stamp." + USER_NAME + ".dat").getAbsolutePath());

    /**
     * Checks if the given class is trivially copyable.
     *
     * @param clazz Class to check.
     * @return true if the class is trivially copyable, false otherwise.
     */
    public static boolean isTriviallyCopyable(@NotNull Class<?> clazz) {
        final int[] ints = TRIVIALLY_COPYABLE.get(clazz);
        return ints[1] > 0;
    }

    /**
     * Helper method to determine if the given class is trivially copyable.
     *
     * @param clazz Class to check.
     * @return An array of integers representing certain properties of the class related to its copyability.
     * @throws UnsupportedOperationException If running on Azul Zing JVM.
     */
    static int[] isTriviallyCopyable0(@NotNull Class<?> clazz) {
        if (clazz.isArray()) {
            Class<?>componentType = clazz.getComponentType();
            if (componentType.isPrimitive())
                return new int[]{MEMORY.arrayBaseOffset(clazz)};
            return NO_INTS;
        }
        List<Field> fields = BytesFieldInfo.fields(clazz);
        return calculateMinMax(fields);
    }

    /**
     * Calculates the minimum and maximum offset for the fields of a given class.
     *
     * @param fields The fields of the class.
     * @return An array of two integers, where the first element is the minimum offset, and the second element is the maximum offset.
     */
    private static int[] calculateMinMax(final List<Field> fields) {
        int min = 0;
        int max = 0;
        for (Field field : fields) {
            final FieldGroup fieldGroup = Jvm.findAnnotation(field, FieldGroup.class);
            int start = (int) MEMORY.objectFieldOffset(field);
            int size = sizeOf(field.getType());
            int end = start + size;
            boolean nonTrivial = !field.getType().isPrimitive();
            if (nonTrivial) {
                if (max > 0)
                    break;
                // otherwise skip them.
            } else {
                if (min == 0)
                    min = start;
                max = end;
            }
        }
        return new int[]{min, max};
    }

    /**
     * Checks if all the fields in the specified range of the given class are trivially copyable.
     *
     * @param clazz  Class to check.
     * @param offset Start of the field area.
     * @param length Length of the field area.
     * @return true if all fields in the range are trivially copyable, false otherwise.
     */
    public static boolean isTriviallyCopyable(Class<?>clazz, @NonNegative int offset, @NonNegative int length) {
        int[] ints = TRIVIALLY_COPYABLE.get(clazz);
        if (ints.length == 0)
            return false;
        return offset >= ints[0] && (ints.length == 1 || offset + length <= ints[1]);
    }

    /**
     * Returns the range within which a class is trivially copyable.
     *
     * @param clazz Class to get the range for.
     * @return An array of integers representing the range in which the class is trivially copyable.
     */
    public static int[] triviallyCopyableRange(Class<?>clazz) {
        return TRIVIALLY_COPYABLE.get(clazz);
    }

    /**
     * Returns the offset of the first byte of the trivially copyable fields of a given class.
     *
     * @param clazz The class to examine.
     * @return The offset of the first byte that is trivially copyable.
     */
    public static int triviallyCopyableStart(Class<?>clazz) {
        return triviallyCopyableRange(clazz)[0];
    }

    /**
     * Returns the length of the trivially copyable fields in a given class.
     * Note that references are ignored as they are not considered trivially copyable.
     *
     * @param clazz The class to examine.
     * @return The length of the trivially copyable data.
     */
    public static int triviallyCopyableLength(Class<?>clazz) {
        final int[] startEnd = triviallyCopyableRange(clazz);
        return startEnd[1] - startEnd[0];
    }

    /**
     * Returns the size of a given type.
     *
     * @param type The type to calculate the size of.
     * @return The size of the type in bytes.
     */
    private static int sizeOf(Class<?> type) {
        return Memory.sizeOf(type);
    }

    /**
     * Finds the absolute path of a file specified by name.
     * Throws an exception if the file does not exist.
     *
     * @param name The name of the file to find.
     * @return The absolute path of the file.
     * @throws FileNotFoundException If the file does not exist.
     */
    public static String findFile(@NotNull String name)
            throws FileNotFoundException {
        File file = new File(name);
        URL url = null;
        if (!file.exists()) {
            url = urlFor(Thread.currentThread().getContextClassLoader(), name);
            String file2 = url.getFile().replace("%20", " ")
                    .replace("target/test-classes", "src/test/resources");
            file = new File(file2);
        }
        if (!file.exists())
            throw new FileNotFoundException(name);
        return file.getAbsolutePath();
    }

    /**
     * Reads the content of a file specified by name into a Bytes object.
     * If the name starts with "=", the rest of the name string will be converted to Bytes directly.
     *
     * @param name The name of the file to read.
     * @return The content of the file as a Bytes object.
     * @throws IOException If an I/O error occurs.
     */
    public static Bytes<?> readFile(@NotNull String name)
            throws IOException {
        if (name.startsWith("=")) {
            return Bytes.from(name.substring(1));
        }
        File file = new File(name);
        URL url = null;
        if (!file.exists()) {
            url = urlFor(Thread.currentThread().getContextClassLoader(), name);
            file = new File(url.getFile());
        }
        return Bytes.wrapForRead(readAsBytes(url == null ? new FileInputStream(file) : open(url)));

    }

    /**
     * Writes the content of a Bytes object to a file specified by name.
     *
     * @param file  The name of the file to write to.
     * @param bytes The Bytes object containing the data to write.
     * @throws IOException If an I/O error occurs.
     */
    public static void writeFile(String file, Bytes<byte[]> bytes)
            throws IOException {
        try (OutputStream os = new FileOutputStream(file)) {
            os.write(bytes.underlyingObject());
        }
    }

    /**
     * Compares bytes from two RandomDataInput objects to check if they are equal.
     *
     * @param a            The first RandomDataInput object.
     * @param offset       The starting position in the first object.
     * @param second       The second RandomDataInput object.
     * @param secondOffset The starting position in the second object.
     * @param len          The number of bytes to compare.
     * @return true if the bytes are equal, false otherwise.
     * @throws BufferUnderflowException If there is insufficient data.
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way.
     */
    public static boolean bytesEqual(
            @NotNull RandomDataInput a, @NonNegative long offset,
            @NotNull RandomDataInput second, long secondOffset, long len)
            throws BufferUnderflowException, IllegalStateException, ClosedIllegalStateException {
        long i = 0;
        while (len - i >= 8L) {
            if (a.readLong(offset + i) != second.readLong(secondOffset + i))
                return false;
            i += 8L;
        }
        if (len - i >= 4L) {
            if (a.readInt(offset + i) != second.readInt(secondOffset + i))
                return false;
            i += 4L;
        }
        if (len - i >= 2L) {
            if (a.readShort(offset + i) != second.readShort(secondOffset + i))
                return false;
            i += 2L;
        }
        if (i < len)
            return a.readByte(offset + i) == second.readByte(secondOffset + i);
        return true;
    }

    /**
     * Compares a CharSequence with bytes from a RandomDataInput object to check if they are equal.
     *
     * @param cs     The CharSequence to compare.
     * @param bs     The RandomDataInput object.
     * @param offset The starting position in the RandomDataInput object.
     * @param length The number of bytes to compare.
     * @return true if the bytes are equal to the CharSequence, false otherwise.
     * @throws BufferUnderflowException If there is insufficient data.
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    public static boolean bytesEqual(@Nullable CharSequence cs, @NotNull RandomDataInput bs, @NonNegative long offset, @NonNegative int length)
            throws IllegalStateException, BufferUnderflowException {
        if (cs == null || cs.length() != length)
            return false;
        for (int i = 0; i < length; i++) {
            if (cs.charAt(i) != bs.readUnsignedByte(offset + i))
                return false;
        }
        return true;
    }

    /**
     * Compares two objects for equality, with special handling for CharSequences.
     *
     * @param o1 The first object to compare.
     * @param o2 The second object to compare.
     * @return true if the objects are equal, false otherwise.
     */
    public static boolean equals(Object o1, Object o2) {
        if (o1 == o2) return true;
        if (o1 instanceof CharSequence && o2 instanceof CharSequence)
            return StringUtils.isEqual((CharSequence) o1, (CharSequence) o2);
        return o1 != null && o1.equals(o2);
    }

    /**
     * Converts a string to an integer using ISO_8859_1 encoding.
     *
     * @param str The string to convert.
     * @return The integer value of the string.
     */
    public static int asInt(@NotNull String str) {
        @NotNull ByteBuffer bb = ByteBuffer.wrap(str.getBytes(StandardCharsets.ISO_8859_1)).order(ByteOrder.nativeOrder());
        return bb.getInt();
    }

    /**
     * Calculates the number of bytes required to store a variable-length integer
     * using the stop bit encoding.
     *
     * @param n The integer to calculate the length for.
     * @return The number of bytes required to store the integer.
     */
    public static int stopBitLength(long n) {
        if ((n & ~0x7F) == 0) {
            return 1;
        }
        if ((n & ~0x3FFF) == 0) {
            return 2;
        }
        return BytesInternal.stopBitLength0(n);
    }

    /**
     * Converts the bytes from a Bytes object into a character array.
     *
     * @param bytes The Bytes object to convert.
     * @return The character array converted from the bytes.
     * @throws ArithmeticException      If there is an arithmetic error.
     * @throws BufferUnderflowException If there is insufficient data.
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    @NotNull
    public static char[] toCharArray(@NotNull Bytes<?> bytes)
            throws ArithmeticException, IllegalStateException, BufferUnderflowException {
        @NotNull final char[] chars = new char[Maths.toUInt31(bytes.readRemaining())];

        for (int i = 0; i < bytes.readRemaining(); i++) {
            chars[i] = (char) bytes.readUnsignedByte(i + bytes.readPosition());
        }
        return chars;
    }

    /**
     * Converts a specific range of bytes from a Bytes object into a character array.
     *
     * @param bytes    The Bytes object to convert.
     * @param position The starting position in the Bytes object.
     * @param length   The number of bytes to convert.
     * @return The character array converted from the bytes.
     * @throws BufferUnderflowException If there is insufficient data.
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way
     */
    @NotNull
    public static char[] toCharArray(@NotNull Bytes<?> bytes, @NonNegative long position, @NonNegative int length)
            throws IllegalStateException, BufferUnderflowException {
        @NotNull final char[] chars = new char[length];

        int j = 0;
        for (int i = 0; i < length; i++) {
            chars[j++] = (char) bytes.readUnsignedByte(position + i);
        }
        return chars;
    }

    /**
     * Reads a variable-length integer from a StreamingDataInput using the stop bit encoding.
     *
     * @param in The StreamingDataInput to read from.
     * @return The integer read.
     * @throws IORuntimeException    If an IO error occurs.
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way.
     */
    public static long readStopBit(@NotNull StreamingDataInput in)
            throws IORuntimeException, IllegalStateException, ClosedIllegalStateException {
        return BytesInternal.readStopBit(in);
    }

    /**
     * Writes a variable-length integer to a StreamingDataOutput using the stop bit encoding.
     *
     * @param out The StreamingDataOutput to write to.
     * @param n   The integer to write.
     * @throws BufferOverflowException If there is insufficient space.
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way.
     */
    public static void writeStopBit(@NotNull StreamingDataOutput out, long n)
            throws IllegalStateException, BufferOverflowException, ClosedIllegalStateException {
        BytesInternal.writeStopBit(out, n);
    }

    /**
     * Writes a variable-length integer to a specific position in a BytesStore using the stop bit encoding.
     *
     * @param bs     The BytesStore to write to.
     * @param offset The position in the BytesStore to start writing.
     * @param n      The integer to write.
     * @return The resulting offset after writing.
     * @throws BufferOverflowException If there is insufficient space.
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way.
     */
    public static long writeStopBit(BytesStore<?, ?> bs, @NonNegative long offset, @NonNegative long n)
            throws IllegalStateException, BufferOverflowException, ClosedIllegalStateException {
        return BytesInternal.writeStopBit(bs, offset, n);
    }

    /**
     * Writes a variable-length integer to a specific memory address using the stop bit encoding.
     *
     * @param addr The memory address to write to.
     * @param n    The integer to write.
     * @return The resulting memory address after writing.
     * @throws BufferOverflowException If there is insufficient space.
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way.
     */
    public static long writeStopBit(long addr, long n)
            throws BufferOverflowException {
        return BytesInternal.writeStopBit(addr, n);
    }

    /**
     * Parses a UTF-8 string from a StreamingDataInput and appends it to an Appendable object.
     *
     * @param in         The StreamingDataInput to read from.
     * @param appendable The Appendable to append to.
     * @param utflen     The length of the UTF-8 string.
     * @throws UTFDataFormatRuntimeException If the UTF-8 format is invalid.
     * @throws BufferUnderflowException      If there is insufficient data.
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way.
     */
    public static void parseUtf8(
            @NotNull StreamingDataInput in, Appendable appendable, @NonNegative int utflen)
            throws UTFDataFormatRuntimeException, IllegalStateException, BufferUnderflowException, ClosedIllegalStateException {
        BytesInternal.parseUtf8(in, appendable, true, utflen);
    }

    /**
     * Writes a CharSequence as a UTF-8 string to a StreamingDataOutput.
     *
     * @param out The StreamingDataOutput to write to.
     * @param cs  The CharSequence to write.
     * @throws IndexOutOfBoundsException If the CharSequence length is out of bounds.
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way.
     */
    public static void appendUtf8(@NotNull StreamingDataOutput out, @NotNull CharSequence cs)
            throws IndexOutOfBoundsException, ClosedIllegalStateException, ThreadingIllegalStateException {
        BytesInternal.appendUtf8(out, cs, 0, cs.length());
    }

    /**
     * Reads a Marshallable object from a BytesIn object.
     *
     * @param marshallable The Marshallable object to read.
     * @param bytes        The BytesIn object to read from.
     * @throws InvalidMarshallableException If the Marshallable object is invalid.
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way.
     */
    @SuppressWarnings("unchecked")
    public static void readMarshallable(@NotNull ReadBytesMarshallable marshallable, BytesIn<?> bytes) throws InvalidMarshallableException {
        BytesMarshaller.BYTES_MARSHALLER_CL.get(marshallable.getClass())
                .readMarshallable(marshallable, bytes);
    }

    /**
     * Writes a Marshallable object to a BytesOut object.
     *
     * @param marshallable The Marshallable object to write.
     * @param bytes        The BytesOut object to write to.
     * @throws BufferOverflowException      If there is insufficient space.
     * @throws ArithmeticException          If an arithmetic error occurs.
     * @throws BufferUnderflowException     If there is insufficient data.
     * @throws InvalidMarshallableException If the Marshallable object is invalid.
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way.
     */
    @SuppressWarnings("unchecked")
    public static void writeMarshallable(@NotNull WriteBytesMarshallable marshallable, BytesOut<?> bytes)
            throws IllegalStateException, BufferOverflowException, ArithmeticException, BufferUnderflowException, InvalidMarshallableException {
        BytesMarshaller.BYTES_MARSHALLER_CL.get(marshallable.getClass())
                .writeMarshallable(marshallable, bytes);
    }

    /**
     * Returns a string representation of a throwable's stack trace prefixed with a given string.
     *
     * @param s The string to prefix the stack trace.
     * @param t The throwable to represent.
     * @return The string representation of the throwable's stack trace.
     */
    static String asString(String s, Throwable t) {
        StringWriter sw = new StringWriter();
        sw.append(s).append("\n");
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    /**
     * Converts a byte to a boolean. The byte is considered to represent the boolean value false
     * if it equals 0, 'N', or 'n'. Otherwise, it represents true.
     *
     * @param b The byte to convert.
     * @return The boolean value represented by the byte.
     */
    public static boolean byteToBoolean(byte b) {
        return b != 0 && b != 'N' && b != 'n';
    }

    /**
     * Rounds up a long value to the nearest multiple of 64.
     *
     * @param x The value to round up.
     * @return The rounded value.
     */
    public static long roundUpTo64ByteAlign(long x) {
        return (x + 63L) & ~63L;
    }

    /**
     * Rounds up a long value to the nearest multiple of 8.
     *
     * @param x The value to round up.
     * @return The rounded value.
     */
    public static long roundUpTo8ByteAlign(long x) {
        return (x + 7L) & ~7L;
    }

    /**
     * Reads padding bytes from a Bytes object to align the read position to the nearest 8-byte boundary.
     *
     * @param bytes The Bytes object.
     * @throws BufferUnderflowException If there is insufficient data.
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way.
     */
    public static void read8ByteAlignPadding(Bytes<?> bytes)
            throws IllegalStateException, BufferUnderflowException {
        bytes.readPosition(roundUpTo8ByteAlign(bytes.readPosition()));
    }

    /**
     * Writes padding bytes to a Bytes object to align the write position to the nearest 8-byte boundary.
     *
     * @param bytes The Bytes object.
     * @throws BufferOverflowException If there is insufficient space.
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way.
     */
    public static void write8ByteAlignPadding(Bytes<?> bytes)
            throws BufferOverflowException, ClosedIllegalStateException {
        long start = bytes.writePosition();
        long end = roundUpTo8ByteAlign(start);
        bytes.writePosition(end);
        bytes.zeroOut(start, end);
    }

    /**
     * Returns a debug string representation of a portion of a RandomDataInput object.
     *
     * @param bytes     The RandomDataInput object.
     * @param start     The starting position.
     * @param maxLength The maximum length.
     * @return The debug string.
     * @throws BufferUnderflowException If there is insufficient data.
     */
    public static String toDebugString(@NotNull RandomDataInput bytes, @NonNegative long start, @NonNegative long maxLength)
            throws IllegalStateException, BufferUnderflowException {
        BytesStore<?, ?> bytes2 = bytes.subBytes(start, maxLength);
        return bytes2.toDebugString(maxLength);
    }

    /**
     * Copies 8-bit data from a BytesStore object to a specified address.
     *
     * @param bs              The BytesStore object.
     * @param addressForWrite The address for writing.
     * @param length          The length of data to copy.
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way.
     */
    public static void copy8bit(BytesStore<?, ?> bs, long addressForWrite, @NonNegative long length) throws ClosedIllegalStateException {
        BytesInternal.copy8bit(bs, addressForWrite, length);
    }

    /**
     * Reverses the contents of a Bytes object from a specified starting position.
     *
     * @param text  The Bytes object.
     * @param start The starting position.
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way.
     */
    public static void reverse(Bytes<?> text, @NonNegative int start) throws ClosedIllegalStateException {
        long rp = text.readPosition();
        int end = text.length() - 1;
        int mid = (start + end + 1) / 2;

        for (int i = 0; i < mid - start; ++i) {
            char ch = text.charAt(start + i);
            text.writeUnsignedByte(rp + start + i, text.charAt(end - i));
            text.writeUnsignedByte(rp + end - i, ch);
        }
    }

    /**
     * Pads an offset to align it to the nearest multiple of 4.
     *
     * @param from The original offset.
     * @return The padded offset.
     */
    public static long padOffset(long from) {
        return (-from) & 0x3L;
    }

    /**
     * Checks the last two characters of the given Bytes object. If the last two characters are
     * both newlines, one of them is removed to ensure only a single newline remains. If the last
     * character is a space and the character before it is a space, newline or non-printable,
     * the last character is removed.
     *
     * @param bytes to check and trim as needed.
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way.
     */
    public static void combineDoubleNewline(Bytes<?> bytes) {
        long wp = bytes.writePosition();
        long delta = wp - bytes.start();
        final int ch1 = delta >= 1 ? bytes.peekUnsignedByte(wp - 1) : '\0';
        switch (ch1) {
            case '\n': {
                final int ch2 = delta >= 2 ? bytes.peekUnsignedByte(wp - 2) : '\0';
                switch (ch2) {
                    case '\n':
                        bytes.writePosition(wp - 1);
                        return;

                    case ' ':
                        bytes.writePosition(wp - 2);
                        bytes.append('\n');
                        return;

                    default:
                        return;
                }
            }
            case ' ': {
                final int ch2 = delta >= 2 ? bytes.peekUnsignedByte(wp - 2) : '\0';
                if (ch2 == ' ') {
                    final int ch3 = delta >= 3 ? bytes.peekUnsignedByte(wp - 3) : '\0';
                    if (ch3 > ' ') {
                        bytes.writePosition(wp - 1);
                    }
                }
            }
        }
    }

    /**
     * Checks if the given character is a control space character. A control space character
     * is defined as a character in the range from 0 to space (inclusive).
     *
     * @param ch The character to check.
     * @return True if the character is a control space character, false otherwise.
     */
    static boolean isControlSpace(int ch) {
        return 0 <= ch && ch <= ' ';
    }

    /**
     * Returns a copy of the given Bytes object. The Bytes object must not have been released.
     * If the Bytes object is empty, an empty BytesStore is returned.
     *
     * @param bytes The Bytes object to copy.
     * @return A copy of the given Bytes object.
     * @throws ClosedIllegalStateException    If the resource has been released or closed.
     * @throws ThreadingIllegalStateException If this resource was accessed by multiple threads in an unsafe way.
     */
    public static Bytes<Void> copyOf(@NotNull final Bytes<?> bytes)
            throws ClosedIllegalStateException, ThreadingIllegalStateException {
        ReferenceCountedUtil.throwExceptionIfReleased(bytes);
        final long remaining = bytes.readRemaining();
        final long position = bytes.readPosition();

        final Bytes<Void> bytes2 = Bytes.allocateDirect(Math.max(1, remaining));
        bytes2.write(bytes, position, remaining);
        return bytes2;
    }

    /**
     * Issues a warning that elastic bytes are wrapped with unchecked() must call ensureCapacity() manually.
     */
    static final class WarnUncheckedElasticBytes {
        static {
            Jvm.debug().on(WarnUncheckedElasticBytes.class, "Wrapping elastic bytes with unchecked() will require calling ensureCapacity() as needed!");
        }

        private WarnUncheckedElasticBytes() {
        }

        @SuppressWarnings("EmptyMethod")
        static void warn() {
            // static block does the work.
        }
    }
}
