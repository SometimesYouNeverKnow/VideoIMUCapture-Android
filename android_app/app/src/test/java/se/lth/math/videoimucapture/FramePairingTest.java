package se.lth.math.videoimucapture;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import se.lth.math.videoimucapture.RecordingProtos.VideoFrameMetaData;
import se.lth.math.videoimucapture.RecordingProtos.VideoFrameToTimestamp;

/**
 * What counts as a lost frame record, and what does not.
 *
 * The file's frame_accounting is made of these counters, and a reader who joins pictures to
 * rows by position trusts them to say where the two tables part company. So the cases here
 * are the ones the phone actually produced: frame 0 exposed before the press (fork issue #3),
 * a result that never arrives mid-clip, and two recordings inside one stills run.
 */
public class FramePairingTest {

    private static final long FRAME_NS = 33_333_333L;
    private static final long T0 = 9_000_000_000_000L;      // any sensor clock value

    private final List<VideoFrameMetaData> mRows = new ArrayList<>();
    private final FramePairing.Sink mSink = mRows::add;

    private static VideoFrameMetaData meta(long k) {
        return VideoFrameMetaData.newBuilder().setTimeNs(T0 + k * FRAME_NS).build();
    }

    /** The encoder's half: frame number n is the picture exposed at sensor frame k. */
    private static VideoFrameToTimestamp time(long n, long k) {
        return VideoFrameToTimestamp.newBuilder()
                .setFrameNbr(n).setTimeUs((T0 + k * FRAME_NS) / 1000).build();
    }

    private static void assertNoLoss(FramePairing p) {
        assertEquals(0, p.metaDroppedMerge());
        assertEquals(0, p.timeDroppedMerge());
        assertEquals(0, p.metaDroppedQueue());
        assertEquals(0, p.timeDroppedQueue());
    }

    @Test
    public void bothHalves_inEitherOrder_makeARowNumberedByTheEncoder() throws Exception {
        FramePairing p = new FramePairing();
        p.offerMeta(meta(0), mSink);
        p.offerTime(time(0, 0), mSink);
        p.offerTime(time(1, 1), mSink);      // the encoder's half first, this time
        p.offerMeta(meta(1), mSink);

        assertEquals(2, p.written());
        assertEquals(0, mRows.get(0).getFrameNumber());
        assertEquals(1, mRows.get(1).getFrameNumber());
        assertNoLoss(p);
    }

    /**
     * Issue #3's clip, as it now goes: the results from before the press are handed over, the
     * encoder's frame 0 turns out to be the fourth of them, and the three older ones were
     * never pictures in the mp4. Frame 0 has its row and nothing is reported lost.
     */
    @Test
    public void resultsOlderThanFrameZero_arePreRoll_notLosses() throws Exception {
        FramePairing p = new FramePairing();
        for (long k = 0; k < 6; k++) {
            p.offerMeta(meta(k), mSink);
        }
        p.offerTime(time(0, 3), mSink);
        p.offerTime(time(1, 4), mSink);
        p.offerTime(time(2, 5), mSink);

        assertEquals(3, p.written());
        assertEquals("frame 0 has a row", 0, mRows.get(0).getFrameNumber());
        assertEquals(3, p.metaPreRoll());
        assertNoLoss(p);
    }

    /** What used to happen about half the time: frame 0's result was never recorded at all. */
    @Test
    public void frameZeroWithNoResult_isStillALoss() throws Exception {
        FramePairing p = new FramePairing();
        p.offerTime(time(0, 0), mSink);
        p.offerMeta(meta(1), mSink);         // the first result recorded is frame 1's
        p.offerTime(time(1, 1), mSink);

        assertEquals(1, p.written());
        assertEquals("the first row is frame 1", 1, mRows.get(0).getFrameNumber());
        assertEquals(1, p.timeDroppedMerge());
        assertEquals(0, p.metaPreRoll());
    }

    /** Pre-roll is a statement about the START of a clip. Mid-clip, an orphan is a hole. */
    @Test
    public void anOrphanResultMidClip_isALoss_notPreRoll() throws Exception {
        FramePairing p = new FramePairing();
        p.offerMeta(meta(0), mSink);
        p.offerTime(time(0, 0), mSink);
        p.offerMeta(meta(1), mSink);         // the encoder skipped this sensor frame
        p.offerMeta(meta(2), mSink);
        p.offerTime(time(1, 2), mSink);

        assertEquals(2, p.written());
        assertEquals(1, p.metaDroppedMerge());
        assertEquals(0, p.metaPreRoll());
    }

    /**
     * Two recordings inside one stills run share a metadata file, so the pairing is NOT reset
     * between them. The second clip's pre-roll must not be charged as losses just because rows
     * have already been written -- and neither must the first clip's trailing results, which
     * arrived after its encoder had stopped.
     */
    @Test
    public void aSecondClipInTheSameFile_getsItsOwnPreRoll() throws Exception {
        FramePairing p = new FramePairing();
        p.offerMeta(meta(0), mSink);
        p.offerTime(time(0, 0), mSink);
        p.offerMeta(meta(1), mSink);
        p.offerTime(time(1, 1), mSink);
        p.offerMeta(meta(2), mSink);         // trailing: the first encoder has stopped

        for (long k = 100; k < 104; k++) {   // the second press's pre-roll
            p.offerMeta(meta(k), mSink);
        }
        p.offerTime(time(0, 102), mSink);    // a new encoder numbers from 0 again
        p.offerTime(time(1, 103), mSink);

        assertEquals(4, p.written());
        assertEquals("trailing result + two before the second frame 0", 3, p.metaPreRoll());
        assertNoLoss(p);
    }
}
