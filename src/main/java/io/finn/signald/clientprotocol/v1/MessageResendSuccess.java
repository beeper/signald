/*
 * Copyright 2022 signald contributors
 * SPDX-License-Identifier: GPL-3.0-only
 * See included LICENSE file
 *
 */

package io.finn.signald.clientprotocol.v1;

public class MessageResendSuccess {
  public final long timestamp;

  public MessageResendSuccess(long timestamp) { this.timestamp = timestamp; }
}
