package org.sc.manager

import io.jenetics.jpx.GPX
import io.jenetics.jpx.Metadata
import org.sc.common.rest.*
import org.sc.configuration.AppProperties
import org.sc.configuration.AppProperties.VERSION
import org.sc.data.mapper.TrailCoordinatesMapper
import org.sc.data.model.TrailCoordinates
import org.sc.data.model.Trail
import org.sc.processor.TrailsCalculator
import org.sc.service.AltitudeServiceAdapter
import org.sc.processor.GpxFileHandlerHelper
import org.sc.util.FileManagementUtil
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.io.File
import java.io.FileOutputStream
import java.lang.IllegalStateException
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

@Component
class TrailFileManager @Autowired constructor(
    private val gpxFileHandlerHelper: GpxFileHandlerHelper,
    private val trailsCalculator: TrailsCalculator,
    private val altitudeService: AltitudeServiceAdapter,
    private val trailCoordinatesMapper: TrailCoordinatesMapper,
    private val fileManagementUtil: FileManagementUtil,
    private val appProps: AppProperties
) {

    companion object {
        private const val TRAIL_MID = "file"
        const val GPX_TRAIL_MID = "$TRAIL_MID/gpx"
        const val KML_TRAIL_MID = "$TRAIL_MID/kml"
        const val PDF_TRAIL_MID = "$TRAIL_MID/pdf"

        const val IMPORT_FILE_EXTENSION = "gpx"
    }

    private val pathToStoredFiles = File(fileManagementUtil.getTrailGpxStoragePath()).toPath()
    private val emptyDefaultString = ""

    fun saveRawGpx(fileName: String, tempFile: Path): Path {
        val pathToSavedFile = makePathToSavedFile(fileName)
        val saveFile = saveFile(tempFile, fileName)

        if (hasFileBeenSaved(saveFile)) {
            return File(pathToSavedFile).toPath()
        }
        throw IllegalStateException()
    }

    fun getTrailRawModel(uniqueFileName: String, originalFilename: String, tempFile: Path): TrailRawDto {
        val gpx = gpxFileHandlerHelper.readFromFile(tempFile)
        val track = gpx.tracks.first()
        val segment = track.segments.first()

        val coordinatesWithAltitude: List<CoordinatesDto> = segment.points.map { point ->
            CoordinatesDto(
                point.longitude.toDegrees(), point.latitude.toDegrees(),
                altitudeService.getAltitudeByLongLat(point.latitude.toDegrees(), point.longitude.toDegrees())
            )
        }

        val trailCoordinates = coordinatesWithAltitude.map {
            TrailCoordinates(
                it.longitude, it.latitude, it.altitude,
                trailsCalculator.calculateLengthFromTo(coordinatesWithAltitude, it)
            )
        }

        return TrailRawDto(
            "",
            track.name.orElse(emptyDefaultString),
            track.description.orElse(emptyDefaultString),
            trailCoordinatesMapper.map(trailCoordinates.first()),
            trailCoordinatesMapper.map(trailCoordinates.last()),
            trailCoordinates.map { trailCoordinatesMapper.map(it) },
            FileDetailsDto(Date(), "ANONYMOUS", uniqueFileName, originalFilename)
        )
    }

    fun writeTrailToOfficialGpx(trail: Trail) {
        val creator = "S&C_$VERSION"
        val gpx = GPX.builder(creator)
            .addTrack { track ->
                track.addSegment { segment ->
                    trail.coordinates.forEach {
                        segment.addPoint { p ->
                            p.lat(it.latitude).lon(it.longitude).ele(it.altitude)
                        }
                    }
                }
            }.metadata(
                Metadata.builder()
                    .author("S&C - $creator")
                    .name(trail.code).time(trail.lastUpdate.toInstant()).build()
            ).build()
        gpxFileHandlerHelper.writeToFile(gpx, pathToStoredFiles.resolve(trail.code + ".gpx"))
    }

    fun makeUniqueFileName(originalFilename: String) =
        originalFilename.split(".")[0].replace("[^a-zA-Z0-9._]+".toRegex(), "_") +
                "_" + Date().time.toString() + "." + IMPORT_FILE_EXTENSION

    private fun hasFileBeenSaved(saveFile: Long) = saveFile != 0L

    private fun saveFile(tempFile: Path, fileName: String) =
        Files.copy(tempFile, FileOutputStream(makePathToSavedFile(fileName)))

    private fun makePathToSavedFile(fileName: String) =
        fileManagementUtil.getRawTrailStoragePath() + fileName

}