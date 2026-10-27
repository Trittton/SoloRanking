package dev.gatsyuk.soloranking.core.export

import kotlinx.serialization.Serializable

/**
 * Full-fidelity backup DTOs (SPEC §10 NFR-2). We serialize the RAW source
 * tables only — derived gamification/stats are recomputed on import (NFR-5),
 * so they're deliberately absent. LocalDate is carried as epoch-day Long to
 * match on-disk storage; nullable measurement fields preserve the polymorphic
 * SetEntry exactly.
 *
 * [version] guards forward compatibility: an importer can refuse or adapt a
 * snapshot from a newer schema than it understands.
 */
@Serializable
data class BackupSnapshot(
    val version: Int = CURRENT_VERSION,
    val exportedAtEpochMillis: Long,
    val muscleGroups: List<MuscleGroupDto> = emptyList(),
    val exercises: List<ExerciseDto> = emptyList(),
    val exerciseMuscles: List<ExerciseMuscleDto> = emptyList(),
    val routines: List<RoutineDto> = emptyList(),
    val routineExercises: List<RoutineExerciseDto> = emptyList(),
    val workouts: List<WorkoutDto> = emptyList(),
    val workoutExercises: List<WorkoutExerciseDto> = emptyList(),
    val setEntries: List<SetEntryDto> = emptyList(),
    val foodItems: List<FoodItemDto> = emptyList(),
    val diaryEntries: List<DiaryEntryDto> = emptyList(),
    val nutritionTargets: List<NutritionTargetDto> = emptyList(),
    val preferences: PreferencesDto? = null, // v2; absent in v1 backups
) {
    companion object {
        const val CURRENT_VERSION = 2
    }
}

/**
 * Profile & settings (DataStore, not Room) — added in v2. Sex + bodyweight are
 * rank-engine INPUTS, not derivable from history, so a restore without them
 * left ranking dead until re-entered by hand. Strings mirror enum names;
 * everything nullable so v1 files and partial blocks import unchanged.
 * Auto-backup stays device-local on purpose (its WorkManager schedule
 * shouldn't silently change on import).
 */
@Serializable
data class PreferencesDto(
    val sex: String? = null,
    val bodyweightFallbackKg: Double? = null,
    val weightUnit: String? = null,
    val themeMode: String? = null,
    val restTimerSeconds: Int? = null,
)

@Serializable
data class MuscleGroupDto(val id: Long, val name: String, val displayOrder: Int)

@Serializable
data class ExerciseDto(
    val id: Long,
    val name: String,
    val muscleGroupId: Long,
    val exerciseType: String,
    val isUnilateral: Boolean,
    val isCustom: Boolean,
    val isArchived: Boolean,
    val defaultWarmupSets: Int = 0, // defaulted so pre-v4 backups import cleanly
)

@Serializable
data class ExerciseMuscleDto(
    val exerciseId: Long,
    val muscle: String,
    val role: String,
    val contributionWeight: Double,
)

@Serializable
data class RoutineDto(
    val id: Long,
    val name: String,
    val targetMode: String,
    val notes: String?,
    val displayOrder: Int,
)

@Serializable
data class RoutineExerciseDto(
    val id: Long,
    val routineId: Long,
    val exerciseId: Long,
    val position: Int,
    val targetSets: Int,
    val repMin: Int?,
    val repMax: Int?,
)

@Serializable
data class WorkoutDto(
    val id: Long,
    val name: String,
    val dateEpochDay: Long,
    val startTimeEpochMillis: Long?,
    val endTimeEpochMillis: Long?,
    val bodyweightKg: Double?,
    val notes: String?,
    val sourceRoutineId: Long?,
)

@Serializable
data class WorkoutExerciseDto(
    val id: Long,
    val workoutId: Long,
    val exerciseId: Long,
    val position: Int,
    val supersetGroupId: Long?,
)

@Serializable
data class SetEntryDto(
    val id: Long,
    val workoutExerciseId: Long,
    val position: Int,
    val setKind: String,
    val weightKg: Double?,
    val reps: Int?,
    val timeSeconds: Int?,
    val distanceMeters: Double?,
    val kcal: Int?,
    val notes: String?,
    val isPr: Boolean,
)

@Serializable
data class FoodItemDto(
    val id: Long,
    val name: String,
    val brand: String?,
    val source: String,
    val barcode: String?,
    val servingSize: Double,
    val servingUnit: String,
    val kcalPerServing: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val isFavorite: Boolean,
)

@Serializable
data class DiaryEntryDto(
    val id: Long,
    val dateEpochDay: Long,
    val meal: String,
    val foodItemId: Long,
    val quantityServings: Double,
)

@Serializable
data class NutritionTargetDto(
    val id: Long,
    val effectiveDateEpochDay: Long,
    val kcalTarget: Int,
    val proteinTargetG: Int,
    val carbsTargetG: Int,
    val fatTargetG: Int,
)
