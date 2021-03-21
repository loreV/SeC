package org.sc.controller;

import org.sc.common.rest.*;
import org.sc.common.rest.response.PlaceResponse;
import org.sc.data.validator.*;
import org.sc.manager.PlaceManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static java.util.Collections.*;
import static org.sc.configuration.AppBoundaries.MAX_DOCS_ON_READ;
import static org.sc.configuration.AppBoundaries.MIN_DOCS_ON_READ;

@RestController
@RequestMapping(PlaceController.PREFIX)
public class PlaceController {
    public final static String PREFIX = "/place";

    private final PlaceValidator placeValidator;
    private final PlaceManager placeManager;
    private final LinkedMediaValidator linkedMediaValidator;
    private final MediaExistenceValidator mediaExistenceValidator;
    private final PlaceExistenceValidator placeExistenceValidator;
    private final ControllerPagination controllerPagination;
    private final PointGeolocationValidatorDto pointGeolocationValidatorDto;

    @Autowired
    public PlaceController(PlaceValidator placeValidator,
                           PlaceManager placeManager,
                           LinkedMediaValidator linkedMediaValidator,
                           MediaExistenceValidator mediaExistenceValidator,
                           PlaceExistenceValidator placeExistenceValidator,
                           ControllerPagination controllerPagination,
                           PointGeolocationValidatorDto pointGeolocationValidatorDto) {
        this.placeValidator = placeValidator;
        this.placeManager = placeManager;
        this.linkedMediaValidator = linkedMediaValidator;
        this.mediaExistenceValidator = mediaExistenceValidator;
        this.placeExistenceValidator = placeExistenceValidator;
        this.controllerPagination = controllerPagination;
        this.pointGeolocationValidatorDto = pointGeolocationValidatorDto;
    }

    @GetMapping
    public PlaceResponse get(@RequestParam(required = false, defaultValue = MIN_DOCS_ON_READ) int skip,
                             @RequestParam(required = false, defaultValue = MAX_DOCS_ON_READ) int limit) {
        return constructResponse(emptySet(),
                placeManager.getPaginated(skip, limit), placeManager.count(), skip, limit);
    }

    @GetMapping("/{id}")
    public PlaceResponse get(@PathVariable String id) {
        return constructResponse(emptySet(),
                placeManager.getById(id),
                placeManager.count(),
                Constants.ZERO, Constants.ONE);
    }

    @GetMapping("/name/{name}")
    public PlaceResponse getLikeNameOrTags(@PathVariable String name,
                                           @RequestParam(required = false, defaultValue = MIN_DOCS_ON_READ) int skip,
                                           @RequestParam(required = false, defaultValue = MAX_DOCS_ON_READ) int limit) {
        return constructResponse(emptySet(),
                placeManager.getLikeNameOrTags(name, skip, limit),
                placeManager.count(), skip, limit);
    }

    @PostMapping("/geolocate")
    public PlaceResponse geolocatePlace(@RequestBody PointGeolocationDto pointGeolocationDto,
                                        @RequestParam(required = false, defaultValue = MIN_DOCS_ON_READ) int skip,
                                        @RequestParam(required = false, defaultValue = MAX_DOCS_ON_READ) int limit) {
        final Set<String> errors = pointGeolocationValidatorDto.validate(pointGeolocationDto);
        if (errors.isEmpty()) {
            final CoordinatesDto coordinatesDto = pointGeolocationDto.getCoordinatesDto();
            final List<PlaceDto> results = placeManager.getNearPoint(
                    coordinatesDto.getLongitude(), coordinatesDto.getLatitude(),
                    pointGeolocationDto.getDistance(), skip, limit);
            return constructResponse(emptySet(),
                    results, results.size(), skip, limit);
        }
        return constructResponse(errors, emptyList(),
                placeManager.count(), skip, limit);
    }


    @PutMapping("/media/{id}")
    public PlaceResponse addMedia(@PathVariable String id,
                                  @RequestBody LinkedMediaDto linkedMediaRequest) {
        final Set<String> errors = linkedMediaValidator.validate(linkedMediaRequest);
        errors.addAll(placeExistenceValidator.validate(id));
        errors.addAll(mediaExistenceValidator.validate(linkedMediaRequest.getId()));
        if (errors.isEmpty()) {
            final List<PlaceDto> linkedMediaResultDtos =
                    placeManager.linkMedia(id, linkedMediaRequest);
            return constructResponse(emptySet(),
                    linkedMediaResultDtos,
                    placeManager.count(), Constants.ZERO, Constants.ONE);
        }
        return constructResponse(errors,
                emptyList(),
                placeManager.count(), Constants.ZERO, Constants.ONE);
    }

    @DeleteMapping("/media/{id}")
    public PlaceResponse deleteMedia(@PathVariable String id,
                                     @RequestBody UnLinkeMediaRequestDto unLinkeMediaRequestDto) {
        final Set<String> errors = placeExistenceValidator.validate(id);
        errors.addAll(mediaExistenceValidator.validate(unLinkeMediaRequestDto.getId()));
        if (errors.isEmpty()) {
            final List<PlaceDto> linkedMediaResultDtos =
                    placeManager.unlinkMedia(id, unLinkeMediaRequestDto);
            return constructResponse(emptySet(),
                    linkedMediaResultDtos,
                    placeManager.count(), Constants.ZERO, Constants.ONE);
        }
        return constructResponse(errors,
                emptyList(),
                placeManager.count(), Constants.ZERO, Constants.ONE);
    }

    @PutMapping
    public PlaceResponse create(@RequestBody PlaceDto place) {
        Set<String> errors = placeValidator.validate(place);
        if (!errors.isEmpty()) {
            return constructResponse(errors,
                    emptyList(),
                    placeManager.count(), Constants.ZERO, Constants.ONE);
        }
        List<PlaceDto> placeDtoList = placeManager.create(place);
        return constructResponse(emptySet(),
                placeDtoList,
                placeManager.count(), Constants.ZERO, Constants.ONE);
    }

    @DeleteMapping("/{id}")
    public PlaceResponse delete(@PathVariable String id) {
        final List<PlaceDto> content = placeManager.deleteById(id);
        return constructResponse(emptySet(),
                content, placeManager.count(), Constants.ZERO, Constants.ONE);
    }

    @PostMapping
    public PlaceResponse update(@RequestBody PlaceDto place) {
        Set<String> errors = placeValidator.validate(place);
        if (!errors.isEmpty()) {
            return constructResponse(errors,
                    emptyList(),
                    placeManager.count(), Constants.ZERO, Constants.ONE);
        }
        final List<PlaceDto> placeDtoList = placeManager.update(place);
        return constructResponse(emptySet(),
                placeDtoList, placeManager.count(), Constants.ZERO, Constants.ONE);
    }

    private PlaceResponse constructResponse(Set<String> errors,
                                            List<PlaceDto> dtos,
                                            long totalCount,
                                            int skip,
                                            int limit) {
        if (!errors.isEmpty()) {
            return new PlaceResponse(Status.ERROR, errors, dtos, 1L,
                    Constants.ONE, limit, totalCount);
        }
        return new PlaceResponse(Status.OK, errors, dtos,
                controllerPagination.getCurrentPage(skip, limit),
                controllerPagination.getTotalPages(totalCount, limit), limit, totalCount);
    }

}