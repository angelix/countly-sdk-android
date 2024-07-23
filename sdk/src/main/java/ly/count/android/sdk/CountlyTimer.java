package ly.count.android.sdk;

import androidx.annotation.NonNull;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

class CountlyTimer {

    private ScheduledExecutorService timerService;
    protected static int TIMER_DELAY_MS = 0; // for testing purposes

    protected void stopTimer(@NonNull ModuleLog L) {
        L.i("[CountlyTimer] stopTimer, Stopping global timer");
        if (timerService != null) {
            try {
                timerService.shutdown();
                if (!timerService.awaitTermination(1, TimeUnit.SECONDS)) {
                    timerService.shutdownNow();
                    if (!timerService.awaitTermination(1, TimeUnit.SECONDS)) {
                        L.e("[CountlyTimer] Global timer must be locked");
                    }
                }
            } catch (Exception ignored) {
                L.e("[CountlyTimer] Error while stopping global timer " + t);
            }
        }
    }

    protected void startTimer(long timerDelay, @NonNull Runnable runnable, @NonNull ModuleLog L) {
        L.i("[CountlyTimer] startTimer, Starting global timer timerDelay: [" + timerDelay + "]");
        long timerDelayInternal = timerDelay * 1000;

        if (timerDelayInternal < 1000) {
            timerDelayInternal = 1000;
        }

        long startTime = timerDelayInternal;

        if (TIMER_DELAY_MS > 0) {
            timerDelayInternal = TIMER_DELAY_MS;
            startTime = 0;
        }

        if (timerService == null) {
            timerService = Executors.newSingleThreadScheduledExecutor();
        }

        timerService.scheduleWithFixedDelay(runnable, startTime, timerDelayInternal, TimeUnit.MILLISECONDS);
    }

    protected void purgeTimer(ModuleLog L) {
        L.d("[CountlyTimer] purgeTimer, stopping the times and nulling the service");
        stopTimer(L);
        timerService = null;
    }
}
