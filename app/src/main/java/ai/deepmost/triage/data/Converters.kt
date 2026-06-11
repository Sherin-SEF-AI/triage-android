package ai.deepmost.triage.data

import androidx.room.TypeConverter

/** Room type converters for the domain enums. Stored as their stable enum names. */
class Converters {
    @TypeConverter fun fromInspectionType(v: InspectionType) = v.name
    @TypeConverter fun toInspectionType(v: String) = InspectionType.valueOf(v)

    @TypeConverter fun fromInspectionStatus(v: InspectionStatus) = v.name
    @TypeConverter fun toInspectionStatus(v: String) = InspectionStatus.valueOf(v)

    @TypeConverter fun fromFindingHead(v: FindingHead) = v.name
    @TypeConverter fun toFindingHead(v: String) = FindingHead.valueOf(v)

    @TypeConverter fun fromFindingType(v: FindingType) = v.name
    @TypeConverter fun toFindingType(v: String) = FindingType.valueOf(v)

    @TypeConverter fun fromDiffStatus(v: DiffStatus) = v.name
    @TypeConverter fun toDiffStatus(v: String) = DiffStatus.valueOf(v)

    @TypeConverter fun fromDriverAnnotation(v: DriverAnnotation) = v.name
    @TypeConverter fun toDriverAnnotation(v: String) = DriverAnnotation.valueOf(v)

    @TypeConverter fun fromLabelSource(v: LabelSource) = v.name
    @TypeConverter fun toLabelSource(v: String) = LabelSource.valueOf(v)

    @TypeConverter fun fromRole(v: Role) = v.name
    @TypeConverter fun toRole(v: String) = Role.valueOf(v)
}
