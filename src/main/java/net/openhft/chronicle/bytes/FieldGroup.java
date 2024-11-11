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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
/**
 * Annotation used for logically grouping fields within a class. It is especially useful in classes
 * where precise control over the memory layout of fields is required, or in scenarios where
 * logically grouping related fields can simplify code that operates on these fields.
 *
 * <p>The {@link FieldGroup} annotation is retained at runtime and can be used to annotate
 * fields within a class. The name of the group is specified through the 'value' method,
 * allowing multiple fields to be associated with the same group by assigning them the same name.
 *
 * <p>One common use case is organizing the memory layout for serialization or memory-mapped
 * objects where fields that are accessed together are placed adjacently in memory.
 *
 * <p>Example:
 * <pre>
 * public class Record {
 *     &#64;FieldGroup("body")
 *     private int bodyField1, bodyField2;
 * }
 * </pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface FieldGroup {

    /**
     * Defines the name of the field group. Multiple fields with the same {@code value} are considered
     * part of the same logical group.
     *
     * @return the name of the group
     */
    String value();
}
