package se.lth.math.videoimucapture;

import android.util.Log;

import com.google.protobuf.Timestamp;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import se.lth.math.videoimucapture.RecordingProtos.VideoCaptureData;
import se.lth.math.videoimucapture.RecordingProtos.VideoFrameMetaData;
import se.lth.math.videoimucapture.RecordingProtos.VideoFrameToTimestamp;
import se.lth.math.videoimucapture.RecordingProtos.IMUData;
import se.lth.math.videoimucapture.RecordingProtos.IMUInfo;
import se.lth.math.videoimucapture.RecordingProtos.CameraInfo;
import se.lth.math.videoimucapture.RecordingProtos.EnvironmentData;
import se.lth.math.videoimucapture.RecordingProtos.StepData;
import se.lth.math.videoimucapture.RecordingProtos.OrientationData;
import se.lth.math.videoimucapture.RecordingProtos.GnssData;
import se.lth.math.videoimucapture.RecordingProtos.StillMetaData;
import se.lth.math.videoimucapture.RecordingProtos.ThermalData;
import se.lth.math.videoimucapture.RecordingProtos.LightData;
import se.lth.math.videoimucapture.RecordingProtos.GnssMeasurementData;
import se.lth.math.videoimucapture.RecordingProtos.GnssStatusData;
import se.lth.math.videoimucapture.RecordingProtos.GnssNavigationMessageData;
import se.lth.math.videoimucapture.RecordingProtos.GnssAntennaInfoData;
import se.lth.math.videoimucapture.RecordingProtos.MessageWrapper;

import static java.lang.Math.abs;

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

    // Queues to handle merging of video frames. tryVideoDataMerge() can only drain these in
    // PAIRS, so if one side is produced faster than the other for any sustained period the
    // faster queue fills and never empties. That is not hypothetical: a camera running at a
    // higher rate than the encoder does it in seconds, and so does an encoder stalling under
    // thermal load on a long capture. add() throws IllegalStateException when full, which on
    // this thread is fatal to the whole app and takes the clip with it, so these are filled
    // through offerDropOldest() instead: an unmatchable frame is dropped, and counted.
    private Queue<VideoFrameMetaData> mFrameDataQueue = new ArrayBlockingQueue<>(100);
    private Queue<VideoFrameToTimestamp> mFrameTimeQueue = new ArrayBlockingQueue<>(100);
    private int mFrameMetaDropped = 0;
    private int mFrameTimeDropped = 0;

    //Other state variables
    private Boolean mIsRecording = false;

    public Boolean isRecording() {return mIsRecording;}

    public void startRecording(String resultFile) throws IOException {

        Log.d(TAG, String.format("Starting on %s thread", Thread.currentThread()));
        mFileStream = new FileOutputStream(resultFile);

        //Reset state
        mIsRecording = true;
        mFrameDataQueue.clear();
        mFrameTimeQueue.clear();
        mQueue.clear();

        //Start background thread
        Thread myThread = new Thread(this, "RecordingWriter");
        myThread.start();

    }

    public void stopRecording(){
        try {
            mQueue.put(mPoisonPill);
        } catch (InterruptedException e) {
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
            //TODO:SOMETHING USEFUL
            Log.e(TAG,"Write error, SHOULD stop recording!!!!!" + e);
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
                if (!mFrameDataQueue.offer(msg.getFrameMeta())) {
                    mFrameDataQueue.poll();                     // oldest will never find a partner
                    mFrameDataQueue.offer(msg.getFrameMeta());
                    countDrop(true);
                }
                tryVideoDataMerge();
                break;
            case FRAME_TIME:
                if (VERBOSE) Log.d(TAG,"Got Frame Time");
                if (!mFrameTimeQueue.offer(msg.getFrameTime())) {
                    mFrameTimeQueue.poll();
                    mFrameTimeQueue.offer(msg.getFrameTime());
                    countDrop(false);
                }
                tryVideoDataMerge();
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
    private void countDrop(boolean meta) {
        int n = meta ? ++mFrameMetaDropped : ++mFrameTimeDropped;
        if (n == 1 || n % 100 == 0) {
            Log.w(TAG, String.format(
                    "frame %s queue full, dropped oldest (%d so far; meta=%d time=%d queued). "
                            + "The camera and the encoder are running at different rates.",
                    meta ? "meta" : "time", n, mFrameDataQueue.size(), mFrameTimeQueue.size()));
        }
    }

    private void tryVideoDataMerge() throws IOException {
        if (VERBOSE)  Log.d(TAG, String.format("Trying to merge, Queue lengths: %d,%d", mFrameDataQueue.size(), mFrameTimeQueue.size()));

        // Peek at oldest frame time message
        VideoFrameToTimestamp frameTimeMsg = mFrameTimeQueue.peek();
        VideoFrameMetaData frameMetaMsg = mFrameDataQueue.peek();

        //Try to find frames to match
        while ((frameTimeMsg != null) && (frameMetaMsg != null)) {
            long timeDiffNs = (1000*frameTimeMsg.getTimeUs() - frameMetaMsg.getTimeNs());
            if (VERBOSE) Log.d(TAG, String.format("Time diff: %d ns", timeDiffNs));

            if (abs(timeDiffNs) <= 10000) {
                // They are from the same capture frame
                VideoFrameMetaData.Builder frameBuilder = VideoFrameMetaData.newBuilder().mergeFrom(frameMetaMsg)
                        .setFrameNumber(frameTimeMsg.getFrameNbr());
                VideoCaptureData.newBuilder().addVideoMeta(frameBuilder).build().writeTo(mFileStream);
                // Remove frames from queue
                mFrameTimeQueue.poll();
                mFrameDataQueue.poll();
                //We are done
                break;
            } else if (timeDiffNs > 0) {
                //Meta message is too old, try another one
                mFrameDataQueue.poll(); // throw old
                frameMetaMsg = mFrameDataQueue.peek();
                Log.d(TAG, "Diff too large, skipping frame meta data");
            } else {
                // Frame Time message too old, try another one
                mFrameTimeQueue.poll(); // throw old
                frameTimeMsg = mFrameTimeQueue.peek();
                Log.d(TAG, "Diff too large, skipping frame time data");
            }
        }

    }

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
