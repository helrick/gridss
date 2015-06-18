package au.edu.wehi.idsv.debruijn.positional;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.Comparator;
import java.util.Iterator;
import java.util.PriorityQueue;

import au.edu.wehi.idsv.debruijn.positional.AggregateNodeIterator.KmerNodeAggregator.KmerNodeAggregatorSnapshot;

import com.google.common.collect.Iterators;
import com.google.common.collect.Ordering;
import com.google.common.collect.PeekingIterator;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;

/**
 * Transforms a start position sorted sequence of KmerNodes to a
 * start position sorted sequence of non-overlapping KmerAggregateNodes
 * @author Daniel Cameron
 *
 */
public class AggregateNodeIterator implements Iterator<KmerAggregateNode> {
	private final PeekingIterator<KmerSupportNode> underlying;
	private PriorityQueue<KmerAggregateNode> outputSortBuffer = new PriorityQueue<KmerAggregateNode>(1024, KmerNode.ByStartPosition);
	private Long2ObjectOpenHashMap<KmerNodeAggregator> byKmer = new Long2ObjectOpenHashMap<KmerNodeAggregator>();
	private PriorityQueue<KmerNodeAggregatorSnapshot> byStartPosition = new PriorityQueue<KmerNodeAggregatorSnapshot>(1024, BySnapshotStartPosition);
	public AggregateNodeIterator(Iterator<KmerSupportNode> it) {
		this.underlying = Iterators.peekingIterator(it);
	}
	@Override
	public boolean hasNext() {
		ensureBuffer();
		return !outputSortBuffer.isEmpty();
	}
	@Override
	public KmerAggregateNode next() {
		ensureBuffer();
		return outputSortBuffer.poll();
	}
	private void ensureBuffer() {
		// we can emit whenever there are no unprocessed or incomplete intervals
		// before our current interval
		while (underlying.hasNext() && (outputSortBuffer.isEmpty() ||
				outputSortBuffer.peek().startPosition() >= underlying.peek().startPosition() ||
				(!byStartPosition.isEmpty() && outputSortBuffer.peek().startPosition() >= byStartPosition.peek().snapshotStart))) {
			process(underlying.next());
		}
		if (!underlying.hasNext()) {
			// flush everything
			flushBefore(Integer.MAX_VALUE);
		}
	}
	private void process(KmerSupportNode next) {
		flushBefore(next.startPosition());
		long kmer = next.kmer();
		KmerNodeAggregator ag = byKmer.get(kmer);
		if (ag == null) {
			ag = new KmerNodeAggregator(kmer);
			ag.add(next);
			byKmer.put(kmer, ag);
		}
		byStartPosition.add(ag.new KmerNodeAggregatorSnapshot());
		byStartPosition_removeInvalidHead();
	}
	/**
	 * Flush all aggregate nodes starting before the given position
	 * @param position
	 */
	private void flushBefore(int position) {
		byStartPosition_removeInvalidHead();
		while (!byStartPosition.isEmpty()) {
			if (byStartPosition.peek().snapshotStart < position) {
				KmerNodeAggregator ag = byStartPosition.poll().aggregator();
				ag.advanceTo(position);
				if (ag.isEmpty()) {
					byKmer.remove(ag.kmer);
				} else {
					byStartPosition.add(ag.new KmerNodeAggregatorSnapshot());
				}
				byStartPosition_removeInvalidHead();
			}
		}
	}
	private static final Ordering<KmerNodeAggregatorSnapshot> BySnapshotStartPosition = new Ordering<KmerNodeAggregatorSnapshot>() {
		@Override
		public int compare(KmerNodeAggregatorSnapshot left, KmerNodeAggregatorSnapshot right) {
			return Ints.compare(left.snapshotStart, right.snapshotStart);
		}
	};
	/**
	 * Generates KmerAggregateNode from an underlying sequence of KmerSupportNodes in ascending starting position
	 * 
	 * @author Daniel Cameron
	 *
	 */
	class KmerNodeAggregator implements Comparable<KmerNodeAggregator> {
		public class KmerNodeAggregatorSnapshot {
			public KmerNodeAggregatorSnapshot() {
				this.snapshotStart = KmerNodeAggregator.this.start;
			}
			public final int snapshotStart;
			/**
			 * Determines whether the snapshot is still valid
			 * @return
			 */
			public boolean isValid() {
				return this.snapshotStart == KmerNodeAggregator.this.start;
			}
			// what's the correct syntax for doing this from outside the class?
			public KmerNodeAggregator aggregator() { return KmerNodeAggregator.this; }
		}
		public KmerNodeAggregator(long kmer) {
			this.kmer = kmer;
		}
		public boolean isEmpty() { return active.isEmpty(); }
		/**
		 * KmerNodes in the currently active aggregation interval
		 */
		private PriorityQueue<KmerNode> active = new PriorityQueue<KmerNode>(8, KmerNode.ByEndPosition);
		/**
		 * Start position of currently active aggregation interval
		 */
		private int start = Integer.MIN_VALUE;
		/**
		 * Weight of currently active aggregation interval
		 */
		private int weight = 0;
		/**
		 * Number of active reference KmerNode 
		 */
		private int referenceCount = 0;
		/**
		 * Advances to the next node, adding aggregate nodes to the given collection
		 * @param node next node
		 * @param emitTo collection to emit aggregate records to
		 */
		private final long kmer;
		public void add(KmerNode node) {
			assert(node.kmer() == kmer);
			assert(node.startPosition() >= start);
			advanceTo(node.startPosition() - 1);
			if (node.isReference()) {
				referenceCount++;
			}
			weight += node.weight();
			active.add(node);
		}
		/**
		 * Process up to and including the given position
		 * @param position final processing position
		 * @param emitTo collection to emit aggregate records to
		 */
		public void advanceTo(int position) {
			while (!active.isEmpty() && position > active.peek().endPosition()) {
				KmerNode endingHere = active.poll();
				int end = endingHere.endPosition();
				long kmer = endingHere.kmer();
				outputSortBuffer.add(new KmerAggregateNode(kmer, weight, start, end, referenceCount > 0));
				while (active.peek().endPosition() == end) {
					weight -= endingHere.weight();
					if (endingHere.isReference()) {
						referenceCount--;
					}
				}
				start = end + 1;
			}
		}
		@Override
		public int compareTo(KmerNodeAggregator right) {
			return Longs.compare(kmer, right.kmer);
		}
	}
	@Override
	public void remove() {
		throw new UnsupportedOperationException();
	}
	private void byStartPosition_removeInvalidHead() {
		while (!byStartPosition.isEmpty() && !byStartPosition.peek().isValid()) {
			byStartPosition.poll();
		}
	}
}
