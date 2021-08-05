/*
 * Copyright 2019 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.avro.util;

import javax.annotation.Nullable;

/**
 * Special optional that supports holding null.
 */
public abstract class Optional<T> {

  public static final Optional EMPTY = new Optional() {
    @Override
    public Object get() {
      throw new UnsupportedOperationException("Optional is empty");
    }

    @Override
    public boolean isPresent() {
      return false;
    }
  };

  public static Optional empty() {
    return EMPTY;
  }

  @Nullable
  public abstract T get();

  public abstract boolean isPresent();

  @Override
  public String toString() {
    return "Optional{present = " + isPresent() + ", value=" + get() + '}';
  }

  public static <T> Optional<T> of(@Nullable final T value) {
    return new Optional<T>() {
      @Override
      public T get() {
        return value;
      }

      @Override
      public boolean isPresent() {
        return true;
      }
    };
  }

  public static <T> Optional<T> ofNullIsEmpty(@Nullable final T value) {
    if (value == null) {
      return EMPTY;
    } else {
      return of(value);
    }
  }

}
