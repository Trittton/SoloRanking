package dev.gatsyuk.soloranking

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.gatsyuk.soloranking.core.database.seed.DatabaseSeeder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class SoloRankingApplication : Application(), Configuration.Provider {

    @Inject lateinit var seeder: DatabaseSeeder

    // Lets WorkManager construct @HiltWorker workers with injected dependencies.
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Inject lateinit var workoutDao: dev.gatsyuk.soloranking.core.database.dao.WorkoutDao

    @Inject lateinit var workoutRepository: dev.gatsyuk.soloranking.core.data.WorkoutRepository

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            seeder.seedIfEmpty()
            // Clean up zombie live sessions; the newest stays for the resume bar.
            workoutRepository.sweepAbandonedSessions(keepLatestOpen = true)
        }
        // Ongoing notification mirrors the in-progress workout (user feature).
        applicationScope.launch {
            workoutDao.observeInProgressWorkout().collect { live ->
                if (live != null) {
                    dev.gatsyuk.soloranking.core.ui.WorkoutNotifier.showActiveWorkout(
                        this@SoloRankingApplication, live.name,
                    )
                } else {
                    dev.gatsyuk.soloranking.core.ui.WorkoutNotifier.cancel(this@SoloRankingApplication)
                }
            }
        }
    }
}
