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

import net.openhft.chronicle.bytes.FieldGroup;
import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.Memory;
import net.openhft.chronicle.core.util.ClassLocal;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.stream.Collectors;

import static net.openhft.chronicle.core.UnsafeMemory.MEMORY;

/**
 * This class holds the metadata information for fields within an object that are annotated with
 * {@link FieldGroup}. It provides utilities for analyzing and grouping the fields based on
 * their annotations, calculating the memory offset positions, and determining the size occupied
 * by each group of fields.
 * <p>
 * The class is particularly useful for understanding and manipulating the layout of fields within
 * an object in memory.
 * <p>
 * Example usage might include serialization, memory analysis, or direct memory access.
 *
 * @see FieldGroup
 */
public class BytesFieldInfo {
    private static final ClassLocal<BytesFieldInfo> CACHE = ClassLocal.withInitial(BytesFieldInfo::init);
    static final Field $END$;

    static {
        try {
            $END$ = BytesFieldInfo.class.getDeclaredField("$END$");
        } catch (NoSuchFieldException e) {
            throw new AssertionError(e);
        }
    }

    private final Map<String, BFIEntry> groups = new LinkedHashMap<>();
    private final Class<?> aClass;
    private final int description;

    /**
     * Constructs an instance of BytesFieldInfo for the given class.
     *
     * @param aClass the class to analyze
     */
    BytesFieldInfo(Class<?> aClass) {
        this.aClass = aClass;
        List<Field> fields = fields(aClass);
        String prefix0 = "";
        BFIEntry entry = null;
        int longs = 0;
        int ints = 0;
        int shorts = 0;
        int bytes = 0;
        for (int i = 0; i <= fields.size(); i++) {
            final Field field = i == fields.size() ? $END$ : fields.get(i);
            boolean matches = false;
            String prefix = "";
            long position = 0;
            int size = 0;
            if (field.getType().isPrimitive()) {
                FieldGroup fieldGroup = Jvm.findAnnotation(field, FieldGroup.class);
                if (fieldGroup != null) {
                    prefix = fieldGroup.value();
                    position = MEMORY.getFieldOffset(field);
                    matches = prefix.equals(prefix0);
                }
                size = sizeOf(field.getType());
                switch (size) {
                    case 1:
                        bytes++;
                        break;
                    case 2:
                        shorts++;
                        break;
                    case 4:
                        ints++;
                        break;
                    case 8:
                        longs++;
                        break;
                    default:
                        throw new UnsupportedOperationException("Primitive types of size " + size + " not supported");
                }
            }
            if (matches) {
                assert entry != null;
                entry.end = position + size;

            } else if (!prefix.isEmpty()) {
                if (this.groups.containsKey(prefix)) {
                    Jvm.warn().on(aClass, "Disjoined fields starting with " + prefix);
                    prefix0 = "";

                } else {
                    entry = new BFIEntry();
                    entry.start = position;
                    entry.end = position + size;
                    this.groups.put(prefix, entry);
                    prefix0 = prefix;
                }
            }
        }
        assert longs < 256;
        assert ints < 256;
        assert shorts < 128;
        assert bytes < 256;
        int newDescription = (longs << 24) | (ints << 16) | (shorts << 8) | bytes;
        // ensure the header has an odd parity as a validity check
        if (Integer.bitCount(newDescription) % 2 == 0)
            newDescription |= 0x8000;
        this.description = newDescription;
    }

    /**
     * Computes the size of the given type in bytes.
     *
     * @param type the class representing the primitive type
     * @return the size of the type in bytes
     */
    private static int sizeOf(Class<?> type) {
        return Memory.sizeOf(type);
    }

    /**
     * Returns the description metadata.
     *
     * @return an integer representing the description metadata
     */
    public int description() {
        return description;
    }

    /**
     * Entry representing the start and end memory positions for a group of fields.
     */
    static class BFIEntry {
        long start;
        long end;
    }

    /**
     * Factory method for creating a new BytesFieldInfo instance for the given class.
     *
     * @param aClass the class to analyze
     * @return a BytesFieldInfo instance
     */
    private static BytesFieldInfo init(Class<?> aClass) {
        return new BytesFieldInfo(aClass);
    }

    /**
     * Retrieves the BytesFieldInfo instance for the given class from the cache.
     *
     * @param aClass the class to look up
     * @return the cached BytesFieldInfo instance for the given class
     */
    public static BytesFieldInfo lookup(Class<?> aClass) {
        return CACHE.get(aClass);
    }

    /**
     * Returns a set of group names extracted from the field annotations.
     *
     * @return a set of group names
     */
    public Set<String> groups() {
        return groups.keySet();
    }

    /**
     * Returns the starting memory offset for the group with the given name.
     *
     * @param groupName the name of the group
     * @return the starting memory offset
     * @throws IllegalArgumentException if no group with the given name is found
     */
    public long startOf(String groupName) {
        final BFIEntry bfiEntry = groups.get(groupName);
        if (bfiEntry == null)
            throw new IllegalArgumentException("No groupName " + groupName + " found in " + aClass);
        return bfiEntry.start;
    }

    /**
     * Returns the total memory size in bytes occupied by the group with the given name.
     *
     * @param groupName the name of the group
     * @return the size of the group in bytes
     * @throws IllegalArgumentException if no group with the given name is found
     */
    public long lengthOf(String groupName) {
        final BFIEntry bfiEntry = groups.get(groupName);
        if (bfiEntry == null)
            throw new IllegalArgumentException("No groupName " + groupName + " found in " + aClass);
        return bfiEntry.end - bfiEntry.start;
    }

    /**
     * Generates a string representation of the field groups within the object.
     *
     * @return a string representation of the field groups
     */
    public String dump() {
        final StringBuilder sb = new StringBuilder().append("type: ").append(getClass().getSimpleName()).append(", groups: { ");
        sb.append(groups.entrySet().stream()
                .map(e -> e.getKey() + ": " + e.getValue().start + " to " + e.getValue().end)
                .collect(Collectors.joining(", ")));
        return sb.append(" }").toString();
    }

    /**
     * Retrieves all non-static fields from the given class and its superclasses, sorted by their
     * memory offsets.
     *
     * @param clazz the class from which to retrieve the fields
     * @return a sorted list of fields
     */
    public static List<Field> fields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        while (clazz != null && clazz != Object.class) {
            Collections.addAll(fields, clazz.getDeclaredFields());
            clazz = clazz.getSuperclass();
        }
        fields.removeIf(field -> Modifier.isStatic(field.getModifiers()));
        fields.sort(Comparator.comparingLong(MEMORY::objectFieldOffset));
        return fields;
    }
}
