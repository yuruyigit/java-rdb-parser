package net.whitbeck.rdb_parser;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

public class RdbParser implements AutoCloseable {

  private final static Charset ASCII = Charset.forName("ASCII");

  private static final int
    EOF = 0xff,
    DB_SELECT = 0xfe,
    KEY_VALUE_SECS = 0xfd,
    KEY_VALUE_MS = 0xfc;

  private static final int BUFFER_SIZE = 8 * 1024;

  private final ByteChannel ch;
  private final ByteBuffer buf = ByteBuffer.allocateDirect(BUFFER_SIZE);

  /* Parsing state */
  private int version;
  private boolean isInitialized = false;
  private boolean hasNext = false;

  public RdbParser(ByteChannel ch) {
    this.ch = ch;
  }

  public RdbParser(Path path) throws IOException {
    this.ch = FileChannel.open(path, StandardOpenOption.READ);
  }

  public RdbParser(File file) throws IOException {
    this(file.toPath());
  }

  private void fillBuffer() throws IOException {
    buf.clear();
    if (ch.read(buf) == -1) {
      throw new IOException("Attempting to read past channel end-of-stream.");
    }
    buf.flip();
  }

  private int readByte() throws IOException {
    if (!buf.hasRemaining()) {
      fillBuffer();
    }
    return buf.get() & 0xff;
  }

  private byte[] readBytes(int n) throws IOException {
    int rem = n;
    int pos = 0;
    byte[] bs = new byte[n];
    while (rem > 0) {
      int avail = buf.remaining();
      if (avail >= rem) {
        buf.get(bs, pos, rem);
        pos += rem;
        rem = 0;
      } else {
        buf.get(bs, pos, avail);
        pos += avail;
        rem -= avail;
        fillBuffer();
      }
    }
    return bs;
  }

  private String readMagicNumber() throws IOException {
    return new String(readBytes(5), ASCII);
  }

  private int readVersion() throws IOException {
    return Integer.parseInt(new String(readBytes(4), ASCII));
  }

  private void init() throws IOException {
    fillBuffer();
    if (!readMagicNumber().equals("REDIS")) {
      throw new IllegalStateException("Not a valid redis RDB file");
    }
    version = readVersion();
    if (version < 1 || version > 6) {
      throw new IllegalStateException("Unknown version");
    }
    isInitialized = true;
    hasNext = true;
  }

  public Entry readNext() throws IOException {
    if (!hasNext) {
      if (!isInitialized) {
        init();
        return readNext();
      } else { // EOF reached
        return null;
      }
    }
    int b = readByte();
    switch (b) {
    case EOF:
      return readEOF();
    case DB_SELECT:
      return readDbSelect();
    case KEY_VALUE_SECS:
      return readEntrySeconds();
    case KEY_VALUE_MS:
      return readEntryMillis();
    default:
      return readEntry(null, b);
    }
  }

  private byte[] readChecksum() throws IOException {
    return readBytes(8);
  }

  private byte[] getEmptyChecksum() {
    return new byte[8];
  }

  private Eof readEOF() throws IOException {
    byte[] checksum = (version >= 5)? readChecksum() : getEmptyChecksum();
    hasNext = false;
    return new Eof(checksum);
  }

  private DbSelect readDbSelect() throws IOException {
    return new DbSelect(readLength());
  }

  private long readLength() throws IOException {
    int b = readByte();
    // the first two bits determine the encoding
    int flag = (b & 0xc0) >> 6;
    switch (flag) {
    case 0: // length is read from the lower 6 bits
      return b & 0x3f;
    case 1: // one additional byte is read for a 14 bit encoding
      return (((long)b & 0x3f) << 8) | ((long)readByte() & 0xff);
    case 2: // read next four bytes as unsigned big-endian
      byte[] bs = readBytes(4);
      return ((((long)bs[0] & 0xff) << 24) |
              (((long)bs[1] & 0xff) << 16) |
              (((long)bs[2] & 0xff) <<  8) |
              (((long)bs[3] & 0xff) <<  0));
    default:
      throw new IllegalStateException("Expected a length, but got a special string encoding.");
    }
  }

  private byte[] readStringEncoded() throws IOException {
    int b = readByte();
    // the first two bits determine the encoding
    int flag = (b & 0xc0) >> 6;
    int len;
    switch (flag) {
    case 0: // length is read from the lower 6 bits
      len = (b & 0x3f);
      return readBytes(len);
    case 1: // one additional byte is read for a 14 bit encoding
      len = ((b & 0x3f) << 8) | (readByte() & 0xff);
      return readBytes(len);
    case 2: // read next four bytes as unsigned big-endian
      byte[] bs = readBytes(4);
      len = ((((int)bs[0] & 0xff) << 24) |
             (((int)bs[1] & 0xff) << 16) |
             (((int)bs[2] & 0xff) <<  8) |
             (((int)bs[3] & 0xff) <<  0));
      if (len < 0) {
        throw new IllegalStateException("Strings longer than " + Integer.MAX_VALUE +
                                        "bytes are not supported.");
      }
      return readBytes(len);
    case 3:
      return readSpecialStringEncoded(b & 0x3f);
    default: // never reached
      return null;
    }
  }

  private byte[] readInteger8Bits() throws IOException {
    return String.valueOf(readByte()).getBytes(ASCII);
  }

  private byte[] readInteger16Bits() throws IOException {
    long l = ((((long)readByte() & 0xff) << 0) |
              (((long)readByte() & 0xff) << 8));
    return String.valueOf(l).getBytes(ASCII);
  }

  private byte[] readInteger32Bits() throws IOException {
    byte[] bs = readBytes(4);
    long l = ((((long)bs[3] & 0xff) << 24) |
              (((long)bs[2] & 0xff) << 16) |
              (((long)bs[1] & 0xff) <<  8) |
              (((long)bs[0] & 0xff) <<  0));
    return String.valueOf(l).getBytes(ASCII);
  }

  private byte[] readLzfString() throws IOException {
    int clen = (int)readLength();
    int ulen = (int)readLength();
    byte[] src = readBytes(clen);
    byte[] dest = new byte[ulen];
    Lzf.expand(src, dest);
    return dest;
  }

  private byte[] readDoubleString() throws IOException {
    int len = readByte();
    switch (len) {
    case 0xff:
      return DoubleBytes.NEGATIVE_INFINITY;
    case 0xfe:
      return DoubleBytes.POSITIVE_INFINITY;
    case 0xfd:
      return DoubleBytes.NaN;
    default:
      return readBytes(len);
    }
  }

  private byte[] readSpecialStringEncoded(int type) throws IOException {
    switch (type) {
    case 0:
      return readInteger8Bits();
    case 1:
      return readInteger16Bits();
    case 2:
      return readInteger32Bits();
    case 3:
      return readLzfString();
    default:
      throw new IllegalStateException("Unknown special encoding: " + type);
    }
  }

  private KeyValuePair readEntrySeconds() throws IOException {
    return readEntry(readBytes(4), readByte());
  }

  private KeyValuePair readEntryMillis() throws IOException {
    return readEntry(readBytes(8), readByte());
  }

  private KeyValuePair readEntry(byte[] ts, int valueType) throws IOException {
    byte[] key = readStringEncoded();
    switch (valueType) {
    case KeyValuePair.VALUE:
      return readValue(ts, key);
    case KeyValuePair.LIST:
      return readList(ts, key);
    case KeyValuePair.SET:
      return readSet(ts, key);
    case KeyValuePair.SORTED_SET:
      return readSortedSet(ts, key);
    case KeyValuePair.HASH:
      return readHash(ts, key);
    case KeyValuePair.ZIPMAP:
      throw new UnsupportedOperationException("Parsing zipmaps (deprecated as of redis 2.6) " +
                                              "is not supported!");
    case KeyValuePair.ZIPLIST:
      return readZipList(ts, key);
    case KeyValuePair.INTSET:
      return readIntSet(ts, key);
    case KeyValuePair.SORTED_SET_AS_ZIPLIST:
      return readSortedSetAsZipList(ts, key);
    case KeyValuePair.HASHMAP_AS_ZIPLIST:
      return readHashmapAsZipList(ts, key);
    default:
      throw new UnsupportedOperationException("Unknown value type: " + valueType);
    }
  }

  private KeyValue readValue(byte[] ts, byte[] key) throws IOException {
    return new KeyValue(ts, key, readStringEncoded());
  }

  private KeyValues readList(byte[] ts, byte[] key) throws IOException {
    long len = readLength();
    if (len > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("Lists with more than " + Integer.MAX_VALUE +
                                         " elements are not supported.");
    }
    int size = (int)len;
    List<byte[]> list = new ArrayList<byte[]>(size);
    for (int i=0; i<size; ++i) {
      list.add(readStringEncoded());
    }
    return new KeyValues(KeyValuePair.LIST, ts, key, list);
  }

  private KeyValues readSet(byte[] ts, byte[] key) throws IOException {
    long len = readLength();
    if (len > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("Sets with more than " + Integer.MAX_VALUE +
                                         " elements are not supported.");
    }
    int size = (int)len;
    List<byte[]> set = new ArrayList<byte[]>(size);
    for (int i=0; i<size; ++i) {
      set.add(readStringEncoded());
    }
    return new KeyValues(KeyValuePair.SET, ts, key, set);
  }

  private KeyValues readSortedSet(byte[] ts, byte[] key) throws IOException {
    long len = readLength();
    if (len > (Integer.MAX_VALUE / 2)) {
      throw new IllegalArgumentException("SortedSets with more than " + (Integer.MAX_VALUE / 2) +
                                         " elements are not supported.");
    }
    int size = (int)len;
    List<byte[]> valueScoresPairs = new ArrayList<byte[]>(2 * size);
    for (int i=0; i<size; ++i) {
      valueScoresPairs.add(readStringEncoded());
      valueScoresPairs.add(readDoubleString());
    }
    return new KeyValues(KeyValuePair.SORTED_SET, ts, key, valueScoresPairs);
  }

  private KeyValues readHash(byte[] ts, byte[] key) throws IOException {
    long len = readLength();
    if (len > (Integer.MAX_VALUE / 2)) {
      throw new IllegalArgumentException("Hashes with more than " + (Integer.MAX_VALUE / 2) +
                                         " elements are not supported.");
    }
    int size = (int)len;
    List<byte[]> kvPairs = new ArrayList<byte[]>(2 * size);
    for (int i=0; i<size; ++i) {
      kvPairs.add(readStringEncoded());
      kvPairs.add(readStringEncoded());
    }
    return new KeyValues(KeyValuePair.HASH, ts, key, kvPairs);
  }

  private KeyValues readZipList(byte[] ts, byte[] key) throws IOException {
    return new KeyValues(KeyValuePair.ZIPLIST, ts, key, new ZipList(readStringEncoded()));
  }

  private KeyValues readIntSet(byte[] ts, byte[] key) throws IOException {
    return new KeyValues(KeyValuePair.INTSET, ts, key, new IntSet(readStringEncoded()));
  }

  private KeyValues readSortedSetAsZipList(byte[] ts, byte[] key) throws IOException {
    return new KeyValues(KeyValuePair.SORTED_SET_AS_ZIPLIST, ts, key,
                         new SortedSetAsZipList(readStringEncoded()));
  }

  private KeyValues readHashmapAsZipList(byte[] ts, byte[] key) throws IOException {
    return new KeyValues(KeyValuePair.HASHMAP_AS_ZIPLIST, ts, key, new ZipList(readStringEncoded()));
  }

  @Override
  public void close() throws IOException {
    ch.close();
  }

}