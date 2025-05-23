/*
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
package com.facebook.presto.parquet.batchreader.decoders.rle;

import com.facebook.presto.parquet.batchreader.decoders.ValuesDecoder.Int64TimeAndTimestampMicrosValuesDecoder;
import com.facebook.presto.parquet.dictionary.LongDictionary;
import org.apache.parquet.io.ParquetDecodingException;
import org.openjdk.jol.info.ClassLayout;

import java.io.IOException;
import java.io.InputStream;

import static com.facebook.presto.common.type.DateTimeEncoding.packDateTimeWithZone;
import static com.facebook.presto.common.type.TimeZoneKey.UTC_KEY;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static io.airlift.slice.SizeOf.sizeOf;
import static java.util.concurrent.TimeUnit.MICROSECONDS;

public class Int64TimeAndTimestampMicrosRLEDictionaryValuesDecoder
        extends BaseRLEBitPackedDecoder
        implements Int64TimeAndTimestampMicrosValuesDecoder
{
    private static final int INSTANCE_SIZE = ClassLayout.parseClass(Int64TimeAndTimestampMicrosRLEDictionaryValuesDecoder.class).instanceSize();

    private final LongDictionary dictionary;

    private final PackFunction packFunction;

    public Int64TimeAndTimestampMicrosRLEDictionaryValuesDecoder(int bitWidth, InputStream inputStream, LongDictionary dictionary, boolean withTimezone)
    {
        super(Integer.MAX_VALUE, bitWidth, inputStream);
        this.dictionary = dictionary;
        this.packFunction = withTimezone ? millis -> packDateTimeWithZone(millis, UTC_KEY) : millis -> millis;
    }

    public Int64TimeAndTimestampMicrosRLEDictionaryValuesDecoder(int bitWidth, InputStream inputStream, LongDictionary dictionary)
    {
        this(bitWidth, inputStream, dictionary, false);
    }

    @Override
    public void readNext(long[] values, int offset, int length)
            throws IOException
    {
        int destinationIndex = offset;
        int remainingToCopy = length;
        while (remainingToCopy > 0) {
            if (currentCount == 0) {
                if (!decode()) {
                    break;
                }
            }

            int numEntriesToFill = Math.min(remainingToCopy, currentCount);
            int endIndex = destinationIndex + numEntriesToFill;
            switch (mode) {
                case RLE: {
                    final int rleValue = currentValue;
                    final long rleDictionaryValue = MICROSECONDS.toMillis(dictionary.decodeToLong(rleValue));
                    while (destinationIndex < endIndex) {
                        values[destinationIndex++] = packFunction.pack(rleDictionaryValue);
                    }
                    break;
                }
                case PACKED: {
                    final int[] localBuffer = currentBuffer;
                    final LongDictionary localDictionary = dictionary;
                    for (int srcIndex = currentBuffer.length - currentCount; destinationIndex < endIndex; srcIndex++) {
                        long dictionaryValue = localDictionary.decodeToLong(localBuffer[srcIndex]);
                        long millisValue = MICROSECONDS.toMillis(dictionaryValue);
                        values[destinationIndex++] = packFunction.pack(millisValue);
                    }
                    break;
                }
                default:
                    throw new ParquetDecodingException("not a valid mode " + mode);
            }

            currentCount -= numEntriesToFill;
            remainingToCopy -= numEntriesToFill;
        }

        checkState(remainingToCopy == 0, "End of stream: Invalid read size request");
    }

    @Override
    public void skip(int length)
            throws IOException
    {
        checkArgument(length >= 0, "invalid length %s", length);
        int remaining = length;
        while (remaining > 0) {
            if (currentCount == 0) {
                if (!decode()) {
                    break;
                }
            }

            int chunkSize = Math.min(remaining, currentCount);
            currentCount -= chunkSize;
            remaining -= chunkSize;
        }

        checkState(remaining == 0, "End of stream: Invalid skip size request: %s", length);
    }

    @Override
    public long getRetainedSizeInBytes()
    {
        return INSTANCE_SIZE + (dictionary == null ? 0 : dictionary.getRetainedSizeInBytes()) + sizeOf(currentBuffer);
    }
}
