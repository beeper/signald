/*
 * Copyright 2022 signald contributors
 * SPDX-License-Identifier: GPL-3.0-only
 * See included LICENSE file
 *
 */

package org.asamk.signal;

public class AttachmentInvalidException extends Exception {
  public AttachmentInvalidException(String message) { super(message); }

  public AttachmentInvalidException(String attachment, Exception e) { super(attachment + ": " + e.getMessage()); }
}
