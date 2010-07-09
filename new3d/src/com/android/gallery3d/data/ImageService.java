// Copyright 2010 Google Inc. All Rights Reserved.

package com.android.gallery3d.data;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import java.util.HashMap;
import java.util.PriorityQueue;

public class ImageService {

    private static final String TAG = "ImageService";

    private static final int DECODE_TIMEOUT = 0;

    private static final int INITIAL_TIMEOUT = 2000;
    private static final int MAXIMAL_TIMEOUT = 32000;

    private static ImageService mInstance;

    private final HashMap<Integer, DecodeTask> mMap =
            new HashMap<Integer, DecodeTask>();
    private final PriorityQueue<DecodeTask> mQueue = new PriorityQueue<DecodeTask>();
    private final Handler mHandler;
    private final Context mContext;

    private boolean mActive = true;
    private int mTimeSerial;

    private DecodeTask mCurrentTask;
    private final DecodeThread mDecodeThread = new DecodeThread();

    private ImageService(Context context) {
        mContext = context;

        mHandler = new Handler() {
            @Override
            public void handleMessage(Message m) {
                if (m.what != DECODE_TIMEOUT) return;
                DecodeTask task = mCurrentTask;
                if (task != null) {
                    task.mItem.cancelImageGeneration(
                            mContext.getContentResolver(), task.mType);
                }
            }
        };

        mDecodeThread.start();
    }

    public static void initialize(Context context) {
        mInstance = new ImageService(context);
    }

    public static synchronized ImageService getInstance() {
        if (mInstance == null) throw new IllegalStateException();
        return mInstance;
    }

    protected int requestImage(AbstractMediaItem item, int type) {
        DecodeTask task = new DecodeTask();
        task.mRequestId = ++mTimeSerial;
        task.mItem = item;
        task.mType = type;
        task.mTimeout = INITIAL_TIMEOUT;

        synchronized (mQueue) {
            mMap.put(task.mRequestId, task);
            mQueue.add(task);
            if (mQueue.size() == 1) mQueue.notifyAll();
        }
        return task.mRequestId;
    }

    protected void cancelRequest(int requestId) {
        synchronized (mQueue) {
            DecodeTask task = mMap.remove(requestId);
            if (task == null) return;
            task.mCanceled = true;
            if (mQueue.remove(task)) {
                task.mItem.onImageCanceled(task.mType);
            } else {
                task.mItem.cancelImageGeneration(
                        mContext.getContentResolver(), task.mType);
            }
        }
    }

    public void close() {
        synchronized (mQueue) {
            mActive = false;
            mQueue.notifyAll();
        }
    }

    protected DecodeTask nextDecodeTask() {
        PriorityQueue<DecodeTask> queue = mQueue;
        synchronized (queue) {
            try {
                while (queue.isEmpty() && mActive) {
                    queue.wait();
                }
            } catch (InterruptedException e) {
                Log.v(TAG, "decode-thread is interrupted");
                Thread.currentThread().interrupt();
                return null;
            }
            return !mActive ? null : queue.remove() ;
        }
    }

    private class DecodeThread extends Thread {
        @Override
        public void run() {
            PriorityQueue<DecodeTask> queue = mQueue;
            ContentResolver resolver = mContext.getContentResolver();

            while (true) {
                DecodeTask task = nextDecodeTask();
                if (task == null) break;
                AbstractMediaItem item = task.mItem;
                try {
                    mCurrentTask = task;
                    mHandler.sendEmptyMessageDelayed(
                            DECODE_TIMEOUT, task.mTimeout);
                    Bitmap bitmap = task.mCanceled
                            ? null
                            : item.generateImage(resolver, task.mType);
                    mHandler.removeMessages(DECODE_TIMEOUT);
                    mCurrentTask = null;
                    if (bitmap != null) {
                        task.mItem.onImageReady(task.mType, bitmap);
                        synchronized (mQueue) {
                            mMap.remove(task.mRequestId);
                        }
                    } else if (task.mCanceled) {
                        task.mItem.onImageCanceled(task.mType);
                    } else {
                        // Try to decode the image again by increasing the
                        // timeout by a factor of 2 unless MAXIMAL_TIMEOUT
                        // is reached.
                        task.mTimeout <<= 1;
                        if (task.mTimeout > MAXIMAL_TIMEOUT) {
                            throw new RuntimeException("decode timeout");
                        } else {
                            synchronized (mQueue) {
                                mQueue.add(task);
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "decode error", e);
                    task.mItem.onImageError(task.mType, e);
                    synchronized (mQueue) {
                        mMap.remove(task.mRequestId);
                    }
                }
            }
            synchronized (mQueue) {
                for (DecodeTask task : mQueue) {
                    task.mItem.onImageCanceled(task.mType);
                }
                mQueue.clear();
                mMap.clear();
            }
        }
    }

    private static class DecodeTask implements Comparable<DecodeTask> {
        int mRequestId;
        int mTimeout;
        int mType;
        volatile boolean mCanceled;
        AbstractMediaItem mItem;

        public int compareTo(DecodeTask task) {
            return mTimeout != task.mTimeout
                    ? mTimeout - task.mTimeout
                    : mRequestId - task.mRequestId;
        }
    }
}