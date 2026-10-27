package dev.gatsyuk.soloranking.core.export

import dev.gatsyuk.soloranking.core.data.WorkoutRepository
import dev.gatsyuk.soloranking.core.database.dao.ExportDao
import dev.gatsyuk.soloranking.core.datastore.UserPreferencesRepository
import dev.gatsyuk.soloranking.core.model.Sex
import dev.gatsyuk.soloranking.core.model.ThemeMode
import dev.gatsyuk.soloranking.core.model.WeightUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the whole database into a [BackupSnapshot] and restores one back
 * (SPEC §10). Import is a full REPLACE inside one transaction — the right
 * semantics both for a user restoring their own backup and for the "load a
 * friend's export" sharing flow (§9). Derived data (XP/ranks/stats) is never
 * exported; it recomputes from the restored raw history (NFR-5).
 */
@Singleton
class ExportImportRepository @Inject constructor(
    private val exportDao: ExportDao,
    private val workoutRepository: WorkoutRepository,
    private val userPreferences: UserPreferencesRepository,
) {

    suspend fun buildSnapshot(): BackupSnapshot = withContext(Dispatchers.IO) {
        BackupSnapshot(
            exportedAtEpochMillis = System.currentTimeMillis(),
            muscleGroups = exportDao.allMuscleGroups().map { it.toDto() },
            exercises = exportDao.allExercises().map { it.toDto() },
            exerciseMuscles = exportDao.allExerciseMuscles().map { it.toDto() },
            routines = exportDao.allRoutines().map { it.toDto() },
            routineExercises = exportDao.allRoutineExercises().map { it.toDto() },
            workouts = exportDao.allWorkouts().map { it.toDto() },
            workoutExercises = exportDao.allWorkoutExercises().map { it.toDto() },
            setEntries = exportDao.allSetEntries().map { it.toDto() },
            foodItems = exportDao.allFoodItems().map { it.toDto() },
            diaryEntries = exportDao.allDiaryEntries().map { it.toDto() },
            nutritionTargets = exportDao.allNutritionTargets().map { it.toDto() },
            preferences = PreferencesDto(
                sex = userPreferences.sex.first().takeIf { it != Sex.UNSET }?.name,
                bodyweightFallbackKg = userPreferences.bodyweightFallbackKg.first(),
                weightUnit = userPreferences.weightUnit.first().name,
                themeMode = userPreferences.themeMode.first().name,
                restTimerSeconds = userPreferences.restTimerSeconds.first(),
            ),
        )
    }

    suspend fun exportJson(): String = BackupSerializer.toJson(buildSnapshot())

    suspend fun restore(snapshot: BackupSnapshot) = withContext(Dispatchers.IO) {
        exportDao.clearAndRestore(
            muscleGroups = snapshot.muscleGroups.map { it.toEntity() },
            exercises = snapshot.exercises.map { it.toEntity() },
            exerciseMuscles = snapshot.exerciseMuscles.map { it.toEntity() },
            routines = snapshot.routines.map { it.toEntity() },
            routineExercises = snapshot.routineExercises.map { it.toEntity() },
            workouts = snapshot.workouts.map { it.toEntity() },
            workoutExercises = snapshot.workoutExercises.map { it.toEntity() },
            setEntries = snapshot.setEntries.map { it.toEntity() },
            foodItems = snapshot.foodItems.map { it.toEntity() },
            diaryEntries = snapshot.diaryEntries.map { it.toEntity() },
            nutritionTargets = snapshot.nutritionTargets.map { it.toEntity() },
        )
        // Backups made mid-training carry unfinished sessions (sometimes many,
        // accumulated before the start guard existed). Resolve all but the
        // newest so the restored resume bar isn't a stack of zombies.
        workoutRepository.sweepAbandonedSessions(keepLatestOpen = true)
        // v2 preference block: apply what's present, skip what isn't. Unknown
        // enum values (a future app's new option) are ignored, not fatal.
        snapshot.preferences?.let { p ->
            p.sex?.toEnumOrNull<Sex>()?.let { userPreferences.setSex(it) }
            p.bodyweightFallbackKg?.let { userPreferences.setBodyweightFallbackKg(it) }
            p.weightUnit?.toEnumOrNull<WeightUnit>()?.let { userPreferences.setWeightUnit(it) }
            p.themeMode?.toEnumOrNull<ThemeMode>()?.let { userPreferences.setThemeMode(it) }
            p.restTimerSeconds?.let { userPreferences.setRestTimerSeconds(it) }
        }
    }

    private inline fun <reified E : Enum<E>> String.toEnumOrNull(): E? =
        runCatching { enumValueOf<E>(this) }.getOrNull()

    suspend fun importJson(text: String) = restore(BackupSerializer.fromJson(text))
}
