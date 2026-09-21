package se.lth.math.videoimucapture;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.protobuf.Timestamp;
import se.lth.math.videoimucapture.RecordingProtos.CameraInfo;
import se.lth.math.videoimucapture.RecordingProtos.EnvironmentData;
import se.lth.math.videoimucapture.RecordingProtos.GnssAntennaInfoData;
import se.lth.math.videoimucapture.RecordingProtos.GnssData;
import se.lth.math.videoimucapture.RecordingProtos.GnssMeasurementData;
import se.lth.math.videoimucapture.RecordingProtos.GnssNavigationMessageData;
import se.lth.math.videoimucapture.RecordingProtos.GnssStatusData;
import se.lth.math.videoimucapture.RecordingProtos.IMUData;
import se.lth.math.videoimucapture.RecordingProtos.IMUInfo;
import se.lth.math.videoimucapture.RecordingProtos.LightData;
import se.lth.math.videoimucapture.RecordingProtos.MessageWrapper;
import se.lth.math.videoimucapture.RecordingProtos.OrientationData;
import se.lth.math.videoimucapture.RecordingProtos.StepData;
import se.lth.math.videoimucapture.RecordingProtos.StillMetaData;
import se.lth.math.videoimucapture.RecordingProtos.ThermalData;
import se.lth.math.videoimucapture.RecordingProtos.VideoCaptureData;
import se.lth.math.videoimucapture.RecordingProtos.VideoFrameMetaData;
import se.lth.math.videoimucapture.RecordingProtos.VideoFrameToTimestamp;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;


public class RecordingWriter implements Runnable{
    final private static String TAG = "RecordingWriter";
    final private Boolean VERBOSE = false;

    private FileOutputStream mFileStream;
    // Sized for the full sensor suite (~530 msg/s: 200 Hz IMU + 3x100 Hz rotation vectors
    // + barometer + steps + GNSS). queueData() blocks the sensor thread when full, so
    // headroom here is what keeps a slow filesystem moment from stalling capture.
    private BlockingQueue<MessageWrapper> mQueue = new ArrayBlockingQueue<>(8192);
    //Empty message as poison pill
    private final MessageWrapper mPoisonPill = MessageWrapper.newBuilder().build();

    // The two halves of a frame record are paired, and their losses counted, in FramePairing.
    private final FramePairing mFrames = new FramePairing();
    private final FramePairing.Sink mFrameSink = merged ->
            VideoCaptureData.newBuilder().addVideoMeta(merged).build().writeTo(mFileStream);

    /**
     * What happened to the frame records, snapshotted when the session is sealed. This is
     * the file admitting to its own holes: {@link #isComplete()} is the question every
     * downstream reader that joins images to records by position should be asking first.
     */
    public static final class FrameAccounting {
        public final int metaWritten;
        public final int metaDroppedQueue;
        public final int metaDroppedMerge;
        public final int timeDroppedQueue;
        public final int timeDroppedMerge;

        FrameAccounting(int metaWritten, int metaDroppedQueue, int metaDroppedMerge,
                        int timeDroppedQueue, int timeDroppedMerge) {
            this.metaWritten = metaWritten;
            this.metaDroppedQueue = metaDroppedQueue;
            this.metaDroppedMerge = metaDroppedMerge;
            this.timeDroppedQueue = timeDroppedQueue;
            this.timeDroppedMerge = timeDroppedMerge;
        }

        public int totalDropped() {
            return metaDroppedQueue + metaDroppedMerge + timeDroppedQueue + timeDroppedMerge;
        }

        public boolean isComplete() {
            return totalDropped() == 0;
        }
    }

    /** A snapshot of the frame accounting, safe to take from any thread. */
    public FrameAccounting accounting() {
        return new FrameAccounting(mFrames.written(), mFrames.metaDroppedQueue(),
                mFrames.metaDroppedMerge(), mFrames.timeDroppedQueue(),
                mFrames.timeDroppedMerge());
    }

    /**
     * The last thing in the file: what happened to the frame records. Written on the writer
     * thread as the poison pill is taken, so the counts include everything that was merged.
     */
    private void writeFrameAccounting() throws IOException {
        FrameAccounting a = accounting();
        RecordingProtos.VideoCaptureData.newBuilder()
                .setFrameAccounting(RecordingProtos.FrameAccounting.newBuilder()
                        .setMetaWritten(a.metaWritten)
                        .setMetaDroppedQueueFull(a.metaDroppedQueue)
                        .setMetaDroppedUnmatched(a.metaDroppedMerge)
                        .setTimeDroppedQueueFull(a.timeDroppedQueue)
                        .setTimeDroppedUnmatched(a.timeDroppedMerge)
                        .setComplete(a.isComplete()))
                .build().writeTo(mFileStream);
        if (!a.isComplete()) {
            Log.w(TAG, String.format(Locale.US,
                    "sealing with %d frame records lost (%d written): queue meta=%d time=%d, "
                            + "unmatched meta=%d time=%d. Position-based joins on this file "
                            + "will be off by that much after the first hole.",
                    a.totalDropped(), a.metaWritten, a.metaDroppedQueue, a.timeDroppedQueue,
                    a.metaDroppedMerge, a.timeDroppedMerge));
        } else {
            Log.i(TAG, "sealing complete: " + a.metaWritten + " frame records, none lost ("
                    + mFrames.metaPreRoll() + " pre-roll results from before frame 0 discarded)");
        }
    }

    //Other state variables
    private Boolean mIsRecording = false;

    public Boolean isRecording() {return mIsRecording;}

    public void startRecording(String resultFile) throws IOException {

        Log.d(TAG, String.format("Starting on %s thread", Thread.currentThread()));
        mFileStream = new FileOutputStream(resultFile);

        //Reset state
        mIsRecording = true;
        mFrames.reset();
        mQueue.clear();

        //Start background thread
        Thread myThread = new Thread(this, "RecordingWriter");
        myThread.start();

    }

    /** Told, on the main thread, when the file could not be written. */
    public interface FailureListener {
        void onWriteFailed(IOException cause);
    }

    private volatile FailureListener mFailureListener;

    public void setFailureListener(FailureListener l) {
        mFailureListener = l;
    }

    public void stopRecording(){
        // offer(), not put(). This is called from the main thread, and put() on a queue whose
        // consumer has died is an ANR -- which is exactly the state a write failure used to
        // leave things in. A full queue here means the writer is already gone or hopelessly
        // behind, and in both cases the right move is to stop asking and let the file be
        // whatever was flushed.
        try {
            if (!mQueue.offer(mPoisonPill, 2, TimeUnit.SECONDS)) {
                Log.e(TAG, "writer did not take the stop within 2 s; abandoning the queue");
                mIsRecording = false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.d(TAG, "Interrupted in close.");
        }
    }

    public void run() {
        Log.d(TAG, String.format("Looping on %s thread", Thread.currentThread()));
        try {
            initializeFile();

            while (true) {
                MessageWrapper msg = mQueue.take();
                if (msg.equals(mPoisonPill)) {
                    writeFrameAccounting();
                    mFileStream.flush();
                    mFileStream.close();
                    mIsRecording = false;
                    return;
                }
                writeMessage(msg);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // A write failed -- a full card is the way this happens -- and what used to
            // follow was worse than the lost bytes. This thread ended here while
            // mIsRecording stayed TRUE, so queueData kept accepting samples into a queue
            // nobody was draining. At 8192 messages it filled, and then put() blocked the
            // sensor thread FOREVER: the IMU stream simply stopped, with no gap in the file
            // to show for it because nothing was being written at all. Pressing stop then
            // put the poison pill from the main thread into the same full queue and hung the
            // app. The clip did not end, it thinned out and then froze.
            //
            // So: say we have stopped before anything else, which makes queueData a no-op
            // and can never block a caller again, salvage what is on disk, and tell someone
            // who can end the session properly.
            mIsRecording = false;
            Log.e(TAG, "write failed, recording stopped: " + e);
            try {
                mFileStream.flush();
                mFileStream.close();
            } catch (IOException io) {
                Log.e(TAG, "and the salvage failed too: " + io);
            }
            final FailureListener listener = mFailureListener;
            if (listener != null) {
                new Handler(Looper.getMainLooper()).post(() -> listener.onWriteFailed(e));
            }
        } catch (RuntimeException e) {
            // Nothing here is worth losing the recording over. An uncaught throw on this
            // thread reaches the default handler and kills the process, and the mp4 is then
            // whatever the muxer had flushed — on 2026-09-02 that cost a 97 MB clip to a
            // queue bug. Salvage the sidecar and let the exception be a bug report, not a
            // data loss.
            Log.e(TAG, "Unexpected error in writer, closing file to salvage the clip", e);
            try {
                mFileStream.flush();
                mFileStream.close();
            } catch (IOException io) {
                Log.e(TAG, "and the salvage failed too: " + io);
            }
            mIsRecording = false;
            throw e;
        }
    }

    private void initializeFile() throws IOException {
        if (VERBOSE) Log.d(TAG, String.format("Initialize on %s thread", Thread.currentThread()));
        // Set timestamp
        VideoCaptureData.Builder dataBuilder = VideoCaptureData.newBuilder();
        long millis = System.currentTimeMillis();
        Timestamp timestamp = Timestamp.newBuilder().setSeconds(millis / 1000)
                .setNanos((int) ((millis % 1000) * 1000000)).build();
        dataBuilder.setTime(timestamp);

        //Write to file
        dataBuilder.build().writeTo(mFileStream);
    }

    private void writeMessage(MessageWrapper msg) throws IOException {
        if (VERBOSE) Log.d(TAG, String.format("Queuing message on %s thread", Thread.currentThread()));

        MessageWrapper.MsgCase msgCase = msg.getMsgCase();
        switch (msgCase) {
            case FRAME_META:
                if (VERBOSE) Log.d(TAG,"Got Frame Meta");
                mFrames.offerMeta(msg.getFrameMeta(), mFrameSink);
                break;
            case FRAME_TIME:
                if (VERBOSE) Log.d(TAG,"Got Frame Time");
                mFrames.offerTime(msg.getFrameTime(), mFrameSink);
                break;
            case IMU_DATA:
                if (VERBOSE) Log.d(TAG,"Got IMU data");
                VideoCaptureData.newBuilder().addImu(msg.getImuData())
                        .build().writeTo(mFileStream);
                break;
            case IMU_META:
                if (VERBOSE) Log.d(TAG,"Got IMU Info");
                VideoCaptureData.newBuilder().mergeImuMeta(msg.getImuMeta())
                        .build().writeTo(mFileStream);
                break;
            case CAMERA_META:
                if (VERBOSE) Log.d(TAG,"Got Camera Meta");
                VideoCaptureData.newBuilder().mergeCameraMeta(msg.getCameraMeta())
                        .build().writeTo(mFileStream);
                break;
            case ENVIRONMENT_DATA:
                if (VERBOSE) Log.d(TAG,"Got Environment data");
                VideoCaptureData.newBuilder().addEnvironment(msg.getEnvironmentData())
                        .build().writeTo(mFileStream);
                break;
            case STEP_DATA:
                if (VERBOSE) Log.d(TAG,"Got Step data");
                VideoCaptureData.newBuilder().addSteps(msg.getStepData())
                        .build().writeTo(mFileStream);
                break;
            case ORIENTATION_DATA:
                if (VERBOSE) Log.d(TAG,"Got Orientation data");
                VideoCaptureData.newBuilder().addOrientation(msg.getOrientationData())
                        .build().writeTo(mFileStream);
                break;
            case GNSS_DATA:
                if (VERBOSE) Log.d(TAG,"Got GNSS data");
                VideoCaptureData.newBuilder().addGnss(msg.getGnssData())
                        .build().writeTo(mFileStream);
                break;
            case STILL_DATA:
                if (VERBOSE) Log.d(TAG,"Got Still meta");
                VideoCaptureData.newBuilder().addStills(msg.getStillData())
                        .build().writeTo(mFileStream);
                break;
            case THERMAL_DATA:
                if (VERBOSE) Log.d(TAG,"Got Thermal data");
                VideoCaptureData.newBuilder().addThermal(msg.getThermalData())
                        .build().writeTo(mFileStream);
                break;
            case LIGHT_DATA:
                if (VERBOSE) Log.d(TAG,"Got Light data");
                VideoCaptureData.newBuilder().addLight(msg.getLightData())
                        .build().writeTo(mFileStream);
                break;
            case GNSS_MEASUREMENT_DATA:
                if (VERBOSE) Log.d(TAG,"Got GNSS measurement data");
                VideoCaptureData.newBuilder().addGnssMeasurement(msg.getGnssMeasurementData())
                        .build().writeTo(mFileStream);
                break;
            case GNSS_STATUS_DATA:
                if (VERBOSE) Log.d(TAG,"Got GNSS status data");
                VideoCaptureData.newBuilder().addGnssStatus(msg.getGnssStatusData())
                        .build().writeTo(mFileStream);
                break;
            case GNSS_NAVIGATION_DATA:
                if (VERBOSE) Log.d(TAG,"Got GNSS navigation message");
                VideoCaptureData.newBuilder().addGnssNavigation(msg.getGnssNavigationData())
                        .build().writeTo(mFileStream);
                break;
            case GNSS_ANTENNA_DATA:
                if (VERBOSE) Log.d(TAG,"Got GNSS antenna info");
                VideoCaptureData.newBuilder().addGnssAntenna(msg.getGnssAntennaData())
                        .build().writeTo(mFileStream);
                break;
        }
    }

    /**
     * A dropped frame record is a hole in the file, so say so. Loudly on the first one —
     * that is the moment the two streams came apart and the reason is still in the log
     * above it — then every 100th, so a sustained mismatch does not bury the log.
     */

    private void queueData(MessageWrapper msg) {
        if (!isRecording()) {
            return;
        }
        try {
            mQueue.put(msg);
        } catch (InterruptedException e) {
            throw new RuntimeException("Could not queue data: " + msg + "due to" + e);
        }
    }
    public void queueData(VideoFrameMetaData msg) {
        queueData(MessageWrapper.newBuilder().setFrameMeta(msg).build());
    }
    public void queueData(VideoFrameToTimestamp msg) {
        queueData(MessageWrapper.newBuilder().setFrameTime(msg).build());
    }
    public void queueData(IMUData msg) {
        queueData(MessageWrapper.newBuilder().setImuData(msg).build());
    }
    public void queueData(IMUInfo msg) {
        queueData(MessageWrapper.newBuilder().setImuMeta(msg).build());
    }
    public void queueData(CameraInfo msg) {
        queueData(MessageWrapper.newBuilder().setCameraMeta(msg).build());
    }
    public void queueData(EnvironmentData msg) {
        queueData(MessageWrapper.newBuilder().setEnvironmentData(msg).build());
    }
    public void queueData(StepData msg) {
        queueData(MessageWrapper.newBuilder().setStepData(msg).build());
    }
    public void queueData(OrientationData msg) {
        queueData(MessageWrapper.newBuilder().setOrientationData(msg).build());
    }
    public void queueData(GnssData msg) {
        queueData(MessageWrapper.newBuilder().setGnssData(msg).build());
    }
    public void queueData(StillMetaData msg) {
        queueData(MessageWrapper.newBuilder().setStillData(msg).build());
    }
    public void queueData(ThermalData msg) {
        queueData(MessageWrapper.newBuilder().setThermalData(msg).build());
    }
    public void queueData(LightData msg) {
        queueData(MessageWrapper.newBuilder().setLightData(msg).build());
    }
    public void queueData(GnssMeasurementData msg) {
        queueData(MessageWrapper.newBuilder().setGnssMeasurementData(msg).build());
    }
    public void queueData(GnssStatusData msg) {
        queueData(MessageWrapper.newBuilder().setGnssStatusData(msg).build());
    }
    public void queueData(GnssNavigationMessageData msg) {
        queueData(MessageWrapper.newBuilder().setGnssNavigationData(msg).build());
    }
    public void queueData(GnssAntennaInfoData msg) {
        queueData(MessageWrapper.newBuilder().setGnssAntennaData(msg).build());
    }

}
