package dev.gatsyuk.soloranking.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import dev.gatsyuk.soloranking.core.database.entity.ExerciseEntity
import dev.gatsyuk.soloranking.core.database.entity.SetEntryEntity
import dev.gatsyuk.soloranking.core.database.entity.WorkoutEntity
import dev.gatsyuk.soloranking.core.database.entity.WorkoutExerciseEntity
import kotlinx.coroutines.flow.Flow

data class WorkoutExerciseWithSets(
    @Embedded val workoutExercise: WorkoutExerciseEntity,
    @Relation(parentColumn = "exercise_id", entityColumn = "id")
    val exercise: ExerciseEntity,
    @Relation(parentColumn = "id", entityColumn = "workout_exercise_id")
    val sets: List<SetEntryEntity>,
)

data class WorkoutWithContent(
    @Embedded val workout: WorkoutEntity,
    @Relation(entity = WorkoutExerciseEntity::class, parentColumn = "id", entityColumn = "workout_id")
    val exercises: List<WorkoutExerciseWithSets>,
)

@Dao
interface WorkoutDao {

    // --- inserts ---
    @Insert suspend fun insertWorkout(workout: WorkoutEntity): Long
    @Insert suspend fun insertWorkoutExercise(entry: WorkoutExerciseEntity): Long
    @Insert suspend fun insertSetEntry(set: SetEntryEntity): Long

    // --- updates / deletes ---
    @Update suspend fun updateWorkout(workout: WorkoutEntity)
    @Update suspend fun updateSetEntry(set: SetEntryEntity)
    @Delete suspend fun deleteSetEntry(set: SetEntryEntity)
    @Delete suspend fun deleteWorkoutExercise(entry: WorkoutExerciseEntity)
    @Query("DELETE FROM workout WHERE id = :id")
    suspend fun deleteWorkoutById(id: Long)

    /**
     * Prunes prefab rows with no measurements at finish — even ones carrying
     * an inherited note (an unperformed set shouldn't survive). Exception:
     * OTHER_NOTES-type exercises, whose sets are legitimately notes-only.
     */
    @Query(
        """
        DELETE FROM set_entry WHERE id IN (
            SELECT s.id FROM set_entry s
            JOIN workout_exercise we ON s.workout_exercise_id = we.id
            JOIN exercise e ON we.exercise_id = e.id
            WHERE we.workout_id = :workoutId
              AND s.weight_kg IS NULL AND s.reps IS NULL AND s.time_seconds IS NULL
              AND s.distance_meters IS NULL AND s.kcal IS NULL
              AND (e.exercise_type != 'OTHER_NOTES' OR s.notes IS NULL)
        )
        """,
    )
    suspend fun deleteEmptySets(workoutId: Long)

    @Transaction
    @Query("SELECT * FROM workout WHERE id = :id")
    suspend fun getWorkoutWithContent(id: Long): WorkoutWithContent?

    /** Last finished performance of a routine — source of inherited notes. */
    @Query(
        """
        SELECT * FROM workout
        WHERE source_routine_id = :routineId AND end_time IS NOT NULL
        ORDER BY date DESC, id DESC LIMIT 1
        """,
    )
    suspend fun getLastCompletedWorkoutOfRoutine(routineId: Long): WorkoutEntity?

    // --- reads ---
    @Query("SELECT * FROM workout WHERE id = :id")
    suspend fun getWorkout(id: Long): WorkoutEntity?

    @Transaction
    @Query("SELECT * FROM workout WHERE id = :id")
    fun observeWorkoutWithContent(id: Long): Flow<WorkoutWithContent?>

    /** Completed sessions, newest first — the Log/History list. */
    @Transaction
    @Query("SELECT * FROM workout WHERE end_time IS NOT NULL ORDER BY date DESC, id DESC")
    fun observeCompletedWorkouts(): Flow<List<WorkoutWithContent>>

    /** The single live session, if any (end_time not yet set). */
    @Query("SELECT * FROM workout WHERE end_time IS NULL ORDER BY id DESC LIMIT 1")
    fun observeInProgressWorkout(): Flow<WorkoutEntity?>

    /** ALL live sessions, newest first — abandoned-session sweep + start guard. */
    @Query("SELECT * FROM workout WHERE end_time IS NULL ORDER BY id DESC")
    suspend fun getInProgressWorkouts(): List<WorkoutEntity>

    /**
     * Sets that carry real data — the inverse of the [deleteEmptySets]
     * predicate. Zero means the workout was never actually performed.
     */
    @Query(
        """
        SELECT COUNT(*) FROM set_entry s
        JOIN workout_exercise we ON s.workout_exercise_id = we.id
        JOIN exercise e ON we.exercise_id = e.id
        WHERE we.workout_id = :workoutId
          AND (s.weight_kg IS NOT NULL OR s.reps IS NOT NULL OR s.time_seconds IS NOT NULL
               OR s.distance_meters IS NOT NULL OR s.kcal IS NOT NULL
               OR (e.exercise_type = 'OTHER_NOTES' AND s.notes IS NOT NULL))
        """,
    )
    suspend fun countFilledSets(workoutId: Long): Int

    @Query("SELECT COUNT(*) FROM workout")
    suspend fun countWorkouts(): Int

    @Query("SELECT COALESCE(MAX(position) + 1, 0) FROM workout_exercise WHERE workout_id = :workoutId")
    suspend fun nextExercisePosition(workoutId: Long): Int

    @Query("SELECT COALESCE(MAX(position) + 1, 0) FROM set_entry WHERE workout_exercise_id = :workoutExerciseId")
    suspend fun nextSetPosition(workoutExerciseId: Long): Int

    /**
     * Sets from the most recent COMPLETED performance of an exercise —
     * powers routine TargetMode.LATEST prefill and "same as last time" hints.
     */
    @Query(
        """
        SELECT s.* FROM set_entry s
        WHERE s.workout_exercise_id = (
            SELECT we.id FROM workout_exercise we
            JOIN workout w ON we.workout_id = w.id
            WHERE we.exercise_id = :exerciseId AND w.end_time IS NOT NULL
                AND EXISTS (SELECT 1 FROM set_entry s2 WHERE s2.workout_exercise_id = we.id)
            ORDER BY w.date DESC, we.id DESC LIMIT 1
        )
        ORDER BY s.position
        """,
    )
    suspend fun getLastPerformedSets(exerciseId: Long): List<SetEntryEntity>
}
