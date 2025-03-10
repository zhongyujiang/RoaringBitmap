package org.roaringbitmap.longlong;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

import org.roaringbitmap.*;
import org.roaringbitmap.art.ContainerIterator;
import org.roaringbitmap.art.KeyIterator;
import org.roaringbitmap.art.LeafNode;
import org.roaringbitmap.art.LeafNodeIterator;

/**
 * Roaring64Bitmap is a compressed 64 bit bitmap. It can contain all the numbers of long
 * rang[Long.MAX_VALUE, Long.MIN_VALUE]. Since java has no unsigned long,we treat the negative value
 * as a successor of the positive value. So the ascending ordering of all the long value is:
 * 0,1,2...Long.MAX_VALUE,Long.MIN_VALUE,Long.MIN_VALUE+1.......-1. See Long.toUnsignedString()
 */
public class Roaring64Bitmap implements Externalizable, LongBitmapDataProvider {

  private HighLowContainer highLowContainer;

  public Roaring64Bitmap() {
    highLowContainer = new HighLowContainer();
  }

  public void addInt(int x) {
    addLong(Util.toUnsignedLong(x));
  }

  /**
   * Add the value to the container (set the value to "true"), whether it already appears or not.
   *
   * Java lacks native unsigned longs but the x argument is considered to be unsigned. Within
   * bitmaps, numbers are ordered according to{@link Long#toUnsignedString}. We order the numbers
   * like 0, 1, ..., 9223372036854775807, -9223372036854775808, -9223372036854775807,..., -1.
   *
   * @param x long value
   */
  @Override
  public void addLong(long x) {
    byte[] high = LongUtils.highPart(x);
    char low = LongUtils.lowPart(x);
    ContainerWithIndex containerWithIndex = highLowContainer.searchContainer(high);
    if (containerWithIndex != null) {
      Container container = containerWithIndex.getContainer();
      Container freshOne = container.add(low);
      highLowContainer.replaceContainer(containerWithIndex.getContainerIdx(), freshOne);
    } else {
      ArrayContainer arrayContainer = new ArrayContainer();
      arrayContainer.add(low);
      highLowContainer.put(high, arrayContainer);
    }
  }

  /**
   * Returns the number of distinct integers added to the bitmap (e.g., number of bits set).
   *
   * @return the cardinality
   */
  @Override
  public long getLongCardinality() {
    if (highLowContainer.isEmpty()) {
      return 0L;
    }
    Iterator<Container> containerIterator = highLowContainer.containerIterator();
    long cardinality = 0L;
    while (containerIterator.hasNext()) {
      Container container = containerIterator.next();
      cardinality += container.getCardinality();
    }
    return cardinality;
  }

  /**
   * @return the cardinality as an int
   * @throws UnsupportedOperationException if the cardinality does not fit in an int
   */
  public int getIntCardinality() throws UnsupportedOperationException {
    long cardinality = getLongCardinality();
    if (cardinality > Integer.MAX_VALUE) {
      // TODO: we should handle cardinality fitting in an unsigned int
      throw new UnsupportedOperationException(
          "Can not call .getIntCardinality as the cardinality is bigger than Integer.MAX_VALUE");
    }
    return (int) cardinality;
  }

  /**
   * Return the jth value stored in this bitmap.
   *
   * @param j index of the value
   * @return the value
   * @throws IllegalArgumentException if j is out of the bounds of the bitmap cardinality
   */
  @Override
  public long select(final long j) throws IllegalArgumentException {
    long left = j;
    LeafNodeIterator leafNodeIterator = highLowContainer.highKeyLeafNodeIterator(false);
    while (leafNodeIterator.hasNext()) {
      LeafNode leafNode = leafNodeIterator.next();
      long containerIdx = leafNode.getContainerIdx();
      Container container = highLowContainer.getContainer(containerIdx);
      int card = container.getCardinality();
      if (left >= card) {
        left = left - card;
      } else {
        byte[] high = leafNode.getKeyBytes();
        int leftAsUnsignedInt = (int) left;
        char low = container.select(leftAsUnsignedInt);
        return LongUtils.toLong(high, low);
      }
    }
    return throwSelectInvalidIndex(j);
  }

  private long throwSelectInvalidIndex(long j) {
    throw new IllegalArgumentException(
        "select " + j + " when the cardinality is " + this.getLongCardinality());
  }

  /**
   * Get the first (smallest) integer in this RoaringBitmap,
   * that is, returns the minimum of the set.
   * @return the first (smallest) integer
   * @throws NoSuchElementException if empty
   */
  @Override
  public long first() {
    return highLowContainer.first();
  }

  /**
   * Get the last (largest) integer in this RoaringBitmap,
   * that is, returns the maximum of the set.
   * @return the last (largest) integer
   * @throws NoSuchElementException if empty
   */
  @Override
  public long last() {
    return highLowContainer.last();
  }

  /**
   * For better performance, consider the Use the {@link #forEach forEach} method.
   *
   * @return a custom iterator over set bits, the bits are traversed in ascending sorted order
   */
  public Iterator<Long> iterator() {
    final LongIterator it = getLongIterator();

    return new Iterator<Long>() {

      @Override
      public boolean hasNext() {
        return it.hasNext();
      }

      @Override
      public Long next() {
        return it.next();
      }

      @Override
      public void remove() {
        throw new UnsupportedOperationException();
      }
    };
  }

  @Override
  public void forEach(final LongConsumer lc) {
    KeyIterator keyIterator = highLowContainer.highKeyIterator();
    while (keyIterator.hasNext()) {
      byte[] high = keyIterator.next();
      long containerIdx = keyIterator.currentContainerIdx();
      Container container = highLowContainer.getContainer(containerIdx);
      PeekableCharIterator charIterator = container.getCharIterator();
      while (charIterator.hasNext()) {
        char low = charIterator.next();
        long v = LongUtils.toLong(high, low);
        lc.accept(v);
      }
    }
  }

  /**
   * Consume presence information for all values in the range [start, start + length).
   *
   * @param start Lower bound of values to consume.
   * @param length Maximum number of values to consume.
   * @param rrc Code to be executed for each present or absent value.
   */
  public void forAllInRange(long start, int length, final RelativeRangeConsumer rrc) {
    final LeafNodeIterator leafIterator =
        highLowContainer.highKeyLeafNodeIteratorFrom(start, false);
    if (!leafIterator.hasNext()) {
      rrc.acceptAllAbsent(0, length);
      return; // nothing else to do
    }
    final long end = start + length;
    final byte[] endHigh = LongUtils.highPart(end);
    long filledUntil = start;

    LeafNode node = leafIterator.next();
    byte[] high = node.getKeyBytes();
    while (LongUtils.compareHigh(high, endHigh) <= 0) {
      // fill missing values until start of container
      long containerStart = LongUtils.toLong(high, (char) 0);
      if (filledUntil < containerStart) {
        rrc.acceptAllAbsent((int) (filledUntil - start), (int) (containerStart - start));
        filledUntil = containerStart;
      }
      // Inspect Container
      long containerIdx = node.getContainerIdx();
      Container container = highLowContainer.getContainer(containerIdx);
      long containerEnd = LongUtils.toLong(high, Character.MAX_VALUE) + 1;
      int containerRangeStartOffset = (int) (filledUntil - start);

      boolean startInContainer = containerStart < start;
      boolean endInContainer = end < containerEnd;

      if (startInContainer && endInContainer) {
        // Only part of the container is in range
        char containerRangeStart = LongUtils.lowPart(start);
        char containerRangeEnd = LongUtils.lowPart(end);
        container.forAllInRange(
            LongUtils.lowPart(start),
            LongUtils.lowPart(end),
            rrc);
        filledUntil += containerRangeEnd - containerRangeStart;
      } else if (startInContainer) {//  && !endInContainer
        // range begins within the container
        char containerRangeStart = LongUtils.lowPart(start);
        container.forAllFrom(containerRangeStart, rrc);
        filledUntil += BitmapContainer.MAX_CAPACITY - containerRangeStart;
      } else if (endInContainer) {// && !startInContainer
        // range end within the container
        char containerRangeEnd = LongUtils.lowPart(end);
        container.forAllUntil(containerRangeStartOffset, containerRangeEnd, rrc);
        filledUntil += containerRangeEnd;
      } else {
        container.forAll(containerRangeStartOffset, rrc);
        filledUntil += BitmapContainer.MAX_CAPACITY;
      }
      if (leafIterator.hasNext()) {
        node = leafIterator.next();
        high = node.getKeyBytes();
      } else {
        break;
      }
    }
    // next container (if any) is beyond the end, but there may be missing values in between
    if (filledUntil < end) {
      rrc.acceptAllAbsent((int) (filledUntil - start), length);
    }
  }

  /**
   * Consume each value present in the range [start, start + length).
   *
   * @param start Lower bound of values to consume.
   * @param length Maximum number of values to consume.
   * @param lc Code to be executed for each present value.
   */
  public void forEachInRange(long start, int length, final LongConsumer lc) {
    forAllInRange(start, length, new LongConsumerRelativeRangeAdapter(start, lc));
  }

  @Override
  public long rankLong(long id) {
    long result = 0;
    byte[] high = LongUtils.highPart(id);
    char low = LongUtils.lowPart(id);
    ContainerWithIndex containerWithIndex = highLowContainer.searchContainer(high);
    KeyIterator keyIterator = highLowContainer.highKeyIterator();
    if (containerWithIndex == null) {
      while (keyIterator.hasNext()) {
        byte[] highKey = keyIterator.next();
        int res = LongUtils.compareHigh(highKey, high);
        if (res > 0) {
          break;
        } else {
          long containerIdx = keyIterator.currentContainerIdx();
          Container container = highLowContainer.getContainer(containerIdx);
          result += container.getCardinality();
        }
      }
    } else {
      while (keyIterator.hasNext()) {
        byte[] key = keyIterator.next();
        long containerIdx = keyIterator.currentContainerIdx();
        Container container = highLowContainer.getContainer(containerIdx);
        if (LongUtils.compareHigh(key, high) == 0) {
          result += container.rank(low);
          break;
        } else {
          result += container.getCardinality();
        }
      }
    }
    return result;
  }

  /**
   * In-place bitwise OR (union) operation. The current bitmap is modified.
   *
   * @param x2 other bitmap
   */
  public void or(final Roaring64Bitmap x2) {
    if(this == x2) { return; }
    KeyIterator highIte2 = x2.highLowContainer.highKeyIterator();
    while (highIte2.hasNext()) {
      byte[] high = highIte2.next();
      long containerIdx = highIte2.currentContainerIdx();
      Container container2 = x2.highLowContainer.getContainer(containerIdx);
      ContainerWithIndex containerWithIdx = this.highLowContainer.searchContainer(high);
      if (containerWithIdx == null) {
        Container container2clone = container2.clone();
        this.highLowContainer.put(high, container2clone);
      } else {
        Container freshContainer = containerWithIdx.getContainer().ior(container2);
        this.highLowContainer.replaceContainer(containerWithIdx.getContainerIdx(), freshContainer);
      }
    }
  }

  /**
   * In-place bitwise XOR (symmetric difference) operation. The current bitmap is modified.
   *
   * @param x2 other bitmap
   */
  public void xor(final Roaring64Bitmap x2) {
    if(x2 == this) {
      clear();
      return;
    }
    KeyIterator keyIterator = x2.highLowContainer.highKeyIterator();
    while (keyIterator.hasNext()) {
      byte[] high = keyIterator.next();
      long containerIdx = keyIterator.currentContainerIdx();
      Container container = x2.highLowContainer.getContainer(containerIdx);
      ContainerWithIndex containerWithIndex = this.highLowContainer.searchContainer(high);
      if (containerWithIndex == null) {
        Container containerClone2 = container.clone();
        this.highLowContainer.put(high, containerClone2);
      } else {
        Container freshOne = containerWithIndex.getContainer().ixor(container);
        this.highLowContainer.replaceContainer(containerWithIndex.getContainerIdx(), freshOne);
      }
    }
  }

  /**
   * In-place bitwise AND (intersection) operation. The current bitmap is modified.
   *
   * @param x2 other bitmap
   */
  public void and(final Roaring64Bitmap x2) {
    if(x2 == this) { return; }
    KeyIterator thisIterator = highLowContainer.highKeyIterator();
    while (thisIterator.hasNext()) {
      byte[] highKey = thisIterator.next();
      long containerIdx = thisIterator.currentContainerIdx();
      ContainerWithIndex containerWithIdx = x2.highLowContainer.searchContainer(highKey);
      if (containerWithIdx == null) {
        thisIterator.remove();
      } else {
        Container container1 = highLowContainer.getContainer(containerIdx);
        Container freshContainer = container1.iand(containerWithIdx.getContainer());
        if (!freshContainer.isEmpty()) {
          highLowContainer.replaceContainer(containerIdx, freshContainer);
        } else {
          thisIterator.remove();
        }
      }
    }
  }


  /**
   * In-place bitwise ANDNOT (difference) operation. The current bitmap is modified.
   *
   * @param x2 other bitmap
   */
  public void andNot(final Roaring64Bitmap x2) {
    if(x2 == this) {
      clear();
      return;
    }
    KeyIterator thisKeyIterator = highLowContainer.highKeyIterator();
    while (thisKeyIterator.hasNext()) {
      byte[] high = thisKeyIterator.next();
      long containerIdx = thisKeyIterator.currentContainerIdx();
      ContainerWithIndex containerWithIdx2 = x2.highLowContainer.searchContainer(high);
      if (containerWithIdx2 != null) {
        Container thisContainer = highLowContainer.getContainer(containerIdx);
        Container freshContainer = thisContainer.iandNot(containerWithIdx2.getContainer());
        highLowContainer.replaceContainer(containerIdx, freshContainer);
        if (!freshContainer.isEmpty()) {
          highLowContainer.replaceContainer(containerIdx, freshContainer);
        } else {
          thisKeyIterator.remove();
        }
      }
    }
  }

  /**
   * Complements the bits in the given range, from rangeStart (inclusive) rangeEnd (exclusive). The
   * given bitmap is unchanged.
   *
   * @param rangeStart inclusive beginning of range, in [0, 0xffffffffffffffff]
   * @param rangeEnd exclusive ending of range, in [0, 0xffffffffffffffff + 1]
   */
  public void flip(final long rangeStart, final long rangeEnd) {

    if(rangeStart >= 0 && rangeEnd >= 0 && rangeStart >= rangeEnd){
      // both numbers in positive range, and start is beyond end, nothing to do.
      return;
    } else if(rangeStart < 0 && rangeEnd < 0 && rangeStart >= rangeEnd){
      // both numbers in negative range, and start is beyond end, nothing to do.
      return;
    } else if(rangeStart < 0 && rangeEnd > 0) {
      // start is neg which is "higher" and end is above zero thus, nothing to do.
      return;
    }

    byte[] hbStart = LongUtils.highPart(rangeStart);
    char lbStart = LongUtils.lowPart(rangeStart);
    char lbLast = LongUtils.lowPart(rangeEnd - 1L);

    long shStart = LongUtils.rightShiftHighPart(rangeStart);
    long shEnd = LongUtils.rightShiftHighPart(rangeEnd - 1L);

    // TODO:this can be accelerated considerably
    for (long hb = shStart; hb <= shEnd; ++hb) {
      // first container may contain partial range
      final int containerStart = (hb == shStart) ? lbStart : 0;
      // last container may contain partial range
      final int containerLast = (hb == shEnd) ? lbLast : LongUtils.maxLowBitAsInteger();

      ContainerWithIndex cwi = highLowContainer.searchContainer(
              LongUtils.highPartInPlace(LongUtils.leftShiftHighPart(hb), hbStart));

      if (cwi != null) {
        final long i = cwi.getContainerIdx();
        final Container c = cwi.getContainer().inot(containerStart, containerLast + 1);
        if (!c.isEmpty()) {
          highLowContainer.replaceContainer(i, c);
        } else {
          highLowContainer.remove(hbStart);
        }
      } else {
        Container newContainer = Container.rangeOfOnes(containerStart, containerLast + 1);
        highLowContainer.put(hbStart, newContainer);
      }
    }
  }

  /**
   * {@link Roaring64NavigableMap} are serializable. However, contrary to RoaringBitmap, the
   * serialization format is not well-defined: for now, it is strongly coupled with Java standard
   * serialization. Just like the serialization may be incompatible between various Java versions,
   * {@link Roaring64NavigableMap} are subject to incompatibilities. Moreover, even on a given Java
   * versions, the serialization format may change from one RoaringBitmap version to another
   */
  @Override
  public void writeExternal(ObjectOutput out) throws IOException {
    serialize(out);
  }

  @Override
  public void readExternal(ObjectInput in) throws IOException {
    deserialize(in);
  }

  /**
   * A string describing the bitmap.
   *
   * @return the string
   */
  @Override
  public String toString() {
    final StringBuilder answer = new StringBuilder();
    final LongIterator i = this.getLongIterator();
    answer.append("{");
    if (i.hasNext()) {
      answer.append(i.next());
    }
    while (i.hasNext()) {
      answer.append(",");
      // to avoid using too much memory, we limit the size
      if (answer.length() > 0x80000) {
        answer.append("...");
        break;
      }
      answer.append(i.next());
    }
    answer.append("}");
    return answer.toString();
  }


  /**
   * For better performance, consider the Use the {@link #forEach forEach} method.
   *
   * @return a custom iterator over set bits, the bits are traversed in ascending sorted order
   */
  @Override
  public PeekableLongIterator getLongIterator() {
    LeafNodeIterator leafNodeIterator = highLowContainer.highKeyLeafNodeIterator(false);
    return new ForwardPeekableIterator(leafNodeIterator);
  }

  // for testing only
  LeafNodeIterator getLeafNodeIterator() {
    return highLowContainer.highKeyLeafNodeIterator(false);
  }

  /**
   * Produce an iterator over the values in this bitmap starting from `minval`.
   *
   * @param minval the lower bound of the iterator returned
   * @return a custom iterator over set bits, the bits are traversed in ascending sorted order
   */
  public PeekableLongIterator getLongIteratorFrom(long minval) {
    LeafNodeIterator leafNodeIterator = highLowContainer.highKeyLeafNodeIteratorFrom(minval, false);
    ForwardPeekableIterator fpi = new ForwardPeekableIterator(leafNodeIterator);
    fpi.advanceIfNeeded(minval); // make sure the lower end is advanced as well
    return fpi;
  }

  @Override
  public boolean contains(long x) {
    byte[] high = LongUtils.highPart(x);
    ContainerWithIndex containerWithIdx = highLowContainer.searchContainer(high);
    if (containerWithIdx == null) {
      return false;
    }
    char low = LongUtils.lowPart(x);
    return containerWithIdx.getContainer().contains(low);
  }

  @Override
  public int getSizeInBytes() {
    return (int) getLongSizeInBytes();
  }


  /**
   * Estimate of the memory usage of this data structure. This can be expected to be within 1% of
   * the true memory usage in common usage scenarios.
   * If exact measures are needed, we recommend using dedicated libraries
   * such as ehcache-sizeofengine.
   *
   * In adversarial cases, this estimate may be 10x the actual memory usage. For example, if
   * you insert a single random value in a bitmap, then over a 100 bytes may be used by the JVM
   * whereas this function may return an estimate of 32 bytes.
   *
   * The same will be true in the "sparse" scenario where you have a small set of
   * random-looking integers spanning a wide range of values.
   *
   * These are considered adversarial cases because, as a general rule,
   * if your data looks like a set
   * of random integers, Roaring bitmaps are probably not the right data structure.
   *
   * Note that you can serialize your Roaring Bitmaps to disk and then construct
   * ImmutableRoaringBitmap instances from a ByteBuffer. In such cases, the Java heap
   * usage will be significantly less than
   * what is reported.
   *
   * If your main goal is to compress arrays of integers, there are other libraries
   * that are maybe more appropriate
   * such as JavaFastPFOR.
   *
   * Note, however, that in general, random integers (as produced by random number
   * generators or hash functions) are not compressible.
   * Trying to compress random data is an adversarial use case.
   *
   * @see <a href="https://github.com/lemire/JavaFastPFOR">JavaFastPFOR</a>
   *
   *
   * @return estimated memory usage.
   */
  @Override
  public long getLongSizeInBytes() {
    // 'serializedSizeInBytes' is a better than nothing estimation of the memory footprint
    // It would generally be an optimistic estimator (by underestimating the size in memory)
    return serializedSizeInBytes();
  }

  @Override
  public boolean isEmpty() {
    return getLongCardinality() == 0L;
  }

  @Override
  public ImmutableLongBitmapDataProvider limit(long x) {
    throw new UnsupportedOperationException("TODO");
  }

  /**
   * Use a run-length encoding where it is estimated as more space efficient
   *
   * @return whether a change was applied
   */
  public boolean runOptimize() {
    boolean hasChanged = false;
    ContainerIterator containerIterator = highLowContainer.containerIterator();
    while (containerIterator.hasNext()) {
      Container container = containerIterator.next();
      Container freshContainer = container.runOptimize();
      if (freshContainer instanceof RunContainer) {
        hasChanged = true;
        containerIterator.replace(freshContainer);
      }
    }
    return hasChanged;
  }


  /**
   * Serialize this bitmap.
   *
   * Unlike RoaringBitmap, there is no specification for now: it may change from one java version to
   * another, and from one RoaringBitmap version to another.
   *
   * Consider calling {@link #runOptimize} before serialization to improve compression.
   *
   * The current bitmap is not modified.
   *
   * @param out the DataOutput stream
   * @throws IOException Signals that an I/O exception has occurred.
   */
  @Override
  public void serialize(DataOutput out) throws IOException {
    highLowContainer.serialize(out);
  }

  /**
   * Serialize this bitmap, please make sure the size of the serialized bytes is
   * smaller enough that ByteBuffer can hold it.
   * @param byteBuffer the ByteBuffer
   * @throws IOException Signals that an I/O exception has occurred.
   */
  public void serialize(ByteBuffer byteBuffer) throws IOException {
    highLowContainer.serialize(byteBuffer);
  }

  /**
   * Deserialize (retrieve) this bitmap.
   *
   * Unlike RoaringBitmap, there is no specification for now: it may change from one java version to
   * another, and from one RoaringBitmap version to another.
   *
   * The current bitmap is overwritten.
   *
   * @param in the DataInput stream
   * @throws IOException Signals that an I/O exception has occurred.
   */
  public void deserialize(DataInput in) throws IOException {
    this.clear();
    highLowContainer.deserialize(in);
  }

  /**
   * Deserialize (retrieve) this bitmap.
   *
   * Unlike RoaringBitmap, there is no specification for now: it may change from one java version to
   * another, and from one RoaringBitmap version to another.
   *
   * The current bitmap is overwritten.
   *
   * @param in the ByteBuffer stream
   * @throws IOException Signals that an I/O exception has occurred.
   */
  public void deserialize(ByteBuffer in) throws IOException {
    this.clear();
    highLowContainer.deserialize(in);
  }

  @Override
  public long serializedSizeInBytes() {
    long nbBytes = highLowContainer.serializedSizeInBytes();
    return nbBytes;
  }

  /**
   * reset to an empty bitmap; result occupies as much space a newly created bitmap.
   */
  public void clear() {
    this.highLowContainer.clear();
  }

  /**
   * Return the set values as an array, if the cardinality is smaller than 2147483648. The long
   * values are in sorted order.
   *
   * @return array representing the set values.
   */
  @Override
  public long[] toArray() {
    long cardinality = this.getLongCardinality();
    if (cardinality > Integer.MAX_VALUE) {
      throw new IllegalStateException("The cardinality does not fit in an array");
    }

    final long[] array = new long[(int) cardinality];

    int pos = 0;
    LongIterator it = getLongIterator();

    while (it.hasNext()) {
      array[pos++] = it.next();
    }
    return array;
  }

  /**
   * Generate a bitmap with the specified values set to true. The provided longs values don't have
   * to be in sorted order, but it may be preferable to sort them from a performance point of view.
   *
   * @param dat set values
   * @return a new bitmap
   */
  public static Roaring64Bitmap bitmapOf(final long... dat) {
    final Roaring64Bitmap ans = new Roaring64Bitmap();
    ans.add(dat);
    return ans;
  }

  /**
   * Set all the specified values to true. This can be expected to be slightly faster than calling
   * "add" repeatedly. The provided integers values don't have to be in sorted order, but it may be
   * preferable to sort them from a performance point of view.
   *
   * @param dat set values
   */
  public void add(long... dat) {
    for (long oneLong : dat) {
      addLong(oneLong);
    }
  }

  /**
   * Add to the current bitmap all longs in [rangeStart,rangeEnd).
   *
   * @param rangeStart inclusive beginning of range
   * @param rangeEnd exclusive ending of range
   * @deprecated as this may be confused with adding individual longs
   */
  @Deprecated
  public void add(final long rangeStart, final long rangeEnd) {
    addRange(rangeStart, rangeEnd);
  }

  /**
   * Add to the current bitmap all longs in [rangeStart,rangeEnd).
   *
   * @param rangeStart inclusive beginning of range
   * @param rangeEnd exclusive ending of range
   */
  public void addRange(final long rangeStart, final long rangeEnd) {
    if (rangeEnd == 0 || Long.compareUnsigned(rangeStart, rangeEnd) >= 0) {
      throw new IllegalArgumentException("Invalid range [" + rangeStart + "," + rangeEnd + ")");
    }

    byte[] startHigh = LongUtils.highPart(rangeStart);
    int startLow = LongUtils.lowPart(rangeStart);
    byte[] endHigh = LongUtils.highPart(rangeEnd - 1);
    int endLow = LongUtils.lowPart(rangeEnd - 1);
    long rangeStartVal = rangeStart;
    byte[] startHighKey = startHigh;
    for (; LongUtils.compareHigh(startHighKey, endHigh) <= 0; ) {
      final int containerStart =
          (LongUtils.compareHigh(startHighKey, startHigh) == 0) ? startLow : 0;
      // last container may contain partial range
      final int containerLast = (LongUtils.compareHigh(startHighKey, endHigh) == 0) ? endLow
          : Util.maxLowBitAsInteger();
      ContainerWithIndex containerWithIndex = highLowContainer.searchContainer(startHighKey);
      if (containerWithIndex != null) {
        long containerIdx = containerWithIndex.getContainerIdx();
        Container freshContainer = highLowContainer.getContainer(containerIdx)
            .iadd(containerStart, containerLast + 1);
        highLowContainer.replaceContainer(containerIdx, freshContainer);
      } else {
        Container freshContainer = Container.rangeOfOnes(containerStart, containerLast + 1);
        highLowContainer.put(startHighKey, freshContainer);
      }

      if (LongUtils.isMaxHigh(startHighKey)) {
        break;
      }
      //increase the high
      rangeStartVal = rangeStartVal + (containerLast - containerStart) + 1;
      startHighKey = LongUtils.highPart(rangeStartVal);
    }
  }

  @Override
  public PeekableLongIterator getReverseLongIterator() {
    LeafNodeIterator leafNodeIterator = highLowContainer.highKeyLeafNodeIterator(true);
    return new ReversePeekableIterator(leafNodeIterator);
  }

  /**
   * Produce an iterator over the values in this bitmap starting from `maxval`.
   *
   * @param maxval the upper bound of the iterator returned
   * @return a custom iterator over set bits, the bits are traversed in descending sorted order
   */
  public PeekableLongIterator getReverseLongIteratorFrom(long maxval) {
    LeafNodeIterator leafNodeIterator = highLowContainer.highKeyLeafNodeIteratorFrom(maxval, true);
    ReversePeekableIterator rpi = new ReversePeekableIterator(leafNodeIterator);
    rpi.advanceIfNeeded(maxval); // make sure the lower end is advanced as well
    return rpi;
  }

  @Override
  public void removeLong(long x) {
    byte[] high = LongUtils.highPart(x);
    ContainerWithIndex containerWithIdx = highLowContainer.searchContainer(high);
    if (containerWithIdx != null) {
      char low = LongUtils.lowPart(x);
      Container container = containerWithIdx.getContainer();
      Container freshContainer = container.remove(low);
      if (freshContainer.isEmpty()) {
        // Attempt to remove empty container to save memory
        highLowContainer.remove(high);
      } else {
        highLowContainer.replaceContainer(containerWithIdx.getContainerIdx(), freshContainer);
      }
    }
  }

  /**
   * remove the allocated unused memory space
   */
  @Override
  public void trim() {
    if (highLowContainer.isEmpty()) {
      return;
    }
    KeyIterator keyIterator = highLowContainer.highKeyIterator();
    while (keyIterator.hasNext()) {
      long containerIdx = keyIterator.currentContainerIdx();
      Container container = highLowContainer.getContainer(containerIdx);
      if (container.isEmpty()) {
        keyIterator.remove();
      } else {
        //TODO
        container.trim();
      }
    }
  }

  @Override
  public int hashCode() {
    return highLowContainer.hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null) {
      return false;
    }
    if (getClass() != obj.getClass()) {
      return false;
    }
    Roaring64Bitmap other = (Roaring64Bitmap) obj;
    return Objects.equals(highLowContainer, other.highLowContainer);
  }


  /**
   * Add the value if it is not already present, otherwise remove it.
   *
   * @param x long value
   */
  public void flip(final long x) {
    byte[] high = LongUtils.highPart(x);
    ContainerWithIndex containerWithIndex = highLowContainer.searchContainer(high);
    if (containerWithIndex == null) {
      addLong(x);
    } else {
      char low = LongUtils.lowPart(x);
      Container freshOne = containerWithIndex.getContainer().flip(low);
      highLowContainer.replaceContainer(containerWithIndex.getContainerIdx(), freshOne);
    }
  }

  //mainly used for benchmark
  @Override
  public Roaring64Bitmap clone() {
    long sizeInBytesL = this.serializedSizeInBytes();
    if (sizeInBytesL >= Integer.MAX_VALUE) {
      throw new UnsupportedOperationException();
    }
    int sizeInBytesInt = (int) sizeInBytesL;
    ByteBuffer byteBuffer = ByteBuffer.allocate(sizeInBytesInt).order(ByteOrder.LITTLE_ENDIAN);
    try {
      this.serialize(byteBuffer);
      byteBuffer.flip();
      Roaring64Bitmap freshOne = new Roaring64Bitmap();
      freshOne.deserialize(byteBuffer);
      return freshOne;
    } catch (Exception e) {
      throw new RuntimeException("fail to clone thorough the ser/deser", e);
    }
  }


  private abstract class PeekableIterator implements PeekableLongIterator {
    private final LeafNodeIterator keyIte;
    private byte[] high;
    private PeekableCharIterator charIterator;

    PeekableIterator(final LeafNodeIterator keyIte) {
      this.keyIte = keyIte;
    }
    
    abstract PeekableCharIterator getIterator(Container container);
    abstract boolean compare(long next, long val);
    abstract boolean compareHigh(byte[] next, byte[] val);

    @Override
    public boolean hasNext() {
      if (charIterator != null && charIterator.hasNext()) {
        return true;
      }
      while (keyIte.hasNext()) {
        LeafNode leafNode = keyIte.next();
        high = leafNode.getKeyBytes();
        long containerIdx = leafNode.getContainerIdx();
        Container container = highLowContainer.getContainer(containerIdx);
        charIterator = getIterator(container);
        if(charIterator.hasNext()){
          return true;
        }
      }
      return false;
    }

    @Override
    public long next() {
      if (hasNext()) {
        char low = charIterator.next();
        return LongUtils.toLong(high, low);
      } else {
        throw new IllegalStateException("empty");
      }
    }

    @Override
    public void advanceIfNeeded(long minval) {
      if (!hasNext()) {
        return;
      }
      if(compare(this.peekNext(), minval)) {
        return;
      }
      //empty bitset
      if(this.high == null) {
        return;
      }

      byte[] minHigh = LongUtils.highPart(minval);
      if (!Arrays.equals(this.high, minHigh)) {
        // advance outer
        if (keyIte.hasNext()) {
          LeafNode leafNode = keyIte.next();
          this.high = leafNode.getKeyBytes();
          if (compareHigh(this.high, minHigh)) {
            long containerIdx = leafNode.getContainerIdx();
            Container container = highLowContainer.getContainer(containerIdx);
            charIterator = getIterator(container);
            if(!charIterator.hasNext()){
              return;
            }
          } else {
            keyIte.seek(minval);
            if (keyIte.hasNext()) {
              leafNode = keyIte.next();
              this.high = leafNode.getKeyBytes();
              long containerIdx = leafNode.getContainerIdx();
              Container container = highLowContainer.getContainer(containerIdx);
              charIterator = getIterator(container);
              if(!charIterator.hasNext()){
                return;
              }
            } else {
              // make sure we don't accidentally continue at the previous iterator position
              // after stepping to the end.
              charIterator = null;
              return;
            }
          }
        }
      }

      if (Arrays.equals(this.high, minHigh)) {
        // advance inner
        char low = LongUtils.lowPart(minval);
        charIterator.advanceIfNeeded(low);
      }
    }

    @Override
    public long peekNext() {
      if (hasNext()) {
        char low = charIterator.peekNext();
        return LongUtils.toLong(high, low);
      } else {
        throw new IllegalStateException("empty");
      }
    }

    @Override
    public PeekableLongIterator clone() {
      throw new UnsupportedOperationException("TODO");
    }
  }


  private class ForwardPeekableIterator extends PeekableIterator {

    public ForwardPeekableIterator(final LeafNodeIterator keyIte) {
      super(keyIte);
    }
    
    @Override
    PeekableCharIterator getIterator(Container container) {
      return container.getCharIterator();
    }
    
    @Override
    boolean compare(long next, long val) {
      return Long.compareUnsigned(next, val) >= 0;
    }

    @Override
    boolean compareHigh(byte[] next, byte[] val) {
      return LongUtils.compareHigh(next, val) >= 0;
    }
  }

  private class ReversePeekableIterator extends PeekableIterator {
    public ReversePeekableIterator(final LeafNodeIterator keyIte) {
      super(keyIte);
    }
    
    @Override
    PeekableCharIterator getIterator(Container container) {
      return container.getReverseCharIterator();
    }
    
    @Override
    boolean compare(long next, long val) {
      return Long.compareUnsigned(next, val) <= 0;
    }

    @Override
    boolean compareHigh(byte[] next, byte[] val) {
      return LongUtils.compareHigh(next, val) <= 0;
    }
  }
}
