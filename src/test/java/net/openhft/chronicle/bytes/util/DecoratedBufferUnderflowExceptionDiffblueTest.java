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
package net.openhft.chronicle.bytes.util;

import static org.junit.Assert.assertEquals;

import net.openhft.chronicle.bytes.BytesTestCommon;
import org.junit.Test;

public class DecoratedBufferUnderflowExceptionDiffblueTest extends BytesTestCommon {
  /**
  * Methods under test: 
  * 
  * <ul>
  *   <li>{@link DecoratedBufferUnderflowException#DecoratedBufferUnderflowException(String)}
  *   <li>{@link DecoratedBufferUnderflowException#getMessage()}
  * </ul>
  */
  @Test
  public void testConstructor() {
    // Arrange, Act and Assert
    assertEquals("An error occurred", (new DecoratedBufferUnderflowException("An error occurred")).getMessage());
  }
}

