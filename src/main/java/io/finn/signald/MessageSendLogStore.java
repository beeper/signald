/*
 * Copyright 2022 signald contributors
 * SPDX-License-Identifier: GPL-3.0-only
 * See included LICENSE file
 *
 */

package io.finn.signald;

import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

/*
 * Stores the latest 500 messages sent by a given user.
 */
public class MessageSendLogStore {
  private List<MessageSendLogEntry> lruMessages = new LinkedList<MessageSendLogEntry>();

  public void add(MessageSendLogEntry entry) {
    lruMessages.add(entry);
    if (lruMessages.size() > 500) {
      lruMessages.remove(0);
    }
  }

  public Optional<MessageSendLogEntry> find(long timestamp) { return lruMessages.stream().filter(e -> e.matches(timestamp)).findFirst(); }
}
