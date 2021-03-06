package org.sc.manager

import org.sc.common.rest.*
import org.sc.configuration.auth.AuthFacade
import org.sc.data.mapper.PlaceRefMapper
import org.sc.data.mapper.TrailCoordinatesMapper
import org.sc.data.mapper.TrailMapper
import org.sc.data.mapper.TrailRawMapper
import org.sc.data.model.*
import org.sc.data.repository.TrailDatasetVersionDao
import org.sc.data.repository.TrailRawDAO
import org.sc.processor.DistanceProcessor
import org.sc.processor.TrailSimplifier
import org.sc.processor.TrailSimplifierLevel
import org.sc.processor.TrailsStatsCalculator
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.util.*

@Component
class TrailImporterManager @Autowired constructor(
    private val trailsManager: TrailManager,
    private val trailsStatsCalculator: TrailsStatsCalculator,
    private val trailDatasetVersionDao: TrailDatasetVersionDao,
    private val placeMapper: PlaceRefMapper,
    private val trailCoordinatesMapper: TrailCoordinatesMapper,
    private val trailRawMapper: TrailRawMapper,
    private val trailRawDao: TrailRawDAO,
    private val trailMapper: TrailMapper,
    private val authFacade: AuthFacade,
    private val trailSimplifier: TrailSimplifier
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun saveRaw(trailRaw: TrailRawDto): TrailRawDto =
        trailRawDao.createRawTrail(trailRawMapper.map(trailRaw)).map { trailRawMapper.map(it) }
            .first()

    fun save(importingTrail: TrailImportDto): List<TrailDto> {
        val statsTrailMetadata = StatsTrailMetadata(
            trailsStatsCalculator.calculateTotRise(importingTrail.coordinates),
            trailsStatsCalculator.calculateTotFall(importingTrail.coordinates),
            trailsStatsCalculator.calculateEta(importingTrail.coordinates),
            trailsStatsCalculator.calculateTrailLength(importingTrail.coordinates),
            trailsStatsCalculator.calculateHighestPlace(importingTrail.coordinates),
            trailsStatsCalculator.calculateLowestPlace(importingTrail.coordinates)
        )

        val createdOn = Date()

        val authHelper = authFacade.authHelper
        val coordinates = importingTrail.coordinates.map { trailCoordinatesMapper.map(it) }
        val trail = Trail.builder()
            .name(importingTrail.name)
            .startLocation(importingTrail.locations.map { placeMapper.map(it) }.first())
            .endLocation(importingTrail.locations.map { placeMapper.map(it) }.last())
            .description(importingTrail.description)
            .officialEta(importingTrail.officialEta)
            .code(importingTrail.code)
            .variant(importingTrail.isVariant)
            .locations(getConsistentLocations(importingTrail))
            .classification(importingTrail.classification)
            .country(importingTrail.country)
            .statsTrailMetadata(statsTrailMetadata)
            .coordinates(coordinates)
            .coordinatesLow(trailSimplifier.simplify(coordinates, TrailSimplifierLevel.LOW))
            .coordinatesMedium(trailSimplifier.simplify(coordinates, TrailSimplifierLevel.MEDIUM))
            .coordinatesHigh(trailSimplifier.simplify(coordinates, TrailSimplifierLevel.HIGH))
            .lastUpdate(createdOn)
            .maintainingSection(importingTrail.maintainingSection)
            .territorialDivision(importingTrail.territorialDivision)
            .geoLineString(GeoLineString(importingTrail.coordinates.map {
                Coordinates2D(
                    it.longitude,
                    it.latitude
                )
            }))
            .cycloDetails(
                CycloDetails(
                    CycloClassification.UNCLASSIFIED, 0,
                    CycloFeasibility(true, 0),
                    CycloFeasibility(true, 0), ""
                )
            )
            .mediaList(emptyList())
            .fileDetails(
                FileDetails(
                    importingTrail.fileDetailsDto.uploadedOn,
                    authHelper.username,
                    authHelper.instance,
                    authHelper.realm,
                    importingTrail.fileDetailsDto.filename,
                    importingTrail.fileDetailsDto.originalFilename
                )
            )
            .status(importingTrail.trailStatus)
            .build()


        val savedTrailDao = trailsManager.saveWithGeo(trail)

        trailDatasetVersionDao.increaseVersion()

        return savedTrailDao
    }

    fun updateTrail(trailDto: TrailDto): List<TrailDto> {

        val trailToUpdate = trailsManager.getById(trailDto.id, TrailSimplifierLevel.LOW).first()

        val removedPlacesOnTrail = trailToUpdate.locations.filterNot { trailDto.locations.contains(it) }
        val addedPlacesOnTrail = trailToUpdate.locations.filterNot { trailDto.locations.contains(it) }
        removedPlacesOnTrail.forEach { trailsManager.unlinkPlace(trailDto.id, it) }
        addedPlacesOnTrail.forEach { trailsManager.linkPlace(trailDto.id, it) }

        val removedMediaOnTrail = trailToUpdate.mediaList.filterNot { trailDto.mediaList.contains(it) }
        val addedMediaOnTrail = trailDto.mediaList.filterNot { trailToUpdate.mediaList.contains(it) }

        removedMediaOnTrail.forEach {
            trailsManager.unlinkMedia(trailDto.id, UnLinkeMediaRequestDto(it.id))
        }
        addedMediaOnTrail.forEach {
            trailsManager.linkMedia(trailDto.id, LinkedMediaDto(it.id, it.description, it.keyVal))
        }

        trailToUpdate.name = trailDto.name
        trailToUpdate.description = trailDto.description
        trailToUpdate.officialEta = trailDto.officialEta
        trailToUpdate.code = trailDto.code
        trailToUpdate.isVariant = trailDto.isVariant
        trailToUpdate.locations = trailDto.locations
        trailToUpdate.classification = trailDto.classification
        trailToUpdate.country = trailDto.country
        trailToUpdate.lastUpdate = Date()
        trailToUpdate.maintainingSection = trailDto.maintainingSection
        trailToUpdate.territorialDivision = trailDto.territorialDivision
        trailToUpdate.cycloDetails = trailDto.cycloDetails

        return trailsManager.update(trailMapper.map(trailToUpdate))
    }

    fun switchToStatus(trailDto: TrailDto): List<TrailDto> {
        val trailToUpdate = trailsManager.getById(trailDto.id, TrailSimplifierLevel.LOW).first()

        if(trailDto.status == trailToUpdate.status) {
            logger.info("Did not change status to trail ${trailDto.id}")
            return trailsManager.update(trailMapper.map(trailToUpdate))
        }
        // Turn PUBLIC -> DRAFT
        if(isSwitchingToDraft(trailDto, trailToUpdate)) {
            logger.info("""Trail ${trailToUpdate.code} -> ${TrailStatus.DRAFT}""")
            trailToUpdate.locations.forEach { trailsManager.unlinkPlace(trailDto.id, it) }
        // DRAFT -> PUBLIC
        } else {
            logger.info("""Trail ${trailToUpdate.code} -> ${TrailStatus.PUBLIC}""")
            trailDto.locations.forEach { trailsManager.linkPlace(trailDto.id, it) }
        }
        trailToUpdate.status = trailDto.status
        return trailsManager.update(trailMapper.map(trailToUpdate))
    }

    private fun isSwitchingToDraft(
        trailDto: TrailDto,
        trailToUpdate: TrailDto
    ) = trailDto.status == TrailStatus.DRAFT &&
                trailToUpdate.status == TrailStatus.PUBLIC

    fun countTrailRaw() = trailRawDao.count()

    private fun getConsistentLocations(importingTrail: TrailImportDto) =
        sortLocationsByTrailCoordinates(
            importingTrail.coordinates,
            importingTrail.locations.map { placeMapper.map(it) })

    private fun sortLocationsByTrailCoordinates(
        coordinates: List<TrailCoordinatesDto>,
        locations: List<PlaceRef>
    ): List<PlaceRef> =
        // for each location, check closest trail Coordinate distance
        locations.sortedWith (compareBy{ pr ->
            val closestCoordinatePoint: TrailCoordinatesDto? =
                coordinates.minByOrNull { DistanceProcessor.distanceBetweenPoints(pr.coordinates, it) }

            val distance = closestCoordinatePoint!!.distanceFromTrailStart +
                    DistanceProcessor.distanceBetweenPoints(closestCoordinatePoint, pr.coordinates)
            distance
        })
}