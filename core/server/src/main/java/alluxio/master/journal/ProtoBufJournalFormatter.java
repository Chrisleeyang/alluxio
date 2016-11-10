/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.master.journal;

import alluxio.Constants;
import alluxio.proto.journal.Journal.JournalEntry;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import javax.annotation.concurrent.ThreadSafe;

/**
 * Reads and writes protocol buffer journal entries. The entries contain headers describing their
 * length. This framing is handled entirely by {@link JournalEntry#writeDelimitedTo(OutputStream)}
 * and {@link JournalEntry#parseDelimitedFrom(InputStream)}. This class is thread-safe.
 */
@ThreadSafe
public final class ProtoBufJournalFormatter implements JournalFormatter {
  private static final Logger LOG = LoggerFactory.getLogger(Constants.LOGGER_TYPE);

  /**
   * Constructs a new {@link ProtoBufJournalFormatter}.
   */
  public ProtoBufJournalFormatter() {}

  @Override
  public void serialize(JournalEntry entry, OutputStream outputStream) throws IOException {
    entry.writeDelimitedTo(outputStream);
  }

  @Override
  public JournalInputStream deserialize(final InputStream inputStream) throws IOException {
    return new JournalInputStream() {
      private final byte[] BUFFER = new byte[1024];
      private long mLatestSequenceNumber;

      @Override
      public JournalEntry getNextEntry() throws IOException {
        try {
          CodedInputStream codedInputStream = CodedInputStream.newInstance(inputStream);
          // If the message was truncated while writing the size varint, this will throw an
          // InvalidProtocolBufferException exception.
          int size = codedInputStream.readRawVarint32();
          byte[] buffer = size <= BUFFER.length ? BUFFER : new byte[size];
          int bytes = inputStream.read(buffer, 0, size);
          if (bytes < size) {
            throw new InvalidProtocolBufferException(
                "Journal entry was truncated. Expected to read " + size + " bytes but only got "
                    + bytes);
          }
          JournalEntry entry = JournalEntry.parseFrom(new ByteArrayInputStream(buffer, 0, size));
          if (entry != null) {
            mLatestSequenceNumber = entry.getSequenceNumber();
          }
          return entry;
        } catch (InvalidProtocolBufferException e) {
          LOG.warn("Failed to read journal entry", e);
          // Barring IO corruption, this means that the master crashed while writing the last
          // journal entry, so we can ignore this last entry.
          return null;
        }
      }

      @Override
      public void close() throws IOException {
        inputStream.close();
      }

      @Override
      public long getLatestSequenceNumber() {
        return mLatestSequenceNumber;
      }
    };
  }
}
