/*
 * Copyright 2022 signald contributors
 * SPDX-License-Identifier: GPL-3.0-only
 * See included LICENSE file
 *
 */

package io.finn.signald;

import io.finn.signald.util.GroupsUtil;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.signal.libsignal.zkgroup.InvalidInputException;
import org.signal.libsignal.zkgroup.groups.GroupIdentifier;
import org.signal.libsignal.zkgroup.groups.GroupMasterKey;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;

public class MessageSendLogEntry {
  private static final Logger logger = LogManager.getLogger();

  long timestamp;
  Optional<GroupIdentifier> groupId;
  SignalServiceProtos.Content content;

  public MessageSendLogEntry(long timestamp, SignalServiceProtos.Content content) {
    this.timestamp = timestamp;
    this.content = content;
    this.groupId = Optional.empty();

    try {
      if (content.hasDataMessage()) {
        if (content.getDataMessage().hasGroup()) {
          this.groupId = Optional.of(new GroupIdentifier(content.getDataMessage().getGroup().getId().toByteArray()));
        } else if (content.getDataMessage().hasGroupV2()) {
          var groupMasterKey = new GroupMasterKey(content.getDataMessage().getGroupV2().getMasterKey().toByteArray());
          this.groupId = Optional.of(new GroupIdentifier(GroupsUtil.getGroupId(groupMasterKey)));
        }
      }
    } catch (InvalidInputException e) {
      logger.warn("Failed to parse groupId id from content");
    }
  }

  public boolean matches(long timestamp) { return this.timestamp == timestamp; }

  public Optional<GroupIdentifier> getGroupId() { return groupId; }
  public SignalServiceProtos.Content getContent() { return content; }
}
