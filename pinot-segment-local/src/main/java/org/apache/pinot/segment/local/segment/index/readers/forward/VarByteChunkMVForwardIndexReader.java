/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.segment.local.segment.index.readers.forward;

import java.nio.ByteBuffer;
import java.util.List;
import javax.annotation.Nullable;
import org.apache.pinot.segment.local.io.writer.impl.VarByteChunkForwardIndexWriter;
import org.apache.pinot.segment.local.utils.ArraySerDeUtils;
import org.apache.pinot.segment.spi.memory.PinotDataBuffer;
import org.apache.pinot.spi.data.FieldSpec.DataType;


/**
 * Chunk-based multi-value raw (non-dictionary-encoded) forward index reader for values of variable length data type
 * (STRING, BYTES).
 * <p>For data layout, please refer to the documentation for {@link VarByteChunkForwardIndexWriter}
 *
 * TODO: Consider reading directly from sliced ByteBuffer instead of copying to byte[] first
 */
public final class VarByteChunkMVForwardIndexReader extends BaseChunkForwardIndexReader {
  private static final int ROW_OFFSET_SIZE = VarByteChunkForwardIndexWriter.CHUNK_HEADER_ENTRY_ROW_OFFSET_SIZE;

  private final int _maxChunkSize;

  public VarByteChunkMVForwardIndexReader(PinotDataBuffer dataBuffer, DataType valueType) {
    super(dataBuffer, valueType, false);
    _maxChunkSize = _numDocsPerChunk * (ROW_OFFSET_SIZE + _lengthOfLongestEntry);
  }

  @Nullable
  @Override
  public ChunkReaderContext createContext() {
    if (_isCompressed) {
      return new ChunkReaderContext(_maxChunkSize);
    } else {
      return null;
    }
  }

  @Override
  public int getStringMV(int docId, String[] valueBuffer, ChunkReaderContext context) {
    return ArraySerDeUtils.deserializeStringArray(getBytes(docId, context), valueBuffer);
  }

  @Override
  public String[] getStringMV(int docId, ChunkReaderContext context) {
    return ArraySerDeUtils.deserializeStringArray(getBytes(docId, context));
  }

  @Override
  public int getBytesMV(int docId, byte[][] valueBuffer, ChunkReaderContext context) {
    return ArraySerDeUtils.deserializeBytesArray(getBytes(docId, context), valueBuffer);
  }

  @Override
  public byte[][] getBytesMV(int docId, ChunkReaderContext context) {
    return ArraySerDeUtils.deserializeBytesArray(getBytes(docId, context));
  }

  @Override
  public int getNumValuesMV(int docId, ChunkReaderContext context) {
    return ByteBuffer.wrap(getBytes(docId, context)).getInt();
  }

  @Override
  public byte[] getBytes(int docId, ChunkReaderContext context) {
    if (_isCompressed) {
      return getBytesCompressed(docId, context);
    } else {
      return getBytesUncompressed(docId);
    }
  }

  /**
   * Helper method to read BYTES value from the compressed index.
   */
  private byte[] getBytesCompressed(int docId, ChunkReaderContext context) {
    int chunkRowId = docId % _numDocsPerChunk;
    ByteBuffer chunkBuffer = getChunkBuffer(docId, context);

    // These offsets are offset in the chunk buffer
    int valueStartOffset = chunkBuffer.getInt(chunkRowId * ROW_OFFSET_SIZE);
    int valueEndOffset = getValueEndOffset(chunkRowId, chunkBuffer);

    byte[] bytes = new byte[valueEndOffset - valueStartOffset];
    chunkBuffer.position(valueStartOffset);
    chunkBuffer.get(bytes);
    return bytes;
  }

  /**
   * Helper method to read BYTES value from the uncompressed index.
   */
  private byte[] getBytesUncompressed(int docId) {
    int chunkId = docId / _numDocsPerChunk;
    int chunkRowId = docId % _numDocsPerChunk;

    // These offsets are offset in the data buffer
    long chunkStartOffset = getChunkPosition(chunkId);
    long valueStartOffset =
        chunkStartOffset + _dataBuffer.getInt(chunkStartOffset + (long) chunkRowId * ROW_OFFSET_SIZE);
    long valueEndOffset = getValueEndOffset(chunkId, chunkRowId, chunkStartOffset);

    byte[] bytes = new byte[(int) (valueEndOffset - valueStartOffset)];
    _dataBuffer.copyTo(valueStartOffset, bytes);
    return bytes;
  }

  /**
   * Helper method to compute the end offset of the value in the chunk buffer.
   */
  private int getValueEndOffset(int rowId, ByteBuffer chunkBuffer) {
    if (rowId == _numDocsPerChunk - 1) {
      // Last row in the chunk
      return chunkBuffer.limit();
    } else {
      int valueEndOffset = chunkBuffer.getInt((rowId + 1) * ROW_OFFSET_SIZE);
      if (valueEndOffset == 0) {
        // Last row in the last chunk (chunk is incomplete, which stores 0 as the offset for the absent rows)
        return chunkBuffer.limit();
      } else {
        return valueEndOffset;
      }
    }
  }

  /**
   * Helper method to compute the end offset of the value in the data buffer.
   */
  private long getValueEndOffset(int chunkId, int chunkRowId, long chunkStartOffset) {
    if (chunkId == _numChunks - 1) {
      // Last chunk
      if (chunkRowId == _numDocsPerChunk - 1) {
        // Last row in the last chunk
        return _dataBuffer.size();
      } else {
        int valueEndOffsetInChunk = _dataBuffer.getInt(chunkStartOffset + (long) (chunkRowId + 1) * ROW_OFFSET_SIZE);
        if (valueEndOffsetInChunk == 0) {
          // Last row in the last chunk (chunk is incomplete, which stores 0 as the offset for the absent rows)
          return _dataBuffer.size();
        } else {
          return chunkStartOffset + valueEndOffsetInChunk;
        }
      }
    } else {
      if (chunkRowId == _numDocsPerChunk - 1) {
        // Last row in the chunk
        return getChunkPosition(chunkId + 1);
      } else {
        return chunkStartOffset + _dataBuffer.getInt(chunkStartOffset + (long) (chunkRowId + 1) * ROW_OFFSET_SIZE);
      }
    }
  }

  @Override
  public boolean isBufferByteRangeInfoSupported() {
    return true;
  }

  @Override
  public void recordDocIdByteRanges(int docId, ChunkReaderContext context, List<ByteRange> ranges) {
    if (_isCompressed) {
      recordDocIdRanges(docId, context, ranges);
    } else {
      recordDocIdRangesUncompressed(docId, ROW_OFFSET_SIZE, ranges);
    }
  }

  @Override
  public boolean isFixedOffsetMappingType() {
    return false;
  }

  @Override
  public long getRawDataStartOffset() {
    throw new UnsupportedOperationException("Forward index is not of fixed length type");
  }

  @Override
  public int getDocLength() {
    throw new UnsupportedOperationException("Forward index is not of fixed length type");
  }
}
