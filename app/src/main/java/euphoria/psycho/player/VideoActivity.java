package euphoria.psycho.player;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Point;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnBufferingUpdateListener;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnErrorListener;
import android.media.MediaPlayer.OnInfoListener;
import android.media.MediaPlayer.OnPreparedListener;
import android.media.MediaPlayer.OnSeekCompleteListener;
import android.media.MediaPlayer.OnTimedMetaDataAvailableListener;
import android.media.MediaPlayer.OnTimedTextListener;
import android.media.MediaPlayer.OnVideoSizeChangedListener;
import android.media.TimedMetaData;
import android.media.TimedText;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Formatter;
import java.util.HashMap;

import euphoria.psycho.explorer.R;
import euphoria.psycho.share.DateTimeShare;
import euphoria.psycho.share.KeyShare;
import euphoria.psycho.share.Logger;
import euphoria.psycho.share.WebViewShare;
import euphoria.psycho.videos.Iqiyi;

import static euphoria.psycho.player.PlayerHelper.getNavigationBarHeight;
import static euphoria.psycho.player.PlayerHelper.getNavigationBarSize;
import static euphoria.psycho.player.PlayerHelper.getVideos;
import static euphoria.psycho.player.PlayerHelper.hideSystemUI;
import static euphoria.psycho.player.PlayerHelper.lookupCurrentVideo;
import static euphoria.psycho.player.PlayerHelper.openDeleteVideoDialog;
import static euphoria.psycho.player.PlayerHelper.rotateScreen;
import static euphoria.psycho.player.PlayerHelper.showSystemUI;
import static euphoria.psycho.player.PlayerHelper.switchPlayState;
import static euphoria.psycho.videos.VideosHelper.USER_AGENT;

// https://github.com/google/ExoPlayer
public class VideoActivity extends BaseVideoActivity implements
        GestureDetector.OnGestureListener,
        TimeBar.OnScrubListener,
        OnPreparedListener,
        OnCompletionListener,
        OnBufferingUpdateListener,
        OnSeekCompleteListener,
        OnVideoSizeChangedListener,
        OnTimedTextListener,
        OnTimedMetaDataAvailableListener,
        OnErrorListener,
        OnInfoListener,
        Iqiyi.Callback {
    public static final long DEFAULT_SHOW_TIMEOUT_MS = 5000L;
    public static final String EXTRA_PLAYLSIT = "extra.PLAYLSIT";
    public static final String EXTRA_HEADER = "extra.HEADER";
    private final Handler mHandler = new Handler();
    private final StringBuilder mStringBuilder = new StringBuilder();
    private final Formatter mFormatter = new Formatter(mStringBuilder);
    private final Runnable mProgressChecker = new Runnable() {
        @Override
        public void run() {
            int pos = setProgress();
            mHandler.postDelayed(mProgressChecker, 1000 - (pos % 1000));
        }
    };
    private final Runnable mHideAction = this::hide;
    private Bookmarker mBookmarker;
    private File[] mFiles = null;
    private int mNavigationBarHeight;
    private int mNavigationBarWidth;
    private boolean mScrubbing;
    private GestureDetector mVideoTouchHelper;
    private int mCurrentPlaybackIndex;

    private void downloadFile(DownloadManager manager, String url, String filename, String mimetype) {
        final DownloadManager.Request request;
        Uri uri = Uri.parse(url);
        request = new DownloadManager.Request(uri);
        request.setMimeType(mimetype);
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
        request.allowScanningByMediaScanner();
        request.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        manager.enqueue(request);
    }

    private void downloadVideo() {
        Uri videoUri = getIntent().getData();
        if (videoUri.toString().contains("m3u8")) {
            Intent intent = new Intent(VideoActivity.this, euphoria.psycho.tasks.VideoActivity.class);
            intent.setData(videoUri);
            VideoActivity.this.startActivity(intent);
        } else {
            WebViewShare.downloadFile(this, KeyShare.toHex(videoUri.toString().getBytes(StandardCharsets.UTF_8)), videoUri.toString(), USER_AGENT);
        }
    }

    private void executeTask(String[] videoUris) {
        DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        for (String uris : videoUris) {
            downloadFile(manager, uris, KeyShare.md5(uris) + ".f4v", "video/x-f4v");
        }
    }

    private void hide() {
        if (mController.getVisibility() == View.VISIBLE) {
            mController.setVisibility(View.GONE);
            mHandler.removeCallbacks(mHideAction);
            hideSystemUI(this, false);
            mHandler.removeCallbacks(mProgressChecker);
        }
    }

    private void hideController() {
        mHandler.postDelayed(mHideAction, DEFAULT_SHOW_TIMEOUT_MS);
    }

    private void initializePlayer() {
        hideController();
        if (!loadPlayList())
            return;
        mPlayer.setOnPreparedListener(this);
        mPlayer.setOnCompletionListener(this);
        Log.e("B5aOx2", String.format("initializePlayer, %s", ""));
        mPlayer.setOnErrorListener(this);
        mPlayer.setOnInfoListener(this);
        mPlayer.setOnBufferingUpdateListener(this);
    }


    private boolean loadPlayList() {
        if (getIntent().getData() == null) {
            return false;
        }
        String videoPath = getIntent().getData().getPath();
        mFiles = getVideos(videoPath);
        if (mFiles == null) {
            updateUI();
            if (getIntent().getIntExtra(EXTRA_HEADER, 0) == 1) {
                HashMap<String, String> headers = new HashMap<>();
                headers.put("Referer", "https://www.mgtv.com/");
                headers.put("User-Agent", "Mozilla/5.0 (Windows NT 6.1; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/94.0.4606.81 Safari/537.36");
                mPlayer.setVideoURI(getIntent().getData(), headers);
            } else {
                mPlayer.setVideoURI(getIntent().getData());
            }


        } else {
            mCurrentPlaybackIndex = lookupCurrentVideo(videoPath, mFiles);
            mPlayer.setVideoPath(mFiles[mCurrentPlaybackIndex].getAbsolutePath());
        }
        return true;
    }

    private void savePosition() {
        if (mFiles == null) {
            return;
        }
        String uri = mFiles[mCurrentPlaybackIndex].getAbsolutePath();
        if (mPlayer == null) return;
        if (mPlayer.getDuration() - mPlayer.getCurrentPosition() > 60 * 1000)
            mBookmarker.setBookmark(uri, (int) mPlayer.getCurrentPosition());
    }

    private void seekToLastedState() {
        if (mFiles == null) return;
        String uri = mFiles[mCurrentPlaybackIndex].getAbsolutePath();
        long bookmark = mBookmarker.getBookmark(uri);
        if (bookmark > 0) {
            mPlayer.seekTo((int) bookmark);
        }
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(Utils.getFileName(uri));
        }
    }

    private int setProgress() {
        int position = mPlayer.getCurrentPosition();
        mExoDuration.setText(DateTimeShare.getStringForTime(mStringBuilder, mFormatter, mPlayer.getDuration()));
        mExoPosition.setText(DateTimeShare.getStringForTime(mStringBuilder, mFormatter, position));
        mProgress.setPosition(position);
        return position;
    }

    private void setupView() {
        mRootView.setOnTouchListener((v, event) -> {
            mVideoTouchHelper.onTouchEvent(event);
            return true;
        });
        mProgress.addListener(this);
        mExoPlay.setOnClickListener(v -> {
            switchPlayState(mPlayer, mExoPlay);
        });
        mExoPrev.setOnClickListener(v -> {
            savePosition();
            mCurrentPlaybackIndex = playPreviousVideo(mCurrentPlaybackIndex, mPlayer);
        });
        mExoNext.setOnClickListener(v -> {
            savePosition();
            mCurrentPlaybackIndex = playNextVideo(mCurrentPlaybackIndex, mPlayer);
        });
        mExoRew.setOnClickListener(v -> {
            rotateScreen(this);
        });
        if (mPlayer.isPlaying()) {
            mPlayer.pause();
        }
        mExoDelete.setOnClickListener(v -> {
            openDeleteVideoDialog(this, unused -> {
                if (mFiles.length > 1) {
                    int nextPlaybackIndex = mCurrentPlaybackIndex + 1;
                    if (mCurrentPlaybackIndex >= mFiles.length) {
                        nextPlaybackIndex = 0;
                    }
                    String nextVideoPath = mFiles[nextPlaybackIndex].getAbsolutePath();
                    if (!mFiles[mCurrentPlaybackIndex].delete()) {
                        throw new IllegalStateException();
                    }
                    mFiles = getVideos(nextVideoPath);
                    mCurrentPlaybackIndex = lookupCurrentVideo(nextVideoPath, mFiles);
                    mPlayer.setVideoPath(nextVideoPath);
                } else {
                    if (!mFiles[mCurrentPlaybackIndex].delete()) {
                        throw new IllegalStateException();
                    }
                }
                return null;
            });
        });
        mFileDownload.setOnClickListener(v -> {
            this.downloadVideo();
        });
    }

    private void show() {
        if (mController.getVisibility() == View.VISIBLE) return;
        mController.setVisibility(View.VISIBLE);
        showSystemUI(this, true);
        if (mIsHasBar) {
            PlayerHelper.adjustController(this, mController, mNavigationBarHeight, mNavigationBarWidth);
        }
        mHandler.post(mProgressChecker);
        hideController();
    }

    private void updateUI() {
        if (mFiles == null) {
            mExoNext.setVisibility(View.GONE);
            mExoPrev.setVisibility(View.GONE);
            mExoDelete.setVisibility(View.GONE);
        } else {
            mFileDownload.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mPlayer.stopPlayback();
    }

    @Override
    protected void onPause() {
        savePosition();
        mPlayer.suspend();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mPlayer.resume();
    }

    @Override
    protected void onStart() {
        super.onStart();
        initializePlayer();
    }

    @Override
    protected void onStop() {
        super.onStop();

    }

    @Override
    void initialize() {
        super.initialize();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Point point = getNavigationBarSize(this);
        mNavigationBarHeight = getNavigationBarHeight(this);
        mNavigationBarWidth = point.x;
        mBookmarker = new Bookmarker(this);
        setupView();
        mVideoTouchHelper = new GestureDetector(this, this);
        mVideoTouchHelper.setContextClickListener(e -> false);
    }

    @Override
    public void onBufferingUpdate(MediaPlayer mp, int percent) {
        mProgress.setBufferedPosition((long) (mPlayer.getDuration() * (1f * percent / 100)));
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        mExoPlay.setImageResource(R.drawable.exo_controls_play);
        if (mFiles != null) {
            mCurrentPlaybackIndex = playNextVideo(mCurrentPlaybackIndex, mPlayer);
        } else {
            mExoPlay.setImageResource(R.drawable.exo_controls_play);
        }
    }

    @Override
    public boolean onDown(MotionEvent e) {
        return false;
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        Logger.e(String.format("onError, %s %s", what, extra));
        return false;
    }

    @Override
    public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
        return false;
    }

    @Override
    public boolean onInfo(MediaPlayer mp, int what, int extra) {
        return false;
    }

    @Override
    public void onLongPress(MotionEvent e) {
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        Logger.e(String.format("onPrepared, %s", ""));
        mProgress.setDuration(mp.getDuration());
        mHandler.post(mProgressChecker);
        mPlayer.start();
        seekToLastedState();
        mExoPlay.setImageResource(R.drawable.exo_controls_pause);
    }

    @Override
    public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
        int delta = (int) distanceX * -100;
        //if (Math.abs(delta) < 1000) return true;
        delta = (delta / 1000) * 1000;
        if (delta == 0) {
            if (distanceX > 0) {
                delta = -1000;
            } else {
                delta = 1000;
            }
        }
        int positionMs = delta + mPlayer.getCurrentPosition();
        if (positionMs > 0) {
            mPlayer.seekTo(positionMs);
        }
        return true;
    }

    @Override
    public void onScrubMove(TimeBar timeBar, long position) {
        mExoPosition.setText(DateTimeShare.getStringForTime(mStringBuilder, mFormatter, position));
    }

    @Override
    public void onScrubStart(TimeBar timeBar, long position) {
        mHandler.removeCallbacks(mHideAction);
        mHandler.removeCallbacks(mProgressChecker);
        mScrubbing = true;
    }

    @Override
    public void onScrubStop(TimeBar timeBar, long position, boolean canceled) {
        mScrubbing = false;
        mPlayer.seekTo((int) position);
        mHandler.post(mProgressChecker);
        hideController();
    }

    @Override
    public void onSeekComplete(MediaPlayer mp) {
    }

    @Override
    public void onShowPress(MotionEvent e) {
    }

    @Override
    public boolean onSingleTapUp(MotionEvent e) {
        show();
        return true;
    }

    @Override
    public void onTimedMetaDataAvailable(MediaPlayer mp, TimedMetaData data) {
    }

    @Override
    public void onTimedText(MediaPlayer mp, TimedText text) {
    }

    @Override
    public void onVideoSizeChanged(MediaPlayer mp, int width, int height) {
    }

    @Override
    public void onVideoUri(String uri) {
        runOnUiThread(() -> mPlayer.setVideoPath(uri));
    }

    int playNextVideo(int currentPlaybackIndex, TextureVideoView textureVideoView) {
        if (mFiles != null) {
            int nextPlaybackIndex = mFiles.length > currentPlaybackIndex + 1 ? currentPlaybackIndex + 1 : 0;
            textureVideoView.setVideoPath(mFiles[nextPlaybackIndex].getAbsolutePath());
            return nextPlaybackIndex;
        }
        return 0;
    }

    int playPreviousVideo(int currentPlaybackIndex, TextureVideoView textureVideoView) {
        if (mFiles != null) {
            int nextPlaybackIndex = currentPlaybackIndex - 1 > -1 ? currentPlaybackIndex - 1 : 0;
            textureVideoView.setVideoPath(mFiles[nextPlaybackIndex].getAbsolutePath());
            return nextPlaybackIndex;
        }
        return 0;
    }

}