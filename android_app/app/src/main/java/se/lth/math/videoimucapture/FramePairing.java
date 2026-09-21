package se.lth.math.videoimucapture;

import android.util.Log;

import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;

import se.lth.math.videoimucapture.RecordingProtos.VideoFrameMetaData;
import se.lth.math.videoimucapture.RecordingProtos.VideoFrameToTimestamp;

/**
 * Joins the two halves of a frame record, which arrive separately: the capture result's
 * metadata from the camera thread, and the encoder's "frame N has this timestamp" from the
 * encoder thread. A row is written when both halves of one sensor timestamp are in hand.
 *
 * Out of RecordingWriter so that the rule about what counts as a LOSS can be tested without
 * a file or a camera, because that rule is what the file's frame_accounting is made of.
 *
 * PRE-ROLL IS NOT LOSS. The first frame of a clip was exposed before the record button's
 * press reached the camera callback -- the pipeline is a few frames deep -- so whether its
 * result was recorded came down to which side of the flag it arrived on, and about half of
 * all clips lost frame 0 (fork issue #3: time_dropped_unmatched = 1, first row is frame 1).
 * Camera2Proxy now hands over the last few results from BEFORE the press, so frame 0's is
 * among them. The rest of those are older than anything the encoder will ever number; they
 * are discarded here, and counted apart, because a row for a picture that is not in the mp4
 * is not a hole in the mp4's frame table.
 *
 * The rule is stateless: a result is pre-roll when the encoder frame it is OLDER THAN is frame
 * number 0. The encoder is built afresh for every clip and numbers from 0, so this holds for
 * each clip of a metadata file that carries several (a stills run with two recordings in it),
 * and a result with no partner anywhere later in a clip is a loss exactly as it always was.
 *
 * Not thread-safe: both offers are made from the writer's own thread. The counters are
 * volatile because the receipt reads them from another.
 */
final class FramePairing {
    private static final String TAG = "FramePairing";
    private static final boolean VERBOSE = false;

    /** Two stamps are the same sensor frame if they are this close: 10 us. */
    private static final long MATCH_NS = 10000L;

    interface Sink {
        void write(VideoFrameMetaData merged) throws IOException;
    }

    // Queues to handle merging of video frames. tryMerge() can only drain these in
    // PAIRS, so if one side is produced faster than the other for any sustained period the
    // faster queue fills and never empties. That is not hypothetical: a camera running at a
    // higher rate than the encoder does it in seconds, and so does an encoder stalling under
    // thermal load on a long capture. add() throws IllegalStateException when full, which on
    // this thread is fatal to the whole app and takes the clip with it, so these are filled
    // by offer-and-drop-oldest instead: an unmatchable frame is dropped, and counted.
    private final Queue<VideoFrameMetaData> mFrameDataQueue = new ArrayBlockingQueue<>(100);
    private final Queue<VideoFrameToTimestamp> mFrameTimeQueue = new ArrayBlockingQueue<>(100);

    // The OTHER way a frame record disappears, and the one that had no counter: the merge
    // discards a message that has no partner within the 10 us window. That is not a full
    // queue and not an error -- it is the normal outcome when the two streams drift -- and
    // it left the August jetty file with 9,457 records against 9,462 encoded frames. Five
    // holes, no complaint, and the images were being joined to the records BY LIST POSITION
    // downstream, which is how the wrong focal length was measured (#48). A hole that is not
    // counted is a hole that shifts every record after it by one.
    //
    // Written on the writer thread, read from the UI thread when the session is sealed, so
    // they are volatile: a stale count in the manifest would be a lie in the receipt.
    private volatile int mMetaDroppedQueue = 0;
    private volatile int mTimeDroppedQueue = 0;
    private volatile int mMetaDroppedMerge = 0;
    private volatile int mTimeDroppedMerge = 0;
    private volatile int mMetaPreRoll = 0;
    private volatile int mWritten = 0;

    void reset() {
        mFrameDataQueue.clear();
        mFrameTimeQueue.clear();
        mMetaDroppedQueue = 0;
        mTimeDroppedQueue = 0;
        mMetaDroppedMerge = 0;
        mTimeDroppedMerge = 0;
        mMetaPreRoll = 0;
        mWritten = 0;
    }

    int written() {
        return mWritten;
    }

    int metaDroppedQueue() {
        return mMetaDroppedQueue;
    }

    int timeDroppedQueue() {
        return mTimeDroppedQueue;
    }

    int metaDroppedMerge() {
        return mMetaDroppedMerge;
    }

    int timeDroppedMerge() {
        return mTimeDroppedMerge;
    }

    /** Results from before the clip's first frame, discarded. Not a loss: see the class note. */
    int metaPreRoll() {
        return mMetaPreRoll;
    }

    void offerMeta(VideoFrameMetaData meta, Sink sink) throws IOException {
        if (!mFrameDataQueue.offer(meta)) {
            mFrameDataQueue.poll();                     // oldest will never find a partner
            mFrameDataQueue.offer(meta);
            countDrop(true);
        }
        tryMerge(sink);
    }

    void offerTime(VideoFrameToTimestamp time, Sink sink) throws IOException {
        if (!mFrameTimeQueue.offer(time)) {
            mFrameTimeQueue.poll();
            mFrameTimeQueue.offer(time);
            countDrop(false);
        }
        tryMerge(sink);
    }

    private void countDrop(boolean meta) {
        int n = meta ? ++mMetaDroppedQueue : ++mTimeDroppedQueue;
        if (n == 1 || n % 100 == 0) {
            Log.w(TAG, String.format(
                    "frame %s queue full, dropped oldest (%d so far; meta=%d time=%d queued). "
                            + "The camera and the encoder are running at different rates.",
                    meta ? "meta" : "time", n, mFrameDataQueue.size(), mFrameTimeQueue.size()));
        }
    }

    private void tryMerge(Sink sink) throws IOException {
        if (VERBOSE) {
            Log.d(TAG, String.format("Trying to merge, Queue lengths: %d,%d",
                    mFrameDataQueue.size(), mFrameTimeQueue.size()));
        }

        // Peek at oldest frame time message
        VideoFrameToTimestamp frameTimeMsg = mFrameTimeQueue.peek();
        VideoFrameMetaData frameMetaMsg = mFrameDataQueue.peek();

        //Try to find frames to match
        while ((frameTimeMsg != null) && (frameMetaMsg != null)) {
            long timeDiffNs = (1000 * frameTimeMsg.getTimeUs() - frameMetaMsg.getTimeNs());
            if (VERBOSE) Log.d(TAG, String.format("Time diff: %d ns", timeDiffNs));

            if (Math.abs(timeDiffNs) <= MATCH_NS) {
                // They are from the same capture frame
                VideoFrameMetaData.Builder frameBuilder = VideoFrameMetaData.newBuilder()
                        .mergeFrom(frameMetaMsg)
                        .setFrameNumber(frameTimeMsg.getFrameNbr());
                sink.write(frameBuilder.build());
                mWritten++;
                // Remove frames from queue
                mFrameTimeQueue.poll();
                mFrameDataQueue.poll();
                //We are done
                break;
            } else if (timeDiffNs > 0) {
                //Meta message is too old, try another one
                mFrameDataQueue.poll(); // throw old
                frameMetaMsg = mFrameDataQueue.peek();
                if (frameTimeMsg.getFrameNbr() == 0) {
                    // Older than the clip's first frame: never a picture in the mp4.
                    mMetaPreRoll++;
                } else {
                    mMetaDroppedMerge++;
                    Log.d(TAG, "Diff too large, skipping frame meta data");
                }
            } else {
                // Frame Time message too old, try another one
                mFrameTimeQueue.poll(); // throw old
                frameTimeMsg = mFrameTimeQueue.peek();
                mTimeDroppedMerge++;
                Log.d(TAG, "Diff too large, skipping frame time data");
            }
        }
    }
}
