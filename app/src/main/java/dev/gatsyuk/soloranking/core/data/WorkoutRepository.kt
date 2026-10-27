package dev.gatsyuk.soloranking.core.data

import dev.gatsyuk.soloranking.core.database.dao.ExerciseDao
import dev.gatsyuk.soloranking.core.database.dao.RoutineDao
import dev.gatsyuk.soloranking.core.database.dao.WorkoutDao
import dev.gatsyuk.soloranking.core.database.entity.RoutineEntity
import dev.gatsyuk.soloranking.core.database.entity.RoutineExerciseEntity
import dev.gatsyuk.soloranking.core.database.entity.SetEntryEntity
import dev.gatsyuk.soloranking.core.database.entity.WorkoutEntity
import dev.gatsyuk.soloranking.core.database.entity.WorkoutExerciseEntity
import dev.gatsyuk.soloranking.core.model.ExerciseType
import dev.gatsyuk.soloranking.core.model.SetKind
import dev.gatsyuk.soloranking.core.model.SetValidation
import dev.gatsyuk.soloranking.core.model.TargetMode
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Write-side of the workout domain. This is the layer that enforces the
 * ExerciseType field gating (SetValidation) the DB deliberately does not.
 */
@Singleton
class WorkoutRepository @Inject constructor(
    private val workoutDao: WorkoutDao,
    private val routineDao: RoutineDao,
    private val exerciseDao: ExerciseDao,
) {

    suspend fun startEmptyWorkout(name: String = "Workout"): Long {
        sweepAbandonedSessions()
        return workoutDao.insertWorkout(
            WorkoutEntity(
                name = name,
                date = LocalDate.now(),
                startTimeEpochMillis = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * START on a routine: instantiate a live workout with its exercises AND
     * the target number of set rows per exercise, prefilled from the last
     * performance (TargetMode LATEST). Untouched rows are pruned on finish.
     */
    suspend fun startFromRoutine(routineId: Long): Long {
        val routine = requireNotNull(routineDao.getRoutineWithExercises(routineId)) {
            "Routine $routineId not found"
        }
        sweepAbandonedSessions()
        // Notes inherit from the LAST performance of this routine — a snapshot
        // at creation time, so later edits to old records never leak forward.
        val lastRun = workoutDao.getLastCompletedWorkoutOfRoutine(routineId)
        val workoutId = workoutDao.insertWorkout(
            WorkoutEntity(
                name = routine.routine.name,
                date = LocalDate.now(),
                startTimeEpochMillis = System.currentTimeMillis(),
                notes = lastRun?.notes,
                sourceRoutineId = routineId,
            ),
        )
        routine.exercises.sortedBy { it.position }.forEachIndexed { index, entry ->
            val workoutExerciseId = workoutDao.insertWorkoutExercise(
                WorkoutExerciseEntity(
                    workoutId = workoutId,
                    exerciseId = entry.exerciseId,
                    position = index,
                ),
            )
            prefillSets(workoutExerciseId, entry.exerciseId, entry.targetSets)
        }
        return workoutId
    }

    /** "Repeat Workout": a fresh live session cloning exercises, set values and notes. */
    suspend fun repeatWorkout(sourceWorkoutId: Long): Long {
        // Spare the source: it may itself be the live session being repeated.
        sweepAbandonedSessions(exceptId = sourceWorkoutId)
        val source = requireNotNull(workoutDao.getWorkoutWithContent(sourceWorkoutId)) {
            "Workout $sourceWorkoutId not found"
        }
        val newId = workoutDao.insertWorkout(
            WorkoutEntity(
                name = source.workout.name,
                date = LocalDate.now(),
                startTimeEpochMillis = System.currentTimeMillis(),
                notes = source.workout.notes,
                sourceRoutineId = source.workout.sourceRoutineId,
            ),
        )
        source.exercises.sortedBy { it.workoutExercise.position }.forEachIndexed { index, entry ->
            val weId = workoutDao.insertWorkoutExercise(
                WorkoutExerciseEntity(workoutId = newId, exerciseId = entry.exercise.id, position = index),
            )
            entry.sets.sortedBy { it.position }.forEachIndexed { position, set ->
                workoutDao.insertSetEntry(
                    set.copy(id = 0, workoutExerciseId = weId, position = position, isPr = false),
                )
            }
        }
        // If the source was itself live, the clone supersedes it — resolve it now.
        source.workout.takeIf { it.endTimeEpochMillis == null }?.let { resolveAbandoned(it) }
        return newId
    }

    /** "Save as Routine": template from a performed session (sets count + rep range). */
    suspend fun saveAsRoutine(workoutId: Long): Long {
        val source = requireNotNull(workoutDao.getWorkoutWithContent(workoutId)) {
            "Workout $workoutId not found"
        }
        val routineId = routineDao.insertRoutine(
            RoutineEntity(
                name = source.workout.name,
                targetMode = TargetMode.LATEST,
                notes = source.workout.notes,
            ),
        )
        routineDao.insertRoutineExercises(
            source.exercises.sortedBy { it.workoutExercise.position }.mapIndexed { index, entry ->
                val reps = entry.sets.mapNotNull { it.reps }
                RoutineExerciseEntity(
                    routineId = routineId,
                    exerciseId = entry.exercise.id,
                    position = index,
                    targetSets = entry.sets.size.coerceAtLeast(1),
                    repMin = reps.minOrNull(),
                    repMax = reps.maxOrNull(),
                )
            },
        )
        return routineId
    }

    /** Adding an exercise mid-workout mirrors its last session (count + values). */
    suspend fun addExercise(workoutId: Long, exerciseId: Long): Long {
        val workoutExerciseId = workoutDao.insertWorkoutExercise(
            WorkoutExerciseEntity(
                workoutId = workoutId,
                exerciseId = exerciseId,
                position = workoutDao.nextExercisePosition(workoutId),
            ),
        )
        val lastCount = workoutDao.getLastPerformedSets(exerciseId).size
        if (lastCount > 0) prefillSets(workoutExerciseId, exerciseId, lastCount)
        return workoutExerciseId
    }

    private suspend fun prefillSets(workoutExerciseId: Long, exerciseId: Long, targetSets: Int) {
        val last = workoutDao.getLastPerformedSets(exerciseId)
        if (last.isNotEmpty()) {
            // History exists: repeat last time's STRUCTURE (count, warmup
            // markers, notes) but leave measurements empty — the UI shows last
            // session's numbers as greyed-out ghosts instead (user feedback:
            // visible until you type, back when you erase). Untouched rows are
            // pruned on finish, so unperformed sets never count.
            last.sortedBy { it.position }.forEachIndexed { index, template ->
                workoutDao.insertSetEntry(
                    template.copy(
                        id = 0,
                        workoutExerciseId = workoutExerciseId,
                        position = index,
                        isPr = false,
                        weightKg = null,
                        reps = null,
                        timeSeconds = null,
                        distanceMeters = null,
                        kcal = null,
                    ),
                )
            }
            return
        }
        // Fresh exercise: the exercise's own default warmup count pre-marks W
        // rows (user feedback: no manual W-marking every session), then the
        // routine's working-set target.
        val warmups = exerciseDao.getById(exerciseId)?.defaultWarmupSets ?: 0
        var position = 0
        repeat(warmups.coerceAtLeast(0)) {
            workoutDao.insertSetEntry(
                SetEntryEntity(
                    workoutExerciseId = workoutExerciseId,
                    position = position++,
                    setKind = SetKind.WARMUP,
                ),
            )
        }
        repeat(targetSets.coerceAtLeast(0)) {
            workoutDao.insertSetEntry(
                SetEntryEntity(
                    workoutExerciseId = workoutExerciseId,
                    position = position++,
                    setKind = SetKind.WORKING,
                ),
            )
        }
    }

    /**
     * Insert a set after validating its fields against the exercise type.
     * Policy: fields OUTSIDE the type's set are hard-rejected (returns null);
     * an all-empty row is allowed because a set exists while it is being
     * typed in — emptiness is judged at analytics time, not entry time.
     */
    suspend fun addSet(
        workoutExerciseId: Long,
        type: ExerciseType,
        candidate: SetValidation.Candidate,
        kind: SetKind = SetKind.WORKING,
        notes: String? = null,
    ): Long? {
        val result = SetValidation.validate(type, candidate)
        if (result is SetValidation.Result.Invalid && result.disallowed.isNotEmpty()) return null
        return workoutDao.insertSetEntry(
            SetEntryEntity(
                workoutExerciseId = workoutExerciseId,
                position = workoutDao.nextSetPosition(workoutExerciseId),
                setKind = kind,
                weightKg = candidate.weightKg,
                reps = candidate.reps,
                timeSeconds = candidate.timeSeconds,
                distanceMeters = candidate.distanceMeters,
                kcal = candidate.kcal,
                notes = notes,
            ),
        )
    }

    /** Update measurements only if they stay valid for the type; metadata always updates. */
    suspend fun updateSet(type: ExerciseType, updated: SetEntryEntity): Boolean {
        val candidate = SetValidation.Candidate(
            weightKg = updated.weightKg,
            reps = updated.reps,
            timeSeconds = updated.timeSeconds,
            distanceMeters = updated.distanceMeters,
            kcal = updated.kcal,
        )
        // An all-empty row is allowed to exist while being edited; gate only extras.
        val result = SetValidation.validate(type, candidate)
        val invalidExtras = result is SetValidation.Result.Invalid && result.disallowed.isNotEmpty()
        if (invalidExtras) return false
        workoutDao.updateSetEntry(updated)
        return true
    }

    suspend fun deleteSet(set: SetEntryEntity) = workoutDao.deleteSetEntry(set)

    suspend fun removeExercise(entry: WorkoutExerciseEntity) =
        workoutDao.deleteWorkoutExercise(entry)

    /**
     * Resolves abandoned live sessions (end_time NULL): never-performed ones
     * (no filled sets) are deleted, ones with logged data are finished at
     * "now" — day-old zombies get an implausible duration, which
     * trackedDurationMillis already hides and excludes from stats. Runs before
     * every workout start (the invariant: at most ONE live session), on app
     * launch and after import with [keepLatestOpen] so the resume-bar session
     * survives. [exceptId] spares one workout (Repeat's source).
     */
    suspend fun sweepAbandonedSessions(keepLatestOpen: Boolean = false, exceptId: Long? = null) {
        workoutDao.getInProgressWorkouts() // newest first
            .drop(if (keepLatestOpen) 1 else 0)
            .filter { it.id != exceptId }
            .forEach { resolveAbandoned(it) }
    }

    private suspend fun resolveAbandoned(workout: WorkoutEntity) {
        if (workoutDao.countFilledSets(workout.id) == 0) {
            workoutDao.deleteWorkoutById(workout.id)
        } else {
            workoutDao.deleteEmptySets(workout.id)
            workoutDao.updateWorkout(workout.copy(endTimeEpochMillis = System.currentTimeMillis()))
        }
    }

    /** Live session with logged sets — the start guard asks before touching it. */
    suspend fun nonEmptyLiveWorkout(): WorkoutEntity? =
        workoutDao.getInProgressWorkouts().firstOrNull { workoutDao.countFilledSets(it.id) > 0 }

    suspend fun finishWorkout(workoutId: Long) {
        val workout = workoutDao.getWorkout(workoutId) ?: return
        if (workout.endTimeEpochMillis == null) {
            // Drop prefab rows the user never filled in, then close the session.
            workoutDao.deleteEmptySets(workoutId)
            workoutDao.updateWorkout(workout.copy(endTimeEpochMillis = System.currentTimeMillis()))
        }
    }

    suspend fun updateWorkout(workout: WorkoutEntity) = workoutDao.updateWorkout(workout)

    suspend fun deleteWorkout(workoutId: Long) = workoutDao.deleteWorkoutById(workoutId)

    suspend fun lastPerformedSets(exerciseId: Long): List<SetEntryEntity> =
        workoutDao.getLastPerformedSets(exerciseId)
}
