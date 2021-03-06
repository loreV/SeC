package org.sc.data.validator

import org.sc.common.rest.TrailCoordinatesDto
import org.sc.data.validator.CoordinatesValidator.Companion.bottomPointKnown
import org.sc.data.validator.CoordinatesValidator.Companion.topPeakInTheWorld
import org.springframework.stereotype.Component

@Component
class TrailCoordinatesValidator constructor(private val coordsValidator: CoordinatesValidator): Validator<TrailCoordinatesDto> {

    companion object {
        const val altitudeOutOfBoundsErrorMessage = "Altitude should be a value contained between $bottomPointKnown and $topPeakInTheWorld"
        const val negativeDistanceErrorMessage = "Distance from the start cannot be a negative number"
    }

    override fun validate(request: TrailCoordinatesDto): Set<String> {
        val listOfErrorMessages = mutableSetOf<String>()
        if (request.altitude < bottomPointKnown || request.altitude > topPeakInTheWorld ) listOfErrorMessages.add(altitudeOutOfBoundsErrorMessage)
        val validateLongitude = coordsValidator.validateCoordinates(request.longitude, CoordinatesValidator.Companion.CoordDimension.LONGITUDE)
        if (validateLongitude.isNotEmpty()) listOfErrorMessages.add(validateLongitude)
        val validateLatitude = coordsValidator.validateCoordinates(request.latitude, CoordinatesValidator.Companion.CoordDimension.LATITUDE)
        if (validateLatitude.isNotEmpty()) listOfErrorMessages.add(validateLatitude)
        if(request.distanceFromTrailStart < 0) listOfErrorMessages.add(negativeDistanceErrorMessage)
        return listOfErrorMessages
    }

}