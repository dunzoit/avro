/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.avro.io.parsing;

import java.io.IOException;
import java.util.Arrays;

import org.apache.avro.AvroTypeException;

/**
 * Parser is the class that maintains the stack for parsing. This class
 * is used by encoders, which are not required to skip.
 */
public class Parser {
  /**
   * The parser knows how to handle the terminal and non-terminal
   * symbols. But it needs help from outside to handle implicit
   * and explicit actions. The clients implement this interface to
   * provide this help.
   */
  public interface ActionHandler {
    /**
     * Handle the action symbol <tt>top</tt> when the <tt>input</tt> is
     * sought to be taken off the stack.
     * @param input The input symbol from the caller of advance
     * @param top The symbol at the top the stack.
     * @return  <tt>null</tt> if advance() is to continue processing the
     * stack. If not <tt>null</tt> the return value will be returned
     * by advance().
     * @throws IOException
     */
    Symbol doAction(Symbol input, Symbol top) throws IOException;
  }

  protected final ActionHandler symbolHandler;
  protected Symbol[] stack;
  protected int pos;

  public Parser(Symbol root, ActionHandler symbolHandler) {
    this.symbolHandler = symbolHandler;
    this.stack = new Symbol[10];
    this.stack[0] = root;
    this.pos = 1;
  }

  /**
   * If there is no sufficient room in the stack, use this expand it.
   */
  private void ensureCapacity(int minCapacity) {
    int oldCapacity = stack.length;
    if (minCapacity > oldCapacity) {
        int newCapacity = oldCapacity + (oldCapacity >> 1);
        if (newCapacity - minCapacity < 0) {
            newCapacity = minCapacity;
        }
        stack = Arrays.copyOf(stack, newCapacity);
    }
  }

  /**
   * Recursively replaces the symbol at the top of the stack with its
   * production, until the top is a terminal. Then checks if the
   * top symbol matches the terminal symbol suppled <tt>terminal</tt>.
   * @param input The symbol to match against the terminal at the
   * top of the stack.
   * @return The terminal symbol at the top of the stack unless an
   * implicit action resulted in another symbol, in which case that
   * symbol is returned.
   */
  public final Symbol advance(Symbol input) throws IOException {
    for (; ;) {
      Symbol top = stack[--pos];
      if (top == input) {
        return top; // A common case
      }

      Symbol.Kind k = top.kind;
      if (k == Symbol.Kind.IMPLICIT_ACTION) {
        Symbol result = symbolHandler.doAction(input, top);
        if (result != null) {
          return result;
        }
      } else if (k == Symbol.Kind.TERMINAL) {
        throw new AvroTypeException("Attempt to process a "
                + input + " when a "
                + top + " was expected.");
      } else if (k == Symbol.Kind.REPEATER
          && input == ((Symbol.Repeater) top).end) {
        return input;
      } else {
        pushProduction(top);
      }
    }
  }

  public final void skipTerminal(Symbol input) throws IOException {
    for (; ;) {
      Symbol top = stack[--pos];
      if (top == input) {
        return; // A common case
      }
      Symbol.Kind k = top.kind;
      if (k == Symbol.Kind.TERMINAL) {
        throw new AvroTypeException("Attempt to process a "
                + input + " when a "
                + top + " was expected.");
      }  else if (k == Symbol.Kind.REPEATER
          && input == ((Symbol.Repeater) top).end) {
        return;
      } else if (top.production != null) {
        pushProduction(top);
      }
    }
  }


  public final int countToFirstTerminal() throws IOException {
    int i = 1;
    while (true) {
      Symbol top = stack[--pos];
      if (top.kind == Symbol.Kind.TERMINAL)  {
        return i;
      } else if (top.production != null) {
        pushProduction(top);
      } else {
        i++;
      }
    }
  }

  public final int countToEnd() throws IOException {
    if (pos <= 0) {
      return 0;
    }
    int i = 1;
    while (pos > 0) {
      Symbol top = stack[--pos];
      if (top.kind == Symbol.Kind.ROOT) {
        break;
      }
      if (top.production != null) {
        pushProduction(top);
      } else {
        i++;
      }
    }
    return i;
  }

  public final void goBack(final int count) {
    pos += count;
  }


  public final void advance(int nrToAdvance)  throws IOException {
    while (nrToAdvance > 0) {
      Symbol top = stack[--pos];
      if (top.kind == Symbol.Kind.IMPLICIT_ACTION) {
        symbolHandler.doAction(null, top);
        nrToAdvance--;
      } else if (top.production != null) {
        pushProduction(top);
      } else {
        nrToAdvance--;
      }
    }
  }

  public final void skip(int nrToSkip) throws IOException {
    while (nrToSkip > 0) {
      Symbol top = stack[--pos];
      if (top.production != null) {
        pushProduction(top);
      } else {
        nrToSkip--;
      }
    }
  }


  /**
   * Performs any implicit actions at the top the stack, expanding any
   * production (other than the root) that may be encountered.
   * This method will fail if there are any repeaters on the stack.
   * @throws IOException
   */
  public final void processImplicitActions() throws IOException {
     while (pos > 1) {
      Symbol top = stack[pos - 1];
      if (top.kind == Symbol.Kind.IMPLICIT_ACTION) {
        pos--;
        symbolHandler.doAction(null, top);
      } else if (top.kind != Symbol.Kind.TERMINAL) {
        pos--;
        pushProduction(top);
      } else {
        break;
      }
    }
  }

  /**
   * Performs any "trailing" implicit actions at the top the stack.
   */
  public final void processTrailingImplicitActions() throws IOException {
    while (pos >= 1) {
      Symbol top = stack[pos - 1];
      if (top.kind == Symbol.Kind.IMPLICIT_ACTION
        && ((Symbol.ImplicitAction) top).isTrailing) {
        pos--;
        symbolHandler.doAction(null, top);
      } else {
        break;
      }
    }
  }

  /**
   * Pushes the production for the given symbol <tt>sym</tt>.
   * If <tt>sym</tt> is a repeater and <tt>input</tt> is either
   * {@link Symbol#ARRAY_END} or {@link Symbol#MAP_END} pushes nothing.
   * @param sym
   */
  public final void pushProduction(Symbol sym) {
    Symbol[] p = sym.production;
    int l = p.length;
    ensureCapacity(pos + l);
    System.arraycopy(p, 0, stack, pos, l);
    pos += l;
  }

  /**
   * Pops and returns the top symbol from the stack.
   */
  public Symbol popSymbol() {
    return stack[--pos];
  }

  /**
   * Returns the top symbol from the stack.
   */
  public Symbol topSymbol() {
    return stack[pos - 1];
  }

  public Symbol lastSymbol() {
    return stack[pos];
  }

  /**
   * Pushes <tt>sym</tt> on to the stack.
   */
  public void pushSymbol(Symbol sym) {
    int opos = pos;
    pos++;
    ensureCapacity(pos);
    stack[opos] = sym;
  }

  /**
   * Returns the depth of the stack.
   */
  public int depth() {
    return pos;
  }

  public void reset() {
    pos = 1;
  }
}

