/**
 * Copyright (c) 2015-2019 John Whitbeck. All rights reserved.
 *
 * <p>The use and distribution terms for this software are covered by the
 * Apache License 2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt)
 * which can be found in the file al-v20.txt at the root of this distribution.
 * By using this software in any fashion, you are agreeing to be bound by
 * the terms of this license.
 *
 * <p>You must not remove this notice, or any other, from this software.
 */

package net.whitbeck.rdbparser;

public interface Entry {

  /**
   * Returns the entry type.
   *
   * @return one of EOF, DB_SELECT, or KEY_VALUE_PAIR
   */
  EntryType getType();
}
