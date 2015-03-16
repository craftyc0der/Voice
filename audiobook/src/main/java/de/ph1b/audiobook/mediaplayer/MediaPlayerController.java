package de.ph1b.audiobook.mediaplayer;

import android.content.Context;
import android.net.Uri;

import com.google.android.exoplayer.ExoPlaybackException;
import com.google.android.exoplayer.ExoPlayer;
import com.google.android.exoplayer.MediaCodecAudioTrackRenderer;
import com.google.android.exoplayer.TrackRenderer;
import com.google.android.exoplayer.source.DefaultSampleSource;
import com.google.android.exoplayer.source.FrameworkSampleExtractor;
import com.google.android.exoplayer.source.SampleExtractor;

import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import de.ph1b.audiobook.model.Book;
import de.ph1b.audiobook.model.DataBaseHelper;
import de.ph1b.audiobook.utils.ArgumentValidator;
import de.ph1b.audiobook.utils.BaseApplication;
import de.ph1b.audiobook.utils.L;
import de.ph1b.audiobook.utils.PrefsManager;

public class MediaPlayerController implements ExoPlayer.Listener {

    private static final String TAG = MediaPlayerController.class.getSimpleName();
    private final Context c;
    private final ReentrantLock lock = new ReentrantLock();
    private final PrefsManager prefs;
    private final DataBaseHelper db;
    private final BaseApplication baseApplication;
    private final Book book;
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
    private final ExoPlayer player;
    private volatile State state;
    private ScheduledFuture<?> sleepSand;
    private volatile boolean stopAfterCurrentTrack = false;
    private ScheduledFuture updater = null;

    public MediaPlayerController(BaseApplication baseApplication, Book book) {
        L.e(TAG, "constructor called with book=" + book);
        ArgumentValidator.validate(baseApplication, book);
        this.c = baseApplication.getApplicationContext();
        this.book = book;
        prefs = new PrefsManager(c);
        db = DataBaseHelper.getInstance(c);
        this.baseApplication = baseApplication;

        player = ExoPlayer.Factory.newInstance(1);
        player.addListener(this);

        prepare();
    }

    private void prepare() {
        File rootFile = new File(book.getRoot() + "/" + book.getCurrentChapter().getPath());
        SampleExtractor sampleExtractor = new FrameworkSampleExtractor(c, Uri.fromFile(rootFile), null);
        DefaultSampleSource sampleSource = new DefaultSampleSource(sampleExtractor, 1);
        TrackRenderer audioRenderer = new MediaCodecAudioTrackRenderer(sampleSource);

        player.stop();
        player.seekTo(book.getTime());
        player.prepare(audioRenderer);

        state = State.PREPARED;
    }


    public void pause() {
        lock.lock();
        try {
            switch (state) {
                case STARTED:
                    player.setPlayWhenReady(false);
                    stopUpdating();
                    baseApplication.setPlayState(BaseApplication.PlayState.PAUSED);
                    state = State.PAUSED;
                    break;
                default:
                    L.e(TAG, "pause called in illegal state=" + state);
                    break;
            }
        } finally {
            lock.unlock();
        }
    }


    public void play() {
        lock.lock();
        try {
            switch (state) {
                case PLAYBACK_COMPLETED:
                    player.seekTo(0);
                case PREPARED:
                case PAUSED:
                    player.setPlayWhenReady(true);
                    startUpdating();
                    baseApplication.setPlayState(BaseApplication.PlayState.PLAYING);
                    state = State.STARTED;
                    break;
                case DEAD:
                case IDLE:
                    prepare();
                    play();
                    break;
                default:
                    L.e(TAG, "play called in illegal state:" + state);
                    break;
            }
        } finally {
            lock.unlock();
        }
    }

    private void startUpdating() {
        if (!updaterActive()) {
            updater = executor.scheduleAtFixedRate(new Runnable() {
                @Override
                public void run() {
                    lock.lock();
                    try {
                        if (player.getCurrentPosition() != ExoPlayer.UNKNOWN_TIME) {
                            book.setPosition((int) player.getCurrentPosition(), book.getRelativeMediaPath());
                            db.updateBook(book);
                            baseApplication.notifyPositionChanged();
                        }
                    } finally {
                        lock.unlock();
                    }
                }
            }, 0, 1, TimeUnit.SECONDS);
        }
    }

    private boolean updaterActive() {
        return updater != null && !updater.isCancelled() && !updater.isDone();
    }

    public void skip(Direction direction) {
        lock.lock();
        try {
            int delta = prefs.getSeekTime() * 1000;
            if (direction == Direction.BACKWARD) {
                changePosition(book.getTime() - delta, book.getRelativeMediaPath());
            } else {
                changePosition(book.getTime() + delta, book.getRelativeMediaPath());
            }
        } finally {
            lock.unlock();
        }
    }

    public void changePosition(int time, String relPath) {
        L.v(TAG, "changePosition(" + time + "/" + relPath + ")");
        lock.lock();
        try {
            boolean changeFile = (!book.getCurrentChapter().getPath().equals(relPath));
            if (changeFile) {
                boolean wasPlaying = (state == State.STARTED);
                book.setPosition(time, relPath);
                db.updateBook(book);
                baseApplication.notifyPositionChanged();
                prepare();
                if (wasPlaying) {
                    player.setPlayWhenReady(true);
                    state = State.STARTED;
                    baseApplication.setPlayState(BaseApplication.PlayState.PLAYING);
                } else {
                    state = State.PREPARED;
                    baseApplication.setPlayState(BaseApplication.PlayState.PAUSED);
                }
                baseApplication.notifyPositionChanged();
            } else {
                switch (state) {
                    case PREPARED:
                    case STARTED:
                    case PAUSED:
                    case PLAYBACK_COMPLETED:
                        player.seekTo(time);
                        book.setPosition(time, book.getCurrentChapter().getPath());
                        db.updateBook(book);
                        baseApplication.notifyPositionChanged();
                        break;
                    default:
                        L.e(TAG, "changePosition called in illegal state:" + state);
                        break;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public void setPlaybackSpeed(float speed) {
        lock.lock();
        try {
            book.setPlaybackSpeed(speed);
            db.updateBook(book);
            if (state != State.DEAD) {
                //mediaPlayer.setPlaybackSpeed(speed); TODO: IMPLEMENT
            } else {
                L.e(TAG, "setPlaybackSpeed called in illegal state: " + state);
            }
        } finally {
            lock.unlock();
        }
    }

    private boolean sleepSandActive() {
        return sleepSand != null && !sleepSand.isCancelled() && !sleepSand.isDone();
    }

    public void toggleSleepSand() {
        L.i(TAG, "toggleSleepSand. Old state was:" + sleepSandActive());
        lock.lock();
        try {
            if (sleepSandActive()) {
                L.i(TAG, "sleepsand is active. cancelling now");
                sleepSand.cancel(false);
                stopAfterCurrentTrack = true;
                baseApplication.setSleepTimerActive(false);
            } else {
                L.i(TAG, "preparing new sleepsand");
                int minutes = prefs.getSleepTime();
                stopAfterCurrentTrack = prefs.stopAfterCurrentTrack();
                baseApplication.setSleepTimerActive(true);
                sleepSand = executor.schedule(new Runnable() {
                    @Override
                    public void run() {
                        if (!stopAfterCurrentTrack) {
                            lock.lock();
                            try {
                                release();
                            } finally {
                                lock.unlock();
                            }
                        } else {
                            L.d(TAG, "Sandman: We are not stopping right now. We stop after this track.");
                        }
                    }
                }, minutes, TimeUnit.MINUTES);
            }
        } finally {
            lock.unlock();
        }
    }

    public void next() {
        lock.lock();
        try {
            String nextChapter = book.getNextChapter().getPath();
            if (nextChapter != null) {
                changePosition(0, nextChapter);
            }
        } finally {
            lock.unlock();
        }
    }

    public void previous() {
        lock.lock();
        try {
            if (player.getCurrentPosition() > 2000 || book.getChapters().indexOf(book.getCurrentChapter()) == 0) {
                player.seekTo(0);
                book.setPosition(0, book.getRelativeMediaPath());
                db.updateBook(book);
                baseApplication.notifyPositionChanged();
            } else {
                changePosition(0, book.getPreviousChapter().getPath());
            }
        } finally {
            lock.unlock();
        }
    }

    public Book getBook() {
        return book;
    }

    public void release() {
        L.i(TAG, "release called");
        lock.lock();
        try {
            stopUpdating();
            player.release();
            baseApplication.setPlayState(BaseApplication.PlayState.STOPPED);
            baseApplication.setSleepTimerActive(false);
            executor.shutdown();
            state = State.DEAD;
        } finally {
            lock.unlock();
        }
    }

    private void stopUpdating() {
        if (updaterActive()) {
            updater.cancel(true);
        }
    }

    private void onCompletion() {
        L.v(TAG, "onCompletion called, nextChapter=" + book.getNextChapter());
        if (book.getNextChapter() != null) {
            next();
        } else {
            L.v(TAG, "Reached last track. Stopping player");
            stopUpdating();
            baseApplication.setPlayState(BaseApplication.PlayState.STOPPED);
            state = State.PLAYBACK_COMPLETED;
        }
    }

    @Override
    public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
        if (playbackState == ExoPlayer.STATE_ENDED && playWhenReady) {
            onCompletion();
        }
    }

    @Override
    public void onPlayWhenReadyCommitted() {

    }

    @Override
    public void onPlayerError(ExoPlaybackException error) {

    }


    public enum Direction {
        FORWARD, BACKWARD
    }

    private enum State {
        PAUSED,
        DEAD,
        PREPARED,
        STARTED,
        PLAYBACK_COMPLETED,
        IDLE
    }
}
