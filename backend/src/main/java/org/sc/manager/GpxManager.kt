package org.sc.manager

import io.jenetics.jpx.GPX
import io.jenetics.jpx.Metadata
import org.sc.common.rest.*
import org.sc.configuration.AppProperties
import org.sc.configuration.AppProperties.VERSION
import org.sc.processor.TrailsCalculator
import org.sc.service.AltitudeServiceAdapter
import org.sc.service.GpxFileHandlerHelper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.io.File
import java.nio.file.Path

@Component
class GpxManager @Autowired constructor(private val gpxFileHandlerHelper: GpxFileHandlerHelper,
                                        private val trailsCalculator: TrailsCalculator,
                                        private val altitudeService: AltitudeServiceAdapter,
                                        private val appProps: AppProperties) {

    private val pathToStoredFiles = File(appProps.trailStorage).toPath()
    private val emptyDefaultString = ""

    fun getTrailPreparationFromGpx(tempFile: Path): TrailPreparationModel? {
        val gpx = gpxFileHandlerHelper.readFromFile(tempFile)
        val track = gpx.tracks.first()
        val segment = track.segments.first()

        val coordinatesWithAltitude : List<CoordinatesWithAltitude> = segment.points.map { point ->
            CoordinatesWithAltitude(point.longitude.toDegrees(), point.latitude.toDegrees(),
                    altitudeService.getAltitudeByLongLat(point.latitude.toDegrees(), point.longitude.toDegrees()))
        }

        val trailCoordinates = coordinatesWithAltitude.map {
            TrailCoordinates(it.longitude, it.latitude, it.altitude,
            trailsCalculator.calculateLengthFromTo(coordinatesWithAltitude, it)) }

        return TrailPreparationModel(
                track.name.orElse(emptyDefaultString),
                track.description.orElse(emptyDefaultString),
                Position("", emptyList(), trailCoordinates.first()),
                Position("", emptyList(), trailCoordinates.last()),
                trailCoordinates
        )
    }

    fun writeTrailToGpx(trail: Trail) {
        val creator = "S&C_BO_$VERSION"
        val gpx = GPX.builder(creator)
                .addTrack { track ->
                    track.addSegment { segment ->
                        trail.coordinates.forEach {
                            segment.addPoint {
                                p -> p.lat(it.latitude).lon(it.longitude).ele(it.altitude)
                            }
                        }
                    }
                }.metadata(
                        Metadata.builder()
                                .author("CAI Bologna - $creator")
                                .name(trail.code).time(trail.lastUpdate.toInstant()).build()
                ).build()
        gpxFileHandlerHelper.writeToFile(gpx, pathToStoredFiles.resolve(trail.code + ".gpx"))
    }

}